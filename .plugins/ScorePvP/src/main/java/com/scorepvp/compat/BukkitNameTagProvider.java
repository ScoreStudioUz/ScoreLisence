package com.scorepvp.compat;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
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
 * Saves each player's scoreboard before replacing it and restores
 * the original on match end (instead of always restoring mainScoreboard).
 */
public class BukkitNameTagProvider implements NameTagProvider {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private final ScorePvP plugin;
    private final NameTagFormatter formatter;
    private final boolean healthEnabled;
    private final String healthSymbol;

    /** matchId → the scoreboard created for that match */
    private final Map<UUID, Scoreboard> matchScoreboards = new HashMap<>();

    /** playerUUID → their original scoreboard (saved before match) */
    private final Map<UUID, Scoreboard> savedPlayerBoards = new HashMap<>();

    /** matchId → periodic task that refreshes each fighter's ping suffix. */
    private final Map<UUID, BukkitTask> pingTasks = new HashMap<>();

    /** playerUUID → last suffix string we pushed. Skips the team.suffix() call
     *  when the rendered text hasn't changed — every team.suffix() write is a
     *  ScoreboardTeamUpdate packet broadcast to every viewer of that scoreboard,
     *  so in FFA with N players + per-damage refresh this was the single biggest
     *  source of packet pressure. */
    private final Map<UUID, String> lastSuffixByPlayer = new HashMap<>();

    public BukkitNameTagProvider(ScorePvP plugin, LuckPermsHook luckPerms) {
        this.plugin = plugin;
        this.formatter = new NameTagFormatter(plugin, luckPerms);
        this.healthEnabled = plugin.getConfig().getBoolean("nametag.health-below-name", true);
        this.healthSymbol = plugin.getConfig().getString("nametag.health-symbol", "&c\u2764");
    }

    public Scoreboard getMatchScoreboard(UUID matchId) {
        return matchScoreboards.get(matchId);
    }

    @Override
    public void setupMatchNametags(Match match) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();

