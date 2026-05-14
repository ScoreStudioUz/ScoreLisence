package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Handles the arena selection tool.
 *
 * Bug #2 fix: Changed from WOODEN_AXE to GOLDEN_AXE — the admin receives
 * a Golden Axe when running /arena create <n>. This avoids conflicts with
 * vanilla WOODEN_AXE usage (e.g., attacking mobs).
 *
 * LMB on block = corner 1
 * RMB on block = corner 2
 *
 * Also used for vanilla region selection (/spvp setregion — same tool).
 */
public class ArenaToolListener implements Listener {

    /** Golden Axe — used for both arena corner selection. */
    public static final Material TOOL = Material.GOLDEN_AXE;

    private final ScorePvP plugin;

    public ArenaToolListener(ScorePvP plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player player = e.getPlayer();

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() != TOOL) return;

        // Only fire when player is in arena selection mode
        if (!plugin.getArenaManager().isInSelectionMode(player.getUniqueId())) return;

        Block block = e.getClickedBlock();
        // For LEFT_CLICK we also catch AIR (swing) — but block must be present
        if (e.getAction() == Action.LEFT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_AIR) return;
        if (block == null) return;

        boolean leftClick = e.getAction() == Action.LEFT_CLICK_BLOCK;
        boolean rightClick = e.getAction() == Action.RIGHT_CLICK_BLOCK;
        if (!leftClick && !rightClick) return;

        e.setCancelled(true);

        // Check if player is in region selection mode (setregion) first
        if (plugin.getArenaManager().isInRegionSelectionMode(player.getUniqueId())) {
            plugin.getArenaManager().handleRegionToolClick(player, block.getLocation(), leftClick);
        } else {
            plugin.getArenaManager().handleToolClick(player, block.getLocation(), leftClick);
        }
    }
}
