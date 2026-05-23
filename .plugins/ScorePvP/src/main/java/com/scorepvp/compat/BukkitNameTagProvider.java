package com.scorepvp.compat;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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

        matchScoreboards.remove(match.getMatchId());
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
        if (obj == null) return;
        // Push HP for every fighter so the BELOW_NAME score stays current.
        for (UUID uuid : match.getAllUuids()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            obj.getScore(p.getName()).setScore((int) Math.ceil(p.getHealth()));
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
            blueTeam.addEntry(fighter1.getName());
        }

        if (fighter2 != null) {
            Team redTeam = scoreboard.registerNewTeam("red");
            redTeam.color(NamedTextColor.RED);
            String prefix = formatter.buildPrefix(fighter2.getUuid(), modeId, "&c", false);
            redTeam.prefix(LEGACY.deserialize(prefix));
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
            team.addEntry(mp.getName());
        }
    }
}
