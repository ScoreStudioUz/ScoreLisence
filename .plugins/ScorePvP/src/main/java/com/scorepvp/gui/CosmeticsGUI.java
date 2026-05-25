package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.CosmeticsManager;
import com.scorepvp.manager.CosmeticsManager.ArmorMaterial;
import com.scorepvp.manager.CosmeticsManager.ArmorPiece;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the cosmetics GUIs.
 *
 *  • Main menu (27 slots): shields button + diamond armor + netherite armor.
 *  • Shields sub-menu (54 slots): admin-added shields in slots 0..35.
 *  • Armor sub-menu: layout is fully configurable via cosmetics.yml
 *    {@code armor-menu.pieces.<piece>.slots: [list]} — DeluxeMenus-style.
 *    The same stored item is rendered on every listed slot; clicking any of
 *    them toggles the selection for that piece.
 */
public class CosmeticsGUI {

    public static final int MAIN_SIZE    = 27;
    public static final int SHIELDS_SIZE = 54;

    public static final int MAIN_SHIELDS_SLOT   = 11;
    public static final int MAIN_DIAMOND_SLOT   = 13;
    public static final int MAIN_NETHERITE_SLOT = 15;
    public static final int MAIN_CLEAR_SLOT     = 22;

    public static final int SHIELDS_BACK_SLOT  = 45;
    public static final int SHIELDS_CLEAR_SLOT = 49;

    private final ScorePvP plugin;

    public CosmeticsGUI(ScorePvP plugin) { this.plugin = plugin; }

    // ── Titles (listener compares against these) ─────────────────────────────

    public String mainTitle()      { return ColorUtil.color(plugin.getConfigManager().cosStr("menu-title")); }
    public String shieldsTitle()   { return ColorUtil.color(plugin.getConfigManager().cosStr("shields-menu-title")); }
    public String diamondTitle()   { return ColorUtil.color(plugin.getConfigManager().cosStr("diamond-menu-title")); }
    public String netheriteTitle() { return ColorUtil.color(plugin.getConfigManager().cosStr("netherite-menu-title")); }

    // ── Armor layout: 9 columns × 4 rows (helmet/chest/legs/boots) ──────────
    //
    // Layout is fixed (not configurable) — the user requested an exact 9×4
    // grid with helmet at the top of each column, so we hard-code it.
    // Column = armor set index (0..8). Row = piece order. Slots 36..53 are
    // for back/clear/filler.

    private static final int[] PIECE_ROW_BASE = {0, 9, 18, 27}; // row offsets

    public int armorSize() { return 54; }
    public int armorBackSlot()  { return plugin.getConfigManager().cosInt("armor-menu.back-slot",  45); }
    public int armorClearSlot() { return plugin.getConfigManager().cosInt("armor-menu.clear-slot", 49); }

    /** Maps (piece, setIndex 0..8) → inventory slot in the armor menu. */
    public static int armorSlot(ArmorPiece piece, int setIndex) {
        if (setIndex < 0 || setIndex >= 9) return -1;
        return PIECE_ROW_BASE[piece.ordinal()] + setIndex;
    }

    /** Reverse lookup: which (piece, setIndex) does this slot represent? null if none. */
    public static int[] armorSetForSlot(int slot) {
        if (slot < 0 || slot >= 36) return null;
        int row = slot / 9;          // 0..3 = helmet/chest/legs/boots
        int col = slot % 9;          // 0..8 = set index
        return new int[]{row, col};
    }

    /** True if this slot in the armor menu is one of the 36 armor grid slots. */
    public static boolean isArmorGridSlot(int slot) {
        return slot >= 0 && slot < 36;
    }

    // ── Openers ──────────────────────────────────────────────────────────────

