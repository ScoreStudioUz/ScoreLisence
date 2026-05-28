package com.scorepvp.util;

import com.scorepvp.ScorePvP;

import java.util.List;
import java.util.UUID;

/**
 * Builds the per-player round-finished / duel-finished messages.
 *
 * Previously rendered three extra lines (accuracy / stats / [посмотреть]
 * hover-tooltip) gated by perms — all of that was removed by request, so
 * the message is now just header + winner + score. The {@code accuracy-line},
 * {@code stats-line}, {@code view-text}, {@code view-hover} keys in
 * {@code lang/ru.yml} are also gone.
 *
 * Lang keys consumed (see {@code lang/ru.yml}):
 *   round-finished.{header,winner-line,score-line}
 *   duel-finished.{header,winner-line,score-line}
 */
public final class StatsMessageBuilder {

    private StatsMessageBuilder() {}

    /**
     * Send the round-finished block to the given recipient.
     *
     * @param plugin     ScorePvP instance
     * @param recipient  player who receives the message
     * @param matchId    match UUID (for stats lookup)
     * @param viewerUuid which player's accuracy/stats to display in the
     *                   accuracy + stats lines (usually = recipient)
     * @param round      round number just finished
     * @param winnerName winner display name
     * @param hp         winner remaining HP (formatted string)
     * @param winnerScore / loserScore current score
     */
    public static void sendRoundFinished(ScorePvP plugin,
                                          org.bukkit.entity.Player recipient,
                                          UUID matchId, UUID viewerUuid,
                                          int round,
                                          String winnerName,
                                          String hp,
                                          int winnerScore,
                                          int loserScore) {
        send(plugin, recipient, matchId, viewerUuid, "round-finished",
                /*round*/  String.valueOf(round),
                /*winner*/ winnerName,
                /*hp*/     hp,
                /*winsW*/  String.valueOf(winnerScore),
                /*winsL*/  String.valueOf(loserScore));
    }

    /** Send the duel-finished block (end of match summary). */
    public static void sendDuelFinished(ScorePvP plugin,
                                         org.bukkit.entity.Player recipient,
                                         UUID matchId, UUID viewerUuid,
                                         String winnerName,
                                         String hp,
                                         int winnerScore,
                                         int loserScore) {
        send(plugin, recipient, matchId, viewerUuid, "duel-finished",
                "",
                winnerName,
                hp,
                String.valueOf(winnerScore),
                String.valueOf(loserScore));
    }

    private static void send(ScorePvP plugin,
                              org.bukkit.entity.Player recipient,
                              UUID matchId, UUID viewerUuid,
                              String key,
                              String roundOrEmpty,
                              String winnerName,
                              String hp,
                              String wWins, String lWins) {

        // Header (multi-line)
        List<String> header = plugin.getConfigManager().msgLines(key + ".header",
                "{round}", roundOrEmpty);
        for (String line : header) recipient.sendMessage(line);

        // Winner line
        recipient.sendMessage(plugin.getConfigManager().rawMsg(key + ".winner-line",
                "{winner}", winnerName,
                "{hp}", hp));

        // Score line
        recipient.sendMessage(plugin.getConfigManager().rawMsg(key + ".score-line",
                "{winner_score}", wWins,
                "{loser_score}", lWins));

        // accuracy-line / stats-line / [посмотреть] hover were removed
        // by request — see class header.
    }
}
