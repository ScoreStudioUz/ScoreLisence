package ru.score.hwauction.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ru.score.hwauction.ScoreHWAuction;

import java.io.File;

public final class SQLiteDatabase extends AbstractDatabase {

    private final ScoreHWAuction plugin;

    public SQLiteDatabase(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    @Override
    protected HikariDataSource buildPool() throws Exception {
        String fileName = plugin.getConfig().getString("database.sqlite.file", "auction.db");
        File dbFile = new File(plugin.getDataFolder(), fileName);
        dbFile.getParentFile().mkdirs();

        HikariConfig cfg = new HikariConfig();
        // sqlite-jdbc is NOT relocated (native lib hardcodes original class names)
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        // SQLite supports only one writer at a time
        cfg.setMaximumPoolSize(1);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTestQuery("SELECT 1");
        cfg.setPoolName("ScoreAH-SQLite");
        // WAL mode for better read concurrency
        cfg.addDataSourceProperty("journal_mode", "WAL");
        cfg.addDataSourceProperty("synchronous", "NORMAL");
        cfg.addDataSourceProperty("foreign_keys", "ON");
        return new HikariDataSource(cfg);
    }

    @Override
    protected String autoIncrement() { return "AUTOINCREMENT"; }

    @Override
    protected String upsertSound() {
        return "INSERT INTO ah_player_settings (player_uuid, sound_enabled) VALUES (?,?) " +
               "ON CONFLICT(player_uuid) DO UPDATE SET sound_enabled=?";
    }

    @Override
    public String getVendorName() { return "SQLite"; }
}
