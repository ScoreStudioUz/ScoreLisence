package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.CosmeticsManager;
import com.scorepvp.manager.CosmeticsManager.ArmorMaterial;
import com.scorepvp.manager.CosmeticsManager.ArmorPiece;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the cosmetics GUIs.
 *
 *  • Main menu (27 slots): shields button + diamond + netherite + back/clear.
 *  • Shields sub-menu (54 slots): 0..35 stored shields, 36..44 panes,
 *    45 back, 49 clear (Barrier).
 *  • Armor sub-menu (54 slots, one per material): 4 pieces in slots 11,13,15,
 *    plus back/clear.
 *
 * Titles are read from the {@code cosmetics.*-menu-title} lang keys so the
 * listener can identify which menu was clicked.
 */
public class CosmeticsGUI {

    public static final int MAIN_SIZE     = 27;
    public static final int SHIELDS_SIZE  = 54;
    public static final int ARMOR_SIZE    = 54;

    // Main menu slots
    public static final int MAIN_SHIELDS_SLOT   = 11;
    public static final int MAIN_DIAMOND_SLOT   = 13;
    public static final int MAIN_NETHERITE_SLOT = 15;
    public static final int MAIN_BACK_SLOT      = 22;
    public static final int MAIN_CLEAR_SLOT     = 26;

    // Shields menu
    public static final int SHIELDS_BACK_SLOT  = 45;
    public static final int SHIELDS_CLEAR_SLOT = 49;

    // Armor menu — diamond/netherite pieces
    // helmet=11, chestplate=13, leggings=15, boots=22
    public static final int ARMOR_HELMET_SLOT     = 11;
    public static final int ARMOR_CHESTPLATE_SLOT = 13;
    public static final int ARMOR_LEGGINGS_SLOT   = 15;
    public static final int ARMOR_BOOTS_SLOT      = 22;
    public static final int ARMOR_BACK_SLOT       = 45;
    public static final int ARMOR_CLEAR_SLOT      = 49;

    private final ScorePvP plugin;

    public CosmeticsGUI(ScorePvP plugin) { this.plugin = plugin; }

    // ── Titles ───────────────────────────────────────────────────────────────

    public String mainTitle()      { return ColorUtil.color(plugin.getConfigManager().rawStr("cosmetics.menu-title")); }
    public String shieldsTitle()   { return ColorUtil.color(plugin.getConfigManager().rawStr("cosmetics.shields-menu-title")); }
    public String diamondTitle()   { return ColorUtil.color(plugin.getConfigManager().rawStr("cosmetics.diamond-menu-title")); }
    public String netheriteTitle() { return ColorUtil.color(plugin.getConfigManager().rawStr("cosmetics.netherite-menu-title")); }

    // ── Openers ──────────────────────────────────────────────────────────────

    public void openMain(Player player) {
        Inventory inv = Bukkit.createInventory(null, MAIN_SIZE, mainTitle());
        ItemStack glass = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name("&r").build();
        for (int i = 0; i < MAIN_SIZE; i++) inv.setItem(i, glass);

        inv.setItem(MAIN_SHIELDS_SLOT, new ItemBuilder(Material.SHIELD)
                .name(plugin.getConfigManager().rawStr("cosmetics.main.shields-name"))
                .lore(plugin.getConfigManager().rawList("cosmetics.main.shields-lore"))
                .build());
        inv.setItem(MAIN_DIAMOND_SLOT, new ItemBuilder(Material.DIAMOND_CHESTPLATE)
                .name(plugin.getConfigManager().rawStr("cosmetics.main.diamond-name"))
                .lore(plugin.getConfigManager().rawList("cosmetics.main.diamond-lore"))
                .build());
        inv.setItem(MAIN_NETHERITE_SLOT, new ItemBuilder(Material.NETHERITE_CHESTPLATE)
                .name(plugin.getConfigManager().rawStr("cosmetics.main.netherite-name"))
                .lore(plugin.getConfigManager().rawList("cosmetics.main.netherite-lore"))
                .build());

        inv.setItem(MAIN_CLEAR_SLOT, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().rawStr("cosmetics.main.clear-name"))
                .lore(plugin.getConfigManager().rawList("cosmetics.main.clear-lore"))
                .build());

