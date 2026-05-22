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
 *  • Shields sub-menu (54 slots): admin-added shields in slots 0..35,
 *    divider panes in 36..44, back/clear in the bottom row.
 *  • Armor sub-menu (54 slots, one per material): 4 toggleable pieces +
 *    back/clear in the bottom row.
 *
 * All visible strings come from {@code cosmetics.yml} via
 * {@link com.scorepvp.manager.ConfigManager#cosStr(String)} / {@code cosList}.
 * Listener identifies the active menu by the inventory title; never compare
 * stored display names because they may be coloured at render time.
 */
public class CosmeticsGUI {

    public static final int MAIN_SIZE    = 27;
    public static final int SHIELDS_SIZE = 54;
    public static final int ARMOR_SIZE   = 54;

    // Main menu slot layout — three cosmetic category buttons + clear-all.
    public static final int MAIN_SHIELDS_SLOT   = 11;
    public static final int MAIN_DIAMOND_SLOT   = 13;
    public static final int MAIN_NETHERITE_SLOT = 15;
    public static final int MAIN_CLEAR_SLOT     = 22;

    // Shields menu — slots 0..35 hold shields, 36..44 are dividers, 45..53 nav.
    public static final int SHIELDS_BACK_SLOT  = 45;
    public static final int SHIELDS_CLEAR_SLOT = 49;

    // Armor menu — helmet/chest/leggings line + boots underneath.
    public static final int ARMOR_HELMET_SLOT     = 11;
    public static final int ARMOR_CHESTPLATE_SLOT = 13;
    public static final int ARMOR_LEGGINGS_SLOT   = 15;
    public static final int ARMOR_BOOTS_SLOT      = 22;
    public static final int ARMOR_BACK_SLOT       = 45;
    public static final int ARMOR_CLEAR_SLOT      = 49;

    private final ScorePvP plugin;

    public CosmeticsGUI(ScorePvP plugin) { this.plugin = plugin; }

    // ── Titles (listener compares against these) ─────────────────────────────

    public String mainTitle()      { return ColorUtil.color(plugin.getConfigManager().cosStr("menu-title")); }
    public String shieldsTitle()   { return ColorUtil.color(plugin.getConfigManager().cosStr("shields-menu-title")); }
    public String diamondTitle()   { return ColorUtil.color(plugin.getConfigManager().cosStr("diamond-menu-title")); }
    public String netheriteTitle() { return ColorUtil.color(plugin.getConfigManager().cosStr("netherite-menu-title")); }

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

        // 0..35 — shield slots
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

        // 36..44 — divider row
        Material divMat = matFromCfg("divider-material", Material.BLACK_STAINED_GLASS_PANE);
        ItemStack divider = new ItemBuilder(divMat)
                .name(plugin.getConfigManager().cosStr("divider-name")).build();
        for (int i = 36; i <= 44; i++) inv.setItem(i, divider);

        // 45..53 — filler with navigation overlays
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
        Inventory inv = Bukkit.createInventory(player, ARMOR_SIZE,
                mat == ArmorMaterial.DIAMOND ? diamondTitle() : netheriteTitle());
        fill(inv, ARMOR_SIZE);

        CosmeticsManager mgr = plugin.getCosmeticsManager();
        Map<ArmorPiece, ItemStack> armor = mgr.getArmor(mat);

        setArmorSlot(inv, ARMOR_HELMET_SLOT,     ArmorPiece.HELMET,     armor, mgr, player, mat);
        setArmorSlot(inv, ARMOR_CHESTPLATE_SLOT, ArmorPiece.CHESTPLATE, armor, mgr, player, mat);
        setArmorSlot(inv, ARMOR_LEGGINGS_SLOT,   ArmorPiece.LEGGINGS,   armor, mgr, player, mat);
        setArmorSlot(inv, ARMOR_BOOTS_SLOT,      ArmorPiece.BOOTS,      armor, mgr, player, mat);

        inv.setItem(ARMOR_BACK_SLOT, new ItemBuilder(Material.ARROW)
                .name(plugin.getConfigManager().cosStr("sub.back-name")).build());
        inv.setItem(ARMOR_CLEAR_SLOT, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().cosStr("sub.clear-name"))
                .lore(plugin.getConfigManager().cosList("sub.clear-lore")).build());

        player.openInventory(inv);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void setArmorSlot(Inventory inv, int slot, ArmorPiece piece,
                               Map<ArmorPiece, ItemStack> armor, CosmeticsManager mgr,
                               Player player, ArmorMaterial mat) {
        ItemStack stored = armor.get(piece);
        boolean editor = player.hasPermission("duels.cosmetics.admin");

        if (stored == null) {
            if (editor) {
                String pieceName = pieceLabel(piece);
                inv.setItem(slot, new ItemBuilder(defaultArmorMaterial(mat, piece))
                        .name(plugin.getConfigManager().cosStr("empty-armor-slot-name")
                                .replace("{piece}", pieceName))
                        .lore(applyPlaceholderToList(
                                plugin.getConfigManager().cosList("empty-armor-slot-lore"),
                                "{piece}", piece.name().toLowerCase()))
                        .build());
            }
            return;
        }
        inv.setItem(slot, decorate(stored, mgr.isArmorSelected(player.getUniqueId(), mat, piece)));
    }

    /**
     * Returns a defensive copy of {@code base} with the "selected" prefix and
     * lore line appended when {@code selected} is true. Existing display name
     * and lore are preserved — we never overwrite cosmetic styling.
     */
    private ItemStack decorate(ItemStack base, boolean selected) {
        ItemStack out = base.clone();
        if (!selected) return out;

        ItemMeta meta = out.getItemMeta();
        if (meta == null) return out;

        String selectedPrefix = plugin.getConfigManager().cosStr("selected-prefix");
        String selectedLore   = plugin.getConfigManager().cosStr("selected-lore");

        // Append "selected" marker to display name
        Component current = meta.displayName();
        if (current == null) {
            current = Component.text(out.getType().name().toLowerCase().replace('_', ' '));
        }
        Component prefixed = LegacyComponentSerializer.legacyAmpersand()
                .deserialize(ColorUtil.color(selectedPrefix)).append(current);
        meta.displayName(prefixed);

        // Append "selected" lore line (preserving any existing lore)
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

    /** Russian display label for an armor piece (read from cosmetics.yml). */
    private String pieceLabel(ArmorPiece piece) {
        return plugin.getConfigManager().cosStr("piece-names." + piece.name().toLowerCase());
    }

    private List<String> applyPlaceholderToList(List<String> in, String key, String val) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) out.add(s.replace(key, val));
        return out;
    }

    // ── Slot → ArmorPiece mapping for listener ───────────────────────────────

    public static ArmorPiece armorPieceForSlot(int slot) {
        return switch (slot) {
            case ARMOR_HELMET_SLOT     -> ArmorPiece.HELMET;
            case ARMOR_CHESTPLATE_SLOT -> ArmorPiece.CHESTPLATE;
            case ARMOR_LEGGINGS_SLOT   -> ArmorPiece.LEGGINGS;
            case ARMOR_BOOTS_SLOT      -> ArmorPiece.BOOTS;
            default -> null;
        };
    }
}
