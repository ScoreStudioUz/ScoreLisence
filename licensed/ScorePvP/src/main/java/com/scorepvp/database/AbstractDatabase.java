package com.scorepvp.database;

import com.scorepvp.model.PlayerSettings;
import com.scorepvp.model.PlayerSettings.ProfileVisibility;
import com.scorepvp.model.PlayerSettings.Visibility;
import com.scorepvp.util.SerializationUtil;
import org.bukkit.inventory.ItemStack;

import java.sql.*;
import java.util.*;
import java.util.logging.Logger;

public abstract class AbstractDatabase implements IDatabase {

    protected final Logger log;

    protected AbstractDatabase(Logger log) {
        this.log = log;
    }

    protected abstract Connection getConnection() throws SQLException;

    @Override
    public void createTables() {
        String settings = """
            CREATE TABLE IF NOT EXISTS player_settings (
                uuid          VARCHAR(36)  PRIMARY KEY,
                duel_requests VARCHAR(16)  NOT NULL DEFAULT 'ANYONE',
                party_invites VARCHAR(16)  NOT NULL DEFAULT 'ANYONE',
                show_own_tier VARCHAR(16)  NOT NULL DEFAULT 'ANYONE',
                friend_req    TINYINT(1)   NOT NULL DEFAULT 1,
                direct_msg    VARCHAR(16)  NOT NULL DEFAULT 'ANYONE',
                friend_join   TINYINT(1)   NOT NULL DEFAULT 1,
                profile_vis   VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC'
            )""";

        String tiers = """
            CREATE TABLE IF NOT EXISTS player_tiers (
                uuid  VARCHAR(36) NOT NULL,
                mode  VARCHAR(64) NOT NULL,
                tier  VARCHAR(16) NOT NULL,
                PRIMARY KEY (uuid, mode)
            )""";

        String kits = """
            CREATE TABLE IF NOT EXISTS personal_kits (
                uuid     VARCHAR(36) PRIMARY KEY,
                contents TEXT        NOT NULL
            )""";

        String arenas = """
            CREATE TABLE IF NOT EXISTS arenas (
                number  INT          PRIMARY KEY,
                corner1 VARCHAR(256),
                corner2 VARCHAR(256),
                pos1    VARCHAR(256),
                pos2    VARCHAR(256),
                centre  VARCHAR(256)
            )""";

        String stats = """
            CREATE TABLE IF NOT EXISTS player_stats (
                uuid    VARCHAR(36) NOT NULL,
                mode    VARCHAR(64) NOT NULL,
                wins    INT         NOT NULL DEFAULT 0,
                losses  INT         NOT NULL DEFAULT 0,
                elo     INT         NOT NULL DEFAULT 1000,
                PRIMARY KEY (uuid, mode)
            )""";

        String playtime = """
            CREATE TABLE IF NOT EXISTS player_playtime (
                uuid     VARCHAR(36) PRIMARY KEY,
                playtime BIGINT      NOT NULL DEFAULT 0
            )""";

        String snapshots = """
            CREATE TABLE IF NOT EXISTS arena_snapshots (
                arena_number INT          NOT NULL,
                rel_x        INT          NOT NULL,
                rel_y        INT          NOT NULL,
                rel_z        INT          NOT NULL,
                block_data   VARCHAR(512) NOT NULL,
                tile_json    TEXT,
                PRIMARY KEY (arena_number, rel_x, rel_y, rel_z)
            )""";

        String friendships = """
            CREATE TABLE IF NOT EXISTS friendships (
                sender_uuid   VARCHAR(36) NOT NULL,
                receiver_uuid VARCHAR(36) NOT NULL,
                state         VARCHAR(16) NOT NULL DEFAULT 'PENDING',
                PRIMARY KEY (sender_uuid, receiver_uuid)
            )""";

        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.execute(settings);
            s.execute(tiers);
            s.execute(kits);
            s.execute(arenas);
            s.execute(stats);
            s.execute(playtime);
            s.execute(snapshots);
            s.execute(friendships);
            // Add name+type columns to arenas if missing (upgrade path)
            try { s.execute("ALTER TABLE arenas ADD COLUMN name VARCHAR(64) NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { s.execute("ALTER TABLE arenas ADD COLUMN type VARCHAR(16) NOT NULL DEFAULT 'DEFAULT'"); } catch (Exception ignored) {}
            try { s.execute("ALTER TABLE arenas ADD COLUMN has_snapshot TINYINT(1) NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            // Add player_name + kills/deaths to player_stats if missing
            try { s.execute("ALTER TABLE player_stats ADD COLUMN player_name VARCHAR(64) NOT NULL DEFAULT ''"); } catch (Exception ignored) {}
            try { s.execute("ALTER TABLE player_stats ADD COLUMN kills INT NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
            try { s.execute("ALTER TABLE player_stats ADD COLUMN deaths INT NOT NULL DEFAULT 0"); } catch (Exception ignored) {}
        } catch (SQLException e) {
            log.severe("Failed to create tables: " + e.getMessage());
        }
    }

    // ── Settings ─────────────────────────────────────────────────────────────

    @Override
    public PlayerSettings loadSettings(UUID uuid) {
        String sql = "SELECT * FROM player_settings WHERE uuid = ?";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    PlayerSettings s = new PlayerSettings(uuid);
                    s.setDuelRequests(Visibility.valueOf(rs.getString("duel_requests")));
                    s.setPartyInvites(Visibility.valueOf(rs.getString("party_invites")));
                    s.setShowOwnTier(Visibility.valueOf(rs.getString("show_own_tier")));
                    s.setFriendRequests(rs.getBoolean("friend_req"));
                    s.setDirectMessages(Visibility.valueOf(rs.getString("direct_msg")));
                    s.setFriendJoinNotifier(rs.getBoolean("friend_join"));
                    s.setProfileVisibility(ProfileVisibility.valueOf(rs.getString("profile_vis")));
                    return s;
                }
            }
        } catch (SQLException e) {
            log.severe("loadSettings failed: " + e.getMessage());
        }
        return new PlayerSettings(uuid); // defaults
    }

    @Override
    public void saveSettings(PlayerSettings s) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(upsertSettings())) {
            ps.setString(1, s.getUuid().toString());
            ps.setString(2, s.getDuelRequests().name());
            ps.setString(3, s.getPartyInvites().name());
            ps.setString(4, s.getShowOwnTier().name());
            ps.setBoolean(5, s.isFriendRequests());
            ps.setString(6, s.getDirectMessages().name());
            ps.setBoolean(7, s.isFriendJoinNotifier());
            ps.setString(8, s.getProfileVisibility().name());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.severe("saveSettings failed: " + e.getMessage());
        }
    }

