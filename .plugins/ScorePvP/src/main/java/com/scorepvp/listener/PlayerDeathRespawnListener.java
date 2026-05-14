package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/**
 * Handles player death and respawn:
 *  - On death: clear the drop list so protected hotbar items don't drop
 *  - On respawn: restore the 4 protected hotbar items (after 1-tick delay)
 */
public class PlayerDeathRespawnListener implements Listener {

    private final ScorePvP plugin;

    public PlayerDeathRespawnListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Death ────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player player = e.getEntity();

        // Remove protected items from the drop list so they don't scatter on the ground
        e.getDrops().removeIf(item -> {
            if (item == null) return false;
            // Check if this item is one of the protected hotbar materials
            return isProtectedMaterial(item.getType());
        });

        // If player was in vanilla region — clear region flag on death
        if (plugin.getPlayerDataManager().isInVanillaRegion(player.getUniqueId())) {
            plugin.getPlayerDataManager().setInVanillaRegion(player.getUniqueId(), false);
        }
    }

    // ── Respawn ───────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        Player player = e.getPlayer();

        // Restore hotbar 2 ticks after respawn (inventory is ready by then)
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            // Players in a match respawn into spectator — MatchManager handles their inventory
            if (plugin.getMatchManager().isInMatch(player.getUniqueId())) return;

            // Players in queue keep the Barrier cancel button — don't overwrite it
            if (plugin.getDuelManager().isInQueue(player.getUniqueId())) return;

            plugin.getHotbarManager().giveHotbar(player);
            // If player was in a party, re-apply the barrier slot
            boolean inParty = plugin.getPartyManager().isInParty(player.getUniqueId());
            plugin.getHotbarManager().updatePartySlot(player, inParty);
        }, 2L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean isProtectedMaterial(org.bukkit.Material mat) {
        return switch (mat) {
            case IRON_SWORD, NETHER_STAR, BARRIER, GRINDSTONE, ENDER_PEARL -> true;
            default -> false;
        };
    }
}
