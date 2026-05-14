package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.TopEntry;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maintains in-memory top-leaderboard cache for fast PlaceholderAPI lookups.
 *
 * Refreshed:
 *   • Every N seconds (configured in config.yml → placeholders.top-cache-update-seconds)
 *   • Immediately after any match ends (if update-on-match-end: true)
 */
public class PlaceholderCache {

    public enum Metric { WINS, KD, KILLS }

    private final ScorePvP plugin;

    /** "mode:METRIC" → ordered list of top entries */
    private final Map<String, List<TopEntry>> cache = new ConcurrentHashMap<>();

    /** Tracks when each key was last refreshed (ms) */
    private final Map<String, Long> lastRefresh = new ConcurrentHashMap<>();

    private int maxPosition;
    private String unknownValue;

    public PlaceholderCache(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Init ──────────────────────────────────────────────────────────────────

    public void init() {
        maxPosition  = plugin.getConfig().getInt("placeholders.top-max-position", 10);
        unknownValue = plugin.getConfig().getString("placeholders.placeholder-unknown-value", "—");

        // Schedule periodic refresh
        int seconds = plugin.getConfig().getInt("placeholders.top-cache-update-seconds", 300);
        long ticks  = seconds * 20L;
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, this::refreshAll,
                ticks, ticks);

        // Initial load
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::refreshAll);
    }

    // ── Refresh ───────────────────────────────────────────────────────────────

    public void refreshAll() {
        plugin.getConfigManager().getModes().forEach(mode ->
                refreshMode(mode.getId()));
    }

    public void refreshMode(String modeId) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            var db = plugin.getDatabase().get();
            cache.put(key(modeId, Metric.WINS),  db.loadTopWins(modeId,  maxPosition));
            cache.put(key(modeId, Metric.KD),    db.loadTopKd(modeId,    maxPosition));
            cache.put(key(modeId, Metric.KILLS), db.loadTopKills(modeId, maxPosition));
            lastRefresh.put(modeId, System.currentTimeMillis());
        });
    }

    // ── Lookups ───────────────────────────────────────────────────────────────

    /** Returns the player name at position (1-based). */
    public String getTopName(String mode, Metric metric, int position) {
        List<TopEntry> list = cache.get(key(mode, metric));
        if (list == null || position < 1 || position > list.size()) return unknownValue;
        return list.get(position - 1).getPlayerName();
    }

    /** Returns the value string at position (1-based). */
    public String getTopValue(String mode, Metric metric, int position) {
        List<TopEntry> list = cache.get(key(mode, metric));
        if (list == null || position < 1 || position > list.size()) return unknownValue;
        return list.get(position - 1).getValueDisplay();
    }

    private String key(String mode, Metric metric) {
        return mode + ":" + metric.name();
    }
}
