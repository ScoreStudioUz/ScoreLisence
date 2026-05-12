package org.by1337.bairdrop.listeners;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import org.by1337.bairdrop.AirDrop;
import org.by1337.bairdrop.CAirDrop;
import org.by1337.bairdrop.customListeners.CustomEvent;
import org.by1337.bairdrop.util.AirManager;

public class BlockBreakListener implements Listener {

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent e) {
        AirDrop airDrop = AirManager.getAirDropForLocation(e.getBlock().getLocation());

        if (airDrop == null) {
            return;
        }

        // Always cancel — airdrop block must never be destroyed
        e.setCancelled(true);

        if (!airDrop.isAirDropStarted()) {
            return;
        }

        if (!airDrop.isAirDropLocked()) {
            return;
        }

        if (!(airDrop instanceof CAirDrop)) {
            return;
        }

        CAirDrop cAirDrop = (CAirDrop) airDrop;

        if (!cAirDrop.isBreakEventEnabled()) {
            return;
        }

        if (cAirDrop.getBreakEventHeartsRemaining() <= 0) {
            return;
        }

        Player player = e.getPlayer();

        // Decrement XP by 1
        cAirDrop.decrementBreakEventHearts();

        // Track this player's contribution
        cAirDrop.addPlayerContribution(player.getUniqueId());

        // Instantly re-set the block to ensure it never visually disappears
        e.getBlock().setType(cAirDrop.getMaterialLocked());

        Location blockCenter = e.getBlock().getLocation().clone().add(0.5, 0.5, 0.5);

        // Animation: particles on hit
        if (cAirDrop.isBreakEventAnimation()) {
            blockCenter.getWorld().spawnParticle(Particle.CLOUD, blockCenter, 20, 0.3, 0.3, 0.3, 0.05);
            blockCenter.getWorld().spawnParticle(Particle.BLOCK_CRACK, blockCenter, 15, 0.2, 0.2, 0.2, 0.1,
                    cAirDrop.getMaterialLocked().createBlockData());
        }

        // Sound on hit
        if (cAirDrop.isBreakEventSound()) {
            blockCenter.getWorld().playSound(blockCenter, Sound.BLOCK_STONE_BREAK, 1.0f, 0.8f);
        }

        // Notify observers (triggers hologram updates, listener commands like actionbar)
        airDrop.notifyObservers(CustomEvent.CLICK_CLOSE, player);

        // Check if XP is depleted — unlock the airdrop
        if (cAirDrop.getBreakEventHeartsRemaining() <= 0) {
            cAirDrop.unlock();
        }
    }
}
