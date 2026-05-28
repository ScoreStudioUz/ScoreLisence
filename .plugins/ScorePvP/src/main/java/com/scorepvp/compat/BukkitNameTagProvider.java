package com.scorepvp.compat;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Default nametag provider using Bukkit Scoreboard Team API.
 * Used when the TAB plugin is not present.
 * <p>
 * Layout above each fighter's head:
 *   line 1 — nickname (with optional team / tier prefix)
 *   line 2 — BELOW_NAME, formatted via {@link NameTagFormatter#buildBelowName}
 *            as "{hp_color}{hp}{hp_symbol} | {ping_color}{ping}ms" — that is
 *            THAT player's own HP and ping, identical for every viewer.
 * <p>
 * The trick: BELOW_NAME normally renders "{score} {objective_title}", where
 * title is shared across all entries. Paper 1.20.4+ added per-score
 * {@code Score#numberFormat(NumberFormat)} — we override each entry's score
 * with a {@link NumberFormat#fixed(net.kyori.adventure.text.ComponentLike)
 * fixed Component} so every name gets its own line independent of the title.
 * The numeric score itself becomes irrelevant (we leave it equal to HP just
 * for clarity if someone inspects the scoreboard).
 */
public class BukkitNameTagProvider implements NameTagProvider {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final ScorePvP plugin;
    private final NameTagFormatter formatter;
    private final boolean belowNameEnabled;

    /** matchId → the scoreboard created for that match */
    private final Map<UUID, Scoreboard> matchScoreboards = new HashMap<>();

    /** playerUUID → their original scoreboard (saved before match) */
    private final Map<UUID, Scoreboard> savedPlayerBoards = new HashMap<>();

    /** matchId → periodic task that refreshes each fighter's BELOW_NAME line. */
    private final Map<UUID, BukkitTask> refreshTasks = new HashMap<>();

    /** playerUUID → last BELOW_NAME text string we pushed.
     *  Skips numberFormat() writes when the rendered text hasn't changed —
     *  every write is a SetScore packet broadcast to every viewer. */
    private final Map<UUID, String> lastBelowByPlayer = new HashMap<>();

    public BukkitNameTagProvider(ScorePvP plugin, LuckPermsHook luckPerms) {
        this.plugin = plugin;
        this.formatter = new NameTagFormatter(plugin, luckPerms);
        this.belowNameEnabled = plugin.getConfig().getBoolean("nametag.health-below-name", true);
    }

    public Scoreboard getMatchScoreboard(UUID matchId) {
        return matchScoreboards.get(matchId);
    }

    @Override
    public void setupMatchNametags(Match match) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();

        Objective healthObj = null;
        if (belowNameEnabled) {
            // DUMMY criteria — we drive the value (and the visible text via
            // numberFormat) manually. HEALTH criteria would fight us for the
            // raw score number.
            //
            // The objective title is intentionally a single space: when a
            // per-score numberFormat is set, that overrides the rendered
            // chunk completely, so the title's color/symbol no longer matters.
            healthObj = scoreboard.registerNewObjective(
                    "health", Criteria.DUMMY,
                    Component.text(" ")
            );
            healthObj.setDisplaySlot(DisplaySlot.BELOW_NAME);
        }

        String modeId = match.getMode().getId();

        if (match.isFfa()) {
            setupFfaTeams(scoreboard, match, modeId);
        } else {
            setupDuelTeams(scoreboard, match, modeId);
        }

        for (UUID uuid : match.getAllUuids()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                savedPlayerBoards.put(uuid, player.getScoreboard());
                player.setScoreboard(scoreboard);
                player.playerListName(Component.text(player.getName()));
            }
        }

        matchScoreboards.put(match.getMatchId(), scoreboard);

        // Seed every fighter's BELOW_NAME line ("20❤ | 50ms" etc.) so it
        // shows correct data from the very first frame instead of "0".
        if (healthObj != null) {
            for (UUID uuid : match.getAllUuids()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                writeBelowName(healthObj, p);
            }
        }

        // Refresh BELOW_NAME line periodically — ping changes constantly and
        // HP can change without a damage event (regen, /heal). Damage-driven
        // updates go through updateHealth().
        UUID matchId = match.getMatchId();
        long period = Math.max(1, plugin.getConfig().getLong("nametag.ping-refresh-ticks", 20L));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> refreshBelowName(matchId), period, period);
        refreshTasks.put(matchId, task);
    }

    /** Per-second refresh of every fighter's BELOW_NAME line. */
    private void refreshBelowName(UUID matchId) {
        Scoreboard board = matchScoreboards.get(matchId);
        if (board == null) return;
        Match match = plugin.getMatchManager().getMatchById(matchId);
        if (match == null) return;
        Objective obj = board.getObjective("health");
        if (obj == null) return;
        for (UUID uuid : match.getAllUuids()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            writeBelowName(obj, p);
        }
    }

    /**
     * Diff-checked write of a player's BELOW_NAME line via
     * {@link NumberFormat#fixed}. Only fires a packet when the rendered
     * legacy-string actually changed.
     */
    private void writeBelowName(Objective obj, Player p) {
        String next = formatter.buildBelowName(p);
        String prev = lastBelowByPlayer.get(p.getUniqueId());
        Score score = obj.getScore(p.getName());
        // Keep the raw int in sync with HP for /scoreboard inspectors;
        // the displayed text is fully governed by numberFormat below.
        int hp = (int) Math.ceil(p.getHealth());
        if (score.getScore() != hp) score.setScore(hp);
        if (next.equals(prev)) return;
        lastBelowByPlayer.put(p.getUniqueId(), next);
        score.numberFormat(NumberFormat.fixed(LEGACY.deserialize(next)));
    }

    @Override
    public void clearMatchNametags(Match match) {
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();

        for (UUID uuid : match.getAllUuids()) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                Scoreboard saved = savedPlayerBoards.remove(uuid);
                player.setScoreboard(saved != null ? saved : mainBoard);
                player.playerListName(null);
            } else {
                savedPlayerBoards.remove(uuid);
            }
        }

        BukkitTask task = refreshTasks.remove(match.getMatchId());
        if (task != null) task.cancel();
        matchScoreboards.remove(match.getMatchId());
        for (UUID uuid : match.getAllUuids()) lastBelowByPlayer.remove(uuid);
    }

    @Override
    public void clearMatchNametagsForPlayer(Match match, Player player) {
        UUID id = player.getUniqueId();
        Scoreboard saved = savedPlayerBoards.remove(id);
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        if (player.isOnline()) {
            player.setScoreboard(saved != null ? saved : mainBoard);
            player.playerListName(null);
        }
        Scoreboard board = matchScoreboards.get(match.getMatchId());
        if (board != null) {
            Team team = board.getEntryTeam(player.getName());
            if (team != null) team.removeEntry(player.getName());
            Objective hp = board.getObjective("health");
            if (hp != null) board.resetScores(player.getName());
        }
        lastBelowByPlayer.remove(id);
    }

    @Override
    public void updateHealth(Player player) {
        Match match = plugin.getMatchManager().getMatch(player.getUniqueId());
        if (match == null) return;

        Scoreboard board = matchScoreboards.get(match.getMatchId());
        if (board == null) return;

        if (!player.getScoreboard().equals(board)) {
            player.setScoreboard(board);
        }

        Objective obj = board.getObjective("health");
        if (obj != null) writeBelowName(obj, player);
    }

    @Override
    public void onExternalReload() {
        // Tier or settings change → re-stamp prefixes for every active match.
        // We re-write the team prefix in place rather than rebuilding the
        // scoreboard from scratch, so the player's view doesn't flash.
        for (Map.Entry<UUID, Scoreboard> entry : matchScoreboards.entrySet()) {
            Match match = plugin.getMatchManager().getMatchById(entry.getKey());
            if (match == null || match.getState() == Match.State.FINISHED) continue;
            String modeId = match.getMode().getId();
            Scoreboard board = entry.getValue();

            if (match.isFfa()) {
                int idx = 0;
                for (MatchPlayer mp : match.getFfaFighters()) {
                    String teamName = "ffa_" + idx++;
                    if (teamName.length() > 16) teamName = teamName.substring(0, 16);
                    Team team = board.getTeam(teamName);
                    if (team == null) continue;
                    String prefix = formatter.buildPrefix(mp.getUuid(), modeId, "", true);
                    team.prefix(LEGACY.deserialize(prefix));
                    team.suffix(Component.empty());
                }
            } else {
                MatchPlayer f1 = match.getFighter1();
                MatchPlayer f2 = match.getFighter2();
                if (f1 != null) {
                    Team t = board.getTeam("blue");
                    if (t != null) {
                        String prefix = formatter.buildPrefix(f1.getUuid(), modeId, "&9", false);
                        t.prefix(LEGACY.deserialize(prefix));
                        t.suffix(Component.empty());
                    }
                }
                if (f2 != null) {
                    Team t = board.getTeam("red");
                    if (t != null) {
                        String prefix = formatter.buildPrefix(f2.getUuid(), modeId, "&c", false);
                        t.prefix(LEGACY.deserialize(prefix));
                        t.suffix(Component.empty());
                    }
                }
            }
        }
    }

    // ── Duel teams ──────────────────────────────────────────────────────────────

    private void setupDuelTeams(Scoreboard scoreboard, Match match, String modeId) {
        MatchPlayer fighter1 = match.getFighter1();
        MatchPlayer fighter2 = match.getFighter2();

        if (fighter1 != null) {
            Team blueTeam = scoreboard.registerNewTeam("blue");
            blueTeam.color(NamedTextColor.BLUE);
            String prefix = formatter.buildPrefix(fighter1.getUuid(), modeId, "&9", false);
            blueTeam.prefix(LEGACY.deserialize(prefix));
            // Suffix intentionally empty — HP+ping live on BELOW_NAME now.
            blueTeam.suffix(Component.empty());
            blueTeam.addEntry(fighter1.getName());
        }

        if (fighter2 != null) {
            Team redTeam = scoreboard.registerNewTeam("red");
            redTeam.color(NamedTextColor.RED);
            String prefix = formatter.buildPrefix(fighter2.getUuid(), modeId, "&c", false);
            redTeam.prefix(LEGACY.deserialize(prefix));
            redTeam.suffix(Component.empty());
            redTeam.addEntry(fighter2.getName());
        }
    }

    // ── FFA teams ───────────────────────────────────────────────────────────────

    private void setupFfaTeams(Scoreboard scoreboard, Match match, String modeId) {
        int index = 0;
        for (MatchPlayer mp : match.getFfaFighters()) {
            String teamName = "ffa_" + index++;
            if (teamName.length() > 16) teamName = teamName.substring(0, 16);

            Team team = scoreboard.registerNewTeam(teamName);
            team.color(NamedTextColor.WHITE);

            String prefix = formatter.buildPrefix(mp.getUuid(), modeId, "", true);
            team.prefix(LEGACY.deserialize(prefix));
            team.suffix(Component.empty());
            team.addEntry(mp.getName());
        }
    }
}
