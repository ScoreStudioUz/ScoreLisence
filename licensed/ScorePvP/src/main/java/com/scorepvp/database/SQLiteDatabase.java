package com.scorepvp.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * SQLite database backend.
 *
 * Uses HikariCP with a small pool (max 2 connections) so async tasks can each
 * get their own connection and the "connection is closed" error is eliminated.
 * SQLite WAL mode supports multiple concurrent readers + one writer safely.
 */
public class SQLiteDatabase extends AbstractDatabase {

    private HikariDataSource dataSource;
    private final File dataFolder;

    public SQLiteDatabase(File dataFolder, Logger log) {
        super(log);
        this.dataFolder = dataFolder;
    }

    @Override
    public void connect() throws Exception {
        File dbFile = new File(dataFolder, "data/scorepvp.db");
        dbFile.getParentFile().mkdirs();

        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        cfg.setMaximumPoolSize(2);          // SQLite WAL handles 2 connections well
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(10_000);
        cfg.setPoolName("ScorePvP-SQLite");
        // SQLite-specific pragmas applied on every new connection
        cfg.addDataSourceProperty("journal_mode", "WAL");
        cfg.addDataSourceProperty("synchronous", "NORMAL");
        cfg.addDataSourceProperty("foreign_keys", "ON");
        cfg.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA synchronous=NORMAL;");

        dataSource = new HikariDataSource(cfg);
        log.info("[ScorePvP] Connected to SQLite (HikariCP pool).");
    }