    public void openMain(Player player) {
        Inventory inv = Bukkit.createInventory(player, MAIN_SIZE, mainTitle());
        fill(inv, MAIN_SIZE);

        inv.setItem(MAIN_SHIELDS_SLOT, new ItemBuilder(Material.SHIELD)
                .name(plugin.getConfigManager().cosStr("main.shields-name"))
                .lore(plugin.getConfigManager().cosList("main.shields-lore"))
                .build());
        inv.setItem(MAIN_DIAMOND_SLOT, new ItemBuilder(Material.DIAMOND_CHESTPLATE)
                .name(plugin.getConfigManager().cosStr("main.diamond-name"))
                .lore(plugin.getConfigManager().cosList("main.diamond-lore"))
                .build());
        inv.setItem(MAIN_NETHERITE_SLOT, new ItemBuilder(Material.NETHERITE_CHESTPLATE)
                .name(plugin.getConfigManager().cosStr("main.netherite-name"))
                .lore(plugin.getConfigManager().cosList("main.netherite-lore"))
                .build());

        inv.setItem(MAIN_CLEAR_SLOT, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().cosStr("main.clear-name"))
                .lore(plugin.getConfigManager().cosList("main.clear-lore"))
                .build());

        player.openInventory(inv);
    }

    public void openShields(Player player) {
        Inventory inv = Bukkit.createInventory(player, SHIELDS_SIZE, shieldsTitle());

        boolean editor = player.hasPermission("duels.cosmetics.admin");
        CosmeticsManager mgr = plugin.getCosmeticsManager();
        Map<Integer, ItemStack> shields = mgr.getShields();
        UUID uid = player.getUniqueId();
        int selectedSlot = mgr.getSelection(uid).shieldSlot;

        for (int slot = 0; slot < 36; slot++) {
            ItemStack s = shields.get(slot);
            if (s == null) {
                if (editor) {
                    inv.setItem(slot, new ItemBuilder(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                            .name(plugin.getConfigManager().cosStr("empty-shield-slot-name")
                                    .replace("{slot}", String.valueOf(slot)))
                            .lore(plugin.getConfigManager().cosList("empty-shield-slot-lore"))
                            .build());
                }
                continue;
            }
            inv.setItem(slot, decorate(s, slot == selectedSlot));
        }

        Material divMat = matFromCfg("divider-material", Material.BLACK_STAINED_GLASS_PANE);
        ItemStack divider = new ItemBuilder(divMat)
                .name(plugin.getConfigManager().cosStr("divider-name")).build();
        for (int i = 36; i <= 44; i++) inv.setItem(i, divider);

        Material fillMat = matFromCfg("filler-material", Material.GRAY_STAINED_GLASS_PANE);
        ItemStack filler = new ItemBuilder(fillMat)
                .name(plugin.getConfigManager().cosStr("filler-name")).build();
        for (int i = 45; i < SHIELDS_SIZE; i++) inv.setItem(i, filler);

        inv.setItem(SHIELDS_BACK_SLOT, new ItemBuilder(Material.ARROW)
                .name(plugin.getConfigManager().cosStr("sub.back-name")).build());
        inv.setItem(SHIELDS_CLEAR_SLOT, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().cosStr("sub.clear-name"))
                .lore(plugin.getConfigManager().cosList("sub.clear-lore")).build());

        player.openInventory(inv);
    }

    public void openArmor(Player player, ArmorMaterial mat) {
        int size = armorSize();
        Inventory inv = Bukkit.createInventory(player, size,
                mat == ArmorMaterial.DIAMOND ? diamondTitle() : netheriteTitle());
        fill(inv, size);

        CosmeticsManager mgr = plugin.getCosmeticsManager();
        boolean editor = player.hasPermission("duels.cosmetics.admin");
        int selectedSet = mgr.getSelectedSet(player.getUniqueId(), mat);

        // 9 columns × 4 rows — column = set index, row = piece order.
        for (ArmorPiece piece : ArmorPiece.values()) {
            for (int setIdx = 0; setIdx < CosmeticsManager.ARMOR_SETS; setIdx++) {
                int slot = armorSlot(piece, setIdx);
                ItemStack stored = mgr.getArmorPiece(mat, setIdx, piece);
                if (stored == null) {
                    if (editor) {
                        inv.setItem(slot, new ItemBuilder(defaultArmorMaterial(mat, piece))
                                .name(plugin.getConfigManager().cosStr("empty-armor-slot-name")
                                        .replace("{piece}", pieceLabel(piece))
                                        .replace("{set}",   String.valueOf(setIdx + 1)))
                                .lore(applyPlaceholderToList(
                                        applyPlaceholderToList(
                                                plugin.getConfigManager().cosList("empty-armor-slot-lore"),
                                                "{piece}", piece.name().toLowerCase()),
                                        "{set}", String.valueOf(setIdx + 1)))
                                .build());
                    }
                    // Non-admins see filler in empty slots.
                    continue;
                }
                inv.setItem(slot, decorate(stored, setIdx == selectedSet));
            }
        }

        int backSlot  = armorBackSlot();
        int clearSlot = armorClearSlot();
        if (backSlot >= 0 && backSlot < size) {
            inv.setItem(backSlot, new ItemBuilder(Material.ARROW)
                    .name(plugin.getConfigManager().cosStr("sub.back-name")).build());
        }
        if (clearSlot >= 0 && clearSlot < size) {
            inv.setItem(clearSlot, new ItemBuilder(Material.BARRIER)
                    .name(plugin.getConfigManager().cosStr("sub.clear-name"))
                    .lore(plugin.getConfigManager().cosList("sub.clear-lore")).build());
        }

        player.openInventory(inv);
    }

    /** Read a stored item's display name (legacy &amp; section signs both) for "Вы выбрали ..." chat. */
    public String displayNameOf(ItemStack item) {
        if (item == null) return "";
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return item.getType().name().toLowerCase().replace('_', ' ');
        }
        return LegacyComponentSerializer.legacyAmpersand()
                .serialize(meta.displayName());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private ItemStack decorate(ItemStack base, boolean selected) {
        ItemStack out = base.clone();
        if (!selected) return out;

        ItemMeta meta = out.getItemMeta();
        if (meta == null) return out;

        String selectedPrefix = plugin.getConfigManager().cosStr("selected-prefix");
        String selectedLore   = plugin.getConfigManager().cosStr("selected-lore");

        Component current = meta.displayName();
        if (current == null) {
            current = Component.text(out.getType().name().toLowerCase().replace('_', ' '));
        }
        Component prefixed = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(ColorUtil.color(selectedPrefix)).append(current);
        meta.displayName(prefixed);

        List<Component> lore = meta.lore();
        if (lore == null) lore = new ArrayList<>();
        else lore = new ArrayList<>(lore);
        lore.add(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(ColorUtil.color(selectedLore)));
        meta.lore(lore);

        out.setItemMeta(meta);
        return out;
    }

    private void fill(Inventory inv, int size) {
        Material fillMat = matFromCfg("filler-material", Material.GRAY_STAINED_GLASS_PANE);
        ItemStack filler = new ItemBuilder(fillMat)
                .name(plugin.getConfigManager().cosStr("filler-name")).build();
        for (int i = 0; i < size; i++) inv.setItem(i, filler);
    }

    private Material matFromCfg(String key, Material def) {
        try {
            String s = plugin.getConfigManager().cosStr(key);
            if (s == null || s.isEmpty()) return def;
            return Material.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException ex) { return def; }
    }

    private Material defaultArmorMaterial(ArmorMaterial mat, ArmorPiece piece) {
        if (mat == ArmorMaterial.DIAMOND) {
            return switch (piece) {
                case HELMET -> Material.DIAMOND_HELMET;
                case CHESTPLATE -> Material.DIAMOND_CHESTPLATE;
                case LEGGINGS -> Material.DIAMOND_LEGGINGS;
                case BOOTS -> Material.DIAMOND_BOOTS;
            };
        }
        return switch (piece) {
            case HELMET -> Material.NETHERITE_HELMET;
            case CHESTPLATE -> Material.NETHERITE_CHESTPLATE;
            case LEGGINGS -> Material.NETHERITE_LEGGINGS;
            case BOOTS -> Material.NETHERITE_BOOTS;
        };
    }

    private String pieceLabel(ArmorPiece piece) {
        return plugin.getConfigManager().cosStr("piece-names." + piece.name().toLowerCase());
    }

    private List<String> applyPlaceholderToList(List<String> in, String key, String val) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) out.add(s.replace(key, val));
        return out;
    }

}
