package com.score.license;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

/**
 * ScoreLicense — validates license directly via MySQL.
 * Config only needs score-key. All other parameters are internal.
 */
public final class ScoreLicensePlugin extends JavaPlugin {

    /** Grace period: 24 hours */
    private static final int GRACE_HOURS = 24;
    /** Heartbeat every 5 minutes */
    private static final int HEARTBEAT_MINUTES = 5;

    private LicenseStorage storage;
    private LicenseDatabase database;
    private LicenseHeartbeat heartbeat;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        String key = getConfig().getString("score-key", "").trim();

        // ── Validate key ──
        if (key.isEmpty()) {
            getLogger().severe("[ScoreLicense] Pluginlarni ishlatish uchun admindan key oling: @SHoXBoSSYT (Telegram)");
            LicenseAPI.setLicensed(false);
            return;
        }

        // ── Connect to MySQL (credentials from obfuscated internal config) ──
        try {
            database = new LicenseDatabase(
                    DatabaseConfig.host(),
                    DatabaseConfig.port(),
                    DatabaseConfig.user(),
                    DatabaseConfig.pass(),
                    DatabaseConfig.db(),
                    getLogger()
            );
        } catch (Exception e) {
            getLogger().severe("[ScoreLicense] MySQL connection failed: " + e.getMessage());
            storage = new LicenseStorage(getDataFolder());
            long gracePeriodMs = TimeUnit.HOURS.toMillis(GRACE_HOURS);
            if (storage.isCacheValid(gracePeriodMs)) {
                getLogger().info("[ScoreLicense] Grace period active — allowing startup.");
                LicenseAPI.setLicensed(true);
            } else {
                getLogger().severe("[ScoreLicense] No valid cache. Plugin disabled.");
                LicenseAPI.setLicensed(false);
            }
            return;
        }

        storage = new LicenseStorage(getDataFolder());
        String serverUuid  = storage.getServerUuid();
        String serverIp    = Bukkit.getIp().isEmpty() ? "0.0.0.0" : Bukkit.getIp();
        int serverPort     = Bukkit.getPort();
        String version     = getDescription().getVersion();
        long gracePeriodMs = TimeUnit.HOURS.toMillis(GRACE_HOURS);

        getLogger().info("[ScoreLicense] Validating license...");

        // ── Validate directly (MySQL call on main thread during startup is OK) ──
        boolean valid = false;
        try {
            LicenseDatabase.LicenseResult lr = database.validate(
                    key, serverUuid, "ALL", serverIp, serverPort, version);

            if (lr.isValid()) {
                storage.saveCache(lr.getExpiresAt());
                valid = true;
                getLogger().info("[ScoreLicense] License valid! Expires: " + lr.getExpiresAt());
            } else if (lr.isDbError()) {
                getLogger().warning("[ScoreLicense] DB error: " + lr.getReason());
                if (storage.isCacheValid(gracePeriodMs)) {
                    getLogger().info("[ScoreLicense] Grace period active — allowing startup.");
                    valid = true;
                }
            } else {
                storage.clearCache();
                logReason(lr.getReason());
            }
        } catch (Exception e) {
            getLogger().warning("[ScoreLicense] Validation error: " + e.getMessage());
            if (storage.isCacheValid(gracePeriodMs)) {
                getLogger().info("[ScoreLicense] Grace period active — allowing startup.");
                valid = true;
            }
        }

        boolean result = valid;

        if (result) {
            LicenseAPI.setLicensed(true);
            getLogger().info("[ScoreLicense] All Score plugins authorized.");

            // Start heartbeat
            long intervalTicks = HEARTBEAT_MINUTES * 60L * 20L;
            heartbeat = new LicenseHeartbeat(this, database, key, serverUuid);
            heartbeat.runTaskTimerAsynchronously(this, intervalTicks, intervalTicks);
        } else {
            LicenseAPI.setLicensed(false);
            getLogger().severe("[ScoreLicense] Validation FAILED. Score plugins will not work.");
        }
    }

    @Override
    public void onDisable() {
        if (heartbeat != null) {
            try { heartbeat.cancel(); } catch (IllegalStateException ignored) {}
        }
        if (database != null) {
            database.close();
        }
        getLogger().info("[ScoreLicense] Disabled.");
    }

    LicenseStorage getStorage() {
        return storage;
    }

    private void logReason(String reason) {
        if (reason == null) reason = "UNKNOWN";
        switch (reason) {
            case "KEY_NOT_FOUND":
                getLogger().severe("[ScoreLicense] License key not found.");
                break;
            case "KEY_BLOCKED":
                getLogger().severe("[ScoreLicense] License key is blocked.");
                break;
            case "KEY_EXPIRED":
                getLogger().severe("[ScoreLicense] License key has expired.");
                break;
            case "WRONG_PLUGIN":
                getLogger().severe("[ScoreLicense] Key not authorized for this plugin.");
                break;
            case "SERVER_LIMIT_EXCEEDED":
                getLogger().severe("[ScoreLicense] Server limit exceeded for this key.");
                break;
            default:
                getLogger().severe("[ScoreLicense] Validation failed: " + reason);
                break;
        }
    }
}
