package ru.score.hwauction.gui.bid;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.gui.GuiUtil;
import ru.score.hwauction.gui.ItemLoreBuilder;
import ru.score.hwauction.model.AuctionItem;
import ru.score.hwauction.model.AuctionMode;
import ru.score.hwauction.util.MathUtil;
import ru.score.hwauction.util.MoneyUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 54-slot bid view. Layout:
 *
 * Row 0 (0-8):   дек дек дек дек [ITEM=4] дек дек дек дек
 * Row 1 (9-17):  дек дек дек дек  дек     дек дек дек дек
 * Row 2 (18-26): [1] [2] дек [10][11][12] дек [20][21]
 * Row 3 (27-35): дек [3] дек  [9] дек [13] дек [19] дек
 * Row 4 (36-44): дек [4] дек  [8] дек [14] дек [18] дек
 * Row 5 (45-53): [◄] [5][6][7] дек [15][16][17] [📖]
 *
 * Snake order (step → slot):
 *  1=18, 2=19, 3=28, 4=37, 5=46, 6=47, 7=48,
 *  8=39, 9=30, 10=21, 11=22, 12=23, 13=32, 14=41,
 *  15=50, 16=51, 17=52, 18=43, 19=34, 20=25, 21=26
 */
public final class BidViewGui {

    public static final int PANES_PER_PAGE = 21;

    /** Snake-path slot order — index+1 = step number */
    public static final int[] PANE_SLOTS = {
        18, 19, 28, 37, 46, 47, 48,
        39, 30, 21, 22, 23, 32, 41,
        50, 51, 52, 43, 34, 25, 26
    };

    private static final int SLOT_ITEM = 4;
    private static final int SLOT_BACK = 45;
    private static final int SLOT_INFO = 53;
    private static final Material DECO  = Material.LIGHT_BLUE_STAINED_GLASS_PANE;
    private static final Material GRAY  = Material.GRAY_STAINED_GLASS_PANE;
    private static final Material LIME  = Material.LIME_STAINED_GLASS_PANE;

    private final ScoreHWAuction plugin;
    private final ItemLoreBuilder loreBuilder;
    private final ConcurrentHashMap<UUID, ViewState> viewing = new ConcurrentHashMap<>();
    private record ViewState(int itemId, int page) {}

    public BidViewGui(ScoreHWAuction plugin) {
        this.plugin = plugin;
        this.loreBuilder = new ItemLoreBuilder(plugin);
    }

    public void open(Player player, AuctionItem aItem) {
        if (!plugin.getPluginConfig().isEnableBidAuction()) {
            plugin.getMainGui().open(player); return;
        }
        int filled = filledAbsoluteSteps(aItem);
        int page   = filled / PANES_PER_PAGE;
        viewing.put(player.getUniqueId(), new ViewState(aItem.getId(), page));
        renderPage(player, aItem, page);
    }

    public void openPage(Player player, AuctionItem aItem, int page) {
        viewing.put(player.getUniqueId(), new ViewState(aItem.getId(), page));
        renderPage(player, aItem, page);
    }

