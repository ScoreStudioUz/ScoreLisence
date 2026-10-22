package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.gui.EnderKitGUI;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Wires the per-kit ender-chest storage.
 *
 * <ul>
 *   <li>In the kit-editor room, right-clicking an ENDER_CHEST block OR holding an
 *       ender-chest item opens the box in EDIT mode; closing it persists to the
 *       kit being edited.</li>
 *   <li>In a vanilla match, right-clicking the ender-chest item (given as part of
 *       the kit) opens a fresh CLONE in PLAY mode — items can be pulled out but
 *       the saved kit never changes, so it resets every round.</li>
 * </ul>
 *
 * The vanilla arena's own ender-chest BLOCKS are intentionally NOT intercepted in
 * a match: fighters use the item they carry (so the box is theirs, not shared).
 */
public class EnderKitListener implements Listener {

    private final ScorePvP plugin;

    public EnderKitListener(ScorePvP plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK && e.getAction() != Action.RIGHT_CLICK_AIR) return;
        Player p = e.getPlayer();

        boolean clickedEnderBlock = e.getClickedBlock() != null
                && e.getClickedBlock().getType() == Material.ENDER_CHEST;
        boolean holdingEnderItem = e.getItem() != null && e.getItem().getType() == Material.ENDER_CHEST;
        if (!clickedEnderBlock && !holdingEnderItem) return;

        // Editing in the room → open the EDIT box for the kit being edited.
        Integer editIdx = plugin.getVanillaKitManager().getEditing(p.getUniqueId());
        if (plugin.getPlayerDataManager().isInVanillaRegion(p.getUniqueId()) && editIdx != null) {
            e.setCancelled(true); // don't open the vanilla ender inventory / place the block
            plugin.getEnderKitGUI().openEdit(p, editIdx);
            return;
        }

        // In a vanilla match → open the PLAY clone from the item they carry.
        if (holdingEnderItem && isInVanillaMatch(p)) {
            e.setCancelled(true);
            plugin.getEnderKitGUI().openPlay(p);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (plugin.getEnderKitGUI().modeOf(p.getUniqueId()) == null) return;
        if (!plugin.getEnderKitGUI().isThisGUI(e.getView().getTitle())) return;
        plugin.getEnderKitGUI().handleClose(p, e.getInventory());
    }

    private boolean isInVanillaMatch(Player p) {
        var mm = plugin.getMatchManager();
        if (mm == null || !mm.isFighter(p.getUniqueId())) return false;
        var match = mm.getMatch(p.getUniqueId());
        return match != null && match.getMode() != null
                && "vanilla".equals(match.getMode().getKitType());
    }
}
