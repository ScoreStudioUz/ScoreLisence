package com.score.license;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

/**
 * Direct MySQL connection for license validation.
 * Replaces the old HTTP-based LicenseClient.
 */
public final class LicenseDatabase {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Minutes after last_seen before a server slot is considered stale and reclaimable. */
    private static final int STALE_MINUTES = 15;

    private final HikariDataSource dataSource;
    private final Logger logger;

    public LicenseDatabase(String host, int port, String user, String password,
                           String database, Logger logger) throws Exception {
        this.logger = logger;

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&autoReconnect=true"
                + "&characterEncoding=utf8&connectTimeout=10000&socketTimeout=30000");
        cfg.setUsername(user);
        cfg.setPassword(password);
        cfg.setMaximumPoolSize(3);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(10_000);
        cfg.setPoolName("ScoreLicense-MySQL");

        dataSource = new HikariDataSource(cfg);
        logger.info("[ScoreLicense] MySQL connection established.");
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    private String now() {
        return FMT.format(Instant.now());
    }

    // ──────────────── VALIDATE ────────────────

    /**
     * Validate a license key directly from MySQL.
     * Returns a LicenseResult with validation outcome.
     */
    public LicenseResult validate(String key, String serverUuid, String pluginName,
                                  String ip, int serverPort, String version) {
        try (Connection conn = dataSource.getConnection()) {

            // 1. Find license
            String licSql = "SELECT id, plugin_name, max_servers, is_blocked, expires_at FROM licenses WHERE `key` = ?";
            int licId = -1;
            String dbPlugin = null;
            int maxServers = 0;
            boolean blocked = false;
            String expiresAt = null;

            try (PreparedStatement ps = conn.prepareStatement(licSql)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        logEvent(conn, null, "validate_fail", "KEY_NOT_FOUND", serverUuid, ip);
                        return LicenseResult.fail("KEY_NOT_FOUND");
                    }
                    licId = rs.getInt("id");
                    dbPlugin = rs.getString("plugin_name");
                    maxServers = rs.getInt("max_servers");
                    blocked = rs.getInt("is_blocked") != 0;
                    expiresAt = rs.getString("expires_at");
                }
            }

            // 2. Check blocked
            if (blocked) {
                logEvent(conn, licId, "validate_fail", "KEY_BLOCKED", serverUuid, ip);
                return LicenseResult.fail("KEY_BLOCKED");
            }

            // 3. Check expired
            if (isExpired(expiresAt)) {
                logEvent(conn, licId, "validate_fail", "KEY_EXPIRED", serverUuid, ip);
                return LicenseResult.fail("KEY_EXPIRED");
            }

            // 4. Check plugin
            if (!"ALL".equals(dbPlugin) && !dbPlugin.equals(pluginName)) {
                logEvent(conn, licId, "validate_fail", "WRONG_PLUGIN", serverUuid, ip);
                return LicenseResult.fail("WRONG_PLUGIN");
            }

            // 5. Check/register server
            boolean serverExists = false;
            String srvSql = "SELECT id FROM servers WHERE license_id = ? AND server_uuid = ?";
            try (PreparedStatement ps = conn.prepareStatement(srvSql)) {
                ps.setInt(1, licId);
                ps.setString(2, serverUuid);
                try (ResultSet rs = ps.executeQuery()) {
                    serverExists = rs.next();
                }
            }

            if (!serverExists) {
                // Count existing servers
                int count = countServers(conn, licId);
                if (count >= maxServers) {
                    // Try to reclaim stale slots (servers not seen for >= STALE_MINUTES).
                    // This lets users reinstall plugins / change server-uuid.txt without
                    // being permanently locked out of their own license.
                    int reclaimed = reclaimStaleServers(conn, licId);
                    if (reclaimed > 0) {
                        logEvent(conn, licId, "slot_reclaimed",
                                "freed=" + reclaimed + " stale-min=" + STALE_MINUTES, serverUuid, ip);
                        count = countServers(conn, licId);
                    }
                    if (count >= maxServers) {
                        logEvent(conn, licId, "limit_exceeded",
                                "count=" + count + " max=" + maxServers, serverUuid, ip);
                        return LicenseResult.fail("SERVER_LIMIT_EXCEEDED");
                    }
                }
                // Register new server
                String ins = "INSERT INTO servers (license_id, server_uuid, ip, port, last_seen, registered_at) VALUES (?,?,?,?,?,?)";
                try (PreparedStatement ps = conn.prepareStatement(ins)) {
                    String n = now();
                    ps.setInt(1, licId);
                    ps.setString(2, serverUuid);
                    ps.setString(3, ip);
                    ps.setInt(4, serverPort);
                    ps.setString(5, n);
                    ps.setString(6, n);
                    ps.executeUpdate();
                }
                logEvent(conn, licId, "new_server", "ip=" + ip + " port=" + serverPort, serverUuid, ip);
            } else {
                // Update existing server
                String upd = "UPDATE servers SET ip=?, port=?, last_seen=? WHERE license_id=? AND server_uuid=?";
                try (PreparedStatement ps = conn.prepareStatement(upd)) {
                    ps.setString(1, ip);
                    ps.setInt(2, serverPort);
                    ps.setString(3, now());
                    ps.setInt(4, licId);
                    ps.setString(5, serverUuid);
                    ps.executeUpdate();
                }
            }