    private void renderPage(Player player, AuctionItem aItem, int page) {
        Inventory inv = Bukkit.createInventory(null, 54,
                plugin.getMessages().raw("gui.bid-view-title"));

        // Fill all 54 slots with deco
        ItemStack decoItem = GuiUtil.build(DECO, " ");
        for (int i = 0; i < 54; i++) inv.setItem(i, decoItem);

        int filled   = filledAbsoluteSteps(aItem);
        boolean isSeller = aItem.isSeller(player.getUniqueId());

        // Place 21 bid panes in snake order
        for (int i = 0; i < PANES_PER_PAGE; i++) {
            int slot         = PANE_SLOTS[i];
            int absoluteStep = page * PANES_PER_PAGE + i + 1;
            double amount    = bidAmountForAbsoluteStep(aItem, absoluteStep);

            if (absoluteStep <= filled) {
                // LIME — this step is bid
                List<String> lore = new ArrayList<>();
                lore.add(plugin.getMessages().raw("gui.bid-pane-filled-bidder",
                        "{name}", aItem.hasBidder() ? aItem.getTopBidderName() : "?"));
                lore.add(plugin.getMessages().raw("gui.bid-pane-filled-amount",
                        "{price}", MoneyUtil.format(amount)));
                inv.setItem(slot, GuiUtil.build(LIME,
                        plugin.getMessages().raw("gui.bid-pane-filled-name"), lore));
            } else {
                // Always GRAY for available panes (seller can see but clicking is ignored in handler)
                List<String> lore = List.of(plugin.getMessages().raw("gui.bid-btn-click",
                        "{price}", MoneyUtil.format(amount)));
                inv.setItem(slot, GuiUtil.build(GRAY,
                        plugin.getMessages().raw("gui.bid-btn-name"), lore));
            }
        }

        // Item display — slot 4 (top centre)
        inv.setItem(SLOT_ITEM, buildDisplayItem(aItem));

        // Back — slot 45 (bottom-left)
        inv.setItem(SLOT_BACK, GuiUtil.build(Material.ARROW,
                plugin.getMessages().raw("gui.nav.back.name")));

        // Info book — slot 53 (bottom-right)
        inv.setItem(SLOT_INFO, buildInfoItem(aItem));

        player.openInventory(inv);
    }

    public int absoluteStepForSlot(Player player, int slot) {
        ViewState vs = viewing.get(player.getUniqueId());
        if (vs == null) return -1;
        for (int i = 0; i < PANE_SLOTS.length; i++) {
            if (PANE_SLOTS[i] == slot) return vs.page() * PANES_PER_PAGE + i + 1;
        }
        return -1;
    }

    public double bidAmountForAbsoluteStep(AuctionItem item, int absoluteStep) {
        if (absoluteStep <= 1) return item.getStartPrice();
        return MathUtil.round(item.getStartPrice() + (double)(absoluteStep - 1) * item.getStepPrice(), 2);
    }

    public int filledAbsoluteSteps(AuctionItem item) {
        if (!item.hasBidder() || item.getStepPrice() <= 0) return 0;
        return (int) Math.round((item.getCurrentBid() - item.getStartPrice()) / item.getStepPrice()) + 1;
    }

    private ItemStack buildDisplayItem(AuctionItem aItem) {
        ItemStack display = aItem.getItemStack().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());
        if (meta == null) return display;
        List<String> lore = new ArrayList<>();
        if (meta.hasLore()) lore.addAll(meta.getLore());
        lore.addAll(loreBuilder.buildBidViewLore(aItem));
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private ItemStack buildInfoItem(AuctionItem aItem) {
        return GuiUtil.build(Material.BOOK,
                plugin.getMessages().raw("gui.bid-info-header"),
                "",
                plugin.getMessages().raw("gui.bid-how-to-buy"),
                "",
                plugin.getMessages().raw("gui.bid-how-to-buy-desc"),
                plugin.getMessages().raw("gui.bid-how-to-buy-desc2"),
                plugin.getMessages().raw("gui.bid-how-to-buy-desc3"),
                "",
                plugin.getMessages().raw("gui.bid-when-get"),
                "",
                plugin.getMessages().raw("gui.bid-when-get-desc"),
                plugin.getMessages().raw("gui.bid-when-get-desc2"),
                plugin.getMessages().raw("gui.bid-when-get-desc3"));
    }

    public int  getViewingId(Player p)    { ViewState v = viewing.get(p.getUniqueId()); return v != null ? v.itemId() : -1; }
    public int  getViewingPage(Player p)   { ViewState v = viewing.get(p.getUniqueId()); return v != null ? v.page()   : 0; }
    public void clearViewing(Player p)    { viewing.remove(p.getUniqueId()); }
}