        Objective healthObj = null;
        if (healthEnabled) {
            // DUMMY criteria so we can seed the initial value immediately
            // (HEALTH criteria leaves it at 0 until the first damage event).
            healthObj = scoreboard.registerNewObjective(
                    "health", Criteria.DUMMY,
                    LEGACY.deserialize(healthSymbol)
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

        // Seed every fighter's HP on the BELOW_NAME score so it shows the
        // correct value from the very first frame instead of "0".
        if (healthObj != null) {
            for (UUID uuid : match.getAllUuids()) {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null) continue;
                healthObj.getScore(p.getName()).setScore((int) Math.ceil(p.getHealth()));
            }
        }

        // Refresh ping suffixes periodically — ping is volatile and the player
        // wants to see the OPPONENT's current value, not what it was at match start.
        UUID matchId = match.getMatchId();
        long period = Math.max(1, plugin.getConfig().getLong("nametag.ping-refresh-ticks", 20L));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> refreshPingSuffixes(matchId), period, period);
        pingTasks.put(matchId, task);
    }

    /** Called every second per match: re-stamps each fighter's team suffix with their live ping. */
    private void refreshPingSuffixes(UUID matchId) {
        Scoreboard board = matchScoreboards.get(matchId);
        if (board == null) return;
        Match match = plugin.getMatchManager().getMatchById(matchId);
        if (match == null) return;
        for (UUID uuid : match.getAllUuids()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            Team team = board.getEntryTeam(p.getName());
            if (team == null) continue;
            applySuffixIfChanged(team, p);
        }
    }

    /** Only sends a team.suffix() packet when the rendered text actually changed.
     *  FFA + frequent damage triggered a O(N²) packet storm without this. */
    private void applySuffixIfChanged(Team team, Player p) {
        String next = formatter.buildPingSuffix(p);
        String prev = lastSuffixByPlayer.get(p.getUniqueId());
        if (next.equals(prev)) return;
        lastSuffixByPlayer.put(p.getUniqueId(), next);
        team.suffix(LEGACY.deserialize(next));
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

        BukkitTask task = pingTasks.remove(match.getMatchId());
        if (task != null) task.cancel();
        matchScoreboards.remove(match.getMatchId());
        for (UUID uuid : match.getAllUuids()) lastSuffixByPlayer.remove(uuid);
    }

    @Override
    public void clearMatchNametagsForPlayer(Match match, Player player) {
        UUID id = player.getUniqueId();
        // Restore the player's pre-match scoreboard (or fall back to main).
        Scoreboard saved = savedPlayerBoards.remove(id);
        Scoreboard mainBoard = Bukkit.getScoreboardManager().getMainScoreboard();
        if (player.isOnline()) {
            player.setScoreboard(saved != null ? saved : mainBoard);
            player.playerListName(null);
        }
        // Remove their entry from any team in the still-active match scoreboard
        // so the remaining players don't see ghost entries in their team list.
        Scoreboard board = matchScoreboards.get(match.getMatchId());
        if (board != null) {
            Team team = board.getEntryTeam(player.getName());
            if (team != null) team.removeEntry(player.getName());
            Objective hp = board.getObjective("health");
            if (hp != null) board.resetScores(player.getName());
        }
        lastSuffixByPlayer.remove(id);
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
        if (obj != null) {
            // Only push the damaged player's HP score — every score write is a
            // packet broadcast and FFA was sending N writes per hit.
            obj.getScore(player.getName()).setScore((int) Math.ceil(player.getHealth()));
        }
        // Suffix no longer carries HP (it lives on BELOW_NAME), so we don't
        // re-stamp the suffix here. The periodic ping task handles refreshes.
    }

    @Override
    public void onExternalReload() {
        // Tier or settings change → re-stamp prefixes for every active match.
        // We re-write the team prefix/suffix in place rather than rebuilding
        // the scoreboard from scratch, so the player's view doesn't flash.
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
                    team.suffix(LEGACY.deserialize(formatter.buildPingSuffix(Bukkit.getPlayer(mp.getUuid()))));
                }
            } else {
                MatchPlayer f1 = match.getFighter1();
                MatchPlayer f2 = match.getFighter2();
                if (f1 != null) {
                    Team t = board.getTeam("blue");
                    if (t != null) {
                        String prefix = formatter.buildPrefix(f1.getUuid(), modeId, "&9", false);
                        t.prefix(LEGACY.deserialize(prefix));
                        t.suffix(LEGACY.deserialize(formatter.buildPingSuffix(Bukkit.getPlayer(f1.getUuid()))));
                    }
                }
                if (f2 != null) {
                    Team t = board.getTeam("red");
                    if (t != null) {
                        String prefix = formatter.buildPrefix(f2.getUuid(), modeId, "&c", false);
                        t.prefix(LEGACY.deserialize(prefix));
                        t.suffix(LEGACY.deserialize(formatter.buildPingSuffix(Bukkit.getPlayer(f2.getUuid()))));
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
            blueTeam.suffix(LEGACY.deserialize(formatter.buildPingSuffix(Bukkit.getPlayer(fighter1.getUuid()))));
            blueTeam.addEntry(fighter1.getName());
        }

        if (fighter2 != null) {
            Team redTeam = scoreboard.registerNewTeam("red");
            redTeam.color(NamedTextColor.RED);
            String prefix = formatter.buildPrefix(fighter2.getUuid(), modeId, "&c", false);
            redTeam.prefix(LEGACY.deserialize(prefix));
            redTeam.suffix(LEGACY.deserialize(formatter.buildPingSuffix(Bukkit.getPlayer(fighter2.getUuid()))));
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
            team.suffix(LEGACY.deserialize(formatter.buildPingSuffix(Bukkit.getPlayer(mp.getUuid()))));
            team.addEntry(mp.getName());
        }
    }
}
