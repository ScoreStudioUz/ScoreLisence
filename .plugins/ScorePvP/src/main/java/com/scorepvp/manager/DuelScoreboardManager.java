package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import com.scorepvp.util.ColorUtil;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.scoreboard.Line;
import me.neznamy.tab.api.scoreboard.Scoreboard;
import me.neznamy.tab.api.scoreboard.ScoreboardManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.*;

/**
 * Per-duel sidebar scoreboard, config-driven via {@code scoreboard.yml}.
 *
 * <p>Shows ONLY in 1v1 duel / queue matches — never in FFA. Rendered entirely
 * through the TAB plugin's own {@link ScoreboardManager}: TAB owns every sidebar
 * packet, so there is zero packet war with TAB and no vanilla-scoreboard
 * conflict. On match end {@code resetScoreboard()} hands the board back to TAB's
 * own lobby scoreboard.
 *
 * <p><b>No flicker:</b> the periodic refresh updates each line in place via
 * {@link Line#setText} instead of tearing the board down and rebuilding it. The
 * board object is created once per viewer and only recreated if the configured
 * line-count changes (a reload), never every tick.
 *
 * <p>If TAB is absent the scoreboard is simply not shown (this server always
 * runs TAB); there is intentionally no Bukkit fallback, which previously caused
 * the flicker and could fight a vanilla sidebar.
 *
 * Everything is per-viewer: {@code %you%}/{@code %opponent%} resolve relative to
 * the player looking at the board, and {@code %player_1%}/{@code %player_2%} are
 * fixed to fighter1 (blue) / fighter2 (red).
 */
public class DuelScoreboardManager {

    private final ScorePvP plugin;

    // ── Config ────────────────────────────────────────────────────────────────
    private boolean enabled;
    private boolean showToSpectators;
    private long refreshTicks;
    private String title;
    private List<String> lines;
    private String redColor;   // legacy/hex color code for the RED side
    private String blueColor;  // legacy/hex color code for the BLUE side

    /** matchId → periodic refresh task. */
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();

    /** viewerUUID → TAB scoreboard object we created for them. */
    private final Map<UUID, Scoreboard> boards = new HashMap<>();

    /** viewerUUID → the line-count WE created the board with. Compared against the
     *  config line-count to decide recreation — NEVER against TAB's own
     *  {@code getLines().size()}, which may differ from what we passed (TAB can
     *  store empty/long lines differently), which would recreate every tick and
     *  cause the flicker the user reported. */
    private final Map<UUID, Integer> lineCounts = new HashMap<>();

    public DuelScoreboardManager(ScorePvP plugin) {
        this.plugin = plugin;
        reload();
    }

    // ── Config load ─────────────────────────────────────────────────────────────

