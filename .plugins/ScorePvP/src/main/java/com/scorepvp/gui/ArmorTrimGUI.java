package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.CosmeticsManager.ArmorMaterial;
import com.scorepvp.manager.CosmeticsManager.ArmorPiece;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ArmorMeta;
import org.bukkit.inventory.meta.trim.ArmorTrim;
import org.bukkit.inventory.meta.trim.TrimMaterial;
import org.bukkit.inventory.meta.trim.TrimPattern;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Armor cosmetics — per-piece trim editor, separate for DIAMOND and NETHERITE.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #openArmor(Player, ArmorMaterial)} — a 3×9 (27-slot) menu with
 *       the four armor pieces (helmet/chestplate/leggings/boots) of the chosen
 *       material, a reset button and a back button. Each piece previews the
 *       player's currently-chosen trim for THAT material.</li>
 *   <li>Left-clicking a piece → {@link #openPattern}: a 54-slot bordered menu
 *       listing every Minecraft trim pattern, previewed on that exact piece.</li>
 *   <li>Right-clicking a piece → {@link #openMaterial}: a 54-slot bordered menu
 *       listing every trim material (ore colours).</li>
 * </ol>
 *
 * Diamond and netherite are fully independent: selections never bleed across.
 * Layout decoration is read from item_menus/cosmetics_armor.yml.
 */
public class ArmorTrimGUI {

    // 3×9 piece menu slots (fixed by request).
    public static final int HELMET_SLOT     = 11;
    public static final int CHESTPLATE_SLOT = 12;
    public static final int LEGGINGS_SLOT   = 13;
    public static final int BOOTS_SLOT      = 14;
    public static final int RESET_SLOT      = 17;
    public static final int BACK_SLOT       = 22; // row 3, slot 5

    // Sub-menu back button.
    public static final int SUB_BACK_SLOT   = 49;

    private final ScorePvP plugin;

    private String diamondTitle;
    private String netheriteTitle;
    private String patternTitle;
    private String materialTitle;
    private Material fillerMaterial;
    private String fillerName;

    /** uuid → what the player is currently editing in a sub-menu. */
    private final Map<UUID, EditState> editing = new ConcurrentHashMap<>();

    public record EditState(ArmorMaterial material, ArmorPiece piece) {}

    public ArmorTrimGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "item_menus/cosmetics_armor.yml");
        if (file.exists()) {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            diamondTitle   = ColorUtil.color(cfg.getString("diamond-title", "&8Olmos bronya — Trim"));
            netheriteTitle = ColorUtil.color(cfg.getString("netherite-title", "&8Netherite bronya — Trim"));
            patternTitle   = ColorUtil.color(cfg.getString("pattern-title", "&8Trim shakli"));
            materialTitle  = ColorUtil.color(cfg.getString("material-title", "&8Trim rangi"));
            try { fillerMaterial = Material.valueOf(cfg.getString("filler.material", "GRAY_STAINED_GLASS_PANE")); }
            catch (IllegalArgumentException e) { fillerMaterial = Material.GRAY_STAINED_GLASS_PANE; }
            fillerName = cfg.getString("filler.name", "&r");
        } else {
            diamondTitle   = ColorUtil.color("&8Olmos bronya — Trim");
            netheriteTitle = ColorUtil.color("&8Netherite bronya — Trim");
            patternTitle   = ColorUtil.color("&8Trim shakli");
            materialTitle  = ColorUtil.color("&8Trim rangi");
            fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
            fillerName = "&r";
        }
    }

    public String diamondTitle()   { return diamondTitle; }
    public String netheriteTitle() { return netheriteTitle; }
    public String patternTitle()   { return patternTitle; }
    public String materialTitle()  { return materialTitle; }

    public String titleFor(ArmorMaterial mat) {
        return mat == ArmorMaterial.DIAMOND ? diamondTitle : netheriteTitle;
    }

    public EditState getEditing(UUID uuid) { return editing.get(uuid); }
    public void clearEditing(UUID uuid)    { editing.remove(uuid); }

    // ── Piece menu (3×9) ────────────────────────────────────────────────────────

    public void openArmor(Player player, ArmorMaterial mat) {
        Inventory inv = Bukkit.createInventory(player, 27, titleFor(mat));
        ItemStack filler = new ItemBuilder(fillerMaterial).name(fillerName).build();
        for (int i = 0; i < 27; i++) inv.setItem(i, filler);

        inv.setItem(HELMET_SLOT,     pieceIcon(player, mat, ArmorPiece.HELMET));
        inv.setItem(CHESTPLATE_SLOT, pieceIcon(player, mat, ArmorPiece.CHESTPLATE));
        inv.setItem(LEGGINGS_SLOT,   pieceIcon(player, mat, ArmorPiece.LEGGINGS));
        inv.setItem(BOOTS_SLOT,      pieceIcon(player, mat, ArmorPiece.BOOTS));

        inv.setItem(RESET_SLOT, new ItemBuilder(Material.BARRIER)
                .name(cos("armor-trim.reset-name", "&c&lTrimlarni tozalash"))
                .lore(cosList("armor-trim.reset-lore", List.of("&7Hamma qism trimlarini o'chirish")))
                .build());
        inv.setItem(BACK_SLOT, new ItemBuilder(Material.ARROW)
                .name(cos("armor-trim.back-name", "&c&lOrqaga"))
                .build());

        player.openInventory(inv);
    }

    private ItemStack pieceIcon(Player player, ArmorMaterial mat, ArmorPiece piece) {
        UUID uid = player.getUniqueId();
        String patKey = plugin.getCosmeticsManager().getTrimPatternKey(uid, mat, piece);
        String matKey = plugin.getCosmeticsManager().getTrimMaterialKey(uid, mat, piece);

        ItemStack icon = new ItemStack(armorItemMaterial(mat, piece));
        if (icon.getItemMeta() instanceof ArmorMeta am) {
            ArmorTrim trim = plugin.getCosmeticsManager().getArmorTrim(uid, mat, piece);
            if (trim != null) am.setTrim(trim);
            am.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES);
            icon.setItemMeta(am);
        }
        ItemBuilder b = new ItemBuilder(icon)
                .name(cos("armor-trim.piece-" + pieceKey(piece) + "-name", "&b" + pieceLabel(piece)));
        List<String> lore = new ArrayList<>();
        lore.add(cos("armor-trim.current-pattern", "&7Shakl: &f{value}")
                .replace("{value}", patKey == null ? cos("armor-trim.none", "&8yo'q") : patKey));
        lore.add(cos("armor-trim.current-material", "&7Rang: &f{value}")
                .replace("{value}", matKey == null ? cos("armor-trim.none", "&8yo'q") : matKey));
        lore.add("");
        lore.add(cos("armor-trim.lmb-pattern", "&eLKM &7— shaklni tanlash"));
        lore.add(cos("armor-trim.rmb-material", "&ePKM &7— rangni tanlash"));
        b.lore(lore);
        return b.build();
    }

    // ── Pattern sub-menu (54) ────────────────────────────────────────────────────

    public void openPattern(Player player, ArmorMaterial mat, ArmorPiece piece) {
        editing.put(player.getUniqueId(), new EditState(mat, piece));
        Inventory inv = bordered(patternTitle);
        UUID uid = player.getUniqueId();
        String selectedPat = plugin.getCosmeticsManager().getTrimPatternKey(uid, mat, piece);

        int[] inner = innerSlots();
        int idx = 0;
        for (TrimPattern pat : Registry.TRIM_PATTERN) {
            if (idx >= inner.length) break;
            String key = pat.getKey().getKey();
            // Show the trim PATTERN's own smithing-template item — never the
            // diamond/netherite armor piece — so the icon reflects the pattern.
            ItemStack icon = new ItemStack(patternIcon(key));
            if (icon.getItemMeta() != null) {
                var im = icon.getItemMeta();
                im.addItemFlags(org.bukkit.inventory.ItemFlag.values());
                icon.setItemMeta(im);
            }
            boolean sel = key.equals(selectedPat);
            ItemBuilder b = new ItemBuilder(icon).name((sel ? "&a✔ &f" : "&f") + prettify(key));
            if (sel) b.lore(List.of(cos("armor-trim.selected", "&aTanlangan")));
            inv.setItem(inner[idx++], b.build());
        }

        inv.setItem(SUB_BACK_SLOT, new ItemBuilder(Material.ARROW)
                .name(cos("armor-trim.back-name", "&c&lOrqaga")).build());
        player.openInventory(inv);
    }

    /** Resolves the pattern registry key at a clicked sub-menu slot, or null. */
    public String patternKeyAt(int slot) {
        int[] inner = innerSlots();
        int idx = indexOf(inner, slot);
        if (idx < 0) return null;
        int i = 0;
        for (TrimPattern pat : Registry.TRIM_PATTERN) {
            if (i == idx) return pat.getKey().getKey();
            i++;
        }
        return null;
    }

    // ── Material sub-menu (54) ────────────────────────────────────────────────────

    public void openMaterial(Player player, ArmorMaterial mat, ArmorPiece piece) {
        editing.put(player.getUniqueId(), new EditState(mat, piece));
        Inventory inv = bordered(materialTitle);
        String selectedMat = plugin.getCosmeticsManager().getTrimMaterialKey(player.getUniqueId(), mat, piece);

        int[] inner = innerSlots();
        int idx = 0;
        for (TrimMaterial tm : Registry.TRIM_MATERIAL) {
            if (idx >= inner.length) break;
            String key = tm.getKey().getKey();
            boolean sel = key.equals(selectedMat);
            // Always show the material's own ingot/dye item — never the
            // diamond/netherite armor piece — so the colour is picked from a
            // recognisable ingredient.
            ItemStack icon = new ItemStack(materialIcon(key));
            if (icon.getItemMeta() != null) {
                var im = icon.getItemMeta();
                im.addItemFlags(org.bukkit.inventory.ItemFlag.values());
                icon.setItemMeta(im);
            }
            ItemBuilder b = new ItemBuilder(icon).name((sel ? "&a✔ &f" : "&f") + prettify(key));
            if (sel) b.lore(List.of(cos("armor-trim.selected", "&aTanlangan")));
            inv.setItem(inner[idx++], b.build());
        }

        inv.setItem(SUB_BACK_SLOT, new ItemBuilder(Material.ARROW)
                .name(cos("armor-trim.back-name", "&c&lOrqaga")).build());
        player.openInventory(inv);
    }

    public String materialKeyAt(int slot) {
        int[] inner = innerSlots();
        int idx = indexOf(inner, slot);
        if (idx < 0) return null;
        int i = 0;
        for (TrimMaterial mat : Registry.TRIM_MATERIAL) {
            if (i == idx) return mat.getKey().getKey();
            i++;
        }
        return null;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    /** The smithing-template item that teaches each trim pattern. */
    private Material patternIcon(String key) {
        String upper = key.toUpperCase() + "_ARMOR_TRIM_SMITHING_TEMPLATE";
        Material m = Material.matchMaterial(upper);
        return m != null ? m : Material.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE;
    }

    /** Concrete armor item for (material, piece) — e.g. DIAMOND_LEGGINGS. */
    private Material armorItemMaterial(ArmorMaterial mat, ArmorPiece piece) {
        if (mat == ArmorMaterial.DIAMOND) {
            return switch (piece) {
                case HELMET     -> Material.DIAMOND_HELMET;
                case CHESTPLATE -> Material.DIAMOND_CHESTPLATE;
                case LEGGINGS   -> Material.DIAMOND_LEGGINGS;
                case BOOTS      -> Material.DIAMOND_BOOTS;
            };
        }
        return switch (piece) {
            case HELMET     -> Material.NETHERITE_HELMET;
            case CHESTPLATE -> Material.NETHERITE_CHESTPLATE;
            case LEGGINGS   -> Material.NETHERITE_LEGGINGS;
            case BOOTS      -> Material.NETHERITE_BOOTS;
        };
    }

    private Material materialIcon(String key) {
        return switch (key) {
            case "quartz" -> Material.QUARTZ;
            case "iron" -> Material.IRON_INGOT;
            case "netherite" -> Material.NETHERITE_INGOT;
            case "redstone" -> Material.REDSTONE;
            case "copper" -> Material.COPPER_INGOT;
            case "gold" -> Material.GOLD_INGOT;
            case "emerald" -> Material.EMERALD;
            case "diamond" -> Material.DIAMOND;
            case "lapis" -> Material.LAPIS_LAZULI;
            case "amethyst" -> Material.AMETHYST_SHARD;
            case "resin" -> Material.RESIN_BRICK;
            default -> Material.PAPER;
        };
    }

    private Inventory bordered(String title) {
        Inventory inv = Bukkit.createInventory(null, 54, title);
        ItemStack filler = new ItemBuilder(fillerMaterial).name(fillerName).build();
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);
        // Clear inner region so content shows on plain background.
        for (int s : innerSlots()) inv.setItem(s, null);
        return inv;
    }

    /** 28 inner slots of a 6-row bordered chest (rows 1..4, cols 1..7). */
    private static int[] innerSlots() {
        int[] out = new int[28];
        int i = 0;
        for (int r = 1; r <= 4; r++) {
            for (int c = 1; c <= 7; c++) out[i++] = r * 9 + c;
        }
        return out;
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return -1;
    }

    private static String pieceKey(ArmorPiece piece) { return piece.name().toLowerCase(); }

    private String pieceLabel(ArmorPiece piece) {
        return switch (piece) {
            case HELMET -> "Dubulg'a";
            case CHESTPLATE -> "Ko'krak";
            case LEGGINGS -> "Shim";
            case BOOTS -> "Etik";
        };
    }

    /** "stripe_middle" → "Stripe Middle". */
    private static String prettify(String key) {
        String[] parts = key.split("_");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1)).append(' ');
        }
        return sb.toString().trim();
    }

    private String cos(String path, String def) {
        String v = plugin.getConfigManager().cosStr(path);
        return (v == null || v.isEmpty() || v.equals(path)) ? def : v;
    }

    private List<String> cosList(String path, List<String> def) {
        List<String> v = plugin.getConfigManager().cosList(path);
        return (v == null || v.isEmpty()) ? def : v;
    }
}
