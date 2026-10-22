package com.kiteditor;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public class EditorListener implements Listener {

    private final JavaPlugin plugin;
    private final EditorManager editorManager;
    private final KitManager kitManager;

    public EditorListener(JavaPlugin plugin, EditorManager editorManager, KitManager kitManager) {
        this.plugin = plugin;
        this.editorManager = editorManager;
        this.kitManager = kitManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        Inventory inv = event.getInventory();
        String title = event.getView().getTitle();

        if (title.startsWith("§8Kit Editor")) {
            event.setCancelled(true);

            int slot = event.getRawSlot();
            if (slot < 0 || slot >= inv.getSize()) return;

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || clicked.getType() == org.bukkit.Material.AIR) return;

            if (clicked.getType() == org.bukkit.Material.ARROW) {
                if (slot == 48) {
                    int page = editorManager.getKitListPage(player.getUniqueId()) - 1;
                    editorManager.openKitList(player, page);
                } else if (slot == 50) {
                    int page = editorManager.getKitListPage(player.getUniqueId()) + 1;
                    editorManager.openKitList(player, page);
                }
                return;
            }

            if (clicked.getType() == org.bukkit.Material.CHEST) {
                String kitName = clicked.getItemMeta().getDisplayName().replace("§a", "");
                editorManager.openKitEditor(player, kitName);
                return;
            }
            return;
        }

        if (title.startsWith("§8Редактор:")) {
            int slot = event.getRawSlot();
            int rawSlot = event.getRawSlot();

            if (rawSlot == 51) {
                event.setCancelled(true);
                editorManager.openKitList(player, editorManager.getKitListPage(player.getUniqueId()));
                return;
            }

            if (rawSlot == 52) {
                event.setCancelled(true);
                editorManager.resetKit(player);
                return;
            }

            if (rawSlot == 53) {
                event.setCancelled(true);
                editorManager.saveKit(player, inv);
                return;
            }

            if (event.isShiftClick()) {
                if (rawSlot >= 0 && rawSlot < 54) {
                    if (!editorManager.isKitSlot(rawSlot)) {
                        event.setCancelled(true);
                        return;
                    }
                    event.setCancelled(true);
                    return;
                } else {
                    event.setCancelled(true);
                    return;
                }
            }

            if (rawSlot >= 0 && rawSlot < 54) {
                if (!editorManager.isKitSlot(rawSlot)) {
                    event.setCancelled(true);
                    return;
                }
            }

            if (event.getCursor() != null && event.getCursor().getType() != org.bukkit.Material.AIR) {
                if (rawSlot < 0 || rawSlot >= 54 || !editorManager.isKitSlot(rawSlot)) {
                    event.setCancelled(true);
                    return;
                }
            }

            return;
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();

        if (title.startsWith("§8Редактор:")) {
            for (int slot : event.getRawSlots()) {
                if (!editorManager.isKitSlot(slot)) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler
    public void onInventoryCreative(InventoryCreativeEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        String title = event.getView().getTitle();

        if (title.startsWith("§8Редактор:")) {
            int slot = event.getRawSlot();
            if (!editorManager.isKitSlot(slot)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        String title = event.getView().getTitle();

        if (title.startsWith("§8Редактор:")) {
            editorManager.cleanup(player.getUniqueId());
        }
    }
}
