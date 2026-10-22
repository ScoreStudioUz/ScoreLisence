package ru.score.hwauction.gui.main;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.config.Messages;
import ru.score.hwauction.gui.GuiUtil;
import ru.score.hwauction.gui.ItemLoreBuilder;
import ru.score.hwauction.gui.NavSlot;
import ru.score.hwauction.model.*;
import ru.score.hwauction.util.ColorUtil;

import java.util.ArrayList;
import java.util.List;

public final class MainAuctionGui {

    private final ScoreHWAuction plugin;
    private final ItemLoreBuilder loreBuilder;

    public MainAuctionGui(ScoreHWAuction plugin) {
        this.plugin = plugin;
        this.loreBuilder = new ItemLoreBuilder(plugin);
    }

    public void open(Player player) {
        PlayerSession session = plugin.getSessionManager().getOrCreate(player);
        List<AuctionItem> filtered = plugin.getFilterService()
                .filter(plugin.getAuctionCache().active(), session);

        int perPage    = plugin.getPluginConfig().getItemsPerPage();
        int totalPages = Math.max(1, (int) Math.ceil((double) filtered.size() / perPage));

        if (session.getCurrentPage() >= totalPages) session.setCurrentPage(totalPages - 1);
        int page = session.getCurrentPage();

        String baseTitle = plugin.getMessages().raw("gui.main-title");
        String title     = GuiUtil.pageTitle(baseTitle, page, totalPages);

        Inventory inv = Bukkit.createInventory(null, 54, title);

        int start = page * perPage;
        for (int slot = 0; slot < perPage; slot++) {
            int idx = start + slot;
            if (idx < filtered.size()) {
                inv.setItem(slot, buildListingItem(filtered.get(idx), player));
            }
        }

        buildNavRow(inv, session, page, totalPages, player);
        player.openInventory(inv);
    }

    private ItemStack buildListingItem(AuctionItem aItem, Player viewer) {
        ItemStack display = aItem.getItemStack().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());
        if (meta == null) return display;

        List<String> lore = new ArrayList<>();
        if (meta.hasLore()) lore.addAll(meta.getLore());
        lore.addAll(loreBuilder.buildMainLore(aItem, viewer));
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private void buildNavRow(Inventory inv, PlayerSession session,
                              int page, int totalPages, Player player) {
        Messages msg = plugin.getMessages();

        // 45 — My Items
        inv.setItem(NavSlot.MY_ITEMS, buildMyItemsNavItem(player));

        // 46 — Expired
        inv.setItem(NavSlot.EXPIRED, buildExpiredNavItem());

        // 47 — Refresh
        inv.setItem(NavSlot.REFRESH, GuiUtil.build(Material.EMERALD,
                msg.raw("gui.nav.refresh.name")));

        // 48 — Prev page
        inv.setItem(NavSlot.PREV_PAGE, page > 0
                ? GuiUtil.build(Material.LIME_DYE, msg.raw("gui.nav.prev-page.name"))
                : GuiUtil.build(Material.GRAY_DYE, msg.raw("gui.nav.prev-page.name-disabled")));

        // 49 — Mode toggle (Аукцион label)
        inv.setItem(NavSlot.MODE, buildModeItem(session));

        // 50 — Next page
        inv.setItem(NavSlot.NEXT_PAGE, page < totalPages - 1
                ? GuiUtil.build(Material.LIME_DYE, msg.raw("gui.nav.next-page.name"))
                : GuiUtil.build(Material.GRAY_DYE, msg.raw("gui.nav.next-page.name-disabled")));

        // 51 — Help
        inv.setItem(NavSlot.HELP, buildHelpItem());

        // 52 — Sort
        inv.setItem(NavSlot.SORT, buildSortItem(session));

        // 53 — Category
        inv.setItem(NavSlot.CATEGORY, buildCategoryItem(session));
    }

    private ItemStack buildCategoryItem(PlayerSession session) {
        Messages msg = plugin.getMessages();
        List<String> lore = new ArrayList<>();
        lore.add("");
        String selPrefix   = msg.raw("gui.list-selected-prefix");
        String unselPrefix = msg.raw("gui.list-unselected-prefix");
        for (var entry : plugin.getCategoryRegistry().all().entrySet()) {
            boolean selected = entry.getKey().equals(session.getCategoryFilter());
            lore.add(ColorUtil.translate((selected ? selPrefix : unselPrefix) + entry.getValue().getDisplayName()));
        }
        lore.add("");
        lore.add(msg.raw("gui.category-click-hint"));
        return GuiUtil.build(Material.CHEST_MINECART, msg.raw("gui.nav.category.name"), lore);
    }

