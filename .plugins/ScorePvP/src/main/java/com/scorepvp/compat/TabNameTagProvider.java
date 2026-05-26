package com.scorepvp.compat;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.nametag.NameTagManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

/**
 * NameTag provider that uses the TAB plugin API for prefix/suffix management.
 * <p>
 * Key design principles:
 * - NEVER calls player.setScoreboard() — avoids overriding TAB's scoreboard
 * - NEVER calls player.playerListName() — lets TAB manage the tab list
 * - Health BELOW_NAME is injected into the player's EXISTING scoreboard
 * - On setup: saves original TAB prefix/suffix, then sets match-specific ones
 * - On clear: restores original TAB values, unregisters health objective
 */
public class TabNameTagProvider implements NameTagProvider {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private static final String HEALTH_OBJ_NAME = "scorepvp_hp";

    private final ScorePvP plugin;
    private final NameTagFormatter formatter;
    private final boolean healthEnabled;
    private final String healthSymbol;

    /** matchId → { playerUUID → [originalPrefix, originalSuffix] } */
    private final Map<UUID, Map<UUID, String[]>> savedTags = new HashMap<>();

    /** matchId → set of player UUIDs that had health objective injected */
    private final Map<UUID, Set<UUID>> healthInjected = new HashMap<>();

    /** matchId → periodic task that refreshes each fighter's ping suffix. */
    private final Map<UUID, BukkitTask> pingTasks = new HashMap<>();

    public TabNameTagProvider(ScorePvP plugin, LuckPermsHook luckPerms) {
        this.plugin = plugin;
        this.formatter = new NameTagFormatter(plugin, luckPerms);
        this.healthEnabled = plugin.getConfig().getBoolean("nametag.health-below-name", true);
        this.healthSymbol = plugin.getConfig().getString("nametag.health-symbol", "&c\u2764");
    }

    @Override
    public void setupMatchNametags(Match match) {
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        if (ntm == null) return;

        Map<UUID, String[]> saved = new HashMap<>();
        String modeId = match.getMode().getId();

        if (match.isFfa()) {
            for (MatchPlayer mp : match.getFfaFighters()) {
                TabPlayer tp = TabAPI.getInstance().getPlayer(mp.getUuid());
                if (tp == null) continue;

                saved.put(mp.getUuid(), new String[]{
                        ntm.getOriginalPrefix(tp),
                        ntm.getOriginalSuffix(tp)
                });

                String prefix = formatter.buildPrefix(mp.getUuid(), modeId, "", true);
                ntm.setPrefix(tp, prefix);
                ntm.setSuffix(tp, formatter.buildPingSuffix(Bukkit.getPlayer(mp.getUuid())));
            }
        } else {
            MatchPlayer f1 = match.getFighter1();
            MatchPlayer f2 = match.getFighter2();

            if (f1 != null) {
                TabPlayer tp1 = TabAPI.getInstance().getPlayer(f1.getUuid());
                if (tp1 != null) {
                    saved.put(f1.getUuid(), new String[]{
                            ntm.getOriginalPrefix(tp1),
                            ntm.getOriginalSuffix(tp1)
                    });
                    String prefix = formatter.buildPrefix(f1.getUuid(), modeId, "&9", false);
                    ntm.setPrefix(tp1, prefix);
                    ntm.setSuffix(tp1, formatter.buildPingSuffix(Bukkit.getPlayer(f1.getUuid())));
                }
            }
            if (f2 != null) {
                TabPlayer tp2 = TabAPI.getInstance().getPlayer(f2.getUuid());
                if (tp2 != null) {
                    saved.put(f2.getUuid(), new String[]{
                            ntm.getOriginalPrefix(tp2),
                            ntm.getOriginalSuffix(tp2)
                    });
                    String prefix = formatter.buildPrefix(f2.getUuid(), modeId, "&c", false);
                    ntm.setPrefix(tp2, prefix);
                    ntm.setSuffix(tp2, formatter.buildPingSuffix(Bukkit.getPlayer(f2.getUuid())));
                }
            }
        }

        savedTags.put(match.getMatchId(), saved);

        // Inject health objective into each player's EXISTING scoreboard
        if (healthEnabled) {
            Set<UUID> injected = new HashSet<>();
            for (UUID uuid : match.getAllUuids()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    injectHealthObjective(player);
                    injected.add(uuid);
                }
            }
            healthInjected.put(match.getMatchId(), injected);
        }

