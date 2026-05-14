package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.HotbarManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.Location;

public class HotbarInteractListener implements Listener {

    private final ScorePvP plugin;

    public HotbarInteractListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action action = e.getAction();
        boolean isRightClick = (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK);
        boolean isLeftClick  = (action == Action.LEFT_CLICK_AIR  || action == Action.LEFT_CLICK_BLOCK);
        if (!isRightClick && !isLeftClick) return;

        Player player = e.getPlayer();
        int slot = player.getInventory().getHeldItemSlot();

        // FIX (hotbar residual functions): in vanilla region, cancel ALL
        // slot-based lobby-item interactions so no residual functions fire.
        if (plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId())) {
            // Only cancel if it's a protected slot (lobby item slot); let
            // vanilla kit item usage proceed normally.
            if (com.scorepvp.manager.ConfigManager.PROTECTED_SET.contains(slot)) {
                e.setCancelled(true);
            }
            return; // Never open menus or trigger warps while in region
        }

        // Don't intercept hotbar items during active matches
        if (plugin.getMatchManager().isInMatch(player.getUniqueId())) return;

        // --- Party leader/member hotbar handling (RMB only) ---
        if (plugin.getPartyManager().isInParty(player.getUniqueId())) {
            if (!isRightClick) return;
            handlePartyHotbar(player, slot, e);
            return;
        }

        // Queue cancel
        if (plugin.getDuelManager().isInQueue(player.getUniqueId())) {
            if (isRightClick && slot == HotbarManager.SLOT_QUEUE) {
                handleQueueCancel(player, e);
            }
            return;
        }

        // Normal hotbar (right-click only)
        if (!isRightClick) return;
        switch (slot) {
            case HotbarManager.SLOT_MENU     -> handleMenu(player, e);
            case HotbarManager.SLOT_PARTY    -> handlePartyCreate(player, e);
            case HotbarManager.SLOT_SETTINGS -> handleSettings(player, e);
            case HotbarManager.SLOT_WARP     -> handleWarp(player, e);
            case HotbarManager.SLOT_QUEUE    -> handleQueueCancel(player, e);
        }
    }

    // ── Party hotbar ──────────────────────────────────────────────────────────

    private void handlePartyHotbar(Player player, int slot, PlayerInteractEvent e) {
        boolean isLeader = plugin.getPartyManager().isLeader(player.getUniqueId());
        if (isLeader) {
            if (slot == HotbarManager.SLOT_MENU) {
                e.setCancelled(true);
                plugin.getPartyFfaGUI().open(player);
            } else if (slot == HotbarManager.SLOT_WARP) {
                e.setCancelled(true);
                // FIX (section 3): block disband during active FFA match
                if (plugin.getMatchManager().isInMatch(player.getUniqueId())) {
                    player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("hotbar.cannot-disband-in-match"));
                    return;
                }
                plugin.getPartyManager().disband(player);
            }
        } else {
            if (slot == HotbarManager.SLOT_QUEUE) {
                e.setCancelled(true);
                // FIX (section 3): block leave during active FFA match
                if (plugin.getMatchManager().isInMatch(player.getUniqueId())) {
                    player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                            + " " + plugin.getConfigManager().rawMsg("hotbar.cannot-leave-use-cmd"));
                    return;
                }
                plugin.getPartyManager().leave(player);
            }
        }
    }

    // ── Slot 0 — Main Menu ────────────────────────────────────────────────────

    private void handleMenu(Player player, PlayerInteractEvent e) {
        e.setCancelled(true);
        plugin.getMainMenuGUI().open(player);
    }

    // ── Slot 1 — Party Create ─────────────────────────────────────────────────

    private void handlePartyCreate(Player player, PlayerInteractEvent e) {
        e.setCancelled(true);
        // FIX (section 3): block party creation during match
        if (plugin.getMatchManager().isInMatch(player.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("hotbar.cannot-create-in-match"));
            return;
        }
        org.bukkit.inventory.ItemStack heldItem = player.getInventory().getItemInMainHand();
        if (heldItem == null || heldItem.getType().isAir()) return;
        if (heldItem.getType() == Material.NETHER_STAR) {
            plugin.getPartyManager().createParty(player);
        }
    }

    // ── Slot 7 — Settings ─────────────────────────────────────────────────────

    private void handleSettings(Player player, PlayerInteractEvent e) {
        e.setCancelled(true);
        plugin.getSettingsGUI().open(player);
    }

    // ── Slot 8 — Vanilla Warp ─────────────────────────────────────────────────

    private void handleWarp(Player player, PlayerInteractEvent e) {
        e.setCancelled(true);
        // Already guarded at top — if we reach here, player is NOT in region
        teleportToVanilla(player);
    }

    // ── Slot 4 — Queue cancel ──────────────────────────────────────────────────

    private void handleQueueCancel(Player player, PlayerInteractEvent e) {
        org.bukkit.inventory.ItemStack item = player.getInventory().getItem(HotbarManager.SLOT_QUEUE);
        if (item == null || item.getType() != org.bukkit.Material.BARRIER) return;
        e.setCancelled(true);
        if (plugin.getDuelManager().isInQueue(player.getUniqueId())) {
            plugin.getDuelManager().leaveQueue(player);
        }
    }

    private void teleportToVanilla(Player player) {
        Location warpLoc = plugin.getConfigManager().getWarpLocation();
        if (warpLoc == null) {
            player.sendMessage(plugin.getConfigManager().msg("kit.not-in-region"));
            return;
        }

        // FIX: clear ALL inventory immediately before teleport so lobby item
        // functions cannot fire during the teleport/kit-give window
        player.getInventory().clear();
        player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        player.getInventory().setItemInOffHand(null);

        plugin.getPlayerDataManager().setInVanillaRegion(player.getUniqueId(), true);
        player.teleport(warpLoc);

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            plugin.getKitManager().giveVanillaKit(player);
            player.sendMessage(plugin.getConfigManager().msg("kit.given", "{kit}", "Vanilla"));
        }, 2L);
    }
}
