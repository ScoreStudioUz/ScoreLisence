package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.ElytraCosmeticManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemMendEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Keeps the lobby elytra + wind-charge cosmetic well-behaved:
 * <ul>
 *   <li>Refills the off-hand back to one wind-charge right after a throw, so it
 *       never runs out.</li>
 *   <li>Cancels fall damage while gliding in the lobby (spawn hopping).</li>
 * </ul>
 * The cosmetic can only exist in the lobby loadout; match/party/queue loadouts
 * wipe the inventory before applying, so none of this leaks into a duel.
 */
public class ElytraCosmeticListener implements Listener {

    private final ScorePvP plugin;

    public ElytraCosmeticListener(ScorePvP plugin) { this.plugin = plugin; }

    /** After a wind-charge throw, top the off-hand back up next tick. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onThrow(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.OFF_HAND && e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack off = e.getItem();
        if (off == null || off.getType() != Material.WIND_CHARGE) return;
        Player p = e.getPlayer();
        ElytraCosmeticManager mgr = plugin.getElytraCosmeticManager();
        if (!mgr.isEnabled(p) || !mgr.inLobby(p)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> mgr.refillWindCharge(p));
    }

    /**
     * Reliable refill: whenever a lobby player actually launches a wind-charge
     * projectile, restore the off-hand next tick. This catches throws the
     * interact event misses (fast repeated throws, off-hand edge cases) so the
     * wind-charge never depletes.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent e) {
        if (!(e.getEntity() instanceof org.bukkit.entity.WindCharge)) return;
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        ElytraCosmeticManager mgr = plugin.getElytraCosmeticManager();
        if (!mgr.isEnabled(p) || !mgr.inLobby(p)) return;
        plugin.getServer().getScheduler().runTask(plugin, () -> mgr.refillWindCharge(p));
    }

    /** No fall/kinetic damage while enjoying the lobby elytra. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        EntityDamageEvent.DamageCause c = e.getCause();
        if (c != EntityDamageEvent.DamageCause.FALL
                && c != EntityDamageEvent.DamageCause.FLY_INTO_WALL) return;
        ElytraCosmeticManager mgr = plugin.getElytraCosmeticManager();
        if (mgr.isEnabled(p) && mgr.inLobby(p)) e.setCancelled(true);
    }

    /** Belt-and-braces: never let the cosmetic elytra consume XP via mending. */
    @EventHandler(ignoreCancelled = true)
    public void onMend(PlayerItemMendEvent e) {
        if (e.getItem().getType() == Material.ELYTRA
                && plugin.getElytraCosmeticManager().isEnabled(e.getPlayer())) {
            e.setCancelled(true);
        }
    }
}
