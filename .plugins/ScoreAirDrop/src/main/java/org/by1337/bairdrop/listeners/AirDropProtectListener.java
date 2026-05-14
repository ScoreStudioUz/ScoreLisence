package org.by1337.bairdrop.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.inventory.ItemStack;
import org.by1337.bairdrop.AirDrop;
import org.by1337.bairdrop.BAirDrop;
import org.by1337.bairdrop.util.Message;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AirDropProtectListener implements Listener {
    private final AirDrop airDrop;
    private final Map<UUID, Long> lastPickupTime = new HashMap<>();

    public AirDropProtectListener(AirDrop airDrop) {
        this.airDrop = airDrop;
        Bukkit.getServer().getPluginManager().registerEvents(this, BAirDrop.getInstance());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!event.getInventory().equals(airDrop.getInventory())) return;
        if (event.getCurrentItem() == null) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        // Only handle clicks in the airdrop inventory (top), not player inventory
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(airDrop.getInventory())) return;

        int slot = event.getSlot();

        // protect-speed check (not for vulcan airdrops)
        if (airDrop.isProtectSpeedEnabled() && !airDrop.isVulcanEnabled()) {
            long now = System.currentTimeMillis();
            long delayMs = (long) (airDrop.getProtectSpeedDelay() * 1000);
            Long lastTime = lastPickupTime.get(player.getUniqueId());
            if (lastTime != null && (now - lastTime) < delayMs) {
                event.setCancelled(true);
                double remaining = (delayMs - (now - lastTime)) / 1000.0;
                Message.sendMsg(player, String.format("&cTez ololmaysiz! %.1f sekund kuting", remaining));
                if (event.getCurrentItem() != null) {
                    player.setCooldown(event.getCurrentItem().getType(), (int) (delayMs / 50));
                }
                return;
            }
            lastPickupTime.put(player.getUniqueId(), now);
        }

        // protect-cheats: swap fake item with real item on pickup
        if (airDrop.isProtectCheatsEnabled()) {
            HashMap<Integer, ItemStack> originalItems = airDrop.getOriginalItems();
            if (originalItems.containsKey(slot)) {
                ItemStack realItem = originalItems.remove(slot);
                // Let the event proceed but replace what the player gets
                event.setCancelled(true);
                airDrop.getInventory().setItem(slot, null);
                // Give the real item to the player
                HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(realItem);
                if (!leftover.isEmpty()) {
                    // Drop leftover items at player's feet
                    for (ItemStack item : leftover.values()) {
                        player.getWorld().dropItemNaturally(player.getLocation(), item);
                    }
                }
                player.updateInventory();
            }
        }
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        lastPickupTime.clear();
    }
}