    public void reload() {
        File file = new File(plugin.getDataFolder(), "scoreboard.yml");
        if (!file.exists()) {
            try { plugin.saveResource("scoreboard.yml", false); } catch (IllegalArgumentException ignored) {}
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        enabled          = cfg.getBoolean("enabled", true);
        showToSpectators = cfg.getBoolean("show-to-spectators", true);
        refreshTicks     = Math.max(1, cfg.getLong("refresh-ticks", 10));
        title            = cfg.getString("title", "&cDUEL");
        redColor         = cfg.getString("team-colors.red",  "&c");
        blueColor        = cfg.getString("team-colors.blue", "&9");
        lines            = cfg.getStringList("lines");
        if (lines.isEmpty()) {
            lines = List.of("%p1_color%%player_1% &7» %p1_color%%score_1%",
                            "%p2_color%%player_2% &7» %p2_color%%score_2%");
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Shows the duel scoreboard to every viewer of a 1v1 match and starts the
     * periodic refresh. No-op for FFA matches, when disabled, or when TAB is
     * absent.
     */
    public void show(Match match) {
        if (!enabled || match == null || match.isFfa()) return;
        if (scoreboardManager() == null) return;
        UUID matchId = match.getMatchId();
        cancelTask(matchId);

        renderAll(match);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            Match m = plugin.getMatchManager().getMatchById(matchId);
            if (m == null || m.getState() == Match.State.FINISHED) { cancelTask(matchId); return; }
            renderAll(m);
        }, refreshTicks, refreshTicks);
        tasks.put(matchId, task);
    }

    /** Removes the scoreboard from all of a match's viewers and stops refresh. */
    public void hide(Match match) {
        if (match == null) return;
        cancelTask(match.getMatchId());
        for (UUID uuid : match.getAllUuids()) hideFor(uuid);
    }

    /** Removes the scoreboard for a single player (e.g. one who left mid-match). */
    public void hideFor(UUID uuid) {
        lineCounts.remove(uuid);
        Scoreboard board = boards.remove(uuid);
        if (board == null) return;
        try {
            ScoreboardManager sm = scoreboardManager();
            TabPlayer tp = tabPlayer(uuid);
            if (sm != null && tp != null) sm.resetScoreboard(tp);
            board.unregister();
        } catch (Throwable ignored) {}
    }

    private void cancelTask(UUID matchId) {
        BukkitTask t = tasks.remove(matchId);
        if (t != null) t.cancel();
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    private void renderAll(Match match) {
        for (UUID uuid : match.getAllUuids()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) continue;
            boolean fighter = match.isFighter(uuid);
            if (!fighter && !showToSpectators) { hideFor(uuid); continue; }
            renderFor(p, match);
        }
    }

    private void renderFor(Player viewer, Match match) {
        ScoreboardManager sm = scoreboardManager();
        TabPlayer tp = tabPlayer(viewer.getUniqueId());
        if (sm == null || tp == null) return;

        String rTitle = ColorUtil.color(replace(title, viewer, match));
        List<String> rLines = new ArrayList<>(lines.size());
        for (String line : lines) rLines.add(ColorUtil.color(replace(line, viewer, match)));

        UUID id = viewer.getUniqueId();
        Scoreboard board = boards.get(id);
        Integer madeWith = lineCounts.get(id);

        // Create once. Recreate ONLY when OUR configured line-count changed
        // (a reload), tracked in lineCounts — never against TAB's own
        // getLines().size() (which may not equal what we passed, forcing a
        // recreate every tick → flicker).
        if (board == null || madeWith == null || madeWith != rLines.size()) {
            if (board != null) { try { board.unregister(); } catch (Throwable ignored) {} }
            String name = "spvp_" + id.toString().substring(0, 8);
            board = sm.createScoreboard(name, rTitle, rLines);
            sm.showScoreboard(tp, board);
            boards.put(id, board);
            lineCounts.put(id, rLines.size());
            return;
        }

        // Steady state: update in place — no teardown, so no flicker.
        if (!Objects.equals(board.getTitle(), rTitle)) board.setTitle(rTitle);
        List<Line> boardLines = board.getLines();
        int count = Math.min(boardLines.size(), rLines.size());
        for (int i = 0; i < count; i++) {
            String next = rLines.get(i);
            if (!Objects.equals(boardLines.get(i).getText(), next)) {
                boardLines.get(i).setText(next);
            }
        }
    }

    // ── Placeholder resolution ────────────────────────────────────────────────

    private String replace(String raw, Player viewer, Match match) {
        if (raw == null || raw.isEmpty()) return raw == null ? "" : raw;
        MatchPlayer f1 = match.getFighter1();
        MatchPlayer f2 = match.getFighter2();
        Player p1 = f1 != null ? Bukkit.getPlayer(f1.getUuid()) : null;
        Player p2 = f2 != null ? Bukkit.getPlayer(f2.getUuid()) : null;

        String name1 = f1 != null ? f1.getName() : "?";
        String name2 = f2 != null ? f2.getName() : "?";
        int score1 = f1 != null ? f1.getWins() : 0;
        int score2 = f2 != null ? f2.getWins() : 0;

        // Per-viewer "you"/"opponent" — a spectator has no fighter, so fall back
        // to fighter1's perspective for those tokens.
        MatchPlayer self = match.getFighter(viewer.getUniqueId());
        MatchPlayer opp  = self != null ? match.getOpponent(viewer.getUniqueId()) : null;
        if (self == null) { self = f1; opp = f2; }

        String you       = self != null ? self.getName() : "?";
        String opponent  = opp  != null ? opp.getName()  : "?";
        int yourScore    = self != null ? self.getWins() : 0;
        int oppScore     = opp  != null ? opp.getWins()  : 0;

        // Team colors — randomised per match (see Match.fighter1Red). Fighter1's
        // color is whichever side the coin-flip put them on; fighter2 gets the
        // other. Each VIEWER sees themselves in their own side's color and the
        // opponent in the other, so %you_color% is stable per-player even though
        // which physical player is red/blue is random each match.
        String p1Color = colorFor(f1 != null && match.isRed(f1.getUuid()));
        String p2Color = colorFor(f2 != null && match.isRed(f2.getUuid()));
        boolean viewerIsFighter = self != null && match.isFighter(viewer.getUniqueId());
        String youColor = viewerIsFighter ? colorFor(match.isRed(viewer.getUniqueId()))
                                           : p1Color; // spectator → fighter1 perspective
        String oppColor = viewerIsFighter ? colorFor(!match.isRed(viewer.getUniqueId()))
                                          : p2Color;

        return raw
                .replace("%player_1%", name1)
                .replace("%player_2%", name2)
                .replace("%score_1%", String.valueOf(score1))
                .replace("%score_2%", String.valueOf(score2))
                .replace("%ping_1%", String.valueOf(p1 != null ? p1.getPing() : 0))
                .replace("%ping_2%", String.valueOf(p2 != null ? p2.getPing() : 0))
                .replace("%hp_1%", String.valueOf(p1 != null ? (int) Math.ceil(p1.getHealth()) : 0))
                .replace("%hp_2%", String.valueOf(p2 != null ? (int) Math.ceil(p2.getHealth()) : 0))
                .replace("%p1_color%", p1Color)
                .replace("%p2_color%", p2Color)
                .replace("%you_color%", youColor)
                .replace("%opp_color%", oppColor)
                .replace("%you%", you)
                .replace("%opponent%", opponent)
                .replace("%your_score%", String.valueOf(yourScore))
                .replace("%opp_score%", String.valueOf(oppScore))
                .replace("%round%", String.valueOf(match.getCurrentRound()))
                .replace("%rounds%", String.valueOf(match.getTotalRounds()))
                .replace("%mode%", match.getMode().getDisplayName())
                .replace("%arena%", match.getArena().getName() != null ? match.getArena().getName() : "");
    }

    /** RED side → red color, else blue. */
    private String colorFor(boolean red) { return red ? redColor : blueColor; }

    // ── TAB helpers ────────────────────────────────────────────────────────────

    private ScoreboardManager scoreboardManager() {
        try {
            if (Bukkit.getPluginManager().getPlugin("TAB") == null) return null;
            return TabAPI.getInstance().getScoreboardManager();
        } catch (Throwable t) {
            return null;
        }
    }

    private TabPlayer tabPlayer(UUID uuid) {
        try {
            return TabAPI.getInstance().getPlayer(uuid);
        } catch (Throwable t) {
            return null;
        }
    }
}
