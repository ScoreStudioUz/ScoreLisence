package ru.score.hwauction.task;

import org.bukkit.scheduler.BukkitRunnable;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.AuctionStatus;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Periodically hard-deletes items that have been in EXPIRED state for longer
 * than features.expired-cleanup-delay-hours. Runs async every 15 minutes.
 */
public final class ExpiredCleanupTask extends BukkitRunnable {

    private final ScoreHWAuction plugin;

    public ExpiredCleanupTask(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        if (!plugin.getPluginConfig().isEnableExpiredCleaner()) return;
        long delayMs = TimeUnit.HOURS.toMillis(plugin.getPluginConfig().getExpiredCleanupDelayHours());
        long cutoff  = System.currentTimeMillis() - delayMs;
        try {
            int deleted = plugin.getDb().deleteExpiredBefore(cutoff);
            if (deleted > 0 && plugin.getPluginConfig().isDebugMode()) {
                plugin.getLogger().info("[ExpiredCleanup] Deleted " + deleted + " old expired listing(s).");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "ExpiredCleanupTask error", e);
        }
    }

    public void start() {
        // Every 15 minutes, first run after 5 min
        runTaskTimerAsynchronously(plugin, 20L * 60 * 5, 20L * 60 * 15);
    }
}
