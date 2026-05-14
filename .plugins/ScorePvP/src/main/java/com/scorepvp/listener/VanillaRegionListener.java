package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Detects when a player crosses the vanilla chest region boundary.
 *
 * ON ENTER: fully clear inventory (nullifies residual lobby item functions),
 *           then give the vanilla kit.
 * ON EXIT:  restore the 4 protected hotbar items.
 *
 * FIX (hotbar residual functions): complete inventory clear on enter ensures
 * lobby items (iron sword slot-0, ender pearl slot-8) cannot trigger their
 * GUI/warp functions while inside the vanilla region.
 */
public class VanillaRegionListener implements Listener {

    private final ScorePvP plugin;

    public VanillaRegionListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Move ──────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location from = e.getFrom();
        Location to   = e.getTo();
        if (to == null) return;
        if (from.getBlockX() == to.getBlockX()
         && from.getBlockY() == to.getBlockY()
         && from.getBlockZ() == to.getBlockZ()) return;

        checkTransition(e.getPlayer(), to);
    }

    // ── Teleport ──────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) return;
        if (e.getTo() == null) return;
        checkTransition(e.getPlayer(), e.getTo());
    }

    // ── Core Logic ────────────────────────────────────────────────────────────

    private void checkTransition(Player player, Location to) {
        boolean wasInRegion = plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId());
        boolean nowInRegion = plugin.getConfigManager().isInChestRegion(to);

        if (wasInRegion == nowInRegion) return;

        plugin.getPlayerDataManager().setInVanillaRegion(player.getUniqueId(), nowInRegion);

        if (nowInRegion) {
            onEnterRegion(player);
        } else {
            onExitRegion(player);
        }
    }

    private void onEnterRegion(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));

        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            // Section 2: if reset was pending, consume the flag and give default kit
            boolean wasReset = plugin.getKitManager().consumeResetPending(player.getUniqueId());
            if (wasReset) {
                // Force default kit (ignore any saved personal kit)
                player.getInventory().clear();
                player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
                player.getInventory().setItemInOffHand(null);
                plugin.getKitManager().giveDefaultVanillaKit(player);
            } else {
                plugin.getKitManager().giveVanillaKit(player);
            }
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("vanilla-region.enter"));
        }, 1L);
    }

    private void onExitRegion(Player player) {
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            plugin.getHotbarManager().restoreHotbar(player);
            boolean inParty = plugin.getPartyManager().isInParty(player.getUniqueId());
            plugin.getHotbarManager().updatePartySlot(player, inParty);
            player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("vanilla-region.exit"));
        });
    }
}
