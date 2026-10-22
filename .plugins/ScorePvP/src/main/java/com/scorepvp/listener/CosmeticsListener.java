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
            case 2 -> handleArmorPieces(p, slot, rightClick, ArmorMaterial.DIAMOND);
            case 5 -> handleArmorPieces(p, slot, rightClick, ArmorMaterial.NETHERITE);
            case 3 -> handlePatternMenu(p, slot);
            case 4 -> handleMaterialMenu(p, slot);
            case 6 -> handleChest(p, slot);
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

    /**
     * -1 = not a cosmetics menu, 0 main, 1 shields, 2 diamond-piece menu,
     * 3 trim-pattern sub-menu, 4 trim-material sub-menu, 5 netherite-piece menu.
     */
    private int which(CosmeticsGUI gui, String title) {
        if (title.equals(gui.mainTitle()))    return 0;
        if (title.equals(gui.chestTitle()))   return 6;
        if (title.equals(gui.shieldsTitle())) return 1;
        com.scorepvp.gui.ArmorTrimGUI atg = plugin.getArmorTrimGUI();
        if (title.equals(atg.diamondTitle()))   return 2;
        if (title.equals(atg.netheriteTitle())) return 5;
        if (title.equals(atg.patternTitle()))   return 3;
        if (title.equals(atg.materialTitle()))  return 4;
        return -1;
    }

    // ── Main menu ────────────────────────────────────────────────────────────

    private void handleMain(Player p, int slot) {
        if (slot == CosmeticsGUI.MAIN_SHIELDS_SLOT) {
            if (!p.hasPermission(CosmeticsCommand.PERM_SHIELDS)) { tell(p, "messages.no-category-permission"); return; }
            plugin.getCosmeticsGUI().openShields(p);
        } else if (slot == CosmeticsGUI.MAIN_ELYTRA_SLOT) {
            if (!p.hasPermission(CosmeticsGUI.PERM_ELYTRA)) { tell(p, "messages.no-category-permission"); return; }
            boolean now = plugin.getElytraCosmeticManager().toggle(p);
            tell(p, now ? "elytra.enabled" : "elytra.disabled");
            plugin.getCosmeticsGUI().openMain(p);
        } else if (slot == CosmeticsGUI.MAIN_TIERTAG_SLOT) {
            if (!p.hasPermission(CosmeticsGUI.PERM_TIERTAG)) { tell(p, "messages.no-category-permission"); return; }
            plugin.getTierTagGUI().openSelect(p);
        } else if (slot == CosmeticsGUI.MAIN_HATS_SLOT) {
            // Hats are open to everyone (bought with silver) — no permission gate.
            // Don't open until owned-hats loaded, so a just-relogged player isn't
            // charged again for a hat they own; re-trigger the load if it stalled.
            if (!plugin.getHatManager().isOwnedLoaded(p.getUniqueId())) {
                plugin.getHatManager().loadOwnedAsync(p.getUniqueId());
                tell(p, "messages.loading");
                return;
            }
            plugin.getHatGUI().open(p);
        } else if (slot == CosmeticsGUI.MAIN_GRADIENT_SLOT) {
            if (!p.hasPermission(CosmeticsGUI.PERM_GRADIENT)) { tell(p, "messages.no-category-permission"); return; }
            plugin.getNameColorGUI().open(p, true, 0);
        } else if (slot == CosmeticsGUI.MAIN_BADGES_SLOT) {
            // Badges are open to everyone (bought with silver) — no permission gate.
            plugin.getBadgeGUI().openList(p, 0);
        } else if (slot == CosmeticsGUI.MAIN_TRIM_SLOT) {
            if (!p.hasPermission(CosmeticsCommand.PERM_DIAMOND)
                    && !p.hasPermission(CosmeticsCommand.PERM_NETHERITE)) {
                tell(p, "messages.no-category-permission"); return;
            }
            plugin.getCosmeticsGUI().openChest(p);
        }
    }

    // ── Armor "Chest" submenu ──────────────────────────────────────────────────

    private void handleChest(Player p, int slot) {
        if (slot == CosmeticsGUI.CHEST_DIAMOND_SLOT) {
            if (!p.hasPermission(CosmeticsCommand.PERM_DIAMOND)) { tell(p, "messages.no-category-permission"); return; }
            plugin.getArmorTrimGUI().openArmor(p, ArmorMaterial.DIAMOND);
        } else if (slot == CosmeticsGUI.CHEST_NETHERITE_SLOT) {
            if (!p.hasPermission(CosmeticsCommand.PERM_NETHERITE)) { tell(p, "messages.no-category-permission"); return; }
            plugin.getArmorTrimGUI().openArmor(p, ArmorMaterial.NETHERITE);
        }
    }

    // ── Shields menu ─────────────────────────────────────────────────────────

    private void handleShields(Player p, int slot, boolean shift, boolean right) {
        CosmeticsManager mgr = plugin.getCosmeticsManager();
        CosmeticsGUI gui = plugin.getCosmeticsGUI();

        // Content area (0..44) → a stored shield of the active category+page.
        if (slot >= 0 && slot < CosmeticsGUI.SHIELDS_PER_CATEGORY) {
            int storeIndex = gui.shieldStoreIndexAt(p.getUniqueId(), slot);
            if (storeIndex < 0) return; // empty slot

            // Admin shift-click — remove this shield entirely.
            if (shift && p.hasPermission(CosmeticsCommand.PERM_ADMIN)) {
                mgr.removeShield(storeIndex);
                tellPlaceholder(p, "messages.shield-removed", "{slot}", String.valueOf(storeIndex));
                gui.openShields(p); // keep current category + page
                return;
            }

            // Right-click — deselect if currently selected.
            int currentlySelected = mgr.getSelection(p.getUniqueId()).shieldSlot;
            if (right && currentlySelected == storeIndex) {
                mgr.clearShield(p.getUniqueId());
                tell(p, "messages.cleared-shield");
                gui.openShields(p);
                return;
            }

            mgr.selectShield(p.getUniqueId(), storeIndex);
            tellPlaceholder(p, "messages.selected-shield", "{slot}", String.valueOf(storeIndex));
            gui.openShields(p);
            return;
        }

        int page = gui.shieldsPage(p.getUniqueId());
        String category = gui.shieldsCategory(p.getUniqueId());
        switch (slot) {
            case CosmeticsGUI.SHIELDS_RESET_SLOT -> {
                mgr.clearShield(p.getUniqueId());
                tell(p, "messages.cleared-shield");
                gui.openShields(p);
            }
            case CosmeticsGUI.SHIELDS_PREV_SLOT -> {
                if (page > 0) gui.openShields(p, category, page - 1);
            }
            case CosmeticsGUI.SHIELDS_NEXT_SLOT -> {
                if (page < gui.shieldsPageCount(category) - 1) gui.openShields(p, category, page + 1);
            }
            case CosmeticsGUI.SHIELDS_CAT1_SLOT -> gui.openShields(p, "plain");
            case CosmeticsGUI.SHIELDS_CAT2_SLOT -> gui.openShields(p, "banner");
            case CosmeticsGUI.SHIELDS_CAT3_SLOT -> gui.openShields(p, "flag");
            case CosmeticsGUI.SHIELDS_BACK_SLOT -> gui.openMain(p);
        }
    }

    // ── Armor trim: piece menu (3×9) ───────────────────────────────────────────

    private void handleArmorPieces(Player p, int slot, boolean right, ArmorMaterial mat) {
        com.scorepvp.gui.ArmorTrimGUI gui = plugin.getArmorTrimGUI();
        CosmeticsManager mgr = plugin.getCosmeticsManager();

        ArmorPiece piece = pieceForSlot(slot);
        if (piece != null) {
            if (right) gui.openMaterial(p, mat, piece);
            else       gui.openPattern(p, mat, piece);
            return;
        }
        if (slot == com.scorepvp.gui.ArmorTrimGUI.RESET_SLOT) {
            for (ArmorPiece pc : ArmorPiece.values()) mgr.resetTrim(p.getUniqueId(), mat, pc);
            tell(p, "messages.cleared-all");
            gui.openArmor(p, mat);
        } else if (slot == com.scorepvp.gui.ArmorTrimGUI.BACK_SLOT) {
            plugin.getCosmeticsGUI().openChest(p);
        }
    }

    private ArmorPiece pieceForSlot(int slot) {
        if (slot == com.scorepvp.gui.ArmorTrimGUI.HELMET_SLOT)     return ArmorPiece.HELMET;
        if (slot == com.scorepvp.gui.ArmorTrimGUI.CHESTPLATE_SLOT) return ArmorPiece.CHESTPLATE;
        if (slot == com.scorepvp.gui.ArmorTrimGUI.LEGGINGS_SLOT)   return ArmorPiece.LEGGINGS;
        if (slot == com.scorepvp.gui.ArmorTrimGUI.BOOTS_SLOT)      return ArmorPiece.BOOTS;
        return null;
    }

    // ── Armor trim: pattern sub-menu (54) ───────────────────────────────────────

    private void handlePatternMenu(Player p, int slot) {
        com.scorepvp.gui.ArmorTrimGUI gui = plugin.getArmorTrimGUI();
        com.scorepvp.gui.ArmorTrimGUI.EditState st = gui.getEditing(p.getUniqueId());
        if (st == null) { plugin.getCosmeticsGUI().openMain(p); return; }

        if (slot == com.scorepvp.gui.ArmorTrimGUI.SUB_BACK_SLOT) {
            gui.openArmor(p, st.material());
            return;
        }
        String key = gui.patternKeyAt(slot);
        if (key == null) return;
        plugin.getCosmeticsManager().setTrimPattern(p.getUniqueId(), st.material(), st.piece(), key);
        gui.openPattern(p, st.material(), st.piece());
    }

    // ── Armor trim: material sub-menu (54) ──────────────────────────────────────

    private void handleMaterialMenu(Player p, int slot) {
        com.scorepvp.gui.ArmorTrimGUI gui = plugin.getArmorTrimGUI();
        com.scorepvp.gui.ArmorTrimGUI.EditState st = gui.getEditing(p.getUniqueId());
        if (st == null) { plugin.getCosmeticsGUI().openMain(p); return; }

        if (slot == com.scorepvp.gui.ArmorTrimGUI.SUB_BACK_SLOT) {
            gui.openArmor(p, st.material());
            return;
        }
        String key = gui.materialKeyAt(slot);
        if (key == null) return;
        plugin.getCosmeticsManager().setTrimMaterial(p.getUniqueId(), st.material(), st.piece(), key);
        gui.openMaterial(p, st.material(), st.piece());
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
