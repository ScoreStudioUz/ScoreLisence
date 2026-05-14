package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.UUID;

public class PlayerJoinQuitListener implements Listener {

    private final ScorePvP plugin;

    public PlayerJoinQuitListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();

        // Initialise runtime state
        plugin.getPlayerDataManager().get(uuid);

        // Load persistent data async
        plugin.getSettingsManager().loadAsync(uuid);
        plugin.getTierManager().loadAsync(uuid);
        plugin.getKitManager().loadAsync(uuid);
        plugin.getFriendsManager().loadAsync(uuid);

        // Track playtime session
        plugin.getStatsManager().onLogin(uuid);

        // Give hotbar 2 ticks after join — clear inventory first to prevent stale items
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (e.getPlayer().isOnline()) {
                // Full inventory clear to remove any leftover kit/armor/offhand from previous session
                e.getPlayer().getInventory().clear();
                e.getPlayer().getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
                e.getPlayer().getInventory().setItemInOffHand(null);
                e.getPlayer().getActivePotionEffects().forEach(
                        eff -> e.getPlayer().removePotionEffect(eff.getType()));

                boolean inParty = plugin.getPartyManager().isInParty(uuid);
                if (inParty) {
                    plugin.getHotbarManager().updatePartySlot(e.getPlayer(), true);
                } else {
                    plugin.getHotbarManager().giveHotbar(e.getPlayer());
                }
                plugin.getFriendsManager().notifyFriendsOnJoin(e.getPlayer());
            }
        }, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        Player player = e.getPlayer();

        // Match/queue forfeit
        boolean wasInMatch = plugin.getMatchManager().isInMatch(uuid);
        boolean wasInQueue = plugin.getDuelManager().isInQueue(uuid);
        plugin.getMatchManager().handleDisconnect(uuid);
        plugin.getDuelManager().handleDisconnect(uuid);

        // If player was in a match or queue, fully clear their inventory
        // so no kit/armor/offhand items persist to next login
        if (wasInMatch || wasInQueue) {
            player.getInventory().clear();
            player.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
            player.getInventory().setItemInOffHand(null);
        }

        // Party cleanup
        plugin.getPartyManager().disbandOnQuit(uuid);

        // Persist & unload data
        plugin.getSettingsManager().unload(uuid);
        plugin.getTierManager().unload(uuid);
        plugin.getKitManager().unload(uuid);
        plugin.getStatsManager().onLogout(uuid);
        plugin.getMsgManager().clearPartner(uuid);
        plugin.getFriendsManager().unload(uuid);
        plugin.getPlayerDataManager().remove(uuid);
    }
}
