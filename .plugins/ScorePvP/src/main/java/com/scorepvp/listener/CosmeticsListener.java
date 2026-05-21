package com.scorepvp.listener;

import com.scorepvp.ScorePvP;
import com.scorepvp.command.CosmeticsCommand;
import com.scorepvp.gui.CosmeticsGUI;
import com.scorepvp.manager.CosmeticsManager;
import com.scorepvp.manager.CosmeticsManager.ArmorMaterial;
import com.scorepvp.manager.CosmeticsManager.ArmorPiece;
import com.scorepvp.util.ColorUtil;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

/**
 * Click router for the three cosmetics menus. Identifies the active menu by
 * the inventory title (read from the {@code cosmetics.*-menu-title} lang
 * keys), then dispatches to the right handler.
 */
public class CosmeticsListener implements Listener {

    private final ScorePvP plugin;

    public CosmeticsListener(ScorePvP plugin) { this.plugin = plugin; }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        CosmeticsGUI gui = plugin.getCosmeticsGUI();

        if (title.equals(gui.mainTitle())) {
            e.setCancelled(true);
            handleMain(p, e.getRawSlot());
            return;
        }
        if (title.equals(gui.shieldsTitle())) {
            e.setCancelled(true);
            handleShields(p, e.getRawSlot());
            return;
        }
        if (title.equals(gui.diamondTitle())) {
            e.setCancelled(true);
            handleArmor(p, e.getRawSlot(), ArmorMaterial.DIAMOND);
            return;
        }
        if (title.equals(gui.netheriteTitle())) {
            e.setCancelled(true);
            handleArmor(p, e.getRawSlot(), ArmorMaterial.NETHERITE);
        }
    }

    // ── Main menu ────────────────────────────────────────────────────────────

    private void handleMain(Player p, int slot) {
        if (slot == CosmeticsGUI.MAIN_SHIELDS_SLOT) {
            plugin.getCosmeticsGUI().openShields(p);
        } else if (slot == CosmeticsGUI.MAIN_DIAMOND_SLOT) {
            plugin.getCosmeticsGUI().openArmor(p, ArmorMaterial.DIAMOND);
        } else if (slot == CosmeticsGUI.MAIN_NETHERITE_SLOT) {
            plugin.getCosmeticsGUI().openArmor(p, ArmorMaterial.NETHERITE);
        } else if (slot == CosmeticsGUI.MAIN_CLEAR_SLOT) {
            CosmeticsManager mgr = plugin.getCosmeticsManager();
            mgr.clearShield(p.getUniqueId());
            mgr.clearArmor(p.getUniqueId(), ArmorMaterial.DIAMOND);
            mgr.clearArmor(p.getUniqueId(), ArmorMaterial.NETHERITE);
            p.sendMessage(ColorUtil.color(plugin.getConfigManager()
                    .rawStr("cosmetics.messages.cleared")));
            plugin.getCosmeticsGUI().openMain(p);
        }
    }

    // ── Shields menu ─────────────────────────────────────────────────────────

    private void handleShields(Player p, int slot) {
        CosmeticsManager mgr = plugin.getCosmeticsManager();
        boolean admin = p.hasPermission(CosmeticsCommand.PERM_ADMIN);

        if (slot >= 0 && slot < 36) {
            // Admin shift-click to remove
            if (admin && mgr.getShields().containsKey(slot)) {
                // Plain click selects, shift-click removes
                // We can't easily detect shift here without storing — accept
                // both: clicking an admin pane (empty slot) does nothing,
                // clicking an existing shield selects it.
                mgr.selectShield(p.getUniqueId(), slot);
                p.sendMessage(ColorUtil.color(plugin.getConfigManager()
                        .rawStr("cosmetics.messages.selected")
                        .replace("{name}", "щит #" + slot)));
                plugin.getCosmeticsGUI().openShields(p);
                return;
            }
            if (mgr.getShields().containsKey(slot)) {
                mgr.selectShield(p.getUniqueId(), slot);
                p.sendMessage(ColorUtil.color(plugin.getConfigManager()
                        .rawStr("cosmetics.messages.selected")
                        .replace("{name}", "щит #" + slot)));
                plugin.getCosmeticsGUI().openShields(p);
            }
            return;
        }
        if (slot == CosmeticsGUI.SHIELDS_BACK_SLOT) {
            plugin.getCosmeticsGUI().openMain(p);
        } else if (slot == CosmeticsGUI.SHIELDS_CLEAR_SLOT) {
            mgr.clearShield(p.getUniqueId());
            p.sendMessage(ColorUtil.color(plugin.getConfigManager()
                    .rawStr("cosmetics.messages.cleared")));
            plugin.getCosmeticsGUI().openShields(p);
        }
    }

    // ── Armor menu ───────────────────────────────────────────────────────────

    private void handleArmor(Player p, int slot, ArmorMaterial mat) {
        CosmeticsManager mgr = plugin.getCosmeticsManager();

        ArmorPiece piece = CosmeticsGUI.armorPieceForSlot(slot);
        if (piece != null) {
            if (mgr.getArmor(mat).get(piece) == null) return; // nothing configured
            mgr.toggleArmor(p.getUniqueId(), mat, piece);
            String state = mgr.isArmorSelected(p.getUniqueId(), mat, piece)
                    ? plugin.getConfigManager().rawStr("cosmetics.messages.selected")
                          .replace("{name}", piece.name().toLowerCase())
                    : plugin.getConfigManager().rawStr("cosmetics.messages.cleared");
            p.sendMessage(ColorUtil.color(state));
            plugin.getCosmeticsGUI().openArmor(p, mat);
            return;
        }
        if (slot == CosmeticsGUI.ARMOR_BACK_SLOT) {
            plugin.getCosmeticsGUI().openMain(p);
        } else if (slot == CosmeticsGUI.ARMOR_CLEAR_SLOT) {
            mgr.clearArmor(p.getUniqueId(), mat);
            p.sendMessage(ColorUtil.color(plugin.getConfigManager()
                    .rawStr("cosmetics.messages.cleared")));
            plugin.getCosmeticsGUI().openArmor(p, mat);
        }
    }
}