            logEvent(conn, licId, "validate_ok",
                    "plugin=" + pluginName + " v=" + version, serverUuid, ip);

            return LicenseResult.ok(expiresAt);

        } catch (SQLException e) {
            logger.severe("[ScoreLicense] MySQL validate error: " + e.getMessage());
            return LicenseResult.error(e.getMessage());
        }
    }

    // ──────────────── HEARTBEAT ────────────────

    /**
     * Check if license is still valid (called periodically).
     * Returns true if license is alive.
     */
    public boolean heartbeat(String key, String serverUuid) {
        try (Connection conn = dataSource.getConnection()) {

            String sql = "SELECT id, is_blocked, expires_at FROM licenses WHERE `key` = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;

                    int licId = rs.getInt("id");
                    boolean blocked = rs.getInt("is_blocked") != 0;
                    String expiresAt = rs.getString("expires_at");

                    if (blocked || isExpired(expiresAt)) {
                        logEvent(conn, licId, "plugin_disabled",
                                blocked ? "KEY_BLOCKED" : "KEY_EXPIRED", serverUuid, null);
                        return false;
                    }

                    // Update last_seen
                    String upd = "UPDATE servers SET last_seen=? WHERE license_id=? AND server_uuid=?";
                    try (PreparedStatement ps2 = conn.prepareStatement(upd)) {
                        ps2.setString(1, now());
                        ps2.setInt(2, licId);
                        ps2.setString(3, serverUuid);
                        ps2.executeUpdate();
                    }
                    return true;
                }
            }
        } catch (SQLException e) {
            logger.warning("[ScoreLicense] MySQL heartbeat error: " + e.getMessage());
            return true; // Assume alive on DB error (grace)
        }
    }

    // ──────────────── HELPERS ────────────────

    private int countServers(Connection conn, int licId) throws SQLException {
        String sql = "SELECT COUNT(*) FROM servers WHERE license_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, licId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /**
     * Delete server rows for this license whose last_seen is older than STALE_MINUTES.
     * Returns how many rows were deleted. Compares last_seen as a string in our
     * "yyyy-MM-dd HH:mm:ss" UTC format, which is lexicographically comparable.
     */
    private int reclaimStaleServers(Connection conn, int licId) throws SQLException {
        String cutoff = FMT.format(Instant.now().minusSeconds(STALE_MINUTES * 60L));
        String sql = "DELETE FROM servers WHERE license_id = ? AND (last_seen IS NULL OR last_seen < ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, licId);
            ps.setString(2, cutoff);
            return ps.executeUpdate();
        }
    }

    private boolean isExpired(String expiresAt) {
        if (expiresAt == null) return true;
        try {
            java.time.LocalDateTime exp = java.time.LocalDateTime.parse(expiresAt, FMT);
            return exp.atZone(ZoneOffset.UTC).toInstant().isBefore(Instant.now());
        } catch (Exception e) {
            return true;
        }
    }

    private void logEvent(Connection conn, Integer licId, String event,
                          String detail, String serverUuid, String ip) {
        try {
            String sql = "INSERT INTO events (license_id, event, detail, server_uuid, ip, created_at) VALUES (?,?,?,?,?,?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (licId != null) ps.setInt(1, licId);
                else ps.setNull(1, Types.INTEGER);
                ps.setString(2, event);
                ps.setString(3, detail);
                ps.setString(4, serverUuid);
                ps.setString(5, ip);
                ps.setString(6, now());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            logger.warning("[ScoreLicense] Failed to log event: " + e.getMessage());
        }
    }

    // ──────────────── RESULT MODEL ────────────────

    public static final class LicenseResult {
        private final boolean valid;
        private final String reason;
        private final String expiresAt;
        private final boolean dbError;

        private LicenseResult(boolean valid, String reason, String expiresAt, boolean dbError) {
            this.valid = valid;
            this.reason = reason;
            this.expiresAt = expiresAt;
            this.dbError = dbError;
        }

        static LicenseResult ok(String expiresAt) {
            return new LicenseResult(true, null, expiresAt, false);
        }

        static LicenseResult fail(String reason) {
            return new LicenseResult(false, reason, null, false);
        }

        static LicenseResult error(String message) {
            return new LicenseResult(false, message, null, true);
        }

        public boolean isValid()    { return valid; }
        public String getReason()   { return reason; }
        public String getExpiresAt() { return expiresAt; }
        public boolean isDbError()  { return dbError; }
    }
}
