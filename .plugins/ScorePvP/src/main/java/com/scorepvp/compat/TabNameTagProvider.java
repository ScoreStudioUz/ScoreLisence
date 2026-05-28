package com.scorepvp.compat;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
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
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

/**
 * NameTag provider that uses the TAB plugin API for prefix management.
 * <p>
 * Layout above each fighter's head:
 *   line 1 — nickname with match prefix (delegated to TAB's NameTagManager)
 *   line 2 — BELOW_NAME, formatted by {@link NameTagFormatter#buildBelowName}
 *            into "{hp}{hp_symbol} | {ping}ms" — same string for every viewer
 *            (per-entry rendering achieved via Paper Score#numberFormat).
 * <p>
 * Why we mix TAB (prefix) and Bukkit (BELOW_NAME): TAB owns line 1 because
 * it carries our tier / team color and we don't want to fight TAB for the
 * Team packet. Line 2 is a vanilla scoreboard objective injected into each
 * player's EXISTING scoreboard, so it doesn't touch TAB's state.
 * <p>
 * Key design principles:
 * - NEVER calls player.setScoreboard() — avoids overriding TAB's scoreboard
 * - NEVER calls player.playerListName() — lets TAB manage the tab list
 * - On setup: saves original TAB prefix, then sets match-specific one; TAB
 *   suffix is forced to empty (HP/ping live on BELOW_NAME now).
 * - On clear: restores original TAB values, unregisters BELOW_NAME objective.
 */
public class TabNameTagProvider implements NameTagProvider {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private static final String HEALTH_OBJ_NAME = "scorepvp_hp";

    private final ScorePvP plugin;
    private final NameTagFormatter formatter;
    private final boolean belowNameEnabled;

    /** matchId → { playerUUID → [originalPrefix, originalSuffix] } */
    private final Map<UUID, Map<UUID, String[]>> savedTags = new HashMap<>();

    /** matchId → set of player UUIDs that had the BELOW_NAME objective injected */
    private final Map<UUID, Set<UUID>> belowInjected = new HashMap<>();

    /** matchId → periodic task that refreshes each fighter's BELOW_NAME line. */
    private final Map<UUID, BukkitTask> refreshTasks = new HashMap<>();

    /** targetUUID → last BELOW_NAME text written for that player. Skips
     *  numberFormat() writes when the rendered text hasn't changed. */
    private final Map<UUID, String> lastBelowByPlayer = new HashMap<>();

