package com.scorepvp.task;

import com.scorepvp.ScorePvP;
import org.bukkit.scheduler.BukkitRunnable;

public class PlaytimeTask extends BukkitRunnable {
    private final ScorePvP plugin;
    public PlaytimeTask(ScorePvP plugin) { this.plugin = plugin; }

    @Override
    public void run() {
        plugin.getStatsManager().flushAll();
    }
}
