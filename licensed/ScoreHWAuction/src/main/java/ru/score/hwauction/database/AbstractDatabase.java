package ru.score.hwauction.database;

import com.zaxxer.hikari.HikariDataSource;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.AuctionMode;
import ru.score.hwauction.model.AuctionStatus;
import ru.score.hwauction.util.ItemSerializer;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class AbstractDatabase implements DatabaseManager {

    protected HikariDataSource dataSource;

    // Subclasses provide driver-specific pieces
    protected abstract HikariDataSource buildPool() throws Exception;
    protected abstract String autoIncrement();
    protected abstract String upsertSound();   // full UPSERT SQL for player_settings

    @Override
    public void init() throws Exception {
        dataSource = buildPool();
        createTables();
    }

    @Override
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    // ---- DDL ----

    private void createTables() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ah_items (" +
                "  id           INTEGER PRIMARY KEY " + autoIncrement() + "," +
                "  seller_uuid  VARCHAR(36)  NOT NULL," +
                "  seller_name  VARCHAR(64)  NOT NULL," +
                "  item_data    TEXT         NOT NULL," +
                "  amount       INTEGER      NOT NULL," +
                "  price        DOUBLE       NOT NULL," +
                "  price_per    DOUBLE       NOT NULL," +
                "  category     VARCHAR(64)  NOT NULL," +
                "  mode         VARCHAR(16)  NOT NULL DEFAULT 'MARKET'," +
                "  full_only    TINYINT(1)   NOT NULL DEFAULT 0," +
                "  start_price  DOUBLE       NOT NULL DEFAULT 0," +
                "  current_bid  DOUBLE       NOT NULL DEFAULT 0," +
                "  step_price   DOUBLE       NOT NULL DEFAULT 0," +
                "  bidder_uuid  VARCHAR(36)," +
                "  bidder_name  VARCHAR(64)," +
                "  listed_at    BIGINT       NOT NULL," +
                "  expires_at   BIGINT       NOT NULL," +
                "  status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'" +
                ")"
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ah_player_settings (" +
                "  player_uuid    VARCHAR(36) PRIMARY KEY," +
                "  sound_enabled  TINYINT(1)  NOT NULL DEFAULT 1" +
                ")"
            );

            // Index for fast player lookups
            try {
                st.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_ah_seller ON ah_items(seller_uuid, status)");
            } catch (SQLException ignored) { /* MySQL may not support IF NOT EXISTS on index */ }

            // Pending refunds for offline players
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ah_pending_refunds (" +
                "  id          INTEGER PRIMARY KEY " + autoIncrement() + "," +
                "  player_uuid VARCHAR(36) NOT NULL," +
                "  amount      DOUBLE      NOT NULL," +
                "  reason      VARCHAR(255) NOT NULL," +
                "  created_at  BIGINT      NOT NULL" +
                ")"
            );

            // Pending notifications for offline players
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ah_pending_notifications (" +
                "  id          INTEGER PRIMARY KEY " + autoIncrement() + "," +
                "  player_uuid VARCHAR(36) NOT NULL," +
                "  message     TEXT        NOT NULL," +
                "  created_at  BIGINT      NOT NULL" +
                ")"
            );
        }
    }

    // ---- DML ----

    @Override
    public int insertItem(AuctionItem item) throws Exception {
        final String sql =
            "INSERT INTO ah_items (seller_uuid, seller_name, item_data, amount, price, price_per, " +
            "category, mode, full_only, start_price, current_bid, step_price, " +
            "bidder_uuid, bidder_name, listed_at, expires_at, status) " +
            "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bindInsert(ps, item);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
    }

    @Override
    public void updateItem(AuctionItem item) throws Exception {
        final String sql =
            "UPDATE ah_items SET seller_uuid=?, seller_name=?, item_data=?, amount=?, price=?, " +
            "price_per=?, category=?, mode=?, full_only=?, start_price=?, current_bid=?, " +
            "step_price=?, bidder_uuid=?, bidder_name=?, listed_at=?, expires_at=?, status=? " +
            "WHERE id=?";

        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bindInsert(ps, item);
            ps.setInt(18, item.getId());
            ps.executeUpdate();
        }
    }

    @Override
    public void deleteItem(int id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM ah_items WHERE id=?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    @Override
    public List<AuctionItem> loadActiveItems() throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM ah_items WHERE status='ACTIVE' ORDER BY listed_at DESC")) {
            return map(ps.executeQuery());
        }
    }

    @Override
    public List<AuctionItem> loadItemsByPlayer(UUID playerUuid, AuctionStatus status) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM ah_items WHERE seller_uuid=? AND status=? ORDER BY listed_at DESC")) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, status.name());
            return map(ps.executeQuery());
        }
    }

    @Override
    public List<AuctionItem> loadExpiredByPlayer(UUID playerUuid) throws Exception {
        // Returns EXPIRED items where player is SELLER (unclaimed market/auction items)
        // OR where player is TOP BIDDER of an EXPIRED auction (won item to claim)
        final String sql =
            "SELECT * FROM ah_items WHERE status='EXPIRED' " +
            "AND (seller_uuid=? OR (mode='AUCTION' AND bidder_uuid=?)) " +
            "ORDER BY expires_at DESC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, playerUuid.toString());
            return map(ps.executeQuery());
        }
    }

    @Override
    public int countActiveByPlayer(UUID playerUuid) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM ah_items WHERE seller_uuid=? AND status='ACTIVE'")) {
            ps.setString(1, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    @Override
    public AuctionItem getItemById(int id) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT * FROM ah_items WHERE id=?")) {
            ps.setInt(1, id);
            List<AuctionItem> list = map(ps.executeQuery());
            return list.isEmpty() ? null : list.get(0);
        }
    }

    @Override
    public void markExpiredItems() throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "UPDATE ah_items SET status='EXPIRED' WHERE status='ACTIVE' AND expires_at < ?")) {
            ps.setLong(1, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public void saveSoundPreference(UUID playerUuid, boolean enabled) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(upsertSound())) {
            ps.setString(1, playerUuid.toString());
            ps.setBoolean(2, enabled);
            ps.setBoolean(3, enabled);
            ps.executeUpdate();
        }
    }

    @Override
    public boolean loadSoundPreference(UUID playerUuid) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT sound_enabled FROM ah_player_settings WHERE player_uuid=?")) {
            ps.setString(1, playerUuid.toString());
            ResultSet rs = ps.executeQuery();
            return !rs.next() || rs.getBoolean("sound_enabled");
        }
    }

    // ---- Helpers ----

    private void bindInsert(PreparedStatement ps, AuctionItem item) throws Exception {
        ps.setString(1, item.getSellerUuid().toString());
        ps.setString(2, item.getSellerName());
        ps.setString(3, ItemSerializer.serialize(item.getItemStack()));
        ps.setInt(4, item.getAmount());
        ps.setDouble(5, item.getPrice());
        ps.setDouble(6, item.getPricePerUnit());
        ps.setString(7, item.getCategory());
        ps.setString(8, item.getMode().name());
        ps.setBoolean(9, item.isFullOnly());
        ps.setDouble(10, item.getStartPrice());
        ps.setDouble(11, item.getCurrentBid());
        ps.setDouble(12, item.getStepPrice());
        ps.setString(13, item.getTopBidderUuid() != null ? item.getTopBidderUuid().toString() : null);
        ps.setString(14, item.getTopBidderName());
        ps.setLong(15, item.getListedAt());
        ps.setLong(16, item.getExpiresAt());
        ps.setString(17, item.getStatus().name());
    }

    private List<AuctionItem> map(ResultSet rs) throws Exception {
        List<AuctionItem> list = new ArrayList<>();
        while (rs.next()) {
            try {
                AuctionItem a = new AuctionItem();
                a.setId(rs.getInt("id"));
                a.setSellerUuid(UUID.fromString(rs.getString("seller_uuid")));
                a.setSellerName(rs.getString("seller_name"));
                a.setItemStack(ItemSerializer.deserialize(rs.getString("item_data")));
                a.setAmount(rs.getInt("amount"));
                a.setPrice(rs.getDouble("price"));
                a.setPricePerUnit(rs.getDouble("price_per"));
                a.setCategory(rs.getString("category"));
                a.setMode(AuctionMode.valueOf(rs.getString("mode")));
                a.setFullOnly(rs.getBoolean("full_only"));
                a.setStartPrice(rs.getDouble("start_price"));
                a.setCurrentBid(rs.getDouble("current_bid"));
                a.setStepPrice(rs.getDouble("step_price"));
                String bUuid = rs.getString("bidder_uuid");
                if (bUuid != null && !bUuid.isEmpty()) a.setTopBidderUuid(UUID.fromString(bUuid));
                a.setTopBidderName(rs.getString("bidder_name"));
                a.setListedAt(rs.getLong("listed_at"));
                a.setExpiresAt(rs.getLong("expires_at"));
                a.setStatus(AuctionStatus.valueOf(rs.getString("status")));
                list.add(a);
            } catch (Exception e) {
                // Skip corrupt rows but log
                System.err.println("[ScoreHWAuction] Failed to deserialize item id=" +
                    safeInt(rs, "id") + " - " + e.getMessage());
            }
        }
        return list;
    }

    @Override
    public int deleteExpiredBefore(long cutoffTimestamp) throws Exception {
        final String sql =
            "DELETE FROM ah_items WHERE status IN ('EXPIRED','CANCELLED','SOLD') AND expires_at < ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, cutoffTimestamp);
            return ps.executeUpdate();
        }
    }

    @Override
    public double getCheapestPerUnit(String materialName) throws Exception {
        final String sql =
            "SELECT MIN(price_per) FROM ah_items WHERE status='ACTIVE' AND mode='MARKET'" +
            " AND item_data LIKE ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, "%" + materialName + "%");
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                double v = rs.getDouble(1);
                return rs.wasNull() ? -1 : v;
            }
            return -1;
        }
    }

    private int safeInt(ResultSet rs, String col) {
        try { return rs.getInt(col); } catch (Exception e) { return -1; }
    }
    // ---- Restart Pending Auctions ----

    @Override
    public List<AuctionItem> loadRestartPendingAuctions() throws Exception {
        // EXPIRED AUCTION items that still have a bidder but were never finalized
        // (i.e. status=EXPIRED, mode=AUCTION, bidder_uuid IS NOT NULL)
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM ah_items WHERE status='EXPIRED' AND mode='AUCTION' " +
                "AND bidder_uuid IS NOT NULL")) {
            return map(ps.executeQuery());
        }
    }

    // ---- Pending Refunds ----

    @Override
    public void queueRefund(UUID playerUuid, double amount, String reason) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ah_pending_refunds (player_uuid, amount, reason, created_at) VALUES (?,?,?,?)")) {
            ps.setString(1, playerUuid.toString());
            ps.setDouble(2, amount);
            ps.setString(3, reason != null ? reason : "");
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public List<DatabaseManager.PendingRefund> drainRefunds(UUID playerUuid) throws Exception {
        List<DatabaseManager.PendingRefund> result = new java.util.ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT amount, reason FROM ah_pending_refunds WHERE player_uuid=?")) {
                sel.setString(1, playerUuid.toString());
                java.sql.ResultSet rs = sel.executeQuery();
                while (rs.next()) {
                    result.add(new DatabaseManager.PendingRefund(
                            rs.getDouble("amount"), rs.getString("reason")));
                }
            }
            if (!result.isEmpty()) {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM ah_pending_refunds WHERE player_uuid=?")) {
                    del.setString(1, playerUuid.toString());
                    del.executeUpdate();
                }
            }
        }
        return result;
    }

    // ---- Pending Notifications ----

    @Override
    public void queueNotification(UUID playerUuid, String message) throws Exception {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                "INSERT INTO ah_pending_notifications (player_uuid, message, created_at) VALUES (?,?,?)")) {
            ps.setString(1, playerUuid.toString());
            ps.setString(2, message);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    @Override
    public List<String> drainNotifications(UUID playerUuid) throws Exception {
        List<String> result = new java.util.ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement sel = c.prepareStatement(
                    "SELECT message FROM ah_pending_notifications WHERE player_uuid=? ORDER BY created_at ASC")) {
                sel.setString(1, playerUuid.toString());
                java.sql.ResultSet rs = sel.executeQuery();
                while (rs.next()) result.add(rs.getString("message"));
            }
            if (!result.isEmpty()) {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM ah_pending_notifications WHERE player_uuid=?")) {
                    del.setString(1, playerUuid.toString());
                    del.executeUpdate();
                }
            }
        }
        return result;
    }

}
