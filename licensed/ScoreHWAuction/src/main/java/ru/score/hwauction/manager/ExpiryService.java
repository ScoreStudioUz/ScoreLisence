package ru.score.hwauction.manager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.api.event.AuctionExpireEvent;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.AuctionMode;
import ru.score.hwauction.model.AuctionStatus;
import ru.score.hwauction.util.ItemUtil;
import ru.score.hwauction.util.MathUtil;
import ru.score.hwauction.util.MoneyUtil;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

public final class ExpiryService {

    private final ScoreHWAuction plugin;

    public ExpiryService(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    /** Expire a single active item. Must be called on the main thread. */
    public void expire(AuctionItem item) {
        if (item.getStatus() != AuctionStatus.ACTIVE) return;

        AuctionExpireEvent event = new AuctionExpireEvent(item);
        plugin.getServer().getPluginManager().callEvent(event);

        item.setStatus(AuctionStatus.EXPIRED);
        plugin.getAuctionCache().remove(item.getId());

        if (item.getMode() == AuctionMode.AUCTION && item.hasBidder()) {
            finalizeAuction(item);
        } else if (item.getMode() == AuctionMode.AUCTION && !item.hasBidder()) {
            // Auction expired with NO bids — notify seller their item is in «Просроченные товары»
            String itemName = ItemUtil.getDisplayName(item.getItemStack());
            Player onlineSeller = Bukkit.getPlayer(item.getSellerUuid());
            if (onlineSeller != null && onlineSeller.isOnline()) {
                onlineSeller.sendMessage(plugin.getMessages().get("auction-no-bids",
                        "{item}", itemName));
            } else {
                queueMsg(item.getSellerUuid(), plugin.getMessages().get("auction-no-bids",
                        "{item}", itemName));
            }
        } else if (item.getMode() == AuctionMode.MARKET) {
            // Market item expired unsold — notify seller
            String itemName = ItemUtil.getDisplayName(item.getItemStack());
            Player onlineSeller = Bukkit.getPlayer(item.getSellerUuid());
            if (onlineSeller != null && onlineSeller.isOnline()) {
                onlineSeller.sendMessage(plugin.getMessages().get("market-expired",
                        "{item}", itemName));
            } else {
                queueMsg(item.getSellerUuid(), plugin.getMessages().get("market-expired",
                        "{item}", itemName));
            }
        }
        // All EXPIRED items → seller claims via ExpiredGui

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try { plugin.getDb().updateItem(item); }
            catch (Exception e) { plugin.getLogger().log(Level.WARNING, "DB error on expire", e); }
        });
    }

    /**
     * Finalize an auction that ended with a winner:
     *  - Pay seller immediately (Vault works for OfflinePlayer)
     *  - Keep item as EXPIRED so winner claims it via «Просроченные товары»
     *  - If winner is ONLINE → notify immediately
     *  - If winner is OFFLINE → queue notification for next login
     */
    private void finalizeAuction(AuctionItem item) {
        String itemName = ItemUtil.getDisplayName(item.getItemStack());
        double tax        = plugin.getPluginConfig().getTax();
        double sellerGets = MathUtil.round(item.getCurrentBid() * (1.0 - tax), 2);

        // ── Pay seller ──
        OfflinePlayer seller = Bukkit.getOfflinePlayer(item.getSellerUuid());
        plugin.getEconomyService().deposit(seller, sellerGets);

        Player onlineSeller = Bukkit.getPlayer(item.getSellerUuid());
        if (onlineSeller != null && onlineSeller.isOnline()) {
            onlineSeller.sendMessage(plugin.getMessages().get("bid-seller-win",
                    "{item}",  itemName,
                    "{buyer}", item.getTopBidderName(),
                    "{price}", MoneyUtil.format(sellerGets)));
            plugin.getSoundService().playSold(onlineSeller);
        } else {
            queueMsg(item.getSellerUuid(), plugin.getMessages().get("bid-seller-win",
                    "{item}",  itemName,
                    "{buyer}", item.getTopBidderName(),
                    "{price}", MoneyUtil.format(sellerGets)));
        }

        // ── Notify winner — item is in «Просроченные товары» ──
        // Status stays EXPIRED so winner claims via ExpiredGui (works for online AND offline)
        Player onlineWinner = Bukkit.getPlayer(item.getTopBidderUuid());
        if (onlineWinner != null && onlineWinner.isOnline()) {
            // Online: notify immediately
            onlineWinner.sendMessage(plugin.getMessages().get("bid-win-notify",
                    "{item}", itemName));
            plugin.getSoundService().playBidWin(onlineWinner);
        } else {
            // Offline: queue notification for next login
            queueMsg(item.getTopBidderUuid(), plugin.getMessages().get("bid-win-notify",
                    "{item}", itemName));
        }
        // item.status stays EXPIRED — winner claims via loadExpiredByPlayer (which includes topBidder)
    }

    /**
     * After server restart: process auctions that expired during downtime.
     * markExpiredItems() already set them to EXPIRED in DB.
     * We need to pay sellers and notify winners.
     */
    public void processRestartExpired() {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<AuctionItem> items = plugin.getDb().loadRestartPendingAuctions();
                if (items.isEmpty()) return;
                plugin.getLogger().info("Processing " + items.size() + " unfinalized auction(s) after restart.");
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    for (AuctionItem item : items) {
                        finalizeAuction(item);
                        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                            try { plugin.getDb().updateItem(item); }
                            catch (Exception e) {
                                plugin.getLogger().log(Level.WARNING, "DB error on restart finalize", e);
                            }
                        });
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error processing restart-expired auctions", e);
            }
        });
    }

    /** Called by expiry task — scan cache for expired active items */
    public void processBatch() {
        List<AuctionItem> expired = plugin.getAuctionCache().expiredAndActive();
        if (expired.isEmpty()) return;
        if (plugin.getPluginConfig().isDebugMode())
            plugin.getLogger().info("Expiry task processing " + expired.size() + " item(s).");
        for (AuctionItem item : expired) expire(item);
    }

    /**
     * Player claims an expired item:
     *  - Seller claims unsold MARKET item back
     *  - Auction winner claims their won item
     */
    public boolean claimExpired(Player player, int itemId) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                AuctionItem item = plugin.getDb().getItemById(itemId);
                if (item == null || item.getStatus() != AuctionStatus.EXPIRED) return;

                boolean isSeller = item.isSeller(player.getUniqueId());
                boolean isWinner = item.getMode() == AuctionMode.AUCTION
                        && item.hasBidder()
                        && item.getTopBidderUuid().equals(player.getUniqueId());

                if (!isSeller && !isWinner) return;

                item.setStatus(AuctionStatus.CANCELLED);
                plugin.getDb().updateItem(item);

                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (ItemUtil.hasSpace(player, item.getItemStack())) {
                        player.getInventory().addItem(item.getItemStack().clone());
                    } else {
                        player.getWorld().dropItemNaturally(
                                player.getLocation(), item.getItemStack().clone());
                    }
                    if (isWinner) {
                        player.sendMessage(plugin.getMessages().get("bid-win-claimed",
                                "{item}", ItemUtil.getDisplayName(item.getItemStack())));
                        plugin.getSoundService().playBidWin(player);
                    } else {
                        player.sendMessage(plugin.getMessages().get("expired-item-claimed",
                                "{item}", ItemUtil.getDisplayName(item.getItemStack())));
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error claiming expired item", e);
            }
        });
        return true;
    }

    private void queueMsg(UUID uuid, String message) {
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try { plugin.getDb().queueNotification(uuid, message); }
            catch (Exception e) { plugin.getLogger().log(Level.WARNING, "Failed to queue notification", e); }
        });
    }
}
