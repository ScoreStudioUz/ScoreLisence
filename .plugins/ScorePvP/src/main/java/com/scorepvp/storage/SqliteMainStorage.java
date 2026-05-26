package com.scorepvp.storage;

import com.scorepvp.model.Friendship;
import com.scorepvp.model.PlayerSettings;
import com.scorepvp.model.PlayerSettings.ProfileVisibility;
import com.scorepvp.model.PlayerSettings.Visibility;
import com.scorepvp.model.PlayerStats;
import com.scorepvp.model.TopEntry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * SQLite-backed storage for stats, playtime, settings, and friendships.
 *
 * <h2>What it stores (and what it doesn't)</h2>
 * Holds only per-player aggregate stats — NOT a per-match history. We never
 * insert a row per kill or per round; we update the single (uuid, mode) row
 * in place. That's why the file stays a couple of MB even after weeks of
 * play, instead of the 500MB that the old monolithic DB ballooned to.
 *
 * <h2>Schema</h2>
 * Four tables, each with sensible primary keys and indices for the
 * leaderboard queries. No legacy columns, no migration paths — this is a
 * fresh start.
 */
public class SqliteMainStorage implements MainStorage {

    private final HikariDataSource ds;
    private final Logger log;

    public SqliteMainStorage(File pluginDataFolder, Logger log) {
        this.log = log;
        if (!pluginDataFolder.exists()) pluginDataFolder.mkdirs();
        File dbFile = new File(pluginDataFolder, "scorepvp.db");

        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("org.sqlite.JDBC");
        cfg.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        cfg.setMaximumPoolSize(4);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5000);
        cfg.setPoolName("ScorePvP-Main-SQLite");
        this.ds = new HikariDataSource(cfg);

        createSchema();
    }

    private void createSchema() {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS player_stats (
                    uuid    VARCHAR(36) NOT NULL,
                    mode    VARCHAR(64) NOT NULL,
                    name    VARCHAR(32) NOT NULL DEFAULT '',
                    wins    INTEGER NOT NULL DEFAULT 0,
                    losses  INTEGER NOT NULL DEFAULT 0,
                    kills   INTEGER NOT NULL DEFAULT 0,
                    deaths  INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (uuid, mode)
                )
            """);
            s.execute("CREATE INDEX IF NOT EXISTS idx_stats_mode_wins   ON player_stats(mode, wins   DESC)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_stats_mode_kills  ON player_stats(mode, kills  DESC)");

            s.execute("""
                CREATE TABLE IF NOT EXISTS player_playtime (
                    uuid    VARCHAR(36) PRIMARY KEY,
                    minutes INTEGER NOT NULL DEFAULT 0
                )
            """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS player_settings (
                    uuid          VARCHAR(36) PRIMARY KEY,
                    duel_requests VARCHAR(16) NOT NULL DEFAULT 'ANYONE',
                    party_invites VARCHAR(16) NOT NULL DEFAULT 'ANYONE',
                    show_own_tier VARCHAR(16) NOT NULL DEFAULT 'ANYONE',
                    friend_req    INTEGER NOT NULL DEFAULT 1,
                    direct_msg    VARCHAR(16) NOT NULL DEFAULT 'ANYONE',
                    friend_join   INTEGER NOT NULL DEFAULT 1,
                    profile_vis   VARCHAR(16) NOT NULL DEFAULT 'PUBLIC'
                )
            """);

            s.execute("""
                CREATE TABLE IF NOT EXISTS friendships (
                    sender_uuid   VARCHAR(36) NOT NULL,
                    receiver_uuid VARCHAR(36) NOT NULL,
                    state         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                    PRIMARY KEY (sender_uuid, receiver_uuid)
                )
            """);
        } catch (SQLException e) {
            log.severe("[ScorePvP] scorepvp.db schema creation failed: " + e.getMessage());
        }
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    @Override
    public PlayerStats loadStats(UUID uuid, String mode) {
        PlayerStats s = new PlayerStats(uuid, mode);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, wins, losses, kills, deaths FROM player_stats " +
                     "WHERE uuid = ? AND mode = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    s.setPlayerName(rs.getString("name"));
                    s.setWins(rs.getInt("wins"));
                    s.setLosses(rs.getInt("losses"));
                    s.setKills(rs.getInt("kills"));
                    s.setDeaths(rs.getInt("deaths"));
                }
            }
        } catch (SQLException e) {
            log.warning("[ScorePvP] loadStats(" + uuid + "," + mode + ") failed: " + e.getMessage());
        }
        return s;
    }

    @Override
    public void saveStats(PlayerStats s) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO player_stats (uuid, mode, name, wins, losses, kills, deaths) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(uuid, mode) DO UPDATE SET " +
                     "name = excluded.name, wins = excluded.wins, losses = excluded.losses, " +
                     "kills = excluded.kills, deaths = excluded.deaths")) {
            ps.setString(1, s.getUuid().toString());
            ps.setString(2, s.getMode());
            ps.setString(3, s.getPlayerName() == null ? "" : s.getPlayerName());
            ps.setInt(4, s.getWins());
            ps.setInt(5, s.getLosses());
            ps.setInt(6, s.getKills());
            ps.setInt(7, s.getDeaths());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[ScorePvP] saveStats(" + s.getUuid() + "," + s.getMode() + ") failed: " + e.getMessage());
        }
    }

    @Override
    public void clearAllStats() {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM player_stats");
            s.execute("DELETE FROM player_playtime");
        } catch (SQLException e) {
            log.warning("[ScorePvP] clearAllStats failed: " + e.getMessage());
        }
    }

    @Override
    public void clearPlayerStats(UUID uuid) {
        try (Connection c = ds.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM player_stats WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM player_playtime WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.warning("[ScorePvP] clearPlayerStats(" + uuid + ") failed: " + e.getMessage());
        }
    }

    // ── Leaderboards ─────────────────────────────────────────────────────────

    @Override
    public List<TopEntry> loadTopWins(String mode, int limit) {
        return topByColumn(mode, "wins", limit);
    }

    @Override
    public List<TopEntry> loadTopKills(String mode, int limit) {
        return topByColumn(mode, "kills", limit);
    }

    @Override
    public List<TopEntry> loadTopElo(String mode, int limit) {
        // ELO not tracked in this schema — return empty rather than crash.
        // PlaceholderCache treats empty lists as "no data" gracefully.
        return new ArrayList<>();
    }

    @Override
    public List<TopEntry> loadTopKd(String mode, int limit) {
        // KD is a derived ratio; compute server-side, ignore zero-death edge.
        List<TopEntry> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT name, kills, deaths FROM player_stats " +
                     "WHERE mode = ? AND name != '' AND (kills > 0 OR deaths > 0) " +
                     "ORDER BY (CAST(kills AS REAL) / MAX(deaths, 1)) DESC LIMIT ?")) {
            ps.setString(1, mode);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int k = rs.getInt("kills");
                    int d = rs.getInt("deaths");
                    double kd = (d == 0) ? k : Math.round((double) k / d * 100.0) / 100.0;
                    out.add(new TopEntry(rs.getString("name"), kd));
                }
            }
        } catch (SQLException e) {
            log.warning("[ScorePvP] loadTopKd(" + mode + ") failed: " + e.getMessage());
        }
        return out;
    }

    private List<TopEntry> topByColumn(String mode, String column, int limit) {
        List<TopEntry> out = new ArrayList<>();
        // column is a whitelisted SQL identifier — never user input.
        String sql = "SELECT name, " + column + " AS v FROM player_stats " +
                     "WHERE mode = ? AND name != '' AND " + column + " > 0 " +
                     "ORDER BY v DESC LIMIT ?";
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mode);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new TopEntry(rs.getString("name"), rs.getInt("v")));
                }
            }
        } catch (SQLException e) {
            log.warning("[ScorePvP] topByColumn(" + mode + "," + column + ") failed: " + e.getMessage());
        }
        return out;
    }

    // ── Playtime ─────────────────────────────────────────────────────────────

    @Override
    public long loadPlaytime(UUID uuid) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT minutes FROM player_playtime WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("minutes");
            }
        } catch (SQLException e) {
            log.warning("[ScorePvP] loadPlaytime(" + uuid + ") failed: " + e.getMessage());
        }
        return 0L;
    }

    @Override
    public void savePlaytime(UUID uuid, long minutes) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO player_playtime (uuid, minutes) VALUES (?, ?) " +
                     "ON CONFLICT(uuid) DO UPDATE SET minutes = excluded.minutes")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, minutes);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[ScorePvP] savePlaytime(" + uuid + ") failed: " + e.getMessage());
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Override
    public PlayerSettings loadSettings(UUID uuid) {
        PlayerSettings s = new PlayerSettings(uuid);
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT duel_requests, party_invites, show_own_tier, friend_req, " +
                     "direct_msg, friend_join, profile_vis FROM player_settings WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    s.setDuelRequests(parseVis(rs.getString("duel_requests")));
                    s.setPartyInvites(parseVis(rs.getString("party_invites")));
                    s.setShowOwnTier(parseVis(rs.getString("show_own_tier")));
                    s.setFriendRequests(rs.getInt("friend_req") != 0);
                    s.setDirectMessages(parseVis(rs.getString("direct_msg")));
                    s.setFriendJoinNotifier(rs.getInt("friend_join") != 0);
                    s.setProfileVisibility(parseProfileVis(rs.getString("profile_vis")));
                }
            }
        } catch (SQLException e) {
            log.warning("[ScorePvP] loadSettings(" + uuid + ") failed: " + e.getMessage());
        }
        return s;
    }

    @Override
    public void saveSettings(PlayerSettings s) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO player_settings (uuid, duel_requests, party_invites, " +
                     "show_own_tier, friend_req, direct_msg, friend_join, profile_vis) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON CONFLICT(uuid) DO UPDATE SET " +
                     "duel_requests = excluded.duel_requests, " +
                     "party_invites = excluded.party_invites, " +
                     "show_own_tier = excluded.show_own_tier, " +
                     "friend_req    = excluded.friend_req, " +
                     "direct_msg    = excluded.direct_msg, " +
                     "friend_join   = excluded.friend_join, " +
                     "profile_vis   = excluded.profile_vis")) {
            ps.setString(1, s.getUuid().toString());
            ps.setString(2, s.getDuelRequests().name());
            ps.setString(3, s.getPartyInvites().name());
            ps.setString(4, s.getShowOwnTier().name());
            ps.setInt(5, s.isFriendRequests() ? 1 : 0);
            ps.setString(6, s.getDirectMessages().name());
            ps.setInt(7, s.isFriendJoinNotifier() ? 1 : 0);
            ps.setString(8, s.getProfileVisibility().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[ScorePvP] saveSettings(" + s.getUuid() + ") failed: " + e.getMessage());
        }
    }

    private Visibility parseVis(String s) {
        try { return Visibility.valueOf(s); }
        catch (Exception e) { return Visibility.ANYONE; }
    }

    private ProfileVisibility parseProfileVis(String s) {
        try { return ProfileVisibility.valueOf(s); }
        catch (Exception e) { return ProfileVisibility.PUBLIC; }
    }

    // ── Friendships ──────────────────────────────────────────────────────────

    @Override
    public List<Friendship> loadFriends(UUID uuid) {
        List<Friendship> out = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT sender_uuid, receiver_uuid, state FROM friendships " +
                     "WHERE sender_uuid = ? OR receiver_uuid = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID sender   = UUID.fromString(rs.getString("sender_uuid"));
                    UUID receiver = UUID.fromString(rs.getString("receiver_uuid"));
                    Friendship f = new Friendship(sender, receiver);
                    if ("ACCEPTED".equalsIgnoreCase(rs.getString("state"))) f.accept();
                    out.add(f);
                }
            }
        } catch (SQLException e) {
            log.warning("[ScorePvP] loadFriends(" + uuid + ") failed: " + e.getMessage());
        }
        return out;
    }

    @Override
    public void saveFriendship(Friendship f) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO friendships (sender_uuid, receiver_uuid, state) VALUES (?, ?, ?) " +
                     "ON CONFLICT(sender_uuid, receiver_uuid) DO UPDATE SET state = excluded.state")) {
            ps.setString(1, f.getSender().toString());
            ps.setString(2, f.getReceiver().toString());
            ps.setString(3, f.getState().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[ScorePvP] saveFriendship failed: " + e.getMessage());
        }
    }

    @Override
    public void deleteFriendship(UUID a, UUID b) {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM friendships WHERE " +
                     "(sender_uuid = ? AND receiver_uuid = ?) OR " +
                     "(sender_uuid = ? AND receiver_uuid = ?)")) {
            ps.setString(1, a.toString());
            ps.setString(2, b.toString());
            ps.setString(3, b.toString());
            ps.setString(4, a.toString());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warning("[ScorePvP] deleteFriendship failed: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        if (!ds.isClosed()) ds.close();
    }
}
