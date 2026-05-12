package ru.score.hwauction.command.sub;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.AuctionMode;
import ru.score.hwauction.model.ListResult;
import ru.score.hwauction.util.ItemUtil;
import ru.score.hwauction.util.MoneyUtil;
import ru.score.hwauction.util.TimeUtil;

import java.util.concurrent.TimeUnit;

/**
 * Handles /ah sell commands.
 *
 * Syntax:
 *   /ah sell <price>               — market listing
 *   /ah sell <price> full          — market listing, full stack only
 *   /ah sell <price> <step> [time] — bid auction (step = bid increment, time = hours)
 *   /ah sell auto                  — auto-price market listing
 *   /ah sell auto confirm          — confirm auto-price listing
 */
public final class SellSubCommand {

    private final ScoreHWAuction plugin;

    public SellSubCommand(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public void execute(Player player, String[] args) {
        if (!player.hasPermission("scoreah.sell")) {
            player.sendMessage(plugin.getMessages().get("no-permission")); return;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();

        // ── /ah sell auto [confirm] ──────────────────────
        if (args.length >= 2 && args[1].equalsIgnoreCase("auto")) {
            if (!player.hasPermission("scoreah.sell.auto")) {
                player.sendMessage(plugin.getMessages().get("no-permission")); return;
            }
            if (!plugin.getPluginConfig().isEnableAutoSell()) {
                player.sendMessage(plugin.getMessages().get("feature-disabled")); return;
            }
            if (args.length >= 3 && args[2].equalsIgnoreCase("confirm")) {
                double suggestedTotal = plugin.getAutoSellService().getPreviewTotalPrice(player);
                ListResult result = plugin.getAutoSellService().confirm(player);
                if (result == ListResult.ERROR) return;
                handleResult(player, result, hand, suggestedTotal, AuctionMode.MARKET, 0);
            } else {
                plugin.getAutoSellService().startPreview(player);
            }
            return;
        }

        if (args.length < 2) {
            player.sendMessage(plugin.getMessages().get("unknown-command")); return;
        }

        // ── Parse price ──────────────────────────────────
        double price = MoneyUtil.parse(args[1]);
        if (price < 0) {
            player.sendMessage(plugin.getMessages().get("invalid-price",
                    "{min}", MoneyUtil.format(plugin.getPluginConfig().getMinPrice())));
            return;
        }

        // ── /ah sell <price> full ────────────────────────
        if (args.length >= 3 && args[2].equalsIgnoreCase("full")) {
            ListResult result = plugin.getListingService()
                    .list(player, hand, price, true, AuctionMode.MARKET,
                            defaultDuration(), 0);
            handleResult(player, result, hand, price, AuctionMode.MARKET, defaultDuration());
            return;
        }

        // ── /ah sell <price> <step> [time] ───────────────
        // If arg[2] is a number → bid auction
        if (args.length >= 3 && isNumber(args[2])) {
            if (!player.hasPermission("scoreah.sell.auction")) {
                player.sendMessage(plugin.getMessages().get("no-permission")); return;
            }
            if (!plugin.getPluginConfig().isEnableBidAuction()) {
                player.sendMessage(plugin.getMessages().get("feature-disabled")); return;
            }

            double stepPrice = MoneyUtil.parse(args[2]);
            if (stepPrice < 0) stepPrice = 0;

            long durationMs = defaultDuration();
            if (args.length >= 4 && isNumber(args[3])) {
                try {
                    int h = Integer.parseInt(args[3]);
                    h = Math.max(plugin.getPluginConfig().getAuctionMinHours(),
                            Math.min(plugin.getPluginConfig().getAuctionMaxHours(), h));
                    durationMs = TimeUtil.hoursToMillis(h);
                } catch (NumberFormatException ignored) {}
            }

            ListResult result = plugin.getListingService()
                    .list(player, hand, price, true, AuctionMode.AUCTION, durationMs, stepPrice);
            handleResult(player, result, hand, price, AuctionMode.AUCTION, durationMs);
            return;
        }

        // ── /ah sell <price> (plain market) ─────────────
        ListResult result = plugin.getListingService()
                .list(player, hand, price, false, AuctionMode.MARKET, defaultDuration(), 0);
        handleResult(player, result, hand, price, AuctionMode.MARKET, defaultDuration());
    }

    private long defaultDuration() {
        return TimeUnit.HOURS.toMillis(plugin.getPluginConfig().getDefaultDurationHours());
    }

    private boolean isNumber(String s) {
        if (s == null || s.isBlank()) return false;
        return MoneyUtil.parse(s) >= 0;
    }

    private void handleResult(Player player, ListResult result,
                               ItemStack hand, double price, AuctionMode mode, long durationMs) {
        switch (result) {
            case SUCCESS -> {
                String itemName = hand != null ? ItemUtil.getDisplayName(hand) : "?";
                if (mode == AuctionMode.AUCTION) {
                    player.sendMessage(plugin.getMessages().get("item-listed-auction",
                            "{item}",  itemName,
                            "{price}", MoneyUtil.format(price),
                            "{time}",  TimeUtil.format(durationMs)));
                } else {
                    player.sendMessage(plugin.getMessages().get("item-listed",
                            "{item}",  itemName,
                            "{price}", MoneyUtil.format(price)));
                }
            }
            case NO_ITEM_IN_HAND -> player.sendMessage(plugin.getMessages().get("item-in-hand"));
            case BLACKLISTED     -> player.sendMessage(plugin.getMessages().get("item-blacklisted"));
            case LIMIT_REACHED   -> player.sendMessage(plugin.getMessages().get("sell-limit-reached",
                    "{max}", String.valueOf(plugin.getPluginConfig().getMaxLotsForPlayer(player))));
            case PRICE_TOO_LOW   -> player.sendMessage(plugin.getMessages().get("price-too-low",
                    "{min}", MoneyUtil.format(plugin.getPluginConfig().getMinPrice())));
            case PRICE_TOO_HIGH  -> player.sendMessage(plugin.getMessages().get("price-too-high",
                    "{max}", MoneyUtil.format(plugin.getPluginConfig().getMaxPrice())));
            case NO_PERMISSION   -> player.sendMessage(plugin.getMessages().get("no-permission"));
            default -> {}
        }
    }
}
