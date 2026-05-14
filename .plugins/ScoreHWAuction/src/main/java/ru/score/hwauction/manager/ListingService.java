package ru.score.hwauction.manager;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.api.event.AuctionListEvent;
import ru.score.hwauction.model.*;
import ru.score.hwauction.util.ItemUtil;
import ru.score.hwauction.util.MathUtil;

import java.util.logging.Level;

public final class ListingService {

    private final ScoreHWAuction plugin;

    public ListingService(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public ListResult list(Player player, ItemStack item, double price,
                           boolean fullOnly, AuctionMode mode,
                           long durationMs, double stepPrice) {
        if (!plugin.getPluginConfig().isEnableBidAuction() && mode == AuctionMode.AUCTION) {
            return ListResult.NO_PERMISSION;
        }
        if (item == null || item.getType().isAir()) return ListResult.NO_ITEM_IN_HAND;

        if (!player.hasPermission("scoreah.bypass.blacklist")
                && plugin.getBlacklistRegistry().isBlacklisted(item)) {
            return ListResult.BLACKLISTED;
        }

        double min = plugin.getPluginConfig().getMinPrice();
        double max = plugin.getPluginConfig().getMaxPrice();
        if (!MathUtil.inRange(price, min, max)) {
            return price < min ? ListResult.PRICE_TOO_LOW : ListResult.PRICE_TOO_HIGH;
        }

        if (!player.hasPermission("scoreah.bypass.limit")) {
            int maxSlots = plugin.getPluginConfig().getMaxLotsForPlayer(player);
            try {
                int active = plugin.getDb().countActiveByPlayer(player.getUniqueId());
                if (active >= maxSlots) return ListResult.LIMIT_REACHED;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "DB error counting listings", e);
                return ListResult.ERROR;
            }
        }
        if (durationMs <= 0) return ListResult.INVALID_DURATION;

        long expires = System.currentTimeMillis() + durationMs;
        // Use ItemStack-aware category detection (supports skull textures)
        String category = plugin.getCategoryRegistry().detectCategory(item);
        AuctionItem aItem = new AuctionItem(
                player.getUniqueId(), player.getName(),
                item, price, category, mode, fullOnly, expires
        );
        if (mode == AuctionMode.AUCTION) {
            aItem.setStartPrice(price);
            aItem.setCurrentBid(price);
            double step = stepPrice > 0 ? stepPrice
                    : MathUtil.round(price * (plugin.getPluginConfig().getDefaultStepPercent() / 100.0), 2);
            aItem.setStepPrice(step);
        }

        AuctionListEvent event = new AuctionListEvent(player, aItem);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return ListResult.NO_PERMISSION;
        aItem.setPrice(event.getPrice());

        // Remove from player hand before async insert
        ItemUtil.removeFromHand(player, item.getAmount());

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int id = plugin.getDb().insertItem(aItem);
                if (id < 0) {
                    plugin.getLogger().severe("insertItem returned -1 for " + player.getName());
                    // Give item back on main thread
                    plugin.getServer().getScheduler().runTask(plugin, () ->
                            player.getInventory().addItem(item.clone()));
                    return;
                }
                aItem.setId(id);
                plugin.getAuctionCache().put(aItem);
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to insert listing", e);
                plugin.getServer().getScheduler().runTask(plugin, () ->
                        player.getInventory().addItem(item.clone()));
            }
        });

        return ListResult.SUCCESS;
    }

    /** Cancel a listing — fires AuctionCancelEvent internally. */
    public boolean cancel(Player player, int itemId) {
        return plugin.getAuctionCache().get(itemId).map(item -> {
            if (!item.isSeller(player.getUniqueId())
                    && !player.hasPermission("scoreah.admin.removeany")) {
                return false;
            }
            AuctionCancelHelper.process(plugin, player, item);
            return true;
        }).orElse(false);
    }
}