        // Re-stamp ping suffix every second so opponents see the LIVE ping,
        // not what was sampled at match start.
        UUID matchId = match.getMatchId();
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> refreshPingSuffixes(matchId), 20L, 20L);
        pingTasks.put(matchId, task);
    }

    /** Per-second refresh of every fighter's TAB suffix with their live ping. */
    private void refreshPingSuffixes(UUID matchId) {
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        if (ntm == null) return;
        Match match = plugin.getMatchManager().getMatchById(matchId);
        if (match == null || match.getState() == Match.State.FINISHED) return;
        for (UUID uuid : match.getAllUuids()) {
            TabPlayer tp = TabAPI.getInstance().getPlayer(uuid);
            if (tp == null) continue;
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            ntm.setSuffix(tp, formatter.buildPingSuffix(p));
        }
    }

    @Override
    public void clearMatchNametags(Match match) {
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        Map<UUID, String[]> saved = savedTags.remove(match.getMatchId());
        Set<UUID> injected = healthInjected.remove(match.getMatchId());
        BukkitTask task = pingTasks.remove(match.getMatchId());
        if (task != null) task.cancel();

        for (UUID uuid : match.getAllUuids()) {
            Player player = Bukkit.getPlayer(uuid);

            // Remove health objective from player's scoreboard
            if (player != null && player.isOnline() && injected != null && injected.contains(uuid)) {
                removeHealthObjective(player);
            }

            // Restore TAB prefix/suffix
            if (ntm != null && saved != null && saved.containsKey(uuid)) {
                TabPlayer tp = TabAPI.getInstance().getPlayer(uuid);
                if (tp != null) {
                    String[] orig = saved.get(uuid);
                    ntm.setPrefix(tp, orig[0]);
                    ntm.setSuffix(tp, orig[1]);
                }
            }
        }
    }

    @Override
    public void updateHealth(Player player) {
        if (!healthEnabled) return;

        Match match = plugin.getMatchManager().getMatch(player.getUniqueId());
        if (match == null) return;

        Set<UUID> injected = healthInjected.get(match.getMatchId());
        if (injected == null || !injected.contains(player.getUniqueId())) return;

        // Push the latest HP for every fighter in the match — the objective
        // is DUMMY so it does not auto-track health. Pushing on every damage
        // event keeps the BELOW_NAME score in sync.
        for (UUID uuid : match.getAllUuids()) {
            Player target = Bukkit.getPlayer(uuid);
            if (target == null) continue;
            Scoreboard board = target.getScoreboard();
            Objective obj = board.getObjective(HEALTH_OBJ_NAME);
            if (obj == null) {
                injectHealthObjective(target);
                obj = board.getObjective(HEALTH_OBJ_NAME);
                if (obj == null) continue;
            }
            for (UUID other : match.getAllUuids()) {
                Player o = Bukkit.getPlayer(other);
                if (o == null) continue;
                obj.getScore(o.getName()).setScore((int) Math.ceil(o.getHealth()));
            }
        }
    }

    @Override
    public void onExternalReload() {
        // TAB was reloaded — re-apply prefixes for all active matches
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        if (ntm == null) return;

        for (Map.Entry<UUID, Map<UUID, String[]>> entry : savedTags.entrySet()) {
            UUID matchId = entry.getKey();
            Match match = plugin.getMatchManager().getMatchById(matchId);
            if (match == null || match.getState() == Match.State.FINISHED) continue;

            String modeId = match.getMode().getId();

            if (match.isFfa()) {
                for (MatchPlayer mp : match.getFfaFighters()) {
                    TabPlayer tp = TabAPI.getInstance().getPlayer(mp.getUuid());
                    if (tp == null) continue;
                    String prefix = formatter.buildPrefix(mp.getUuid(), modeId, "", true);
                    ntm.setPrefix(tp, prefix);
                    ntm.setSuffix(tp, formatter.buildPingSuffix(Bukkit.getPlayer(mp.getUuid())));
                }
            } else {
                MatchPlayer f1 = match.getFighter1();
                MatchPlayer f2 = match.getFighter2();
                if (f1 != null) {
                    TabPlayer tp1 = TabAPI.getInstance().getPlayer(f1.getUuid());
                    if (tp1 != null) {
                        String prefix = formatter.buildPrefix(f1.getUuid(), modeId, "&9", false);
                        ntm.setPrefix(tp1, prefix);
                        ntm.setSuffix(tp1, formatter.buildPingSuffix(Bukkit.getPlayer(f1.getUuid())));
                    }
                }
                if (f2 != null) {
                    TabPlayer tp2 = TabAPI.getInstance().getPlayer(f2.getUuid());
                    if (tp2 != null) {
                        String prefix = formatter.buildPrefix(f2.getUuid(), modeId, "&c", false);
                        ntm.setPrefix(tp2, prefix);
                        ntm.setSuffix(tp2, formatter.buildPingSuffix(Bukkit.getPlayer(f2.getUuid())));
                    }
                }
            }

            // Re-inject health objectives
            if (healthEnabled) {
                Set<UUID> injected = healthInjected.get(matchId);
                if (injected != null) {
                    for (UUID uuid : injected) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null && player.isOnline()) {
                            injectHealthObjective(player);
                        }
                    }
                }
            }
        }
    }

    // ── Health objective helpers ──────────────────────────────────────────────────

    private void injectHealthObjective(Player player) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective(HEALTH_OBJ_NAME);
        if (obj == null) {
            // DUMMY criteria — we push values manually on damage so the
            // BELOW_NAME score is correct from the very first frame instead
            // of waiting for a damage tick from the HEALTH criteria.
            obj = board.registerNewObjective(
                    HEALTH_OBJ_NAME, Criteria.DUMMY,
                    LEGACY.deserialize(healthSymbol)
            );
        }
        obj.setDisplaySlot(DisplaySlot.BELOW_NAME);
        // Seed scores for every player in the match so the value is non-zero
        // immediately. The MatchListener.onDamageMonitor handler keeps it in
        // sync after that.
        Match match = plugin.getMatchManager().getMatch(player.getUniqueId());
        if (match != null) {
            for (UUID uuid : match.getAllUuids()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    obj.getScore(p.getName()).setScore((int) Math.ceil(p.getHealth()));
                }
            }
        } else {
            obj.getScore(player.getName()).setScore((int) Math.ceil(player.getHealth()));
        }
    }

    private void removeHealthObjective(Player player) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective(HEALTH_OBJ_NAME);
        if (obj != null) {
            obj.unregister();
        }
    }
}