    @Override
    public void disconnect() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("[ScorePvP] SQLite connection pool closed.");
        }
    }

    @Override
    protected Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("SQLite pool is not initialized");
        }
        return dataSource.getConnection();
    }

    // ── SQLite dialect overrides ──────────────────────────────────────────────

    @Override
    protected String upsert(String table, String columns, String updateCol) {
        String[] cols  = columns.split(",");
        String   marks = "?,".repeat(cols.length);
        marks = marks.substring(0, marks.length() - 1);
        return "INSERT OR REPLACE INTO " + table + " (" + columns + ") VALUES (" + marks + ")";
    }

    @Override
    protected String upsertSettings() {
        return "INSERT OR REPLACE INTO player_settings " +
               "(uuid,duel_requests,party_invites,show_own_tier,friend_req,direct_msg,friend_join,profile_vis) " +
               "VALUES (?,?,?,?,?,?,?,?)";
    }

    @Override
    public void createTables() {
        String settings = """
            CREATE TABLE IF NOT EXISTS player_settings (
                uuid          TEXT PRIMARY KEY,
                duel_requests TEXT NOT NULL DEFAULT 'ANYONE',
                party_invites TEXT NOT NULL DEFAULT 'ANYONE',
                show_own_tier TEXT NOT NULL DEFAULT 'ANYONE',
                friend_req    INTEGER NOT NULL DEFAULT 1,
                direct_msg    TEXT NOT NULL DEFAULT 'ANYONE',
                friend_join   INTEGER NOT NULL DEFAULT 1,
                profile_vis   TEXT NOT NULL DEFAULT 'PUBLIC'
            )""";
        String tiers = """
            CREATE TABLE IF NOT EXISTS player_tiers (
                uuid TEXT NOT NULL, mode TEXT NOT NULL, tier TEXT NOT NULL,
                PRIMARY KEY (uuid, mode)
            )""";
        String kits = """
            CREATE TABLE IF NOT EXISTS personal_kits (
                uuid TEXT PRIMARY KEY, contents TEXT NOT NULL
            )""";
        String arenas = """
            CREATE TABLE IF NOT EXISTS arenas (
                number  INTEGER PRIMARY KEY,
                corner1 TEXT, corner2 TEXT, pos1 TEXT, pos2 TEXT, centre TEXT,
                name TEXT NOT NULL DEFAULT '',
                type TEXT NOT NULL DEFAULT 'DEFAULT',
                has_snapshot INTEGER NOT NULL DEFAULT 0
            )""";
        String stats = """
            CREATE TABLE IF NOT EXISTS player_stats (
                uuid TEXT NOT NULL, mode TEXT NOT NULL,
                wins INTEGER NOT NULL DEFAULT 0,
                losses INTEGER NOT NULL DEFAULT 0,
                kills INTEGER NOT NULL DEFAULT 0,
                deaths INTEGER NOT NULL DEFAULT 0,
                elo INTEGER NOT NULL DEFAULT 1000,
                player_name TEXT NOT NULL DEFAULT '',
                PRIMARY KEY (uuid, mode)
            )""";
        String playtime = """
            CREATE TABLE IF NOT EXISTS player_playtime (
                uuid TEXT PRIMARY KEY, playtime INTEGER NOT NULL DEFAULT 0
            )""";
        String snapshots = """
            CREATE TABLE IF NOT EXISTS arena_snapshots (
                arena_number INTEGER NOT NULL,
                rel_x INTEGER NOT NULL, rel_y INTEGER NOT NULL, rel_z INTEGER NOT NULL,
                block_data TEXT NOT NULL, tile_json TEXT,
                PRIMARY KEY (arena_number, rel_x, rel_y, rel_z)
            )""";
        String friendships = """
            CREATE TABLE IF NOT EXISTS friendships (
                sender_uuid TEXT NOT NULL, receiver_uuid TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT 'PENDING',
                PRIMARY KEY (sender_uuid, receiver_uuid)
            )""";

        try (Connection c = getConnection(); var st = c.createStatement()) {
            st.execute(settings);  st.execute(tiers);     st.execute(kits);
            st.execute(arenas);    st.execute(stats);
            st.execute(playtime);  st.execute(snapshots); st.execute(friendships);
            // Safe upgrade columns for existing databases
            for (String sql : new String[]{
                "ALTER TABLE arenas ADD COLUMN name TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE arenas ADD COLUMN type TEXT NOT NULL DEFAULT 'DEFAULT'",
                "ALTER TABLE arenas ADD COLUMN has_snapshot INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE player_stats ADD COLUMN player_name TEXT NOT NULL DEFAULT ''",
                "ALTER TABLE player_stats ADD COLUMN kills INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE player_stats ADD COLUMN deaths INTEGER NOT NULL DEFAULT 0"
            }) { try { st.execute(sql); } catch (Exception ignored) {} }
        } catch (SQLException e) {
            log.severe("SQLite createTables failed: " + e.getMessage());
        }
    }

    @Override
    public void saveArena(com.scorepvp.model.Arena a) {
        String sql = "INSERT OR REPLACE INTO arenas " +
                     "(number,corner1,corner2,pos1,pos2,centre,name,type,has_snapshot)" +
                     " VALUES (?,?,?,?,?,?,?,?,?)";
        try (Connection c = getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setInt(1, a.getNumber());
            ps.setString(2, serializeLocationOrRaw(a.getCorner1(), a.getRawCorner1()));
            ps.setString(3, serializeLocationOrRaw(a.getCorner2(), a.getRawCorner2()));
            ps.setString(4, serializeLocationOrRaw(a.getPos1(), a.getRawPos1()));
            ps.setString(5, serializeLocationOrRaw(a.getPos2(), a.getRawPos2()));
            ps.setString(6, serializeLocationOrRaw(a.getCentre(), a.getRawCentre()));
            ps.setString(7, a.getName());
            ps.setString(8, a.getType().name());
            ps.setBoolean(9, a.hasSnapshot());
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("SQLite saveArena: " + e.getMessage()); }
    }

    @Override
    public void saveStats(com.scorepvp.model.PlayerStats s) {
        String sql = "INSERT OR REPLACE INTO player_stats " +
                     "(uuid,mode,wins,losses,kills,deaths,player_name) VALUES (?,?,?,?,?,?,?)";
        try (Connection c = getConnection(); var ps = c.prepareStatement(sql)) {
            ps.setString(1, s.getUuid().toString());
            ps.setString(2, s.getMode());
            ps.setInt(3, s.getWins());
            ps.setInt(4, s.getLosses());
            ps.setInt(5, s.getKills());
            ps.setInt(6, s.getDeaths());
            ps.setString(7, s.getPlayerName());
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("SQLite saveStats: " + e.getMessage()); }
    }
}
