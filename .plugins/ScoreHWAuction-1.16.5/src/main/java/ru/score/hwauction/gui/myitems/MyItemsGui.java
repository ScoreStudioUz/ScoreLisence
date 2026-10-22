package ru.score.hwauction.gui.myitems;

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
import ru.score.hwauction.model.AuctionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class MyItemsGui {

    private final ScoreHWAuction plugin;
    private final ItemLoreBuilder loreBuilder;

    public MyItemsGui(ScoreHWAuction plugin) {
        this.plugin = plugin;
        this.loreBuilder = new ItemLoreBuilder(plugin);
    }

    public void open(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<AuctionItem> items = plugin.getDb()
                        .loadItemsByPlayer(player.getUniqueId(), AuctionStatus.ACTIVE);
                Bukkit.getScheduler().runTask(plugin, () -> render(player, items));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error loading my items for " + player.getName(), e);
            }
        });
    }

    private void render(Player player, List<AuctionItem> items) {
        // Make sure player is still online and the inventory is openable
        if (!player.isOnline()) return;
        String title = plugin.getMessages().raw("gui.my-items-title");
        Inventory inv = Bukkit.createInventory(null, 54, title);

        for (int slot = 0; slot < Math.min(items.size(), 45); slot++) {
            inv.setItem(slot, buildItem(items.get(slot)));
        }

        inv.setItem(45, GuiUtil.build(Material.ARROW, plugin.getMessages().raw("gui.nav.back.name")));
        inv.setItem(53, buildHelpItem());

        GuiUtil.fillEmpty(inv);
        player.openInventory(inv);
    }

    private ItemStack buildItem(AuctionItem aItem) {
        ItemStack display = aItem.getItemStack().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());
        if (meta == null) return display;

        List<String> lore = new ArrayList<>();
        if (meta.hasLore()) lore.addAll(meta.getLore());
        lore.addAll(loreBuilder.buildMyItemLore(aItem));
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private ItemStack buildHelpItem() {
        return GuiUtil.build(Material.BOOK,
                plugin.getMessages().raw("gui.my-items-how-remove-name"),
                "",
                plugin.getMessages().raw("gui.my-items-how-remove-hdr"),
                "",
                plugin.getMessages().raw("gui.my-items-how-remove-desc"),
                plugin.getMessages().raw("gui.my-items-how-remove-desc2"));
    }

    public void resolveAndCancel(Player player, int slot) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<AuctionItem> items = plugin.getDb()
                        .loadItemsByPlayer(player.getUniqueId(), AuctionStatus.ACTIVE);
                if (slot < 0 || slot >= items.size()) {
                    // Slot out of range (stale GUI state) — just refresh
                    Bukkit.getScheduler().runTask(plugin, () -> open(player));
                    return;
                }
                int itemId = items.get(slot).getId();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    boolean ok = plugin.getListingService().cancel(player, itemId);
                    if (ok) player.sendMessage(plugin.getMessages().get("item-removed"));
                    // Re-open immediately after cancel to show updated list
                    // Small delay (1 tick) so async DB update has time to process
                    Bukkit.getScheduler().runTaskLater(plugin, () -> open(player), 2L);
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error resolving my items slot", e);
                Bukkit.getScheduler().runTask(plugin, () -> open(player));
            }
        });
    }
}
