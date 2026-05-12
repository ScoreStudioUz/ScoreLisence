package com.scoretpa.listener;

import com.scoretpa.ScoreTpa;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

public class MoveListener implements Listener {

    private final ScoreTpa plugin;

    public MoveListener(ScoreTpa plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfigManager().isCancelOnMovement()) return;

        Player player = event.getPlayer();
        if (!plugin.getTeleportManager().isTeleporting(player.getUniqueId())) return;

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;

        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        plugin.getTeleportManager().cancelTeleport(player.getUniqueId(), "teleport-cancelled-movement");
    }
}
