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

    // ── Inventory Click ───────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player player)) return;
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
