package ru.score.hwauction.task;

import org.bukkit.scheduler.BukkitRunnable;
import ru.score.hwauction.ScoreHWAuction;

public final class ExpiryTask extends BukkitRunnable {

    private final ScoreHWAuction plugin;

    public ExpiryTask(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    @Override
    public void run() {
        // processBatch is safe to call on main thread (no DB calls in it)
        plugin.getExpiryService().processBatch();
    }

    /** Start the task running every 60 seconds */
    public void start() {
        runTaskTimer(plugin, 20L * 30, 20L * 60);
    }
}
