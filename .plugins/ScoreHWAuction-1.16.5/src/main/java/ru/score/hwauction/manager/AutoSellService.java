package ru.score.hwauction.manager;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.AuctionMode;
import ru.score.hwauction.model.ListResult;
import ru.score.hwauction.util.ItemUtil;
import ru.score.hwauction.util.MathUtil;
import ru.score.hwauction.util.MoneyUtil;

import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.TimeUnit;

public final class AutoSellService {

    private final ScoreHWAuction plugin;

    public AutoSellService(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public boolean startPreview(Player player) {
        if (!plugin.getPluginConfig().isEnableAutoSell()) {
            player.sendMessage(plugin.getMessages().get("feature-disabled"));
            return false;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            player.sendMessage(plugin.getMessages().get("item-in-hand"));
            return false;
        }
        if (plugin.getBlacklistRegistry().isBlacklisted(hand)
                && !player.hasPermission("scoreah.bypass.blacklist")) {
            player.sendMessage(plugin.getMessages().get("item-blacklisted"));
            return false;
        }

        int amount = hand.getAmount();

        List<AuctionItem> active = plugin.getAuctionCache().active();
        OptionalDouble cheapestPerUnit = active.stream()
                .filter(a -> a.getMode() == AuctionMode.MARKET)
                .filter(a -> matchesSameItem(a, hand))
                .mapToDouble(AuctionItem::getPricePerUnit)
                .min();

        double suggestedPerUnit;
        String sourceMsg;

        if (cheapestPerUnit.isPresent()) {
            // Set price equal to cheapest on market (not above, not below)
            suggestedPerUnit = MathUtil.round(cheapestPerUnit.getAsDouble(), 2);
            suggestedPerUnit = Math.max(suggestedPerUnit, plugin.getPluginConfig().getMinPrice());
            suggestedPerUnit = Math.min(suggestedPerUnit, plugin.getPluginConfig().getMaxPrice());

            sourceMsg = plugin.getMessages().get("auto-sell-market-found",
                    "{cheapest}",  MoneyUtil.format(cheapestPerUnit.getAsDouble()),
                    "{suggested}", MoneyUtil.format(suggestedPerUnit),
                    "{amount}",    String.valueOf(amount),
                    "{total}",     MoneyUtil.format(MathUtil.round(suggestedPerUnit * amount, 2)));
        } else {
            // No market listings — suggest a sensible default (100 per unit)
            suggestedPerUnit = Math.max(100.0, plugin.getPluginConfig().getMinPrice());
            suggestedPerUnit = Math.min(suggestedPerUnit, plugin.getPluginConfig().getMaxPrice());

            sourceMsg = plugin.getMessages().get("auto-sell-no-market",
                    "{suggested}", MoneyUtil.format(suggestedPerUnit),
                    "{amount}",    String.valueOf(amount),
                    "{total}",     MoneyUtil.format(MathUtil.round(suggestedPerUnit * amount, 2)));
        }

        PendingConfirmManager.AutoSellPreview preview =
                new PendingConfirmManager.AutoSellPreview(
                        suggestedPerUnit, amount, ItemUtil.getDisplayName(hand),
                        System.currentTimeMillis());
        plugin.getPendingConfirmManager().set(player.getUniqueId(), preview);

        player.sendMessage(sourceMsg);
        player.sendMessage(plugin.getMessages().get("auto-sell-confirm-prompt",
                "{cmd}", "/ah sell auto confirm"));
        return true;
    }

    public ListResult confirm(Player player) {
        if (!plugin.getPluginConfig().isEnableAutoSell()) {
            player.sendMessage(plugin.getMessages().get("feature-disabled"));
            return ListResult.ERROR;
        }
        PendingConfirmManager.AutoSellPreview preview =
                plugin.getPendingConfirmManager().get(player.getUniqueId());
        if (preview == null) {
            player.sendMessage(plugin.getMessages().get("auto-sell-no-pending"));
            return ListResult.ERROR;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType().isAir()) {
            plugin.getPendingConfirmManager().clear(player.getUniqueId());
            player.sendMessage(plugin.getMessages().get("item-in-hand"));
            return ListResult.NO_ITEM_IN_HAND;
        }

        double totalPrice = MathUtil.round(preview.suggestedPrice() * preview.amount(), 2);
        long durationMs = TimeUnit.HOURS.toMillis(plugin.getPluginConfig().getDefaultDurationHours());

        plugin.getPendingConfirmManager().clear(player.getUniqueId());
        return plugin.getListingService().list(
                player, hand, totalPrice, false, AuctionMode.MARKET, durationMs, 0);
    }

    /** The total price that was suggested in the last preview for this player */
    public double getPreviewTotalPrice(Player player) {
        PendingConfirmManager.AutoSellPreview p = plugin.getPendingConfirmManager().get(player.getUniqueId());
        return p != null ? p.totalPrice() : 0;
    }

    private boolean matchesSameItem(AuctionItem listed, ItemStack hand) {
        if (listed.getItemStack() == null) return false;
        if (listed.getItemStack().getType() != hand.getType()) return false;
        return ItemUtil.getDisplayName(listed.getItemStack())
                .equalsIgnoreCase(ItemUtil.getDisplayName(hand));
    }
}
