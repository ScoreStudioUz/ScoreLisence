package ru.score.hwauction.gui.expired;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class ExpiredGui {

    private final ScoreHWAuction plugin;
    private final ItemLoreBuilder loreBuilder;

    public ExpiredGui(ScoreHWAuction plugin) {
        this.plugin = plugin;
        this.loreBuilder = new ItemLoreBuilder(plugin);
    }

    public void open(Player player) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<AuctionItem> items = plugin.getDb().loadExpiredByPlayer(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> render(player, items));
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error loading expired items for " + player.getName(), e);
            }
        });
    }

    private void render(Player player, List<AuctionItem> items) {
        if (!player.isOnline()) return;
        String title = plugin.getMessages().raw("gui.expired-title");
        Inventory inv = Bukkit.createInventory(null, 54, title);

        long cleanupDelayMs = TimeUnit.HOURS.toMillis(plugin.getPluginConfig().getExpiredCleanupDelayHours());

        for (int slot = 0; slot < Math.min(items.size(), 45); slot++) {
            inv.setItem(slot, buildItem(items.get(slot), cleanupDelayMs));
        }

        inv.setItem(45, GuiUtil.build(Material.ARROW, plugin.getMessages().raw("gui.nav.back.name")));
        inv.setItem(53, buildHelpItem());
        GuiUtil.fillEmpty(inv);
        player.openInventory(inv);
    }

    private ItemStack buildItem(AuctionItem aItem, long cleanupDelayMs) {
        ItemStack display = aItem.getItemStack().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta == null) meta = Bukkit.getItemFactory().getItemMeta(display.getType());
        if (meta == null) return display;

        List<String> lore = new ArrayList<>();
        if (meta.hasLore()) lore.addAll(meta.getLore());
        lore.addAll(loreBuilder.buildExpiredItemLore(aItem, cleanupDelayMs));
        meta.setLore(lore);
        display.setItemMeta(meta);
        return display;
    }

    private ItemStack buildHelpItem() {
        return GuiUtil.build(Material.BOOK,
                plugin.getMessages().raw("gui.expired-how-claim-name"),
                "",
                plugin.getMessages().raw("gui.expired-how-claim-hdr"),
                "",
                plugin.getMessages().raw("gui.expired-how-claim-desc"),
                plugin.getMessages().raw("gui.expired-how-claim-desc2"));
    }

    public void resolveAndClaim(Player player, int slot) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<AuctionItem> items = plugin.getDb().loadExpiredByPlayer(player.getUniqueId());
                if (slot < 0 || slot >= items.size()) {
                    Bukkit.getScheduler().runTask(plugin, () -> open(player));
                    return;
                }
                int itemId = items.get(slot).getId();
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getExpiryService().claimExpired(player, itemId);
                    // Refresh after slight delay so DB update completes
                    Bukkit.getScheduler().runTaskLater(plugin, () -> open(player), 2L);
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error resolving expired slot", e);
                Bukkit.getScheduler().runTask(plugin, () -> open(player));
            }
        });
    }
}
