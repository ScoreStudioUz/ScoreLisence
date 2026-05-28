package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Arena;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.Material;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles all in-match events:
 *   - Freeze during countdown
 *   - Smooth death (no vanilla death screen): would-be-fatal damage is
 *     intercepted; we mark the player as dead, switch them to spectator at
 *     the death location, and trigger MatchManager round handling.
 *   - Quit → forfeit
 *   - Damage isolation (only fighters on same arena can hurt each other)
 *   - Spectator boundary enforcement
 */
public class MatchListener implements Listener {

    /** ms a recorded killer reference stays "live" after the last hit. */
    private static final long KILLER_TTL_MS = 10_000L;

    private final ScorePvP plugin;

    /** victim UUID → last attacker UUID (kept until KILLER_TTL_MS expires). */
    private final Map<UUID, UUID> lastAttacker = new ConcurrentHashMap<>();
    /** victim UUID → ms timestamp of the last damage. */
    private final Map<UUID, Long> lastAttackerAt = new ConcurrentHashMap<>();

    public MatchListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Freeze during countdown + Spectator bounds ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Match match = plugin.getMatchManager().getMatch(e.getPlayer().getUniqueId());
        if (match == null) return;

        if (match.getState() == Match.State.COUNTDOWN) {
            MatchPlayer mp = match.getFighter(e.getPlayer().getUniqueId());
            if (mp != null && mp.isFrozen()) {
                if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                 || e.getFrom().getBlockZ() != e.getTo().getBlockZ()
                 || e.getFrom().getBlockY() != e.getTo().getBlockY()) {
                    e.setTo(e.getFrom().clone().add(0, 0, 0));
                }
            }
        }
    }

    // ── Projectile lock during countdown ──────────────────────────────────────
    //
    // The PlayerMoveEvent freeze above keeps fighters in place during the
    // 5-second countdown — but ender pearls / chorus fruit / fishing rods /
    // tridents bypass it (they consume motion, not produce it). A player
    // could right-click a pearl, fly across the arena, then "thaw" at the
    // FIGHT! tick already on top of the opponent. We block ANY right-click
    // item use while frozen, AND any projectile launch from a frozen entity
    // as a belt-and-braces check (e.g. dispensers triggered by redstone,
    // server-side scripts, etc).

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractDuringCountdown(PlayerInteractEvent e) {
        // Only block right-click item use — left-clicks / physical events are
        // already harmless during freeze.
        if (e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
         && e.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() == null) return;

        Player p = e.getPlayer();
        Match match = plugin.getMatchManager().getMatch(p.getUniqueId());
        if (match == null || match.getState() != Match.State.COUNTDOWN) return;
        MatchPlayer mp = match.getFighter(p.getUniqueId());
        if (mp == null || !mp.isFrozen()) return;

        ItemStack item = e.getHand() == EquipmentSlot.OFF_HAND
                ? p.getInventory().getItemInOffHand()
                : p.getInventory().getItemInMainHand();
        if (item == null) return;
        Material m = item.getType();
        if (isMovementProjectile(m)) {
            e.setCancelled(true);
            e.setUseItemInHand(org.bukkit.event.Event.Result.DENY);
            e.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunchDuringCountdown(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        Match match = plugin.getMatchManager().getMatch(p.getUniqueId());
        if (match == null || match.getState() != Match.State.COUNTDOWN) return;
        MatchPlayer mp = match.getFighter(p.getUniqueId());
        if (mp == null || !mp.isFrozen()) return;
        e.setCancelled(true);
    }

    /**
     * Items that either teleport the user (ender pearl, chorus fruit,
     * elytra-rocket, trident with Riptide) or fire a projectile that we don't
     * want spawned during freeze. Bows / crossbows are included so a charged
     * shot doesn't release the instant the countdown ends.
     */
    private boolean isMovementProjectile(Material m) {
        switch (m) {
            case ENDER_PEARL:
            case CHORUS_FRUIT:
            case TRIDENT:
            case FIREWORK_ROCKET:
            case FISHING_ROD:
            case SNOWBALL:
            case EGG:
            case BOW:
            case CROSSBOW:
            case SPLASH_POTION:
            case LINGERING_POTION:
                return true;
            default:
                return false;
        }
    }

    // ── Damage isolation ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPvP(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;

        Match victimMatch   = plugin.getMatchManager().getMatch(victim.getUniqueId());
        Match attackerMatch = plugin.getMatchManager().getMatch(attacker.getUniqueId());

        if (victimMatch == null || attackerMatch == null) { e.setCancelled(true); return; }
        if (!victimMatch.getMatchId().equals(attackerMatch.getMatchId())) { e.setCancelled(true); return; }
        if (victimMatch.getState() != Match.State.ACTIVE) { e.setCancelled(true); return; }

        MatchPlayer vmp = victimMatch.getFighter(victim.getUniqueId());
        MatchPlayer amp = attackerMatch.getFighter(attacker.getUniqueId());

        if (victimMatch.isFfa()) {
            if (vmp == null || !vmp.isFighter() || vmp.isDead()) { e.setCancelled(true); return; }
            if (amp == null || !amp.isFighter() || amp.isDead()) { e.setCancelled(true); return; }
            recordAttacker(victim.getUniqueId(), attacker.getUniqueId());
            return;
        }

        if (vmp == null || !vmp.isFighter()) { e.setCancelled(true); return; }
        if (amp == null || !amp.isFighter()) { e.setCancelled(true); return; }
        recordAttacker(victim.getUniqueId(), attacker.getUniqueId());
    }

    /**
     * Ensures health BELOW_NAME objective stays intact after damage.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageMonitor(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (plugin.getMatchManager().getMatch(victim.getUniqueId()) != null) {
            plugin.getNameTagProvider().updateHealth(victim);
        }
    }

    // ── Smooth death: intercept fatal damage BEFORE vanilla kills the player ──

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFatalDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Match match = plugin.getMatchManager().getMatch(victim.getUniqueId());
        if (match == null || match.getState() != Match.State.ACTIVE) return;

        MatchPlayer vmp = match.getFighter(victim.getUniqueId());
        if (vmp == null || !vmp.isFighter() || vmp.isDead()) return;

        // Would this damage drop them below 0?
        if (victim.getHealth() - e.getFinalDamage() > 0) return;

        // Intercept — no real death.
        e.setCancelled(true);

        Location deathLoc = victim.getLocation().clone();
        UUID killerUuid = currentAttacker(victim.getUniqueId());

        // Reset to a clean spectator state at the death location.
        // BUT: handleFighterDeath below runs synchronously in the same tick and
        // for instant-restore (vanilla arena, tracker-based) it goes straight
        // into startRound → resetFighter(SURVIVAL) + giveKit + safeTP. If we
        // unconditionally re-apply SPECTATOR / clear inventory next tick, we
        // overwrite the freshly-started new round — that's how the victim was
        // ending up stuck in spectator with no kit. Skip when the match has
        // already advanced past ROUND_END.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!victim.isOnline()) return;
            Match m2 = plugin.getMatchManager().getMatch(victim.getUniqueId());
            if (m2 == null) return;
            Match.State st = m2.getState();
            // If the new round has already started (COUNTDOWN / ACTIVE), the
            // post-kill state has been replaced — don't undo it.
            if (st == Match.State.COUNTDOWN || st == Match.State.ACTIVE) return;
            try {
                victim.setHealth(victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                        ? victim.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()
                        : 20.0);
            } catch (Throwable ignored) { /* ignore clamp issues */ }
            victim.setFireTicks(0);
            for (org.bukkit.potion.PotionEffect effect : victim.getActivePotionEffects()) {
                victim.removePotionEffect(effect.getType());
            }
            // Drop arena-clean inventory (we don't want it left on the ground; arena clean-up handles it).
            victim.getInventory().clear();
            victim.setGameMode(GameMode.SPECTATOR);
            victim.teleport(deathLoc);
        });

        if (match.isFfa()) {
            MatchPlayer killerMp = killerUuid == null ? null : match.getFighter(killerUuid);
            plugin.getMatchManager().handleFfaDeath(match, vmp, killerMp);
        } else {
            MatchPlayer killerMp = match.getOpponent(victim.getUniqueId());
            if (killerMp == null) return;
            plugin.getMatchManager().handleFighterDeath(match, vmp, killerMp);
        }

        // Clear killer tracking for this victim.
        lastAttacker.remove(victim.getUniqueId());
        lastAttackerAt.remove(victim.getUniqueId());
    }

    // ── Legacy death path: console /kill, setHealth(0), or any death that
    // bypassed onFatalDamage's pre-damage intercept. Force-respawn instantly
    // and run the round-end logic so the vanilla respawn screen never appears.

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        Match match = plugin.getMatchManager().getMatch(dead.getUniqueId());
        if (match == null) return;

        e.setDeathMessage(null);
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(true);

        // If we reach here for an ACTIVE fighter it means /kill (or another
        // path) bypassed onFatalDamage's pre-damage cancel. The respawn screen
        // would normally pop up — auto-respawn next tick, then convert them
        // to spectator and trigger the round end so the match behaves as if
        // they'd been killed normally.
        if (match.getState() != Match.State.ACTIVE) return;
        MatchPlayer vmp = match.getFighter(dead.getUniqueId());
        if (vmp == null || !vmp.isFighter() || vmp.isDead()) return;

        UUID killerUuid = currentAttacker(dead.getUniqueId());
        Location deathLoc = dead.getLocation().clone();

        // Skip the respawn screen — Paper supports auto-respawn via spigot().
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!dead.isOnline()) return;
            try { dead.spigot().respawn(); } catch (Throwable ignored) {}
            Match m2 = plugin.getMatchManager().getMatch(dead.getUniqueId());
            if (m2 == null) return;
            Match.State st = m2.getState();
            // If the new round already started, don't overwrite that state.
            if (st == Match.State.COUNTDOWN || st == Match.State.ACTIVE) return;
            try {
                org.bukkit.attribute.AttributeInstance maxAttr =
                        dead.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
                dead.setHealth(maxAttr != null ? maxAttr.getValue() : 20.0);
            } catch (Throwable ignored) {}
            dead.setFireTicks(0);
            for (org.bukkit.potion.PotionEffect effect : dead.getActivePotionEffects()) {
                dead.removePotionEffect(effect.getType());
            }
            dead.getInventory().clear();
            dead.setGameMode(GameMode.SPECTATOR);
            dead.teleport(deathLoc);
        });

        if (match.isFfa()) {
            MatchPlayer killerMp = killerUuid == null ? null : match.getFighter(killerUuid);
            plugin.getMatchManager().handleFfaDeath(match, vmp, killerMp);
        } else {
            MatchPlayer killerMp = match.getOpponent(dead.getUniqueId());
            if (killerMp != null) plugin.getMatchManager().handleFighterDeath(match, vmp, killerMp);
        }
        lastAttacker.remove(dead.getUniqueId());
        lastAttackerAt.remove(dead.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent e) {
        Match match = plugin.getMatchManager().getMatch(e.getPlayer().getUniqueId());
        if (match == null) return;
        if (match.getArena().getCentre() != null) {
            e.setRespawnLocation(match.getArena().getCentre());
        }
    }

    // ── Quit = forfeit ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        lastAttacker.remove(e.getPlayer().getUniqueId());
        lastAttackerAt.remove(e.getPlayer().getUniqueId());
    }

    // ── Attacker tracking ─────────────────────────────────────────────────────

    private void recordAttacker(UUID victim, UUID attacker) {
        lastAttacker.put(victim, attacker);
        lastAttackerAt.put(victim, System.currentTimeMillis());
    }

    private UUID currentAttacker(UUID victim) {
        Long at = lastAttackerAt.get(victim);
        if (at == null) return null;
        if (System.currentTimeMillis() - at > KILLER_TTL_MS) {
            lastAttacker.remove(victim);
            lastAttackerAt.remove(victim);
            return null;
        }
        return lastAttacker.get(victim);
    }

    // ── Arena bounds check ────────────────────────────────────────────────────

    private boolean isInsideArenaBounds(Arena arena, Location loc) {
        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();
        if (c1 == null || c2 == null || loc == null) return true;

        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int minY = Math.min(c1.getBlockY(), c2.getBlockY());
        int maxY = Math.max(c1.getBlockY(), c2.getBlockY());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());

        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }
}
