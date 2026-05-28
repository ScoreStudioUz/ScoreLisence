package com.scorepvp.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
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
 * SQLite-backed tier storage. One file ({@code tiers.db}) with two tables:
 * {@code player_tiers} for the tier rows (uuid, mode, tier) and
 * {@code player_names} for the name → uuid index used to target offline
 * players from {@code /tier set <name> …}.
 * Slim by design — tiers churn rarely and never explode in row count.
 */
public class SqliteTierStorage implements TierStorage {

    private final HikariDataSource ds;
    private final Logger log;

    public SqliteTierStorage(File pluginDataFolder, Logger log) {
        this.log = log;
        if (!pluginDataFolder.exists()) pluginDataFolder.mkdirs();
        File dbFile = new File(pluginDataFolder, "tiers.db");

        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        cfg.setMaximumPoolSize(2);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5000);
        cfg.setPoolName("ScorePvP-Tiers-SQLite");
        this.ds = new HikariDataSource(cfg);

        createSchema();
    }

    private void createSchema() {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS player_tiers (
                    uuid VARCHAR(36) NOT NULL,
                    mode VARCHAR(64) NOT NULL,
                    tier VARCHAR(16) NOT NULL,
                    PRIMARY KEY (uuid, mode)
                )
            """);
            // Separate names table keeps the (uuid, mode, tier) primary key
            // intact and lets us update the name without rewriting every
            // tier row. NOCASE makes the lookup case-insensitive, which is
            // what admins expect when typing /tier set MrBroccoli vs mrbroccoli.
            s.execute("""
                CREATE TABLE IF NOT EXISTS player_names (
                    uuid VARCHAR(36) PRIMARY KEY,
                    name VARCHAR(32) NOT NULL COLLATE NOCASE
                )
            """);
            s.execute("""
                CREATE INDEX IF NOT EXISTS idx_player_names_name
                ON player_names(name COLLATE NOCASE)
            """);
        } catch (SQLException e) {
            log.severe("[ScorePvP] tiers.db schema creation failed: " + e.getMessage());
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
                    "ON CONFLICT(uuid, mode) DO UPDATE SET tier = excluded.tier")) {
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
                     "SELECT uuid FROM player_names WHERE name = ? COLLATE NOCASE LIMIT 1")) {
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

    /** Shared name-upsert helper used by both saveTier and rememberName. */
    private void upsertName(Connection c, UUID uuid, String playerName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO player_names (uuid, name) VALUES (?, ?) " +
                "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name")) {
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