    public TabNameTagProvider(ScorePvP plugin, LuckPermsHook luckPerms) {
        this.plugin = plugin;
        this.formatter = new NameTagFormatter(plugin, luckPerms);
        this.belowNameEnabled = plugin.getConfig().getBoolean("nametag.health-below-name", true);
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
                // Suffix forced empty — HP+ping moved to BELOW_NAME.
                ntm.setSuffix(tp, "");
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
                    ntm.setSuffix(tp1, "");
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
                    ntm.setSuffix(tp2, "");
                }
            }
        }

        savedTags.put(match.getMatchId(), saved);

        // Inject BELOW_NAME objective into each player's EXISTING scoreboard
        if (belowNameEnabled) {
            Set<UUID> injected = new HashSet<>();
            for (UUID uuid : match.getAllUuids()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    injectBelowName(player, match);
                    injected.add(uuid);
                }
            }
            belowInjected.put(match.getMatchId(), injected);
        }

        // Re-stamp BELOW_NAME every N ticks so ping (and regenerating HP)
        // stay live for every viewer.
        UUID matchId = match.getMatchId();
        long period = Math.max(1, plugin.getConfig().getLong("nametag.ping-refresh-ticks", 20L));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> refreshBelowName(matchId), period, period);
        refreshTasks.put(matchId, task);
    }

    /** Per-second refresh of every fighter's BELOW_NAME line on every viewer's board. */
    private void refreshBelowName(UUID matchId) {
        Match match = plugin.getMatchManager().getMatchById(matchId);
        if (match == null || match.getState() == Match.State.FINISHED) return;
        Set<UUID> injected = belowInjected.get(matchId);
        if (injected == null) return;

        for (UUID viewer : injected) {
            Player viewerPlayer = Bukkit.getPlayer(viewer);
            if (viewerPlayer == null) continue;
            Scoreboard board = viewerPlayer.getScoreboard();
            Objective obj = board.getObjective(HEALTH_OBJ_NAME);
            if (obj == null) {
                injectBelowName(viewerPlayer, match);
                obj = board.getObjective(HEALTH_OBJ_NAME);
                if (obj == null) continue;
            }
            for (UUID uuid : match.getAllUuids()) {
                Player target = Bukkit.getPlayer(uuid);
                if (target == null) continue;
                writeBelowName(obj, target);
            }
        }
    }

    /** Pushes one player's BELOW_NAME line into ONE objective (one viewer's scoreboard). */
    private void writeBelowName(Objective obj, Player target) {
        String next = formatter.buildBelowName(target);
        String prev = lastBelowByPlayer.get(target.getUniqueId());
        Score score = obj.getScore(target.getName());
        int hp = (int) Math.ceil(target.getHealth());
        if (score.getScore() != hp) score.setScore(hp);
        if (next.equals(prev)) return;
        lastBelowByPlayer.put(target.getUniqueId(), next);
        score.numberFormat(NumberFormat.fixed(LEGACY.deserialize(next)));
    }

    @Override
    public void clearMatchNametagsForPlayer(Match match, Player player) {
        UUID id = player.getUniqueId();
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        Map<UUID, String[]> saved = savedTags.get(match.getMatchId());
        Set<UUID> injected = belowInjected.get(match.getMatchId());

        if (player.isOnline() && injected != null && injected.contains(id)) {
            removeBelowName(player);
            injected.remove(id);
        }
        if (ntm != null && saved != null) {
            String[] orig = saved.remove(id);
            if (orig != null) {
                TabPlayer tp = TabAPI.getInstance().getPlayer(id);
                if (tp != null) {
                    ntm.setPrefix(tp, orig[0]);
                    ntm.setSuffix(tp, orig[1]);
                }
            }
        }
        lastBelowByPlayer.remove(id);
    }

    @Override
    public void clearMatchNametags(Match match) {
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        Map<UUID, String[]> saved = savedTags.remove(match.getMatchId());
        Set<UUID> injected = belowInjected.remove(match.getMatchId());
        BukkitTask task = refreshTasks.remove(match.getMatchId());
        if (task != null) task.cancel();
        for (UUID uuid : match.getAllUuids()) lastBelowByPlayer.remove(uuid);

        for (UUID uuid : match.getAllUuids()) {
            Player player = Bukkit.getPlayer(uuid);

            if (player != null && player.isOnline() && injected != null && injected.contains(uuid)) {
                removeBelowName(player);
            }

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
        Match match = plugin.getMatchManager().getMatch(player.getUniqueId());
        if (match == null) return;
        if (!belowNameEnabled) return;
        Set<UUID> injected = belowInjected.get(match.getMatchId());
        if (injected == null) return;

        // Push this player's HP+ping line into every viewer's scoreboard.
        for (UUID viewer : injected) {
            Player viewerPlayer = Bukkit.getPlayer(viewer);
            if (viewerPlayer == null) continue;
            Scoreboard board = viewerPlayer.getScoreboard();
            Objective obj = board.getObjective(HEALTH_OBJ_NAME);
            if (obj == null) {
                injectBelowName(viewerPlayer, match);
                obj = board.getObjective(HEALTH_OBJ_NAME);
                if (obj == null) continue;
            }
            writeBelowName(obj, player);
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
                    ntm.setSuffix(tp, "");
                }
            } else {
                MatchPlayer f1 = match.getFighter1();
                MatchPlayer f2 = match.getFighter2();
                if (f1 != null) {
                    TabPlayer tp1 = TabAPI.getInstance().getPlayer(f1.getUuid());
                    if (tp1 != null) {
                        String prefix = formatter.buildPrefix(f1.getUuid(), modeId, "&9", false);
                        ntm.setPrefix(tp1, prefix);
                        ntm.setSuffix(tp1, "");
                    }
                }
                if (f2 != null) {
                    TabPlayer tp2 = TabAPI.getInstance().getPlayer(f2.getUuid());
                    if (tp2 != null) {
                        String prefix = formatter.buildPrefix(f2.getUuid(), modeId, "&c", false);
                        ntm.setPrefix(tp2, prefix);
                        ntm.setSuffix(tp2, "");
                    }
                }
            }

            if (belowNameEnabled) {
                Set<UUID> injected = belowInjected.get(matchId);
                if (injected != null) {
                    for (UUID uuid : injected) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null && player.isOnline()) {
                            injectBelowName(player, match);
                        }
                    }
                }
            }
        }
    }

    // ── BELOW_NAME helpers ────────────────────────────────────────────────────

    /** Registers (or refreshes) the BELOW_NAME objective on a single viewer's
     *  scoreboard and seeds every match-member's line. */
    private void injectBelowName(Player viewer, Match match) {
        Scoreboard board = viewer.getScoreboard();
        Objective obj = board.getObjective(HEALTH_OBJ_NAME);
        if (obj == null) {
            // Title is a single space — irrelevant, every score has its own
            // numberFormat that overrides the rendering.
            obj = board.registerNewObjective(
                    HEALTH_OBJ_NAME, Criteria.DUMMY,
                    net.kyori.adventure.text.Component.text(" ")
            );
        }
        obj.setDisplaySlot(DisplaySlot.BELOW_NAME);
        for (UUID uuid : match.getAllUuids()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            writeBelowName(obj, p);
        }
    }

    private void removeBelowName(Player player) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective(HEALTH_OBJ_NAME);
        if (obj != null) {
            obj.unregister();
        }
    }
}
