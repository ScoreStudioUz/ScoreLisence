package ru.score.hwauction.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.gui.GuiTitle;
import ru.score.hwauction.gui.NavSlot;
import ru.score.hwauction.gui.bid.BidViewGui;
import ru.score.hwauction.gui.buyamount.BuyAmountGui;
import ru.score.hwauction.gui.buyamount.BuyConfirmGui;
import ru.score.hwauction.model.*;
import ru.score.hwauction.util.ColorUtil;
import ru.score.hwauction.util.MoneyUtil;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GuiClickHandler {

    private final ScoreHWAuction plugin;
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public GuiClickHandler(ScoreHWAuction plugin) {
        this.plugin = plugin;
    }

    public boolean handles(String rawTitle) {
        String main       = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_MAIN));
        String my         = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_MY));
        String expired    = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_EXPIRED));
        String bid        = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_BID));
        String buyAmt     = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_BUY_AMT));
        String buyConfirm = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_BUY_CONFIRM));
        return rawTitle.startsWith(main)
                || rawTitle.equals(my)
                || rawTitle.equals(expired)
                || rawTitle.equals(bid)
                || rawTitle.equals(buyAmt)
                || rawTitle.equals(buyConfirm);
    }

    public void handle(InventoryClickEvent e, Player player, String rawTitle) {
        int cooldown = plugin.getPluginConfig().getMaxClickCooldownMs();
        if (cooldown > 0) {
            long now  = System.currentTimeMillis();
            long last = lastClick.getOrDefault(player.getUniqueId(), 0L);
            if (now - last < cooldown) { e.setCancelled(true); return; }
            lastClick.put(player.getUniqueId(), now);
        }

        String main       = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_MAIN));
        String my         = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_MY));
        String expired    = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_EXPIRED));
        String bid        = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_BID));
        String buyAmt     = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_BUY_AMT));
        String buyConfirm = ColorUtil.strip(plugin.getMessages().raw(GuiTitle.KEY_BUY_CONFIRM));

        int slot       = e.getSlot();
        ClickType click = e.getClick();

        if      (rawTitle.startsWith(main)) handleMain(player, slot, click);
        else if (rawTitle.equals(my))       handleMyItems(player, slot);
        else if (rawTitle.equals(expired))  handleExpired(player, slot);
        else if (rawTitle.equals(bid))      handleBidView(player, slot);
        else if (rawTitle.equals(buyAmt))   handleBuyAmount(player, slot);
        else if (rawTitle.equals(buyConfirm)) handleBuyConfirm(player, slot);
    }

    // ═══ MAIN ════════════════════════════════════════════

    private void handleMain(Player player, int slot, ClickType click) {
        int perPage = plugin.getPluginConfig().getItemsPerPage();
        PlayerSession session = plugin.getSessionManager().getOrCreate(player);

        if (slot < perPage) {
            int id = plugin.getMainGui().resolveItemId(player, slot);
            if (id == -1) return;
            AuctionItem item = plugin.getAuctionCache().get(id).orElse(null);
            if (item == null) {
                plugin.getNotificationService().send(player, plugin.getMessages().get("item-already-sold"));
                plugin.getMainGui().open(player);
                return;
            }
            if (item.getMode() == AuctionMode.AUCTION) {
                plugin.getBidViewGui().open(player, item);
                return;
            }
            // MARKET item — both LMB and RMB go through confirm unless full-only with single unit
            if (item.isFullOnly() || !plugin.getPluginConfig().isAllowPerUnit()) {
                // Full-stack only → open confirm directly
                openConfirm(player, item, item.getAmount());
            } else if (click.isLeftClick()) {
                // LMB = buy full stack → confirm
                openConfirm(player, item, item.getAmount());
            } else if (click.isRightClick()) {
                // RMB = choose amount
                plugin.getBuyAmountGui().open(player, item);
            }
            return;
        }

        switch (slot) {
            case NavSlot.MY_ITEMS  -> plugin.getMyItemsGui().open(player);
            case NavSlot.EXPIRED   -> plugin.getExpiredGui().open(player);
            case NavSlot.REFRESH   -> plugin.getMainGui().open(player);
            case NavSlot.PREV_PAGE -> {
                if (session.getCurrentPage() > 0) {
                    session.setCurrentPage(session.getCurrentPage() - 1);
                    plugin.getMainGui().open(player);
                }
            }
            case NavSlot.MODE -> {
                session.setViewMode(session.getViewMode() == AuctionMode.MARKET
                        ? AuctionMode.AUCTION : AuctionMode.MARKET);
                plugin.getMainGui().open(player);
            }
            case NavSlot.NEXT_PAGE -> {
                List<AuctionItem> filtered = plugin.getFilterService()
                        .filter(plugin.getAuctionCache().active(), session);
                int total = Math.max(1, (int) Math.ceil(
                        (double) filtered.size() / plugin.getPluginConfig().getItemsPerPage()));
                if (session.getCurrentPage() < total - 1) {
                    session.setCurrentPage(session.getCurrentPage() + 1);
                    plugin.getMainGui().open(player);
                }
            }
            case NavSlot.HELP    -> {}
            case NavSlot.SORT -> {
                SortType[] sorts = SortType.values();
                int idx = session.getSortType().ordinal();
                idx = click.isLeftClick()
                        ? (idx + 1) % sorts.length
                        : (idx - 1 + sorts.length) % sorts.length;
                session.setSortType(sorts[idx]);
                plugin.getMainGui().open(player);
            }
            case NavSlot.CATEGORY -> {
                List<String> keys = List.copyOf(plugin.getCategoryRegistry().all().keySet());
                String cur = session.getCategoryFilter();
                int idx = keys.indexOf(cur);
                if (idx < 0) idx = 0;
                idx = click.isLeftClick()
                        ? (idx + 1) % keys.size()
                        : (idx - 1 + keys.size()) % keys.size();
                session.setCategoryFilter(keys.get(idx));
                plugin.getMainGui().open(player);
            }
        }
    }

    // ═══ MY ITEMS ════════════════════════════════════════

    private void handleMyItems(Player player, int slot) {
        if (slot == 45) { plugin.getMainGui().open(player); return; }
        if (slot == 53) return;
        if (slot >= 45) return;
        plugin.getMyItemsGui().resolveAndCancel(player, slot);
    }

    // ═══ EXPIRED ═════════════════════════════════════════

    private void handleExpired(Player player, int slot) {
        if (slot == 45) { plugin.getMainGui().open(player); return; }
        if (slot == 53) return;
        if (slot >= 45) return;
        plugin.getExpiredGui().resolveAndClaim(player, slot);
    }

    // ═══ BID VIEW ════════════════════════════════════════

    private void handleBidView(Player player, int slot) {
        if (slot == 45) {
            plugin.getBidViewGui().clearViewing(player);
            plugin.getMainGui().open(player);
            return;
        }

        int absoluteStep = plugin.getBidViewGui().absoluteStepForSlot(player, slot);
        if (absoluteStep < 1) return;

        int id = plugin.getBidViewGui().getViewingId(player);
        if (id == -1) { plugin.getMainGui().open(player); return; }

        AuctionItem item = plugin.getAuctionCache().get(id).orElse(null);
        if (item == null) {
            plugin.getNotificationService().send(player, plugin.getMessages().get("item-already-sold"));
            plugin.getMainGui().open(player);
            return;
        }

        if (item.isSeller(player.getUniqueId())) return;

        BidResult result = plugin.getBidService().placeBidAtStep(player, id, absoluteStep);
        switch (result) {
            case SUCCESS -> {
                AuctionItem updated = plugin.getAuctionCache().get(id).orElse(item);
                plugin.getSoundService().playBid(player);
                player.sendMessage(plugin.getMessages().get("bid-placed",
                        "{item}",  updated.getSellerName(),
                        "{price}", MoneyUtil.format(updated.getCurrentBid())));

                int filledNow   = plugin.getBidViewGui().filledAbsoluteSteps(updated);
                int currentPage = plugin.getBidViewGui().getViewingPage(player);
                int lastOnPage  = (currentPage + 1) * BidViewGui.PANES_PER_PAGE;

                if (filledNow >= lastOnPage) {
                    plugin.getBidViewGui().openPage(player, updated, currentPage + 1);
                } else {
                    plugin.getBidViewGui().open(player, updated);
                }
            }
            case NOT_ENOUGH_MONEY -> {
                plugin.getSoundService().playError(player);
                plugin.getNotificationService().sendError(player, plugin.getMessages().get("not-enough-money"));
            }
            case SELF_BID -> {
                plugin.getSoundService().playError(player);
                plugin.getNotificationService().sendError(player, plugin.getMessages().get("cannot-bid-own"));
            }
            case ALREADY_TOP_BIDDER -> {
                plugin.getSoundService().playError(player);
                plugin.getNotificationService().sendError(player, plugin.getMessages().get("already-top-bidder"));
            }
            case ALREADY_SOLD, EXPIRED -> {
                plugin.getNotificationService().send(player, plugin.getMessages().get("item-already-sold"));
                plugin.getMainGui().open(player);
            }
            default -> {}
        }
    }

    // ═══ BUY AMOUNT ══════════════════════════════════════

    private void handleBuyAmount(Player player, int slot) {
        BuyAmountGui.BuySession bs = plugin.getBuyAmountGui().getSession(player);
        if (bs == null) { plugin.getMainGui().open(player); return; }
        AuctionItem item = plugin.getAuctionCache().get(bs.itemId()).orElse(null);
        if (item == null) {
            plugin.getNotificationService().send(player, plugin.getMessages().get("item-already-sold"));
            plugin.getBuyAmountGui().removeSession(player);
            plugin.getMainGui().open(player);
            return;
        }
        switch (slot) {
            case 0 -> plugin.getBuyAmountGui().adjust(player, item, -10);
            case 1 -> plugin.getBuyAmountGui().adjust(player, item,  -1);
            case 2 -> {
                // Click the actual item (slot 2) → open confirmation
                int amount = bs.amount();
                plugin.getBuyAmountGui().removeSession(player);
                openConfirm(player, item, amount);
            }
            case 3 -> plugin.getBuyAmountGui().adjust(player, item,  1);
            case 4 -> plugin.getBuyAmountGui().adjust(player, item, 10);
        }
    }

    // ═══ BUY CONFIRM ═════════════════════════════════════

    private void handleBuyConfirm(Player player, int slot) {
        BuyConfirmGui.ConfirmSession cs = plugin.getBuyConfirmGui().getSession(player);
        if (cs == null) { plugin.getMainGui().open(player); return; }

        AuctionItem item = plugin.getAuctionCache().get(cs.itemId()).orElse(null);

        if (plugin.getBuyConfirmGui().isYes(slot)) {
            plugin.getBuyConfirmGui().removeSession(player);
            if (item == null) {
                plugin.getNotificationService().send(player, plugin.getMessages().get("item-already-sold"));
                plugin.getMainGui().open(player);
                return;
            }
            doBuy(player, item, cs.amount());
            plugin.getMainGui().open(player);
        } else if (plugin.getBuyConfirmGui().isNo(slot)) {
            plugin.getBuyConfirmGui().removeSession(player);
            plugin.getMainGui().open(player);
        }
        // slot 4 = item itself, ignore clicks on it
    }

    // ═══ SHARED ══════════════════════════════════════════

    private void openConfirm(Player player, AuctionItem item, int amount) {
        plugin.getBuyConfirmGui().open(player, item, amount);
    }

    private void doBuy(Player player, AuctionItem item, int amount) {
        BuyResult result = plugin.getPurchaseService().buy(player, item.getId(), amount);
        switch (result) {
            case SUCCESS -> {
                plugin.getSoundService().playPurchase(player);
                player.sendMessage(plugin.getMessages().get("bought-full",
                        "{item}",  item.getSellerName(),
                        "{price}", MoneyUtil.format(item.getPrice())));
            }
            case NOT_ENOUGH_MONEY -> {
                plugin.getSoundService().playError(player);
                plugin.getNotificationService().sendError(player, plugin.getMessages().get("not-enough-money"));
            }
            case SELF_BUY -> {
                plugin.getSoundService().playError(player);
                plugin.getNotificationService().sendError(player, plugin.getMessages().get("cannot-buy-own"));
            }
            case ALREADY_SOLD -> plugin.getNotificationService().send(player, plugin.getMessages().get("item-already-sold"));
            case INVENTORY_FULL -> {
                plugin.getSoundService().playError(player);
                plugin.getNotificationService().sendError(player, plugin.getMessages().get("inventory-full"));
            }
            default -> {}
        }
    }

    public void cleanupPlayer(UUID uuid) { lastClick.remove(uuid); }
}
