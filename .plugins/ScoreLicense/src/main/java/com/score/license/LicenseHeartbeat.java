package com.score.license;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.logging.Logger;

/**
 * Periodic heartbeat — runs every N minutes on async scheduler.
 * Checks license status directly via MySQL.
 * If license is revoked/expired/blocked, disables all Score plugins.
 */
public final class LicenseHeartbeat extends BukkitRunnable {

    private final ScoreLicensePlugin licensePlugin;
    private final LicenseDatabase database;
    private final String key;
    private final String serverUuid;
    private final Logger logger;

    public LicenseHeartbeat(ScoreLicensePlugin licensePlugin, LicenseDatabase database,
                            String key, String serverUuid) {
        this.licensePlugin = licensePlugin;
        this.database = database;
        this.key = key;
        this.serverUuid = serverUuid;
        this.logger = licensePlugin.getLogger();
    }

    @Override
    public void run() {
        // This runs on async thread (runTaskTimerAsynchronously)
        // so MySQL call is safe here without blocking main thread
        boolean alive = database.heartbeat(key, serverUuid);
        if (!alive) {
            // Schedule disable on main thread (Bukkit API requires main thread)
            Bukkit.getScheduler().runTask(licensePlugin, () -> {
                logger.severe("[ScoreLicense] License revoked! Disabling all Score plugins.");
                LicenseAPI.setLicensed(false);
                licensePlugin.getStorage().clearCache();
                disableScorePlugins();
            });
        }
    }

    private void disableScorePlugins() {
        String[] scorePlugins = {
                "ScoreTpa", "ScoreHWAuction", "ScoreAirDrop",
                "ScoreAutoBuyer", "ScorePvP"
        };
        for (String name : scorePlugins) {
            Plugin p = Bukkit.getPluginManager().getPlugin(name);
            if (p != null && p.isEnabled()) {
                logger.warning("[ScoreLicense] Disabling " + name);
                Bukkit.getPluginManager().disablePlugin(p);
            }
        }
        // Also disable ScoreLicense heartbeat itself
        try { cancel(); } catch (IllegalStateException ignored) {}
    }
}