    /** MySQL dialect — override in SQLite subclass. */
    protected String upsertSettings() {
        return "INSERT INTO player_settings " +
               "(uuid,duel_requests,party_invites,show_own_tier,friend_req,direct_msg,friend_join,profile_vis) " +
               "VALUES (?,?,?,?,?,?,?,?) " +
               "ON DUPLICATE KEY UPDATE " +
               "duel_requests=VALUES(duel_requests)," +
               "party_invites=VALUES(party_invites)," +
               "show_own_tier=VALUES(show_own_tier)," +
               "friend_req=VALUES(friend_req)," +
               "direct_msg=VALUES(direct_msg)," +
               "friend_join=VALUES(friend_join)," +
               "profile_vis=VALUES(profile_vis)";
    }

    // ── Tiers ────────────────────────────────────────────────────────────────

    @Override
    public Map<String, String> loadTiers(UUID uuid) {
        Map<String, String> tiers = new LinkedHashMap<>();
        String sql = "SELECT mode, tier FROM player_tiers WHERE uuid = ?";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tiers.put(rs.getString("mode"), rs.getString("tier"));
                }
            }
        } catch (SQLException e) {
            log.severe("loadTiers failed: " + e.getMessage());
        }
        return tiers;
    }

    @Override
    public void saveTier(UUID uuid, String mode, String tier) {
        String sql = upsert("player_tiers", "uuid,mode,tier", "tier");
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mode);
            ps.setString(3, tier);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.severe("saveTier failed: " + e.getMessage());
        }
    }

    @Override
    public void removeTier(UUID uuid, String mode) {
        String sql = "DELETE FROM player_tiers WHERE uuid = ? AND mode = ?";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mode);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.severe("removeTier failed: " + e.getMessage());
        }
    }

    // ── Kits ─────────────────────────────────────────────────────────────────

    @Override
    public ItemStack[] loadPersonalKit(UUID uuid) {
        String sql = "SELECT contents FROM personal_kits WHERE uuid = ?";
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return SerializationUtil.itemsFromBase64(rs.getString("contents"));
                }
            }
        } catch (Exception e) {
            log.severe("loadPersonalKit failed: " + e.getMessage());
        }
        return null;
    }

    @Override
    public void savePersonalKit(UUID uuid, ItemStack[] contents) {
        String sql = upsert("personal_kits", "uuid,contents", "contents");
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, SerializationUtil.itemsToBase64(contents));
            ps.executeUpdate();
        } catch (Exception e) {
            log.severe("savePersonalKit failed: " + e.getMessage());
        }
    }

    /** Generates MySQL-style upsert SQL — overridden by SQLite impl. */

    protected String upsert(String table, String columns, String updateCol) {
        String[] cols   = columns.split(",");
        String   marks  = "?,".repeat(cols.length);
        marks = marks.substring(0, marks.length() - 1);
        return "INSERT INTO " + table + " (" + columns + ") VALUES (" + marks + ")" +
               " ON DUPLICATE KEY UPDATE " + updateCol + "=VALUES(" + updateCol + ")";
    }

    // ── Arenas ───────────────────────────────────────────────────────────────

    @Override
    public List<com.scorepvp.model.Arena> loadAllArenas() {
        List<com.scorepvp.model.Arena> arenas = new java.util.ArrayList<>();
        String sql = "SELECT * FROM arenas";
        try (Connection c = getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) {
                com.scorepvp.model.Arena a = new com.scorepvp.model.Arena(rs.getInt("number"));
                // Parse locations; also preserve raw strings so data is not lost if world is unavailable
                String rawC1 = rs.getString("corner1");
                String rawC2 = rs.getString("corner2");
                String rawP1 = rs.getString("pos1");
                String rawP2 = rs.getString("pos2");
                String rawCe = rs.getString("centre");
                a.setCorner1(parseLocation(rawC1));
                a.setCorner2(parseLocation(rawC2));
                a.setPos1(parseLocation(rawP1));
                a.setPos2(parseLocation(rawP2));
                a.setCentre(parseLocation(rawCe));
                // Preserve raw strings for positions that failed to parse (world not loaded yet)
                if (a.getCorner1() == null && rawC1 != null && !rawC1.isEmpty()) a.setRawCorner1(rawC1);
                if (a.getCorner2() == null && rawC2 != null && !rawC2.isEmpty()) a.setRawCorner2(rawC2);
                if (a.getPos1() == null && rawP1 != null && !rawP1.isEmpty()) a.setRawPos1(rawP1);
                if (a.getPos2() == null && rawP2 != null && !rawP2.isEmpty()) a.setRawPos2(rawP2);
                if (a.getCentre() == null && rawCe != null && !rawCe.isEmpty()) a.setRawCentre(rawCe);
                try { a.setName(rs.getString("name")); } catch (Exception ignored) {}
                try {
                    String t = rs.getString("type");
                    if (t != null) a.setType(com.scorepvp.model.Arena.ArenaType.valueOf(t));
                } catch (Exception ignored) {}
                try { a.setHasSnapshot(rs.getBoolean("has_snapshot")); } catch (Exception ignored) {}
                arenas.add(a);
            }
        } catch (SQLException e) { log.severe("loadAllArenas: " + e.getMessage()); }
        return arenas;
    }

    @Override
    public void saveArena(com.scorepvp.model.Arena a) {
        String sql = "INSERT INTO arenas (number,corner1,corner2,pos1,pos2,centre,name,type,has_snapshot)" +
                     " VALUES (?,?,?,?,?,?,?,?,?)" +
                     " ON DUPLICATE KEY UPDATE corner1=VALUES(corner1),corner2=VALUES(corner2)," +
                     "pos1=VALUES(pos1),pos2=VALUES(pos2),centre=VALUES(centre)," +
                     "name=VALUES(name),type=VALUES(type),has_snapshot=VALUES(has_snapshot)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
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
        } catch (SQLException e) { log.severe("saveArena: " + e.getMessage()); }
    }

    @Override
    public void deleteArena(int number) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM arenas WHERE number=?")) {
            ps.setInt(1, number);
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("deleteArena: " + e.getMessage()); }
    }

    // ── Player Stats ─────────────────────────────────────────────────────────

    @Override
    public com.scorepvp.model.PlayerStats loadStats(UUID uuid, String mode) {
        com.scorepvp.model.PlayerStats stats = new com.scorepvp.model.PlayerStats(uuid, mode);
        String sql = "SELECT wins,losses,kills,deaths,elo,player_name FROM player_stats WHERE uuid=? AND mode=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mode);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stats.setWins(rs.getInt("wins"));
                    stats.setLosses(rs.getInt("losses"));
                    stats.setKills(rs.getInt("kills"));
                    stats.setDeaths(rs.getInt("deaths"));
                    stats.setPlayerName(rs.getString("player_name"));
                }
            }
        } catch (SQLException e) { log.severe("loadStats: " + e.getMessage()); }
        return stats;
    }

    @Override
    public void saveStats(com.scorepvp.model.PlayerStats s) {
        String sql = "INSERT INTO player_stats (uuid,mode,wins,losses,kills,deaths,player_name) " +
                     "VALUES (?,?,?,?,?,?,?) " +
                     "ON DUPLICATE KEY UPDATE " +
                     "wins=VALUES(wins),losses=VALUES(losses),kills=VALUES(kills)," +
                     "deaths=VALUES(deaths),player_name=VALUES(player_name)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, s.getUuid().toString());
            ps.setString(2, s.getMode());
            ps.setInt(3, s.getWins());
            ps.setInt(4, s.getLosses());
            ps.setInt(5, s.getKills());
            ps.setInt(6, s.getDeaths());
            ps.setString(7, s.getPlayerName());
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("saveStats: " + e.getMessage()); }
    }

    // ── Clear Stats (admin commands) ──────────────────────────────────────────

    @Override
    public void clearAllStats() {
        try (Connection c = getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM player_stats");
            s.executeUpdate("DELETE FROM player_tiers");
            s.executeUpdate("DELETE FROM player_playtime");
        } catch (SQLException e) {
            log.severe("clearAllStats failed: " + e.getMessage());
        }
    }

    @Override
    public void clearPlayerStats(UUID uuid) {
        try (Connection c = getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM player_stats WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM player_tiers WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM player_playtime WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            log.severe("clearPlayerStats failed for " + uuid + ": " + e.getMessage());
        }
    }

    // ── Playtime ─────────────────────────────────────────────────────────────

    @Override
    public long loadPlaytime(UUID uuid) {
        String sql = "SELECT playtime FROM player_playtime WHERE uuid=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong("playtime");
            }
        } catch (SQLException e) { log.severe("loadPlaytime: " + e.getMessage()); }
        return 0;
    }

    @Override
    public void savePlaytime(UUID uuid, long minutes) {
        String sql = upsert("player_playtime", "uuid,playtime", "playtime");
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, minutes);
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("savePlaytime: " + e.getMessage()); }
    }

    // ── Location serialisation ────────────────────────────────────────────────

    protected String serializeLocation(org.bukkit.Location l) {
        if (l == null) return null;
        if (l.getWorld() == null) return null;
        return l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ()
                + "," + l.getYaw() + "," + l.getPitch();
    }

    /** Serializes Location if available, otherwise falls back to the raw DB string to prevent data loss. */
    protected String serializeLocationOrRaw(org.bukkit.Location l, String rawFallback) {
        if (l != null) {
            String serialized = serializeLocation(l);
            if (serialized != null) return serialized;
        }
        return rawFallback; // may be null (= no data) or a previously stored string
    }

    protected org.bukkit.Location parseLocation(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            String[] p = s.split(",");
            org.bukkit.World w = org.bukkit.Bukkit.getWorld(p[0]);
            if (w == null) return null;
            return new org.bukkit.Location(w,
                    Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]),   Float.parseFloat(p[5]));
        } catch (Exception e) { return null; }
    }
    // ── Arena snapshot ────────────────────────────────────────────────────────

    @Override
    public List<com.scorepvp.model.ArenaBlock> loadArenaSnapshot(int arenaNumber) {
        List<com.scorepvp.model.ArenaBlock> blocks = new java.util.ArrayList<>();
        String sql = "SELECT rel_x,rel_y,rel_z,block_data,tile_json FROM arena_snapshots WHERE arena_number=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, arenaNumber);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    org.bukkit.block.data.BlockData bd;
                    try {
                        bd = org.bukkit.Bukkit.createBlockData(rs.getString("block_data"));
                    } catch (Exception e) {
                        continue;
                    }
                    blocks.add(new com.scorepvp.model.ArenaBlock(
                            rs.getInt("rel_x"), rs.getInt("rel_y"), rs.getInt("rel_z"),
                            bd, rs.getString("tile_json")));
                }
            }
        } catch (SQLException e) { log.severe("loadArenaSnapshot: " + e.getMessage()); }
        return blocks;
    }

    @Override
    public void saveArenaSnapshot(int arenaNumber, List<com.scorepvp.model.ArenaBlock> blocks) {
        // Delete old snapshot first
        deleteArenaSnapshot(arenaNumber);
        if (blocks.isEmpty()) return;
        String sql = "INSERT INTO arena_snapshots (arena_number,rel_x,rel_y,rel_z,block_data,tile_json) VALUES (?,?,?,?,?,?)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (com.scorepvp.model.ArenaBlock b : blocks) {
                ps.setInt(1, arenaNumber);
                ps.setInt(2, b.getRelX());
                ps.setInt(3, b.getRelY());
                ps.setInt(4, b.getRelZ());
                ps.setString(5, b.getBlockData().getAsString());
                ps.setString(6, b.getTileEntityJson());
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) { log.severe("saveArenaSnapshot: " + e.getMessage()); }
    }

    @Override
    public void deleteArenaSnapshot(int arenaNumber) {
        try (Connection c = getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM arena_snapshots WHERE arena_number=?")) {
            ps.setInt(1, arenaNumber);
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("deleteArenaSnapshot: " + e.getMessage()); }
    }

    // ── Top Leaderboards ──────────────────────────────────────────────────────

    @Override
    public List<com.scorepvp.model.TopEntry> loadTopWins(String mode, int limit) {
        return loadTop("wins", mode, limit, "wins DESC");
    }

    @Override
    public List<com.scorepvp.model.TopEntry> loadTopElo(String mode, int limit) {
        return loadTop("elo", mode, limit, "elo DESC");
    }

    @Override
    public List<com.scorepvp.model.TopEntry> loadTopKd(String mode, int limit) {
        List<com.scorepvp.model.TopEntry> list = new java.util.ArrayList<>();
        // Bug #10: only include rows where player_name is not empty
        String sql = "SELECT player_name, CAST(wins AS FLOAT)/CASE WHEN losses < 1 THEN 1 ELSE losses END AS kd " +
                     "FROM player_stats WHERE mode=? AND player_name != '' ORDER BY kd DESC LIMIT ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mode);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new com.scorepvp.model.TopEntry(rs.getString("player_name"),
                            Math.round(rs.getDouble("kd") * 100.0) / 100.0));
                }
            }
        } catch (SQLException e) { log.severe("loadTopKd: " + e.getMessage()); }
        return list;
    }

    @Override
    public List<com.scorepvp.model.TopEntry> loadTopKills(String mode, int limit) {
        return loadTop("kills", mode, limit, "kills DESC");
    }

    private List<com.scorepvp.model.TopEntry> loadTop(String valueCol, String mode, int limit, String order) {
        List<com.scorepvp.model.TopEntry> list = new java.util.ArrayList<>();
        // Bug #10: only include rows where player_name is not empty
        String sql = "SELECT player_name, " + valueCol + " FROM player_stats WHERE mode=? AND player_name != '' ORDER BY " + order + " LIMIT ?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, mode);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new com.scorepvp.model.TopEntry(rs.getString("player_name"), rs.getDouble(valueCol)));
                }
            }
        } catch (SQLException e) { log.severe("loadTop(" + valueCol + "): " + e.getMessage()); }
        return list;
    }



    // ── Friends ───────────────────────────────────────────────────────────────

    @Override
    public List<com.scorepvp.model.Friendship> loadFriends(UUID uuid) {
        List<com.scorepvp.model.Friendship> list = new java.util.ArrayList<>();
        String sql = "SELECT sender_uuid, receiver_uuid, state FROM friendships WHERE sender_uuid=? OR receiver_uuid=?";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid.toString());
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID s = UUID.fromString(rs.getString("sender_uuid"));
                    UUID r = UUID.fromString(rs.getString("receiver_uuid"));
                    com.scorepvp.model.Friendship f = new com.scorepvp.model.Friendship(s, r);
                    if ("ACCEPTED".equals(rs.getString("state"))) f.accept();
                    list.add(f);
                }
            }
        } catch (SQLException e) { log.severe("loadFriends: " + e.getMessage()); }
        return list;
    }

    @Override
    public void saveFriendship(com.scorepvp.model.Friendship f) {
        String sql = upsert("friendships", "sender_uuid,receiver_uuid,state", "state");
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, f.getSender().toString());
            ps.setString(2, f.getReceiver().toString());
            ps.setString(3, f.getState().name());
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("saveFriendship: " + e.getMessage()); }
    }

    @Override
    public void deleteFriendship(UUID a, UUID b) {
        String sql = "DELETE FROM friendships WHERE (sender_uuid=? AND receiver_uuid=?) OR (sender_uuid=? AND receiver_uuid=?)";
        try (Connection c = getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, a.toString()); ps.setString(2, b.toString());
            ps.setString(3, b.toString()); ps.setString(4, a.toString());
            ps.executeUpdate();
        } catch (SQLException e) { log.severe("deleteFriendship: " + e.getMessage()); }
    }
}
