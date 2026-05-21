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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Listens to attack swings + actual damage events to populate
 * {@link RoundStatsManager} and push an action-bar HUD to attackers
 * with the {@code duels.actionbar} permission.
 *
 *  ● A swing without a damage event = miss (combo reset).
 *  ● A swing followed by an EntityDamageByEntityEvent on a fighter in the
 *    same match in the same tick = hit (combo +1, damage/reach recorded).
 *  ● Action bar shows: combo / best combo / distance / last damage. Fades
 *    out ~1.5s after the last hit.
 */
public class CombatStatsListener implements Listener {

    /** ms before the action bar stops being refreshed after the last hit. */
    private static final long ACTIONBAR_FADE_MS = 1500L;

    private final ScorePvP plugin;
    /** uuid -> tick of last MAIN_HAND swing (used to correlate with damage). */
    private final Map<UUID, Long> pendingSwingTick = new ConcurrentHashMap<>();
    /** uuid -> BukkitTask that refreshes the action bar each tick. */
    private final Map<UUID, BukkitTask> actionbarTasks = new ConcurrentHashMap<>();

    public CombatStatsListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Swing → tentative miss (will be cancelled if damage event arrives) ──

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = e.getPlayer();
        Match match = plugin.getMatchManager().getMatch(p.getUniqueId());
        if (match == null || match.getState() != Match.State.ACTIVE) return;

        long tick = Bukkit.getServer().getCurrentTick();
        pendingSwingTick.put(p.getUniqueId(), tick);

        // Schedule a 1-tick later check: if no damage event consumed this swing, count miss.
        UUID matchId = match.getMatchId();
        UUID uuid = p.getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Long stillPending = pendingSwingTick.get(uuid);
            if (stillPending != null && stillPending == tick) {
                pendingSwingTick.remove(uuid);
                plugin.getRoundStatsManager().registerMiss(matchId, uuid);
                // No actionbar update on miss — just combo break
            }
        }, 1L);
    }

    // ── Damage → confirmed hit ────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!(e.getDamager() instanceof Player attacker)) return;
        if (e.getFinalDamage() <= 0) return;

        Match match = plugin.getMatchManager().getMatch(attacker.getUniqueId());
        if (match == null || match.getState() != Match.State.ACTIVE) return;
        Match vm = plugin.getMatchManager().getMatch(victim.getUniqueId());
        if (vm == null || !vm.getMatchId().equals(match.getMatchId())) return;

        // Consume the pending swing — this swing landed.
        pendingSwingTick.remove(attacker.getUniqueId());

        double dmg = e.getFinalDamage();
        double dist = attacker.getLocation().distance(victim.getLocation());

        plugin.getRoundStatsManager().registerHit(match.getMatchId(),
                attacker.getUniqueId(), victim.getUniqueId(), dmg, dist);

        // Action bar (permission gated)
        if (attacker.hasPermission("duels.actionbar")) {
            startOrRefreshActionbar(attacker);
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        pendingSwingTick.remove(id);
        BukkitTask t = actionbarTasks.remove(id);
        if (t != null) t.cancel();
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
        RoundStatsManager.PlayerRoundStats s = plugin.getRoundStatsManager().getOrNull(matchId, uuid);
        RoundStatsManager.LastHit lh = plugin.getRoundStatsManager().lastHit(uuid);
        int combo = s == null ? 0 : s.currentCombo;
        int best = s == null ? 0 : s.bestCombo;
        double dist = lh == null ? 0d : lh.distance;
        double dmg = lh == null ? 0d : lh.damage;

        String template = plugin.getConfigManager().rawStr("match.actionbar-hit");
        if (template == null || template.isEmpty()) {
            template = "&fКомбо: &b×{combo} &8; &fЛучшее комбо: &6♛{best} &8; &fДистанция: &a▲{dist} &8; &fУрон: &c♥{dmg}";
        }
        return ColorUtil.color(template
                .replace("{combo}", Integer.toString(combo))
                .replace("{best}",  Integer.toString(best))
                .replace("{dist}",  String.format(java.util.Locale.ROOT, "%.1f", dist))
                .replace("{dmg}",   String.format(java.util.Locale.ROOT, "%.2f", dmg)));
    }

    private Component deserialize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
