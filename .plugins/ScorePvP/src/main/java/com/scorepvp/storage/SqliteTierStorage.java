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
 * SQLite-backed tier storage. One file ({@code tiers.db}) with one table.
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
    public void saveTier(UUID uuid, String mode, String tier) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO player_tiers (uuid, mode, tier) VALUES (?, ?, ?) " +
                     "ON CONFLICT(uuid, mode) DO UPDATE SET tier = excluded.tier")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mode);
            ps.setString(3, tier);
            ps.executeUpdate();
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
    public void close() {
        if (!ds.isClosed()) ds.close();
    }
}
