package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.ConfigManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.Inventory;

/**
 * Globally blocks manipulation of the 4 protected hotbar slots (0, 1, 7, 8).
 *
 * BUG #9 FIX: protection is disabled when player is in vanilla region, so
 * they can freely edit their kit (move items, rearrange hotbar, etc.).
 */
public class InventoryProtectListener implements Listener {

    private final ScorePvP plugin;

    public InventoryProtectListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Drop (Q key) ──────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (isInVanilla(e.getPlayer())) return;   // Bug #9: free in vanilla region
        if (isInMatch(e.getPlayer())) return;      // Allow drops during PvP/Duel
        if (isKitEditor(e.getPlayer())) return;    // Free slots in kit editor mode
        int held = e.getPlayer().getInventory().getHeldItemSlot();
        if (isProtectedForPlayer(held, e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    /** Bukkit player-inventory raw slot for the worn helmet / chestplate. */
    private static final int HELMET_SLOT = 39;
    private static final int CHESTPLATE_SLOT = 38;

    // ── Inventory Click ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;

        // Cosmetic hat lock: a hat worn in the helmet slot can't be taken off by
        // hand — it "stays stuck on the head" and is only removed via /hats
        // (RMB-unset). This applies everywhere EXCEPT when a real kit helmet is
        // in play (vanilla region / match / kit editor), so armor still works.
        if (!isInVanilla(player) && !isInMatch(player) && !isKitEditor(player)
                && isCosmeticHatClick(e, player)) {
            e.setCancelled(true);
            player.updateInventory();
            return;
        }

        // Lobby elytra lock: the cosmetic elytra worn in the chestplate slot
        // can't be taken off by hand — it's removed only via the cosmetics toggle.
        if (!isInVanilla(player) && !isInMatch(player) && !isKitEditor(player)
                && isCosmeticElytraClick(e, player)) {
            e.setCancelled(true);
            player.updateInventory();
            return;
        }

        if (isInVanilla(player)) return;   // Bug #9: free in vanilla region
        if (isInMatch(player)) return;      // Allow inventory movement during PvP/Duel
        if (isKitEditor(player)) return;    // Free slots in kit editor mode

        Inventory clicked = e.getClickedInventory();
        Inventory bottom  = e.getView().getBottomInventory();
        Inventory top     = e.getView().getTopInventory();
        int slot          = e.getSlot();
        InventoryAction action = e.getAction();

        // Direct click on a protected slot (includes slot 4 for party/queue)
        if (clicked == bottom && isProtectedForPlayer(slot, player)) {
            e.setCancelled(true); return;
        }
        // Shift-click from bottom
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY && clicked == bottom && isProtectedForPlayer(slot, player)) {
            e.setCancelled(true); return;
        }
        // Shift-click from top — block if any protected slot is empty
        if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY && clicked == top) {
            for (int p : ConfigManager.PROTECTED_SLOTS) {
                var item = player.getInventory().getItem(p);
                if (item == null || item.getType().isAir()) {
                    e.setCancelled(true); return;
                }
            }
        }
        // Number-key swap
        if (e.getClick() == ClickType.NUMBER_KEY) {
            if (isProtectedForPlayer(e.getHotbarButton(), player)) { e.setCancelled(true); return; }
            if (clicked == bottom && isProtectedForPlayer(slot, player)) { e.setCancelled(true); return; }
        }
        // COLLECT_TO_CURSOR
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            var cursor = e.getCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                for (int p : ConfigManager.PROTECTED_SLOTS) {
                    var item = player.getInventory().getItem(p);
                    if (item != null && item.getType() == cursor.getType()) {
                        e.setCancelled(true); return;
                    }
                }
            }
        }
    }

    // ── Drag ──────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
        if (isInVanilla(player)) return;   // Bug #9
        if (isInMatch(player)) return;      // Allow drag during PvP/Duel
        if (isKitEditor(player)) return;    // Free slots in kit editor mode
        int topSize = e.getView().getTopInventory().getSize();
        for (int rawSlot : e.getRawSlots()) {
            if (rawSlot >= topSize) {
                int invSlot = rawSlot - topSize;
                if (invSlot < 9 && isProtected(invSlot)) {
                    e.setCancelled(true); return;
                }
            }
        }
    }

    // ── Offhand swap (F) ─────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHand(PlayerSwapHandItemsEvent e) {
        if (isInVanilla(e.getPlayer())) return;
        if (isInMatch(e.getPlayer())) return;   // Allow swap during PvP/Duel
        if (isKitEditor(e.getPlayer())) return; // Free slots in kit editor mode
        if (isProtectedForPlayer(e.getPlayer().getInventory().getHeldItemSlot(), e.getPlayer())) {
            e.setCancelled(true);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isProtected(int slot) {
        return ConfigManager.PROTECTED_SET.contains(slot);
    }

    /** Returns true if this slot is protected for the given player (includes party/queue slot 4). */
    private boolean isProtectedForPlayer(int slot, Player player) {
        if (isProtected(slot)) return true;
        if (slot == 4 && plugin.getPartyManager().isInParty(player.getUniqueId())) return true;
        if (slot == 4 && plugin.getDuelManager().isInQueue(player.getUniqueId())) return true;
        return false;
    }

    /**
     * True if this click would move/remove/swap a cosmetic hat worn in the
     * helmet slot (so we can cancel it — the hat is locked on the head). Covers:
     * a direct click on the helmet slot, a number-key or offhand swap while the
     * helmet holds a hat, and a shift-click of a helmet-type item from the
     * bottom inventory (which would auto-equip and displace the hat).
     */
    private boolean isCosmeticHatClick(InventoryClickEvent e, Player player) {
        if (plugin.getHatManager() == null) return false;
        boolean wearingHat = plugin.getHatManager().hatIdOf(
                player.getInventory().getHelmet()) != null;
        if (!wearingHat) return false;

        Inventory clicked = e.getClickedInventory();
        Inventory bottom  = e.getView().getBottomInventory();

        // Direct interaction with the helmet slot in the player's own inventory.
        if (clicked == bottom && e.getSlot() == HELMET_SLOT) return true;
        // Number-key swap targeting the helmet slot.
        if (e.getClick() == ClickType.NUMBER_KEY && clicked == bottom
                && e.getSlot() == HELMET_SLOT) return true;
        // Shift-clicking another helmet from anywhere would swap into the slot.
        if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            var moved = e.getCurrentItem();
            if (moved != null && moved.getType().name().endsWith("_HELMET")) return true;
        }
        return false;
    }

    /**
     * True if this click would move/remove/swap the lobby cosmetic elytra worn
     * in the chestplate slot. Covers a direct click on the chestplate slot, a
     * number-key swap targeting it, and a shift-click of a chestplate/elytra
     * from the bottom inventory (which would auto-equip and displace it).
     */
    private boolean isCosmeticElytraClick(InventoryClickEvent e, Player player) {
        if (plugin.getElytraCosmeticManager() == null) return false;
        var chest = player.getInventory().getChestplate();
        boolean wearingElytra = chest != null && chest.getType() == org.bukkit.Material.ELYTRA
                && plugin.getElytraCosmeticManager().isEnabled(player);
        if (!wearingElytra) return false;

        Inventory clicked = e.getClickedInventory();
        Inventory bottom  = e.getView().getBottomInventory();

        if (clicked == bottom && e.getSlot() == CHESTPLATE_SLOT) return true;
        if (e.getClick() == ClickType.NUMBER_KEY && clicked == bottom
                && e.getSlot() == CHESTPLATE_SLOT) return true;
        if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            var moved = e.getCurrentItem();
            if (moved != null && (moved.getType() == org.bukkit.Material.ELYTRA
                    || moved.getType().name().endsWith("_CHESTPLATE"))) return true;
        }
        return false;
    }

    /** Returns true when player is in kit editor mode → disable protection */
    private boolean isKitEditor(Player player) {
        return plugin.getPlayerDataManager().isKitEditorMode(player.getUniqueId());
    }

    /** Bug #9: returns true when player is in vanilla region → disable protection */
    private boolean isInVanilla(Player player) {
        return plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId());
    }

    /** Returns true when player is in an active match → allow free inventory use */
    private boolean isInMatch(Player player) {
        return plugin.getMatchManager().isInMatch(player.getUniqueId());
    }
}
