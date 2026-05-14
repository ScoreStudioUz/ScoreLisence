package ru.score.hwauction.manager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.api.event.AuctionCancelEvent;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.AuctionMode;
import ru.score.hwauction.model.AuctionStatus;
import ru.score.hwauction.util.ItemUtil;
import ru.score.hwauction.util.MoneyUtil;

import java.util.logging.Level;

public final class AuctionCancelHelper {

    private AuctionCancelHelper() {}

    public static void process(ScoreHWAuction plugin, Player canceller, AuctionItem item) {
        // Fire cancellable API event
        AuctionCancelEvent event = new AuctionCancelEvent(canceller, item);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        item.setStatus(AuctionStatus.CANCELLED);
        plugin.getAuctionCache().remove(item.getId());

        // Return item to seller (on main thread)
        Player seller = Bukkit.getPlayer(item.getSellerUuid());
        if (seller != null && seller.isOnline()) {
            if (ItemUtil.hasSpace(seller, item.getItemStack())) {
                seller.getInventory().addItem(item.getItemStack().clone());
            } else {
                seller.getWorld().dropItem(seller.getLocation(), item.getItemStack().clone());
            }
        }
        // If seller is offline, item is lost (can be improved with offline storage)

        // Refund top bidder if auction mode
        if (item.getMode() == AuctionMode.AUCTION && item.hasBidder()) {
            OfflinePlayer bidder = Bukkit.getOfflinePlayer(item.getTopBidderUuid());
            plugin.getEconomyService().deposit(bidder, item.getCurrentBid());
            // Notify online bidder
            Player onlineBidder = Bukkit.getPlayer(item.getTopBidderUuid());
            if (onlineBidder != null) {
                onlineBidder.sendMessage(plugin.getMessages().get("bid-return",
                        "{item}", ItemUtil.getDisplayName(item.getItemStack()),
                        "{price}", MoneyUtil.format(item.getCurrentBid())));
            }
        }

        // Persist update async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try { plugin.getDb().updateItem(item); }
            catch (Exception e) { plugin.getLogger().log(Level.WARNING, "DB error on cancel", e); }
        });
    }
}
