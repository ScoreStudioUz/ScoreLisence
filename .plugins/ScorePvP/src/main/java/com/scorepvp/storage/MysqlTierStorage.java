package com.scorepvp.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * MySQL-backed tier storage. Same schema shape as {@link SqliteTierStorage} —
 * two tables: {@code player_tiers} and {@code player_names}. The name table
 * lets admins target offline players with {@code /tier set <name> …}.
 *
 * <p>Connection params come from {@code storage.tiers.mysql.*} in config.yml.
 */
public class MysqlTierStorage implements TierStorage {

    private final HikariDataSource ds;
    private final Logger log;

    public MysqlTierStorage(ConfigurationSection cfg, Logger log) throws SQLException {
        this.log = log;
        if (cfg == null) {
            throw new SQLException("storage.tiers.mysql section is missing");
        }

        String host = cfg.getString("host", "localhost");
        int    port = cfg.getInt("port", 3306);
        String db   = cfg.getString("database", "scorepvp_tiers");
        String user = cfg.getString("username", "root");
        String pass = cfg.getString("password", "");
        int    pool = cfg.getInt("pool-size", 4);
        int    timeout = cfg.getInt("connection-timeout", 5000);

        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8");
        hc.setUsername(user);
        hc.setPassword(pass);
        hc.setMaximumPoolSize(pool);
        hc.setConnectionTimeout(timeout);
        hc.setPoolName("ScorePvP-Tiers-MySQL");
        this.ds = new HikariDataSource(hc);

        // Probe the pool — fail-fast so StorageService can fall back to SQLite.
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS player_tiers (
                    uuid VARCHAR(36) NOT NULL,
                    mode VARCHAR(64) NOT NULL,
                    tier VARCHAR(16) NOT NULL,
                    PRIMARY KEY (uuid, mode)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
            """);
            // utf8mb4 + utf8mb4_general_ci for case-insensitive name lookup.
            s.execute("""
                CREATE TABLE IF NOT EXISTS player_names (
                    uuid VARCHAR(36) NOT NULL,
                    name VARCHAR(32) NOT NULL,
                    PRIMARY KEY (uuid),
                    INDEX idx_player_names_name (name)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
            """);
        }
    }

    @Override
    public Map<String, String> loadTiers(UUID uuid) {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT mode, tier FROM player_tiers WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString("mode"), rs.getString("tier"));
            }
        } catch (SQLException e) {
            log.warning("[ScorePvP] loadTiers(" + uuid + ") failed: " + e.getMessage());
        }
        return out;
    }

    @Override
    public void saveTier(UUID uuid, String mode, String tier, String playerName) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO player_tiers (uuid, mode, tier) VALUES (?, ?, ?) " +
                    "ON DUPLICATE KEY UPDATE tier = VALUES(tier)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, mode);
                ps.setString(3, tier);
                ps.executeUpdate();
            }
            if (playerName != null && !playerName.isBlank()) {
                upsertName(c, uuid, playerName);
            }
        } catch (SQLException e) {
            log.warning("[ScorePvP] saveTier(" + uuid + "," + mode + ") failed: " + e.getMessage());
        }
    }

    @Override
    public void removeTier(UUID uuid, String mode) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM player_tiers WHERE uuid = ? AND mode = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mode);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[ScorePvP] removeTier(" + uuid + "," + mode + ") failed: " + e.getMessage());
        }
    }

    @Override
    public UUID findUuidByName(String playerName) {
        if (playerName == null || playerName.isBlank()) return null;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT uuid FROM player_names WHERE name = ? LIMIT 1")) {
            ps.setString(1, playerName);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString("uuid"));
            }
        } catch (SQLException | IllegalArgumentException e) {
            log.warning("[ScorePvP] findUuidByName(" + playerName + ") failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void rememberName(UUID uuid, String playerName) {
        if (playerName == null || playerName.isBlank()) return;
        try (Connection c = ds.getConnection()) {
            upsertName(c, uuid, playerName);
        } catch (SQLException e) {
            log.warning("[ScorePvP] rememberName(" + uuid + "," + playerName + ") failed: " + e.getMessage());
        }
    }

    private void upsertName(Connection c, UUID uuid, String playerName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO player_names (uuid, name) VALUES (?, ?) " +
                "ON DUPLICATE KEY UPDATE name = VALUES(name)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, playerName);
            ps.executeUpdate();
        }
    }

    @Override
    public void close() {
        if (!ds.isClosed()) ds.close();
    }
}
