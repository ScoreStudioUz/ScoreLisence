package org.by1337.bairdrop.vulcan;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.ItemStack;
import org.by1337.bairdrop.BAirDrop;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class VulcanPickupListener implements Listener {
    private final Map<UUID, ItemStack> fakeToRealMap = new HashMap<>();

    public VulcanPickupListener() {
        Bukkit.getServer().getPluginManager().registerEvents(this, BAirDrop.getInstance());
    }

    public void registerFakeItem(UUID itemEntityUUID, ItemStack realItem) {
        fakeToRealMap.put(itemEntityUUID, realItem.clone());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        UUID itemUUID = event.getItem().getUniqueId();
        ItemStack realItem = fakeToRealMap.remove(itemUUID);
        if (realItem == null) return;

        // Cancel the default pickup (fake item)
        event.setCancelled(true);

        // Remove the fake dropped item from world
        event.getItem().remove();

        // Give real item to player
        ItemStack toGive = realItem.clone();
        toGive.setAmount(event.getItem().getItemStack().getAmount());
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(toGive);
        if (!leftover.isEmpty()) {
            for (ItemStack item : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
        }
    }

    public void unregister() {
        HandlerList.unregisterAll(this);
        fakeToRealMap.clear();
    }

    public boolean hasFakeItems() {
        return !fakeToRealMap.isEmpty();
    }
}
