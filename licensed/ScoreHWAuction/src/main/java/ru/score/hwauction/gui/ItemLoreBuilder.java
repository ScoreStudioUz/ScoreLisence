package ru.score.hwauction.gui;

import org.bukkit.entity.Player;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.config.Messages;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.AuctionMode;
import ru.score.hwauction.model.Category;
import ru.score.hwauction.util.MoneyUtil;
import ru.score.hwauction.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

public final class ItemLoreBuilder {

    private final ScoreHWAuction plugin;

    public ItemLoreBuilder(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    /** Lore for main auction browser listing items */
    public List<String> buildMainLore(AuctionItem aItem, Player viewer) {
        Messages msg = plugin.getMessages();
        List<String> lore = new ArrayList<>();
        lore.add("");

        String catName = plugin.getCategoryRegistry().get(aItem.getCategory())
                .map(Category::getDisplayName).orElse(msg.raw("gui.category-fallback"));

        // Info block (│ prefix)
        lore.add(msg.raw("gui.item-category", "{category}", catName));
        lore.add(msg.raw("gui.item-seller",   "{seller}",   aItem.getSellerName()));
        lore.add(msg.raw("gui.item-expires",  "{time}",     TimeUtil.format(aItem.getRemainingMillis())));

        if (aItem.getMode() == AuctionMode.MARKET) {
            lore.add(msg.raw("gui.item-price", "{price}", MoneyUtil.format(aItem.getPrice())));
            if (aItem.getAmount() > 1 && plugin.getPluginConfig().isAllowPerUnit()) {
                lore.add(msg.raw("gui.item-price-per", "{price}", MoneyUtil.format(aItem.getPricePerUnit())));
            }
        } else {
            lore.add(msg.raw("gui.item-current-bid", "{price}", MoneyUtil.format(aItem.getCurrentBid())));
            lore.add(msg.raw("gui.item-start-bid",   "{price}", MoneyUtil.format(aItem.getStartPrice())));
            lore.add(msg.raw("gui.item-step-bid",    "{price}", MoneyUtil.format(aItem.getStepPrice())));
            if (aItem.hasBidder()) {
                lore.add(msg.raw("gui.item-top-bidder", "{name}", aItem.getTopBidderName()));
            }
        }

        lore.add("");

        // Action block
        boolean isSelf = aItem.isSeller(viewer.getUniqueId());
        if (isSelf && !plugin.getPluginConfig().isAllowSelfBuy()) {
            lore.add(msg.raw("gui.item-own-listing"));
        } else if (aItem.getMode() == AuctionMode.MARKET) {
            if (aItem.isFullOnly() || !plugin.getPluginConfig().isAllowPerUnit()) {
                lore.add(msg.raw("gui.item-full-only"));
                lore.add(msg.raw("gui.item-full-only2"));
            } else {
                lore.add(msg.raw("gui.item-buy-lmb"));
                lore.add(msg.raw("gui.item-buy-rmb"));
            }
        } else {
            double next = aItem.getNextBidAmount();
            lore.add(msg.raw("gui.bid-available"));
            lore.add(msg.raw("gui.bid-click", "{price}", MoneyUtil.format(next)));
        }
        return lore;
    }

    /** Lore for "my items" GUI */
    public List<String> buildMyItemLore(AuctionItem aItem) {
        Messages msg = plugin.getMessages();
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(msg.raw("gui.my-item-price",   "{price}", MoneyUtil.format(aItem.getPrice())));
        lore.add(msg.raw("gui.my-item-expires",  "{time}",  TimeUtil.format(aItem.getRemainingMillis())));
        if (aItem.getMode() == AuctionMode.AUCTION && aItem.hasBidder()) {
            lore.add(msg.raw("gui.item-top-bidder",  "{name}",  aItem.getTopBidderName()));
            lore.add(msg.raw("gui.item-current-bid", "{price}", MoneyUtil.format(aItem.getCurrentBid())));
        }
        lore.add("");
        lore.add(msg.raw("gui.my-item-remove"));
        return lore;
    }

    /** Lore for expired items — includes auto-delete countdown */
    public List<String> buildExpiredItemLore(AuctionItem aItem, long cleanupDelayMs) {
        Messages msg = plugin.getMessages();
        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(msg.raw("gui.my-item-price", "{price}", MoneyUtil.format(aItem.getPrice())));

        if (plugin.getPluginConfig().isEnableExpiredCleaner() && cleanupDelayMs > 0) {
            long deleteAt = aItem.getExpiresAt() + cleanupDelayMs;
            long timeLeft = Math.max(0, deleteAt - System.currentTimeMillis());
            lore.add(msg.raw("gui.expired-item-deletes-in", "{time}", TimeUtil.format(timeLeft)));
        }

        lore.add("");
        lore.add(msg.raw("gui.expired-item-remove"));
        return lore;
    }

    public List<String> buildExpiredItemLore(AuctionItem aItem) {
        return buildExpiredItemLore(aItem, 0);
    }

    /** Lore for bid view screen */
    public List<String> buildBidViewLore(AuctionItem aItem) {
        Messages msg = plugin.getMessages();
        List<String> lore = new ArrayList<>();
        lore.add("");

        String catName = plugin.getCategoryRegistry().get(aItem.getCategory())
                .map(Category::getDisplayName).orElse(msg.raw("gui.category-fallback"));

        lore.add(msg.raw("gui.item-category",    "{category}", catName));
        lore.add(msg.raw("gui.item-seller",      "{seller}",   aItem.getSellerName()));
        lore.add(msg.raw("gui.item-expires",     "{time}",     TimeUtil.format(aItem.getRemainingMillis())));
        lore.add(msg.raw("gui.item-current-bid", "{price}",    MoneyUtil.format(aItem.getCurrentBid())));
        lore.add(msg.raw("gui.item-start-bid",   "{price}",    MoneyUtil.format(aItem.getStartPrice())));
        lore.add(msg.raw("gui.item-step-bid",    "{price}",    MoneyUtil.format(aItem.getStepPrice())));
        if (aItem.hasBidder()) {
            lore.add(msg.raw("gui.item-top-bidder", "{name}", aItem.getTopBidderName()));
        }
        if (plugin.getPluginConfig().isBidTimeExtensionEnabled()) {
            lore.add(msg.raw("gui.bid-time-ext",
                    "{sec}", String.valueOf(plugin.getPluginConfig().getBidTimeExtensionSeconds())));
        }
        lore.add("");
        return lore;
    }
}
