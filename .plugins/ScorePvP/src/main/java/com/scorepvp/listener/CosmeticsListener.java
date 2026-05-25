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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;

/**
 * Routes clicks inside the four cosmetics menus.
 *
 *  • Identifies the active menu by inventory title (each menu uses a unique
 *    title from cosmetics.yml).
 *  • Cancels every interaction unconditionally — there is no legitimate
 *    drag/shift/drop inside these menus.
 *  • Also blocks {@link InventoryDragEvent} so hoppers/drag actions cannot
 *    spawn ghost items.
 *
 * Shift-clicking a shield removes it (admins only). Right-clicking deselects.
 * Left-clicking selects.
 */
public class CosmeticsListener implements Listener {

    private final ScorePvP plugin;

    public CosmeticsListener(ScorePvP plugin) { this.plugin = plugin; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;

        CosmeticsGUI gui = plugin.getCosmeticsGUI();
        String title = e.getView().getTitle();
        int kind = which(gui, title);
        if (kind < 0) return;
        // Any interaction inside a cosmetics menu is cancelled — including
        // shift-clicks from the player's bottom inventory (which would
        // otherwise insert items into the top).
        e.setCancelled(true);

        // Only react to clicks in the TOP inventory (menu).
        if (e.getClickedInventory() == null
                || e.getClickedInventory().getType() == InventoryType.PLAYER) return;

        int slot = e.getRawSlot();
        boolean rightClick = e.isRightClick();
        boolean shiftClick = e.isShiftClick();

        switch (kind) {
            case 0 -> handleMain(p, slot);
            case 1 -> handleShields(p, slot, shiftClick, rightClick);
            case 2 -> handleArmor(p, slot, ArmorMaterial.DIAMOND, rightClick);
            case 3 -> handleArmor(p, slot, ArmorMaterial.NETHERITE, rightClick);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        CosmeticsGUI gui = plugin.getCosmeticsGUI();
        String title = e.getView().getTitle();
        if (which(gui, title) < 0) return;
        e.setCancelled(true);
    }

    /** -1 = not a cosmetics menu, 0 main, 1 shields, 2 diamond, 3 netherite. */
    private int which(CosmeticsGUI gui, String title) {
        if (title.equals(gui.mainTitle()))      return 0;
        if (title.equals(gui.shieldsTitle()))   return 1;
        if (title.equals(gui.diamondTitle()))   return 2;
        if (title.equals(gui.netheriteTitle())) return 3;
        return -1;
    }

    // ── Main menu ────────────────────────────────────────────────────────────

    private void handleMain(Player p, int slot) {
        CosmeticsManager mgr = plugin.getCosmeticsManager();
        if (slot == CosmeticsGUI.MAIN_SHIELDS_SLOT) {
            plugin.getCosmeticsGUI().openShields(p);
        } else if (slot == CosmeticsGUI.MAIN_DIAMOND_SLOT) {
            plugin.getCosmeticsGUI().openArmor(p, ArmorMaterial.DIAMOND);
        } else if (slot == CosmeticsGUI.MAIN_NETHERITE_SLOT) {
            plugin.getCosmeticsGUI().openArmor(p, ArmorMaterial.NETHERITE);
        } else if (slot == CosmeticsGUI.MAIN_CLEAR_SLOT) {
            mgr.clearShield(p.getUniqueId());
            mgr.clearArmor(p.getUniqueId(), ArmorMaterial.DIAMOND);
            mgr.clearArmor(p.getUniqueId(), ArmorMaterial.NETHERITE);
            tell(p, "messages.cleared-all");
            plugin.getCosmeticsGUI().openMain(p);
        }
    }

    // ── Shields menu ─────────────────────────────────────────────────────────

    private void handleShields(Player p, int slot, boolean shift, boolean right) {
        CosmeticsManager mgr = plugin.getCosmeticsManager();

        if (slot >= 0 && slot < 36) {
            boolean exists = mgr.getShields().containsKey(slot);

            // Admin shift-click — remove this shield entirely.
            if (shift && p.hasPermission(CosmeticsCommand.PERM_ADMIN) && exists) {
                mgr.removeShield(slot);
                tellPlaceholder(p, "messages.shield-removed", "{slot}", String.valueOf(slot));
                plugin.getCosmeticsGUI().openShields(p);
                return;
            }

            // Right-click — deselect if currently selected.
            int currentlySelected = mgr.getSelection(p.getUniqueId()).shieldSlot;
            if (right && currentlySelected == slot) {
                mgr.clearShield(p.getUniqueId());
                tell(p, "messages.cleared-shield");
                plugin.getCosmeticsGUI().openShields(p);
                return;
            }

            if (!exists) return; // empty slot, nothing to do
            mgr.selectShield(p.getUniqueId(), slot);
            tellPlaceholder(p, "messages.selected-shield", "{slot}", String.valueOf(slot));
            plugin.getCosmeticsGUI().openShields(p);
            return;
        }
        if (slot == CosmeticsGUI.SHIELDS_BACK_SLOT) {
            plugin.getCosmeticsGUI().openMain(p);
        } else if (slot == CosmeticsGUI.SHIELDS_CLEAR_SLOT) {
            mgr.clearShield(p.getUniqueId());
            tell(p, "messages.cleared-shield");
            plugin.getCosmeticsGUI().openShields(p);
        }
    }

    // ── Armor menu ───────────────────────────────────────────────────────────

    private void handleArmor(Player p, int slot, ArmorMaterial mat, boolean right) {
        CosmeticsManager mgr = plugin.getCosmeticsManager();
        CosmeticsGUI gui = plugin.getCosmeticsGUI();

        if (CosmeticsGUI.isArmorGridSlot(slot)) {
            int[] coords = CosmeticsGUI.armorSetForSlot(slot);
            if (coords == null) return;
            int row = coords[0];
            int setIdx = coords[1];
            ArmorPiece piece = ArmorPiece.values()[row];

            // Slot must actually have an item stored — empty slots are no-ops.
            org.bukkit.inventory.ItemStack stored = mgr.getArmorPiece(mat, setIdx, piece);
            if (stored == null) return;

            String materialLabel = materialLabel(mat);
            String itemName      = stripColour(gui.displayNameOf(stored));

            // Right-click on the currently selected set → deselect.
            if (right) {
                if (mgr.isSetSelected(p.getUniqueId(), mat, setIdx)) {
                    mgr.clearArmor(p.getUniqueId(), mat);
                    tellPlaceholder2(p, "messages.deselected-armor",
                            "{name}", itemName, "{material}", materialLabel);
                    gui.openArmor(p, mat);
                }
                return;
            }

            // Left-click → select THIS whole set for the player.
            mgr.selectArmorSet(p.getUniqueId(), mat, setIdx);
            tellPlaceholder2(p, "messages.selected-armor",
                    "{name}", itemName, "{material}", materialLabel);
            gui.openArmor(p, mat);
            return;
        }
        if (slot == gui.armorBackSlot()) {
            plugin.getCosmeticsGUI().openMain(p);
        } else if (slot == gui.armorClearSlot()) {
            mgr.clearArmor(p.getUniqueId(), mat);
            tellPlaceholder(p, "messages.cleared-armor", "{material}", materialLabel(mat));
            plugin.getCosmeticsGUI().openArmor(p, mat);
        }
    }

    /** Strips &amp;/§ legacy colour codes for clean chat insertion of item names. */
    private static String stripColour(String s) {
        if (s == null || s.isEmpty()) return "";
        return s.replaceAll("[&§§][0-9a-fk-orA-FK-OR]", "");
    }

    // ── Label helpers ────────────────────────────────────────────────────────

    private String pieceLabel(ArmorPiece piece) {
        return plugin.getConfigManager().cosStr("piece-names." + piece.name().toLowerCase());
    }

    private String materialLabel(ArmorMaterial mat) {
        return plugin.getConfigManager().cosStr("material-names." + mat.key());
    }

    private void tell(Player p, String key) {
        String prefix = plugin.getConfigManager().cosStr("messages.prefix");
        String body   = plugin.getConfigManager().cosStr(key);
        p.sendMessage(ColorUtil.color(prefix + body));
    }

    private void tellPlaceholder(Player p, String key, String ph, String value) {
        String prefix = plugin.getConfigManager().cosStr("messages.prefix");
        String body   = plugin.getConfigManager().cosStr(key).replace(ph, value);
        p.sendMessage(ColorUtil.color(prefix + body));
    }

    private void tellPlaceholder2(Player p, String key,
                                   String ph1, String v1, String ph2, String v2) {
        String prefix = plugin.getConfigManager().cosStr("messages.prefix");
        String body   = plugin.getConfigManager().cosStr(key)
                .replace(ph1, v1).replace(ph2, v2);
        p.sendMessage(ColorUtil.color(prefix + body));
    }
}
