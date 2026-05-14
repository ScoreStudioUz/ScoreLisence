package ru.score.hwauction.manager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.api.event.AuctionBidEvent;
import ru.score.hwauction.gui.bid.BidViewGui;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.AuctionMode;
import ru.score.hwauction.model.AuctionStatus;
import ru.score.hwauction.model.BidResult;
import ru.score.hwauction.util.DupeGuard;
import ru.score.hwauction.util.ItemUtil;
import ru.score.hwauction.util.MathUtil;
import ru.score.hwauction.util.MoneyUtil;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class BidService {

    private final ScoreHWAuction plugin;

    public BidService(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public BidResult placeBidAtStep(Player bidder, int itemId, int absoluteStep) {
        if (!plugin.getPluginConfig().isEnableBidAuction()) return BidResult.WRONG_MODE;

        AuctionItem item = plugin.getAuctionCache().get(itemId).orElse(null);
        if (item == null)                              return BidResult.NOT_FOUND;
        if (item.getStatus() != AuctionStatus.ACTIVE) return BidResult.ALREADY_SOLD;
        if (item.isExpired()) { plugin.getExpiryService().expire(item); return BidResult.EXPIRED; }
        if (item.getMode() != AuctionMode.AUCTION)    return BidResult.WRONG_MODE;
        if (item.isSeller(bidder.getUniqueId()))      return BidResult.SELF_BID;
        if (item.hasBidder() && item.getTopBidderUuid().equals(bidder.getUniqueId()))
            return BidResult.ALREADY_TOP_BIDDER;

        BidViewGui bidGui = plugin.getBidViewGui();
        int currentFilled = bidGui.filledAbsoluteSteps(item);
        if (absoluteStep <= currentFilled) return BidResult.ALREADY_TOP_BIDDER;

        double bidAmount = bidGui.bidAmountForAbsoluteStep(item, absoluteStep);
        if (!plugin.getEconomyService().has(bidder, bidAmount)) return BidResult.NOT_ENOUGH_MONEY;

        if (!DupeGuard.lock(itemId)) return BidResult.ALREADY_SOLD;
        try {
            // Re-fetch inside lock
            item = plugin.getAuctionCache().get(itemId).orElse(null);
            if (item == null || item.getStatus() != AuctionStatus.ACTIVE || item.isExpired())
                return (item == null || item.getStatus() != AuctionStatus.ACTIVE)
                        ? BidResult.ALREADY_SOLD : BidResult.EXPIRED;
            if (item.hasBidder() && item.getTopBidderUuid().equals(bidder.getUniqueId()))
                return BidResult.ALREADY_TOP_BIDDER;

            int filledStep = bidGui.filledAbsoluteSteps(item);
            if (absoluteStep <= filledStep) return BidResult.ALREADY_TOP_BIDDER;
            bidAmount = bidGui.bidAmountForAbsoluteStep(item, absoluteStep);
            if (!plugin.getEconomyService().has(bidder, bidAmount)) return BidResult.NOT_ENOUGH_MONEY;

            AuctionBidEvent event = new AuctionBidEvent(bidder, item, bidAmount);
            plugin.getServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) return BidResult.ERROR;
            bidAmount = event.getBidAmount();

            // ── Refund previous bidder (online or offline via DB queue) ──
            if (item.hasBidder()) {
                refundPreviousBidder(item, bidder, bidAmount);
            }

            // ── Charge new bidder ──
            if (!plugin.getEconomyService().withdraw(bidder, bidAmount))
                return BidResult.NOT_ENOUGH_MONEY;

            // ── Update item in memory ──
            final UUID prevBidderUuid = item.hasBidder() ? item.getTopBidderUuid() : null;
            item.setCurrentBid(bidAmount);
            item.setTopBidderUuid(bidder.getUniqueId());
            item.setTopBidderName(bidder.getName());

            // ── Bid time extension ──
            if (plugin.getPluginConfig().isBidTimeExtensionEnabled()) {
                long ext = TimeUnit.SECONDS.toMillis(plugin.getPluginConfig().getBidTimeExtensionSeconds());
                item.setExpiresAt(item.getExpiresAt() + ext);
            }

            plugin.getSoundService().playBid(bidder);

            final AuctionItem finalItem = item;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try { plugin.getDb().updateItem(finalItem); }
                catch (Exception e) { plugin.getLogger().log(Level.WARNING, "DB error on bid", e); }
            });

            return BidResult.SUCCESS;

        } finally {
            DupeGuard.unlock(itemId);
        }
    }

    /**
     * Refund the previous top bidder.
     * If they are online → deposit immediately + send message.
     * If they are offline → queue refund + notification to DB.
     *   These will be delivered on their next login via AuctionListener.onPlayerJoin.
     */
    private void refundPreviousBidder(AuctionItem item, Player newBidder, double newBidAmount) {
        double refund = item.getCurrentBid();
        String itemName = ItemUtil.getDisplayName(item.getItemStack());

        Player onlinePrev = Bukkit.getPlayer(item.getTopBidderUuid());
        if (onlinePrev != null && onlinePrev.isOnline()) {
            // Online — refund and notify immediately
            plugin.getEconomyService().deposit(onlinePrev, refund);
            onlinePrev.sendMessage(plugin.getMessages().get("bid-outbid",
                    "{item}",  itemName,
                    "{price}", MoneyUtil.format(newBidAmount)));
            onlinePrev.sendMessage(plugin.getMessages().get("bid-return",
                    "{item}",  itemName,
                    "{price}", MoneyUtil.format(refund)));
            plugin.getSoundService().playBidOutbid(onlinePrev);
        } else {
            // Offline — deposit to their account immediately (Vault works on OfflinePlayer)
            // AND queue a notification so they see what happened on next login
            OfflinePlayer prev = Bukkit.getOfflinePlayer(item.getTopBidderUuid());
            plugin.getEconomyService().deposit(prev, refund);

            // Queue notification message for next login
            final UUID prevUuid  = item.getTopBidderUuid();
            final double refundF = refund;
            final double newAmtF = newBidAmount;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    String outbidMsg = plugin.getMessages().get("bid-outbid",
                            "{item}",  itemName,
                            "{price}", MoneyUtil.format(newAmtF));
                    String returnMsg = plugin.getMessages().get("bid-return",
                            "{item}",  itemName,
                            "{price}", MoneyUtil.format(refundF));
                    plugin.getDb().queueNotification(prevUuid, outbidMsg);
                    plugin.getDb().queueNotification(prevUuid, returnMsg);
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to queue outbid notification", e);
                }
            });
        }
    }
}
