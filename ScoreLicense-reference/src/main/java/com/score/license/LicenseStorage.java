package com.score.license;

import java.io.*;
import java.util.Properties;
import java.util.UUID;

/**
 * Stores server UUID (persistent) and license cache (grace period).
 * Files are created in the plugin's data folder.
 */
public final class LicenseStorage {

    private static final String UUID_FILE  = "score_data.dat";
    private static final String CACHE_FILE = "score_cache.dat";

    private final File dataFolder;
    private String serverUuid;

    public LicenseStorage(File dataFolder) {
        this.dataFolder = dataFolder;
        if (!dataFolder.exists()) dataFolder.mkdirs();
    }

    /**
     * Load or generate the persistent server UUID.
     */
    public String getServerUuid() {
        if (serverUuid != null) return serverUuid;

        File file = new File(dataFolder, UUID_FILE);
        if (file.exists()) {
            try {
                Properties props = new Properties();
                try (FileInputStream fis = new FileInputStream(file)) {
                    props.load(fis);
                }
                serverUuid = props.getProperty("uuid");
                if (serverUuid != null && !serverUuid.isEmpty()) return serverUuid;
            } catch (IOException ignored) {}
        }

        // Generate new UUID
        serverUuid = UUID.randomUUID().toString();
        try {
            Properties props = new Properties();
            props.setProperty("uuid", serverUuid);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                props.store(fos, "ScoreLicense Server UUID — do not modify");
            }
        } catch (IOException e) {
            // Non-fatal — UUID is in memory
        }
        return serverUuid;
    }

    /**
     * Save a successful validation response as cache for grace period.
     */
    public void saveCache(String expiresAt) {
        File file = new File(dataFolder, CACHE_FILE);
        try {
            Properties props = new Properties();
            props.setProperty("expires_at", expiresAt);
            props.setProperty("cached_at", String.valueOf(System.currentTimeMillis()));
            try (FileOutputStream fos = new FileOutputStream(file)) {
                props.store(fos, "ScoreLicense cache — do not modify");
            }
        } catch (IOException ignored) {}
    }

    /**
     * Check if there is a valid cached response within the grace period.
     * @param gracePeriodMs grace period in milliseconds
     * @return true if cache is valid
     */
    public boolean isCacheValid(long gracePeriodMs) {
        File file = new File(dataFolder, CACHE_FILE);
        if (!file.exists()) return false;
        try {
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(file)) {
                props.load(fis);
            }
            long cachedAt = Long.parseLong(props.getProperty("cached_at", "0"));
            return (System.currentTimeMillis() - cachedAt) < gracePeriodMs;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Delete the cache file (e.g., when license is revoked).
     */
    public void clearCache() {
        File file = new File(dataFolder, CACHE_FILE);
        if (file.exists()) file.delete();
    }
}
