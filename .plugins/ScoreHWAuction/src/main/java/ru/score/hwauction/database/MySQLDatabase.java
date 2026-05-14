package ru.score.hwauction.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.ConfigurationSection;
import ru.score.hwauction.ScoreHWAuction;

public final class MySQLDatabase extends AbstractDatabase {

    private final ScoreHWAuction plugin;

    public MySQLDatabase(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    @Override
    protected HikariDataSource buildPool() throws Exception {
        ConfigurationSection sec = plugin.getConfig()
                .getConfigurationSection("database.mysql");
        if (sec == null) throw new IllegalStateException("database.mysql section missing in config.yml");

        String host     = sec.getString("host", "localhost");
        int    port     = sec.getInt("port", 3306);
        String database = sec.getString("database", "auction");
        String user     = sec.getString("username", "root");
        String pass     = sec.getString("password", "");

        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&characterEncoding=utf8&autoReconnect=true"
                + "&allowPublicKeyRetrieval=true&serverTimezone=UTC";

        HikariConfig cfg = new HikariConfig();
        // Use the relocated (shaded) driver class name
        cfg.setDriverClassName("ru.score.hwauction.libs.mysql.cj.jdbc.Driver");
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setMaximumPoolSize(sec.getInt("pool-size", 10));
        cfg.setMinimumIdle(sec.getInt("min-idle", 2));
        cfg.setConnectionTimeout(sec.getLong("connection-timeout", 30_000));
        cfg.setIdleTimeout(sec.getLong("idle-timeout", 600_000));
        cfg.setMaxLifetime(sec.getLong("max-lifetime", 1_800_000));
        cfg.setPoolName("ScoreAH-MySQL");
        // Performance tweaks
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "true");
        cfg.addDataSourceProperty("rewriteBatchedStatements", "true");
        return new HikariDataSource(cfg);
    }

    @Override
    protected String autoIncrement() { return "AUTO_INCREMENT"; }

    @Override
    protected String upsertSound() {
        return "INSERT INTO ah_player_settings (player_uuid, sound_enabled) VALUES (?,?) " +
               "ON DUPLICATE KEY UPDATE sound_enabled=?";
    }

    @Override
    public String getVendorName() { return "MySQL"; }
}
