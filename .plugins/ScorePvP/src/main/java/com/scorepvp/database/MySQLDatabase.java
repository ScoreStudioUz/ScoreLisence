package com.scorepvp.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

public class MySQLDatabase extends AbstractDatabase {

    private HikariDataSource dataSource;
    private final FileConfiguration config;

    public MySQLDatabase(FileConfiguration config, Logger log) {
        super(log);
        this.config = config;
    }

    @Override
    public void connect() throws Exception {
        String host     = config.getString("database.mysql.host", "localhost");
        int    port     = config.getInt("database.mysql.port", 3306);
        String database = config.getString("database.mysql.database", "scorepvp");
        String user     = config.getString("database.mysql.username", "root");
        String pass     = config.getString("database.mysql.password", "");
        int    poolSize = config.getInt("database.mysql.pool-size", 10);
        int    timeout  = config.getInt("database.mysql.connection-timeout", 5000);

        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&autoReconnect=true&characterEncoding=utf8");
        hikari.setUsername(user);
        hikari.setPassword(pass);
        hikari.setMaximumPoolSize(poolSize);
        hikari.setConnectionTimeout(timeout);
        hikari.setPoolName("ScorePvP-MySQL");
        hikari.addDataSourceProperty("cachePrepStmts", "true");
        hikari.addDataSourceProperty("prepStmtCacheSize", "250");
        hikari.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        dataSource = new HikariDataSource(hikari);
        log.info("[ScorePvP] Connected to MySQL at " + host + ":" + port);
    }

    @Override
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[ScorePvP] MySQL connection closed.");
        }
    }

    @Override
    protected Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
}
