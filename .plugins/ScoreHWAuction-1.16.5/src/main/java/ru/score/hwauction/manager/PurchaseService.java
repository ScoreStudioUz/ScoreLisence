package ru.score.hwauction.manager;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.api.event.AuctionBuyEvent;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.AuctionMode;
import ru.score.hwauction.model.AuctionStatus;
import ru.score.hwauction.model.BuyResult;
import ru.score.hwauction.util.DupeGuard;
import ru.score.hwauction.util.ItemUtil;
import ru.score.hwauction.util.MathUtil;
import ru.score.hwauction.util.MoneyUtil;

import java.util.logging.Level;

public final class PurchaseService {

    private final ScoreHWAuction plugin;

    public PurchaseService(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    /**
     * Buy items from a MARKET listing.
     * Protected against duplication via DupeGuard.
     */
    public BuyResult buy(Player buyer, int itemId, int amount) {
        // ── 1. Validate from cache (fast pre-check before locking) ──
        AuctionItem item = plugin.getAuctionCache().get(itemId).orElse(null);
        if (item == null)                              return BuyResult.NOT_FOUND;
        if (item.getStatus() != AuctionStatus.ACTIVE)  return BuyResult.ALREADY_SOLD;
        if (item.isExpired()) { plugin.getExpiryService().expire(item); return BuyResult.ALREADY_SOLD; }
        if (item.getMode() != AuctionMode.MARKET)      return BuyResult.ERROR;
        if (!plugin.getPluginConfig().isAllowSelfBuy()
                && item.isSeller(buyer.getUniqueId()))  return BuyResult.SELF_BUY;

        // ── 2. Acquire exclusive lock (dupe protection) ──
        if (!DupeGuard.lock(itemId)) return BuyResult.ALREADY_SOLD;

        try {
            // ── 3. Re-validate inside lock (another thread may have sold it) ──
            item = plugin.getAuctionCache().get(itemId).orElse(null);
            if (item == null || item.getStatus() != AuctionStatus.ACTIVE || item.isExpired()) {
                return BuyResult.ALREADY_SOLD;
            }

            int buyAmount = item.isFullOnly()
                    ? item.getAmount()
                    : MathUtil.clamp(amount, 1, item.getAmount());

            double totalPrice = (buyAmount == item.getAmount())
                    ? item.getPrice()
                    : MathUtil.round(item.getPricePerUnit() * buyAmount, 2);

            // Inventory space check
            ItemStack toGive = item.getItemStack().clone();
            toGive.setAmount(buyAmount);
            if (!ItemUtil.hasSpace(buyer, toGive)) return BuyResult.INVENTORY_FULL;

            // Funds check
            if (!plugin.getEconomyService().has(buyer, totalPrice)) return BuyResult.NOT_ENOUGH_MONEY;

            // ── 4. Fire API event ──
            AuctionBuyEvent event = new AuctionBuyEvent(buyer, item, buyAmount, totalPrice);
            plugin.getServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) return BuyResult.ERROR;

            final double finalPrice  = event.getTotalPrice();
            final int    finalAmount = event.getAmount();

            // ── 5. Execute transaction ──
            if (!plugin.getEconomyService().withdraw(buyer, finalPrice)) return BuyResult.NOT_ENOUGH_MONEY;

            double taxRate    = buyer.hasPermission("scoreah.bypass.tax") ? 0.0 : plugin.getPluginConfig().getTax();
            double sellerGets = MathUtil.round(finalPrice * (1.0 - taxRate), 2);

            OfflinePlayer seller = Bukkit.getOfflinePlayer(item.getSellerUuid());
            plugin.getEconomyService().deposit(seller, sellerGets);

            buyer.getInventory().addItem(toGive);
            plugin.getSoundService().playPurchase(buyer);

            // ── 6. Notify seller ──
            Player onlineSeller = Bukkit.getPlayer(item.getSellerUuid());
            if (onlineSeller != null) {
                onlineSeller.sendMessage(plugin.getMessages().get("seller-sold",
                        "{item}",  ItemUtil.getDisplayName(item.getItemStack()),
                        "{buyer}", buyer.getName(),
                        "{price}", MoneyUtil.format(sellerGets)));
                plugin.getSoundService().playSold(onlineSeller);
            }

            // ── 7. Broadcast ──
            plugin.getNotificationService().broadcastPurchase(
                    plugin.getMessages().get("broadcast-purchase",
                            "{buyer}", buyer.getName(),
                            "{item}",  ItemUtil.getDisplayName(item.getItemStack()),
                            "{price}", MoneyUtil.format(finalPrice)));

            // ── 8. Update item state ──
            boolean fullySold = (finalAmount >= item.getAmount());
            if (fullySold) {
                item.setStatus(AuctionStatus.SOLD);
                plugin.getAuctionCache().remove(itemId);
            } else {
                int remaining = item.getAmount() - finalAmount;
                ItemStack updatedStack = item.getItemStack().clone();
                updatedStack.setAmount(remaining);
                item.setItemStack(updatedStack);
                item.setAmount(remaining);
                item.setPrice(MathUtil.round(item.getPricePerUnit() * remaining, 2));
            }

            // ── 9. Persist async ──
            final AuctionItem finalItem = item;
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try { plugin.getDb().updateItem(finalItem); }
                catch (Exception e) { plugin.getLogger().log(Level.WARNING, "DB error after purchase", e); }
            });

            return BuyResult.SUCCESS;

        } finally {
            DupeGuard.unlock(itemId);
        }
    }
}
