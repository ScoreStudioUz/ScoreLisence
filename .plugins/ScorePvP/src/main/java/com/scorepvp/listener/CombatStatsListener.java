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
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Combat stats tracker for the per-round HUD.
 *
 * Definitions per user spec:
 *   • HIT  — landed a damaging EntityDamageByEntityEvent on an enemy fighter.
 *   • MISS — main-hand LEFT-clicked AIR (never on an entity or block).
 *
 * We use PlayerInteractEvent LEFT_CLICK_AIR for misses because PlayerAnimation
 * ARM_SWING fires multiple times per attack (animation + cooldown packets),
 * which produced phantom miss-after-hit and reset the combo to 0 every swing.
 */
public class CombatStatsListener implements Listener {

    /** ms before the action bar stops being refreshed after the last hit. */
    private static final long ACTIONBAR_FADE_MS = 1500L;

    private final ScorePvP plugin;
    /** uuid -> BukkitTask that refreshes the action bar each tick. */
    private final Map<UUID, BukkitTask> actionbarTasks = new ConcurrentHashMap<>();

    public CombatStatsListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Left-click air = miss ──────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.LEFT_CLICK_AIR) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Match match = plugin.getMatchManager().getMatch(p.getUniqueId());
        if (match == null || match.getState() != Match.State.ACTIVE) return;

        plugin.getRoundStatsManager().registerMiss(match.getMatchId(), p.getUniqueId());
        // Action-bar refreshes on combo change so the user sees combo break instantly.
        if (p.hasPermission("duels.actionbar")) startOrRefreshActionbar(p);
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

        Player pl = Bukkit.getPlayer(uuid);
        int ping = pl != null ? pl.getPing() : 0;
        int hp = pl != null ? (int) Math.ceil(pl.getHealth()) : 0;
        int maxHp = 20;
        if (pl != null) {
            org.bukkit.attribute.AttributeInstance attr =
                    pl.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
            if (attr != null) maxHp = (int) Math.ceil(attr.getValue());
        }

        String template = plugin.getConfigManager().rawStr("match.actionbar-hit");
        if (template == null || template.isEmpty()) {
            template = "&fКомбо: &b×{combo} &8; &fЛучшее комбо: &6♛{best} &8; &fДистанция: &a▲{dist} &8; &fУрон: &c♥{dmg} &8; &fПинг: {ping} &8; &fHP: &c{hp}&8/&c{max_hp}";
        }
        return ColorUtil.color(template
                .replace("{combo}", Integer.toString(combo))
                .replace("{best}",  Integer.toString(best))
                .replace("{dist}",  String.format(java.util.Locale.ROOT, "%.1f", dist))
                .replace("{dmg}",   String.format(java.util.Locale.ROOT, "%.2f", dmg))
                .replace("{ping}",  colorizePing(ping))
                .replace("{hp}",    Integer.toString(hp))
                .replace("{max_hp}", Integer.toString(maxHp)));
    }

    /** Wraps ping value in a colour code chosen by config-driven thresholds. */
    private String colorizePing(int ping) {
        int goodMax = plugin.getConfig().getInt("hud.ping.good-max", 80);
        int midMax  = plugin.getConfig().getInt("hud.ping.mid-max", 150);
        String good = plugin.getConfig().getString("hud.ping.good-color", "&a");
        String mid  = plugin.getConfig().getString("hud.ping.mid-color", "&6");
        String bad  = plugin.getConfig().getString("hud.ping.bad-color", "&c");
        String colour = ping <= goodMax ? good : (ping <= midMax ? mid : bad);
        return colour + ping;
    }

    private Component deserialize(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }
}
