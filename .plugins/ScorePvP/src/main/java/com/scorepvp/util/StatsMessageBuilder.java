package com.scorepvp.util;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.RoundStatsManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the per-player round-finished / duel-finished messages described in
 * the spec. The visible content depends on the recipient's permissions:
 *
 *   ● Header + winner + score: always shown.
 *   ● {@code duels.stats.accuracy}: adds "⇂ Точность: X%" line.
 *   ● {@code duels.stats.view}: adds "⇂ Статистика: [посмотреть]" line whose
 *     [посмотреть] component carries a hover-tooltip with the full breakdown.
 *
 * Lang keys consumed (see {@code lang/ru.yml}):
 *   round-finished.{header,winner-line,score-line,accuracy-line,
 *                    stats-line,view-text,view-hover}
 *   duel-finished.{header,winner-line,score-line,stats-line,view-text,view-hover}
 *   stats-tooltip
 */
public final class StatsMessageBuilder {

    public static final String PERM_ACCURACY = "duels.stats.accuracy";
    public static final String PERM_VIEW     = "duels.stats.view";

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

        // Accuracy line — only round-finished, only with the perm
        if ("round-finished".equals(key) && recipient.hasPermission(PERM_ACCURACY)) {
            double acc = plugin.getRoundStatsManager().currentAccuracy(matchId, viewerUuid);
            recipient.sendMessage(plugin.getConfigManager().rawMsg(key + ".accuracy-line",
                    "{accuracy}", fmt1(acc)));
        }

        // Stats line — only on duel-finished (full-match summary). Round-finished
        // shows accuracy only. Gated by duels.stats.view.
        if ("duel-finished".equals(key) && recipient.hasPermission(PERM_VIEW)) {
            Component statsLine = buildStatsLine(plugin, matchId, viewerUuid, key);
            recipient.sendMessage(statsLine);
        }
    }

    private static Component buildStatsLine(ScorePvP plugin, UUID matchId,
                                             UUID viewerUuid, String key) {
        // Template: "&6⇂ &fСтатистика: &b{stats_view}"
        String tpl = plugin.getConfigManager().rawStr(key + ".stats-line");
        String viewText = plugin.getConfigManager().rawStr(key + ".view-text");
        String viewHover = plugin.getConfigManager().rawStr(key + ".view-hover");
        if (tpl == null || tpl.isEmpty()) tpl = "&6⇂ &fСтатистика: {stats_view}";
        if (viewText == null || viewText.isEmpty()) viewText = "&b[посмотреть]";

        int placeholderIdx = tpl.indexOf("{stats_view}");
        String prefix = placeholderIdx < 0 ? tpl : tpl.substring(0, placeholderIdx);
        String suffix = placeholderIdx < 0 ? "" : tpl.substring(placeholderIdx + "{stats_view}".length());

        // Hover content
        RoundStatsManager.PlayerRoundStats totals = plugin.getRoundStatsManager().totals(matchId, viewerUuid);
        List<String> tooltipLines = plugin.getConfigManager().msgLines("stats-tooltip",
                "{hits}",       Integer.toString(totals.hits),
                "{misses}",     Integer.toString(totals.misses),
                "{accuracy}",   fmt1(totals.accuracy()),
                "{damage}",     fmt1(totals.totalDamage),
                "{avg_reach}",  fmt1(totals.avgReach()),
                "{best_combo}", Integer.toString(totals.bestCombo));

        Component tooltip = Component.empty();
        for (int i = 0; i < tooltipLines.size(); i++) {
            tooltip = tooltip.append(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(tooltipLines.get(i)));
            if (i < tooltipLines.size() - 1) tooltip = tooltip.append(Component.newline());
        }

        Component clickable = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(ColorUtil.color(viewText))
                .hoverEvent(HoverEvent.showText(tooltip));

        Component prefixC = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(ColorUtil.color(prefix));
        Component suffixC = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(ColorUtil.color(suffix));

        return Component.empty().append(prefixC).append(clickable).append(suffixC);
    }

    private static String fmt1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
