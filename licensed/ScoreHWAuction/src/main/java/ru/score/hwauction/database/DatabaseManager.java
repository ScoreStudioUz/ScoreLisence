package ru.score.hwauction.database;

import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.AuctionStatus;

import java.util.List;
import java.util.UUID;

public interface DatabaseManager {

    void init() throws Exception;
    void close();

    int  insertItem(AuctionItem item) throws Exception;
    void updateItem(AuctionItem item) throws Exception;
    void deleteItem(int id) throws Exception;

    List<AuctionItem> loadActiveItems() throws Exception;
    List<AuctionItem> loadItemsByPlayer(UUID playerUuid, AuctionStatus status) throws Exception;
    List<AuctionItem> loadExpiredByPlayer(UUID playerUuid) throws Exception;

    int  countActiveByPlayer(UUID playerUuid) throws Exception;
    AuctionItem getItemById(int id) throws Exception;
    void markExpiredItems() throws Exception;

    int deleteExpiredBefore(long cutoffTimestamp) throws Exception;

    double getCheapestPerUnit(String materialName) throws Exception;

    void saveSoundPreference(UUID playerUuid, boolean enabled) throws Exception;
    boolean loadSoundPreference(UUID playerUuid) throws Exception;

    /** Queue a pending refund for an offline player */
    void queueRefund(UUID playerUuid, double amount, String reason) throws Exception;

    /** Load and delete all pending refunds for a player (called on login) */
    List<PendingRefund> drainRefunds(UUID playerUuid) throws Exception;

    /** Queue a notification message for an offline player */
    void queueNotification(UUID playerUuid, String message) throws Exception;

    /** Load and delete all pending notifications for a player (called on login) */
    List<String> drainNotifications(UUID playerUuid) throws Exception;

    record PendingRefund(double amount, String reason) {}

    /**
     * Load EXPIRED AUCTION items with a bidder (unfinalized after server restart).
     * These were marked EXPIRED by markExpiredItems() but never paid out.
     */
    List<AuctionItem> loadRestartPendingAuctions() throws Exception;

    String getVendorName();
}