        player.openInventory(inv);
    }

    public void openShields(Player player) {
        Inventory inv = Bukkit.createInventory(null, SHIELDS_SIZE, shieldsTitle());

        boolean editor = player.hasPermission("duels.cosmetics.admin");
        CosmeticsManager mgr = plugin.getCosmeticsManager();
        Map<Integer, ItemStack> shields = mgr.getShields();

        // 0..35 — shields
        UUID uid = player.getUniqueId();
        int selectedSlot = mgr.getSelection(uid).shieldSlot;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack s = shields.get(slot);
            if (s == null) {
                // Empty: in editor mode show pane hint, otherwise leave blank
                if (editor) {
                    inv.setItem(slot, new ItemBuilder(Material.LIGHT_GRAY_STAINED_GLASS_PANE)
                            .name("&8Свободный слот #" + slot).build());
                }
                continue;
            }
            ItemStack display = s.clone();
            if (slot == selectedSlot) {
                // Visual indicator — add lore line
                display = new ItemBuilder(display).lore("&aВыбрано").build();
            }
            inv.setItem(slot, display);
        }

        // 36..44 — divider panes
        ItemStack pane = new ItemBuilder(Material.BLACK_STAINED_GLASS_PANE).name("&r").build();
        for (int i = 36; i <= 44; i++) inv.setItem(i, pane);

        // Filler rest
        ItemStack glass = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name("&r").build();
        for (int i = 45; i < SHIELDS_SIZE; i++) inv.setItem(i, glass);

        inv.setItem(SHIELDS_BACK_SLOT, new ItemBuilder(Material.ARROW)
                .name(plugin.getConfigManager().rawStr("cosmetics.main.back-name")).build());
        inv.setItem(SHIELDS_CLEAR_SLOT, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().rawStr("cosmetics.main.clear-name"))
                .lore(plugin.getConfigManager().rawList("cosmetics.main.clear-lore")).build());

        player.openInventory(inv);
    }

    public void openArmor(Player player, ArmorMaterial mat) {
        Inventory inv = Bukkit.createInventory(null, ARMOR_SIZE,
                mat == ArmorMaterial.DIAMOND ? diamondTitle() : netheriteTitle());
        ItemStack glass = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE).name("&r").build();
        for (int i = 0; i < ARMOR_SIZE; i++) inv.setItem(i, glass);

        CosmeticsManager mgr = plugin.getCosmeticsManager();
        Map<ArmorPiece, ItemStack> armor = mgr.getArmor(mat);

        setArmorSlot(inv, ARMOR_HELMET_SLOT,     ArmorPiece.HELMET,     armor, mgr, player, mat);
        setArmorSlot(inv, ARMOR_CHESTPLATE_SLOT, ArmorPiece.CHESTPLATE, armor, mgr, player, mat);
        setArmorSlot(inv, ARMOR_LEGGINGS_SLOT,   ArmorPiece.LEGGINGS,   armor, mgr, player, mat);
        setArmorSlot(inv, ARMOR_BOOTS_SLOT,      ArmorPiece.BOOTS,      armor, mgr, player, mat);

        inv.setItem(ARMOR_BACK_SLOT, new ItemBuilder(Material.ARROW)
                .name(plugin.getConfigManager().rawStr("cosmetics.main.back-name")).build());
        inv.setItem(ARMOR_CLEAR_SLOT, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().rawStr("cosmetics.main.clear-name"))
                .lore(plugin.getConfigManager().rawList("cosmetics.main.clear-lore")).build());

        player.openInventory(inv);
    }

    private void setArmorSlot(Inventory inv, int slot, ArmorPiece piece,
                               Map<ArmorPiece, ItemStack> armor, CosmeticsManager mgr,
                               Player player, ArmorMaterial mat) {
        ItemStack stored = armor.get(piece);
        boolean editor = player.hasPermission("duels.cosmetics.admin");
        if (stored == null) {
            if (editor) {
                inv.setItem(slot, new ItemBuilder(defaultArmorMaterial(mat, piece))
                        .name("&8" + piece.name().toLowerCase() + " &7— не задано")
                        .lore("&7Возьмите в руку крашенную броню",
                              "&7и используйте &e/cosmetics admin armor set " + piece.name().toLowerCase())
                        .build());
            } else {
                inv.setItem(slot, null);
            }
            return;
        }

        ItemStack display = stored.clone();
        if (mgr.isArmorSelected(player.getUniqueId(), mat, piece)) {
            display = new ItemBuilder(display).lore("&aВыбрано").build();
        }
        inv.setItem(slot, display);
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
