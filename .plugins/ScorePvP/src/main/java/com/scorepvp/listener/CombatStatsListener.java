package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.RoundStatsManager;
import com.scorepvp.model.Match;
import com.scorepvp.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat stats tracker for the per-round HUD.
 *
 * Definitions per user spec:
 *   • HIT  — landed a damaging EntityDamageByEntityEvent on an enemy fighter.
 *   • MISS — ARM_SWING that did NOT produce a hit within {@link #MISS_GRACE_TICKS}.
 *
 * Why ARM_SWING and not PlayerInteractEvent LEFT_CLICK_AIR:
 *  - LEFT_CLICK_AIR doesn't fire when the player aims at a block (so swings
 *    that wiff past an opponent into the floor never registered as miss).
 *  - LEFT_CLICK_AIR is suppressed by lots of plugins (region protect,
 *    GUI managers) at NORMAL priority — ignoreCancelled=true then drops it.
 *
 * Dedup: each ARM_SWING schedules a 2-tick check. If an EntityDamageByEntityEvent
 * landed in that window we cancel the miss. This also handles the vanilla
 * "swing animation packet duplicates" we hit on Paper 1.21 — only the FIRST
 * swing per attack cooldown produces a pending check; subsequent animation
 * packets within the same window collapse into the same pending task.
 */
public class CombatStatsListener implements Listener {

    /** ms before the action bar stops being refreshed after the last hit. */
    private static final long ACTIONBAR_FADE_MS = 1500L;

    /** Ticks to wait after a swing before deciding it was a miss. */
    private static final long MISS_GRACE_TICKS = 2L;

    private final ScorePvP plugin;
    /** uuid -> BukkitTask that refreshes the action bar each tick. */
    private final Map<UUID, BukkitTask> actionbarTasks = new ConcurrentHashMap<>();

    /** uuid -> pending miss-check task; non-null means "swing waiting for damage". */
    private final Map<UUID, BukkitTask> pendingMisses = new ConcurrentHashMap<>();
    /** uuid -> System.nanoTime of last damage event. Used to invalidate the pending miss. */
    private final Map<UUID, Long> lastDamageNanos = new ConcurrentHashMap<>();

    public CombatStatsListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Arm swing → tentative miss ─────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnimation(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        UUID uuid = p.getUniqueId();
        Match match = plugin.getMatchManager().getMatch(uuid);
        if (match == null || match.getState() != Match.State.ACTIVE) return;

        // Coalesce duplicate ARM_SWING packets that vanilla emits within the
        // attack cooldown — if a check is already pending we don't queue a
        // second one.
        if (pendingMisses.containsKey(uuid)) return;

        long swingNanos = System.nanoTime();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pendingMisses.remove(uuid);
            // If a damage event arrived after the swing fired, treat as hit.
            Long lastDmg = lastDamageNanos.get(uuid);
            if (lastDmg != null && lastDmg >= swingNanos) return;

            Match m = plugin.getMatchManager().getMatch(uuid);
            if (m == null || m.getState() != Match.State.ACTIVE) return;

            plugin.getRoundStatsManager().registerMiss(m.getMatchId(), uuid);
            Player pl = Bukkit.getPlayer(uuid);
            if (pl != null && pl.hasPermission("duels.actionbar")) {
                startOrRefreshActionbar(pl);
            }
        }, MISS_GRACE_TICKS);
        pendingMisses.put(uuid, task);
    }

    // ── Damage = hit ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (e.getFinalDamage() <= 0) return;

        Match match = plugin.getMatchManager().getMatch(attacker.getUniqueId());
        if (match == null || match.getState() != Match.State.ACTIVE) return;
        Match vm = plugin.getMatchManager().getMatch(victim.getUniqueId());
        if (vm == null || !vm.getMatchId().equals(match.getMatchId())) return;

        // Stamp the damage time so the pending miss-check (if any) sees this
        // swing as a hit and bails out.
        lastDamageNanos.put(attacker.getUniqueId(), System.nanoTime());

        double dmg = e.getFinalDamage();
        double dist = attacker.getLocation().distance(victim.getLocation());

        plugin.getRoundStatsManager().registerHit(match.getMatchId(),
                attacker.getUniqueId(), victim.getUniqueId(), dmg, dist);

        if (attacker.hasPermission("duels.actionbar")) startOrRefreshActionbar(attacker);
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        BukkitTask t = actionbarTasks.remove(id);
        if (t != null) t.cancel();
        BukkitTask pending = pendingMisses.remove(id);
        if (pending != null) pending.cancel();
        lastDamageNanos.remove(id);
        plugin.getRoundStatsManager().clearPlayer(id);
    }

    // ── Action bar driver ─────────────────────────────────────────────────

    private void startOrRefreshActionbar(Player p) {
        if (actionbarTasks.containsKey(p.getUniqueId())) return;
        UUID uuid = p.getUniqueId();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Player pl = Bukkit.getPlayer(uuid);
            if (pl == null || !pl.isOnline()) {
                stopActionbar(uuid);
                return;
            }
            Match match = plugin.getMatchManager().getMatch(uuid);
            if (match == null || match.getState() != Match.State.ACTIVE) {
                stopActionbar(uuid);
                return;
            }
            long since = System.currentTimeMillis()
                    - plugin.getRoundStatsManager().lastHitAt(uuid);
            if (since > ACTIONBAR_FADE_MS) {
                stopActionbar(uuid);
                return;
            }
            String line = buildActionbarLine(match.getMatchId(), uuid);
            pl.sendActionBar(deserialize(line));
        }, 0L, 2L);
        actionbarTasks.put(uuid, task);
    }

    private void stopActionbar(UUID uuid) {
        BukkitTask t = actionbarTasks.remove(uuid);
        if (t != null) t.cancel();
    }

    private String buildActionbarLine(UUID matchId, UUID uuid) {
        RoundStatsManager.LastHit lh = plugin.getRoundStatsManager().lastHit(uuid);
        double dist = lh == null ? 0d : lh.distance;
        double dmg  = lh == null ? 0d : lh.damage;

        Player pl = Bukkit.getPlayer(uuid);
        int ping = pl != null ? pl.getPing() : 0;

        // Combo / hits / misses / accuracy were removed from the action bar by
        // request — the bar now shows only the attacker's own ping plus the
        // live distance/damage of their last hit. HP is already on the hearts
        // bar so it isn't rendered here either.
        String template = plugin.getConfigManager().rawStr("match.actionbar-hit");
        if (template == null || template.isEmpty()) {
            template = "&fПинг: &a{ping}ms &8; &fДистанция: &a▲{dist} &8; &fУрон: &c♥{dmg}";
        }
        return ColorUtil.color(template
                .replace("{ping}", Integer.toString(ping))
                .replace("{dist}", String.format(java.util.Locale.ROOT, "%.1f", dist))
                .replace("{dmg}",  String.format(java.util.Locale.ROOT, "%.2f", dmg)));
    }

    private Component deserialize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
