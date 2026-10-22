package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.gui.TierTagGUI;
import com.scorepvp.util.ColorUtil;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;

/**
 * Routes clicks inside the /tags TierTag menus. Both screens are display-only
 * (no item movement), so every interaction is cancelled; we just act on the
 * clicked slot.
 */
public class TierTagListener implements Listener {

    private final ScorePvP plugin;

    public TierTagListener(ScorePvP plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        TierTagGUI gui = plugin.getTierTagGUI();
        // Both screens share the same title, so route by the tracked screen
        // state instead of the title (otherwise confirm clicks were handled by
        // the select handler — buttons appeared dead / wrong tier check).
        boolean select  = gui.isSelectScreen(p.getUniqueId());
        boolean confirm = gui.isConfirmScreen(p.getUniqueId());
        if (!select && !confirm) return;

        e.setCancelled(true);
        if (e.getClickedInventory() == null
                || e.getClickedInventory().getType() == InventoryType.PLAYER) return;

        int slot = e.getRawSlot();
        if (select)  handleSelect(p, slot);
        else         handleConfirm(p, slot);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        TierTagGUI gui = plugin.getTierTagGUI();
        if (gui.isSelectScreen(p.getUniqueId()) || gui.isConfirmScreen(p.getUniqueId())) e.setCancelled(true);
    }

    @EventHandler
    public void onClose(org.bukkit.event.inventory.InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        plugin.getTierTagGUI().clearScreen(p.getUniqueId());
    }

    // ── Select screen ───────────────────────────────────────────────────────────

    private void handleSelect(Player p, int slot) {
        TierTagGUI gui = plugin.getTierTagGUI();

        String modeId = gui.modeAtSelectSlot(slot);
        if (modeId == null) return; // decoration / info

        // The player must actually HOLD a tier in this mode.
        String tier = plugin.getTierManager().getTier(p.getUniqueId(), modeId);
        if (tier == null || tier.isBlank()) {
            playUnhappy(p);
            tell(p, "tags.no-tier-in-mode");
            p.closeInventory();
            return;
        }
        gui.openConfirm(p, modeId);
    }

    // ── Confirm screen ──────────────────────────────────────────────────────────

    private void handleConfirm(Player p, int slot) {
        TierTagGUI gui = plugin.getTierTagGUI();
        String modeId = gui.getConfirming(p.getUniqueId());

        if (slot == gui.confirmCancelSlot()) {
            gui.openSelect(p);
            return;
        }
        if (slot == gui.confirmAcceptSlot()) {
            if (modeId == null) { p.closeInventory(); return; }
            // Re-check the tier still exists before committing.
            String tier = plugin.getTierManager().getTier(p.getUniqueId(), modeId);
            if (tier == null || tier.isBlank()) {
                playUnhappy(p);
                tell(p, "tags.no-tier-in-mode");
                p.closeInventory();
                return;
            }
            plugin.getSettingsManager().get(p.getUniqueId()).setTierTagMode(modeId);
            plugin.getSettingsManager().persist(p.getUniqueId());
            plugin.getNameTagProvider().applyLobbyNametag(p);
            gui.clearConfirming(p.getUniqueId());

            var mode = plugin.getConfigManager().getMode(modeId);
            String modeName = mode != null ? mode.getDisplayName() : modeId;
            String tierDisplay = plugin.getConfigManager().getTierDisplay(tier, modeId);
            String msg = plugin.getConfigManager().rawMsg("tags.applied",
                    "{mode}", modeName, "{tier}", tierDisplay);
            if (msg != null && !msg.isEmpty()) p.sendMessage(msg);

            p.closeInventory();
        }
    }

    private void playUnhappy(Player p) {
        p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
    }

    private void tell(Player p, String key) {
        String msg = plugin.getConfigManager().rawMsg(key);
        if (msg == null || msg.isEmpty() || msg.equals(key)) return;
        p.sendMessage(msg);
    }
}
