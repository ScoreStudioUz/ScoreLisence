package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Arena;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Handles all in-match events:
 *   - Freeze during countdown
 *   - Death → round handling (duel) / elimination (FFA)
 *   - Quit → forfeit
 *   - Damage isolation (only fighters on same arena can hurt each other)
 *   - Spectator boundary enforcement
 *
 * FIX: Party FFA friendly fire — explicitly allow damage between alive
 *      fighters in the same FFA match (was being cancelled by fallthrough).
 */
public class MatchListener implements Listener {

    private final ScorePvP plugin;

    public MatchListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Freeze during countdown + Spectator bounds ─────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Match match = plugin.getMatchManager().getMatch(e.getPlayer().getUniqueId());
        if (match == null) return;

        // Freeze fighters during countdown
        if (match.getState() == Match.State.COUNTDOWN) {
            MatchPlayer mp = match.getFighter(e.getPlayer().getUniqueId());
            if (mp != null && mp.isFrozen()) {
                if (e.getFrom().getBlockX() != e.getTo().getBlockX()
                 || e.getFrom().getBlockZ() != e.getTo().getBlockZ()
                 || e.getFrom().getBlockY() != e.getTo().getBlockY()) {
                    e.setTo(e.getFrom().clone().add(0, 0, 0));
                }
                return;
            }
        }

        // Spectators and dead FFA fighters can fly freely — no boundary enforcement
    }

    // ── Damage isolation ──────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;

        Match victimMatch   = plugin.getMatchManager().getMatch(victim.getUniqueId());
        Match attackerMatch = plugin.getMatchManager().getMatch(attacker.getUniqueId());

        // One of them not in a match — cancel PvP
        if (victimMatch == null || attackerMatch == null) { e.setCancelled(true); return; }
        // Not in the same match — cancel
        if (!victimMatch.getMatchId().equals(attackerMatch.getMatchId())) { e.setCancelled(true); return; }
        // Countdown or not active — cancel
        if (victimMatch.getState() != Match.State.ACTIVE) { e.setCancelled(true); return; }

        MatchPlayer vmp = victimMatch.getFighter(victim.getUniqueId());
        MatchPlayer amp = attackerMatch.getFighter(attacker.getUniqueId());

        // FFA (Party FFA): ALLOW damage between two alive fighters explicitly
        if (victimMatch.isFfa()) {
            // Both must be non-null alive fighters in the FFA list
            if (vmp == null || !vmp.isFighter() || vmp.isDead()) { e.setCancelled(true); return; }
            if (amp == null || !amp.isFighter() || amp.isDead()) { e.setCancelled(true); return; }
            // Explicitly allow — do NOT cancel for alive FFA fighters
            return;
        }

        // Duel: both must be active fighters
        if (vmp == null || !vmp.isFighter()) { e.setCancelled(true); return; }
        if (amp == null || !amp.isFighter()) { e.setCancelled(true); return; }
    }

    /**
     * Ensures health BELOW_NAME objective stays intact after damage.
     * Re-injects the objective if another plugin swapped the scoreboard mid-match.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageMonitor(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (plugin.getMatchManager().getMatch(victim.getUniqueId()) != null) {
            plugin.getNameTagProvider().updateHealth(victim);
        }
    }

    // ── Death handling ────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player dead = e.getEntity();
        Match match = plugin.getMatchManager().getMatch(dead.getUniqueId());
        if (match == null) return;

        e.setDeathMessage(null);
        e.getDrops().clear();
        e.setDroppedExp(0);
        e.setKeepInventory(true);

        if (match.getState() != Match.State.ACTIVE) return;

        MatchPlayer deadMp = match.getFighter(dead.getUniqueId());
        if (deadMp == null) return;

        if (match.isFfa()) {
            MatchPlayer killerMp = null;
            if (dead.getKiller() != null) {
                killerMp = match.getFighter(dead.getKiller().getUniqueId());
            }
            plugin.getMatchManager().handleFfaDeath(match, deadMp, killerMp);
        } else {
            MatchPlayer killerMp = match.getOpponent(dead.getUniqueId());
            if (killerMp == null) return;
            plugin.getMatchManager().handleFighterDeath(match, deadMp, killerMp);
        }
    }

    // ── Respawn inside match ──────────────────────────────────────────────────

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
        // intentionally empty — PlayerJoinQuitListener covers disconnect handling
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