    private ItemStack buildSortItem(PlayerSession session) {
        Messages msg = plugin.getMessages();
        List<String> lore = new ArrayList<>();
        lore.add("");
        SortType cur = session.getSortType();
        SortType[] all = SortType.values();
        String[] labels = {
            msg.raw("gui.sort-newest"),        msg.raw("gui.sort-oldest"),
            msg.raw("gui.sort-cheapest"),      msg.raw("gui.sort-expensive"),
            msg.raw("gui.sort-cheapest-per"),  msg.raw("gui.sort-expensive-per")
        };
        String selPrefix   = msg.raw("gui.list-selected-prefix");
        String unselPrefix = msg.raw("gui.list-unselected-prefix");
        for (int i = 0; i < all.length; i++) {
            lore.add(ColorUtil.translate((all[i] == cur ? selPrefix : unselPrefix) + labels[i]));
        }
        lore.add("");
        lore.add(msg.raw("gui.sort-click-hint"));
        return GuiUtil.build(Material.HOPPER, msg.raw("gui.nav.sort.name"), lore);
    }

    private ItemStack buildHelpItem() {
        Messages msg = plugin.getMessages();
        List<String> lore = new ArrayList<>();
        lore.add("");
        for (String line : plugin.getMessages().list("gui.help-gui-lines")) lore.add(line);
        return GuiUtil.build(Material.PAPER, msg.raw("gui.help-gui-name"), lore);
    }

    private ItemStack buildModeItem(PlayerSession session) {
        Messages msg = plugin.getMessages();
        boolean isMarket = session.getViewMode() == AuctionMode.MARKET;
        String click = isMarket
                ? msg.raw("gui.mode-click-to-bid")
                : msg.raw("gui.mode-click-to-market");
        return GuiUtil.build(Material.NETHER_STAR,
                msg.raw("gui.nav.mode.name"),
                msg.raw("gui.mode-desc"),
                msg.raw("gui.mode-desc2"),
                msg.raw("gui.mode-desc3"),
                "", click);
    }

    private ItemStack buildMyItemsNavItem(Player player) {
        Messages msg = plugin.getMessages();
        long myCount = plugin.getAuctionCache().active().stream()
                .filter(i -> i.isSeller(player.getUniqueId()))
                .count();
        List<String> lore = new ArrayList<>();
        lore.add(msg.raw("gui.my-items-nav-desc"));
        lore.add(msg.raw("gui.my-items-nav-desc2"));
        lore.add("");
        lore.add(msg.raw("gui.my-items-nav-count", "{count}", String.valueOf(myCount)));
        lore.add("");
        lore.add(msg.raw("gui.my-items-nav-click"));
        return GuiUtil.build(Material.ENDER_CHEST, msg.raw("gui.nav.my-items.name"), lore);
    }

    private ItemStack buildExpiredNavItem() {
        Messages msg = plugin.getMessages();
        List<String> lore = new ArrayList<>();
        lore.add(msg.raw("gui.expired-nav-desc"));
        lore.add(msg.raw("gui.expired-nav-desc2"));
        lore.add(msg.raw("gui.expired-nav-desc3"));
        lore.add("");
        lore.add(msg.raw("gui.expired-nav-click"));
        return GuiUtil.build(Material.CHEST, msg.raw("gui.nav.expired.name"), lore);
    }

    public int resolveItemId(Player player, int slot) {
        if (slot < 0 || slot >= plugin.getPluginConfig().getItemsPerPage()) return -1;
        PlayerSession session = plugin.getSessionManager().getOrCreate(player);
        List<AuctionItem> filtered = plugin.getFilterService()
                .filter(plugin.getAuctionCache().active(), session);
        int idx = session.getCurrentPage() * plugin.getPluginConfig().getItemsPerPage() + slot;
        return (idx >= 0 && idx < filtered.size()) ? filtered.get(idx).getId() : -1;
    }
}
