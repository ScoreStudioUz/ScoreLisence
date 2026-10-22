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

    // 27-slot main-menu layout (user's sample):
    //   10 shield, 11 wind-charge(elytra), 12 name-tag(TIERTAG picker),
    //   13 coal(HATS — custom-model-data preview),
    //   14 netherite-chestplate(amethyst trim) → opens the armor "Chest" submenu,
    //   15 echo-shard(gradient colors), 16 brown-bundle(badges).
    public static final int MAIN_SHIELDS_SLOT   = 10;
    public static final int MAIN_ELYTRA_SLOT    = 11;
    public static final int MAIN_TIERTAG_SLOT   = 12;
    public static final int MAIN_HATS_SLOT      = 13;
    public static final int MAIN_TRIM_SLOT      = 14;
    public static final int MAIN_GRADIENT_SLOT  = 15;
    public static final int MAIN_BADGES_SLOT    = 16;

    // Armor "Chest" submenu (27 slots): 11 diamond, 15 netherite.
    public static final int CHEST_DIAMOND_SLOT   = 11;
    public static final int CHEST_NETHERITE_SLOT = 15;

    // Per-button permissions — a missing permission renders the slot as a BARRIER.
    // Hats + badges are intentionally NOT permission-gated: they are bought with
    // silver, so they stay open to everyone. /cosmetics itself is open to all.
    public static final String PERM_ELYTRA   = "duels.cosmetics.elytra";
    public static final String PERM_TIERTAG  = "duels.cosmetics.tiertag";
    public static final String PERM_GRADIENT = "duels.cosmetics.gradient";

    // Shields menu layout (user's "Countries1" sample):
    //   0..44  = shields of the active category+page (45 per page)
    //   45     = BARRIER  → reset (deselect shield)
    //   46,52  = gray glass filler
    //   47     = PREV page arrow (only shown when a previous page exists)
    //   51     = NEXT page arrow (only shown when a next page exists)
    //   48,49,50 = category buttons (plain / banner / flag)
    //   53     = RED_DYE  → back to main cosmetics menu
    public static final int SHIELDS_RESET_SLOT = 45;
    public static final int SHIELDS_PREV_SLOT  = 47;
    public static final int SHIELDS_NEXT_SLOT  = 51;
    public static final int SHIELDS_CAT1_SLOT  = 48; // plain
    public static final int SHIELDS_CAT2_SLOT  = 49; // banner
    public static final int SHIELDS_CAT3_SLOT  = 50; // flag
    public static final int SHIELDS_BACK_SLOT  = 53; // red dye → main
    // Slots 47/51 are reserved for page arrows now, so they're not plain glass.
    private static final int[] SHIELDS_GLASS_SLOTS = {46, 52};
    // Custom-model-data stamped on the page arrows so the resource pack can render
    // a proper arrow model. Configurable via cosmetics.yml.
    public static final int SHIELDS_PREV_CMD_DEFAULT = 9001;
    public static final int SHIELDS_NEXT_CMD_DEFAULT = 9002;

    private final ScorePvP plugin;

    public CosmeticsGUI(ScorePvP plugin) { this.plugin = plugin; }

    // ── Titles (listener compares against these) ─────────────────────────────

    public String mainTitle()      { return ColorUtil.color(plugin.getConfigManager().cosStr("menu-title")); }
    public String chestTitle()     { return ColorUtil.color(plugin.getConfigManager().cosStr("chest-menu-title")); }
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

        inv.setItem(MAIN_SHIELDS_SLOT, shieldsButton(player));
        inv.setItem(MAIN_ELYTRA_SLOT, mainButton(player,
                PERM_ELYTRA, Material.WIND_CHARGE, "main.elytra-name", "main.elytra-lore"));
        // Slot 12: TierTag picker — controls the tier shown in Tab + spawn nick.
        inv.setItem(MAIN_TIERTAG_SLOT, mainButton(player,
                PERM_TIERTAG, Material.NAME_TAG, "main.tiertag-name", "main.tiertag-lore"));
        // Slot 13: Hats — open to everyone (bought with silver). Preview is the
        // configured COAL + custom-model-data hat so the RP renders its 3D model.
        inv.setItem(MAIN_HATS_SLOT, hatsButton());
        // Netherite chestplate with an amethyst trim — decorative category icon
        // that opens the armor "Chest" submenu (diamond / netherite trims).
        inv.setItem(MAIN_TRIM_SLOT, trimIcon(player,
                Material.NETHERITE_CHESTPLATE, "main.trim-name", "main.trim-lore",
                p -> p.hasPermission(com.scorepvp.command.CosmeticsCommand.PERM_DIAMOND)
                        || p.hasPermission(com.scorepvp.command.CosmeticsCommand.PERM_NETHERITE),
                "amethyst", "dune"));
        inv.setItem(MAIN_GRADIENT_SLOT, mainButton(player,
                PERM_GRADIENT, Material.ECHO_SHARD, "main.gradient-name", "main.gradient-lore"));
        // Slot 16: Badges — open to everyone (bought with silver).
        inv.setItem(MAIN_BADGES_SLOT, new ItemBuilder(Material.BROWN_BUNDLE)
                .name(plugin.getConfigManager().cosStr("main.badges-name"))
                .lore(plugin.getConfigManager().cosList("main.badges-lore"))
                .flag(org.bukkit.inventory.ItemFlag.values())
                .build());

        player.openInventory(inv);
    }

    /**
     * The Hats category button (slot 13) — open to everyone. Renders the
     * configured preview hat (COAL + custom-model-data) so the resource pack
     * shows its 3D model; the admin can change which hat is previewed via
     * cosmetics.yml {@code main.hats-preview-hat}, falling back to a plain COAL.
     */
    private ItemStack hatsButton() {
        String previewId = plugin.getConfigManager().cosStr("main.hats-preview-hat");
        com.scorepvp.hats.HatDefinition hat = (previewId == null || previewId.isBlank())
                ? null : plugin.getHatManager().getHat(previewId);
        ItemStack it = hat != null ? hat.buildHatItem() : new ItemStack(Material.COAL);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(ColorUtil.component(plugin.getConfigManager().cosStr("main.hats-name")));
            java.util.List<Component> lore = new ArrayList<>();
            for (String l : plugin.getConfigManager().cosList("main.hats-lore"))
                lore.add(ColorUtil.component(l)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    /**
     * The armor-trim "Chest" submenu: two plain chestplates (no trim) — diamond
     * at slot 11, netherite at slot 15 — each opening its own trim editor.
     */
    public void openChest(Player player) {
        Inventory inv = Bukkit.createInventory(player, MAIN_SIZE, chestTitle());
        fill(inv, MAIN_SIZE);
        inv.setItem(CHEST_DIAMOND_SLOT, mainButton(player,
                com.scorepvp.command.CosmeticsCommand.PERM_DIAMOND,
                Material.DIAMOND_CHESTPLATE, "chest.diamond-name", "chest.diamond-lore"));
        inv.setItem(CHEST_NETHERITE_SLOT, mainButton(player,
                com.scorepvp.command.CosmeticsCommand.PERM_NETHERITE,
                Material.NETHERITE_CHESTPLATE, "chest.netherite-name", "chest.netherite-lore"));
        player.openInventory(inv);
    }

    /**
     * A decorative trimmed-chestplate icon. Rendered with the given amethyst/dune
     * trim so it looks distinct; falls back to a plain chestplate if the trim API
     * is unavailable. Locked (per the {@code unlocked} test) → BARRIER.
     */
    private ItemStack trimIcon(Player player, Material chestplate,
                               String nameKey, String loreKey,
                               java.util.function.Predicate<Player> unlocked,
                               String trimMaterial, String trimPattern) {
        if (!unlocked.test(player)) {
            return new ItemBuilder(Material.BARRIER)
                    .name(plugin.getConfigManager().cosStr("main.locked-name"))
                    .lore(plugin.getConfigManager().cosList("main.locked-lore"))
                    .build();
        }
        ItemStack it = new ItemBuilder(chestplate)
                .name(plugin.getConfigManager().cosStr(nameKey))
                .lore(plugin.getConfigManager().cosList(loreKey))
                .flag(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES)
                .build();
        try {
            if (it.getItemMeta() instanceof org.bukkit.inventory.meta.ArmorMeta am) {
                var trimMat = org.bukkit.Registry.TRIM_MATERIAL.get(
                        org.bukkit.NamespacedKey.minecraft(trimMaterial));
                var trimPat = org.bukkit.Registry.TRIM_PATTERN.get(
                        org.bukkit.NamespacedKey.minecraft(trimPattern));
                if (trimMat != null && trimPat != null) {
                    am.setTrim(new org.bukkit.inventory.meta.trim.ArmorTrim(trimMat, trimPat));
                    it.setItemMeta(am);
                }
            }
        } catch (Throwable ignored) { /* trim API missing — plain chestplate */ }
        return it;
    }

    /**
     * A main-menu category button. If the player lacks {@code perm}, the button
     * is shown as a BARRIER with the locked name+lore from cosmetics.yml
     * (main.locked-name / main.locked-lore) so admins can edit the wording.
     */
    /**
     * The main-menu "Shields" category button — a real patterned shield (the
     * user's requested design) so the icon looks like an actual cosmetic shield.
     * Locked (no permission) → BARRIER, like the other category buttons.
     */
    private ItemStack shieldsButton(Player player) {
        if (!player.hasPermission(com.scorepvp.command.CosmeticsCommand.PERM_SHIELDS)) {
            return new ItemBuilder(Material.BARRIER)
                    .name(plugin.getConfigManager().cosStr("main.locked-name"))
                    .lore(plugin.getConfigManager().cosList("main.locked-lore"))
                    .build();
        }
        ItemStack it = new ItemStack(Material.SHIELD);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta bsm
                && bsm.getBlockState() instanceof org.bukkit.block.Banner banner) {
            banner.setPatterns(shieldsButtonPatterns());
            banner.update();
            bsm.setBlockState(banner);
        }
        if (meta != null) {
            meta.displayName(com.scorepvp.util.ColorUtil.component(
                    plugin.getConfigManager().cosStr("main.shields-name")));
            java.util.List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
            for (String l : plugin.getConfigManager().cosList("main.shields-lore"))
                lore.add(com.scorepvp.util.ColorUtil.component(l)
                        .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
            meta.lore(lore);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            it.setItemMeta(meta);
        }
        return it;
    }

    /** The user's requested shield banner design. */
    private static java.util.List<org.bukkit.block.banner.Pattern> shieldsButtonPatterns() {
        java.util.List<org.bukkit.block.banner.Pattern> p = new ArrayList<>();
        p.add(pat("stripe_top",    org.bukkit.DyeColor.ORANGE));
        p.add(pat("stripe_bottom", org.bukkit.DyeColor.BLACK));
        p.add(pat("flower",        org.bukkit.DyeColor.YELLOW));
        p.add(pat("rhombus",       org.bukkit.DyeColor.ORANGE));
        p.add(pat("skull",         org.bukkit.DyeColor.ORANGE));
        p.add(pat("circle",        org.bukkit.DyeColor.YELLOW));
        p.add(pat("border",        org.bukkit.DyeColor.BLACK));
        p.add(pat("flower",        org.bukkit.DyeColor.YELLOW));
        p.add(pat("circle",        org.bukkit.DyeColor.BLACK));
        p.add(pat("triangle_top",  org.bukkit.DyeColor.BLACK));
        return p;
    }

    private static org.bukkit.block.banner.Pattern pat(String key, org.bukkit.DyeColor color) {
        org.bukkit.block.banner.PatternType type = org.bukkit.Registry.BANNER_PATTERN
                .get(org.bukkit.NamespacedKey.minecraft(key));
        return new org.bukkit.block.banner.Pattern(color, type);
    }

    private ItemStack mainButton(Player player, String perm, Material mat,
                                 String nameKey, String loreKey) {
        if (player.hasPermission(perm)) {
            return new ItemBuilder(mat)
                    .name(plugin.getConfigManager().cosStr(nameKey))
                    .lore(plugin.getConfigManager().cosList(loreKey))
                    .flag(org.bukkit.inventory.ItemFlag.values())
                    .build();
        }
        return new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().cosStr("main.locked-name"))
                .lore(plugin.getConfigManager().cosList("main.locked-lore"))
                .build();
    }

    /** Shields shown per category screen (slots 0..44). */
    public static final int SHIELDS_PER_CATEGORY = CosmeticsManager.SHIELDS_PER_CATEGORY;

    /** uuid → currently-viewed shield category id, so click handlers can map slots. */
    private final Map<UUID, String> shieldsCategory = new java.util.concurrent.ConcurrentHashMap<>();
    /** uuid → currently-viewed shield page (0-based). Reset to 0 on category switch. */
    private final Map<UUID, Integer> shieldsPage = new java.util.concurrent.ConcurrentHashMap<>();

    public String shieldsCategory(UUID uuid) {
        return shieldsCategory.getOrDefault(uuid, CosmeticsManager.SHIELD_CATEGORIES[0]);
    }

    /** The player's current 0-based shield page. */
    public int shieldsPage(UUID uuid) { return shieldsPage.getOrDefault(uuid, 0); }

    /** Total pages for a category (at least 1). */
    public int shieldsPageCount(String category) {
        int n = plugin.getCosmeticsManager().getShieldIndices(category).size();
        return Math.max(1, (int) Math.ceil(n / (double) SHIELDS_PER_CATEGORY));
    }

    /**
     * The stored shield index shown at on-screen {@code slot} (0..44) for the
     * player's active category AND page, or -1 if that slot is empty.
     */
    public int shieldStoreIndexAt(UUID uuid, int slot) {
        if (slot < 0 || slot >= SHIELDS_PER_CATEGORY) return -1;
        java.util.List<Integer> idx = plugin.getCosmeticsManager()
                .getShieldIndices(shieldsCategory(uuid));
        int global = shieldsPage(uuid) * SHIELDS_PER_CATEGORY + slot;
        return global < idx.size() ? idx.get(global) : -1;
    }

    public void openShields(Player player) {
        openShields(player, shieldsCategory(player.getUniqueId()), shieldsPage(player.getUniqueId()));
    }

    /** Opens the category at page 0 (used when switching category). */
    public void openShields(Player player, String category) {
        openShields(player, category, 0);
    }

    public void openShields(Player player, String category, int page) {
        category = normalizeShieldCategory(category);
        Inventory inv = Bukkit.createInventory(player, SHIELDS_SIZE, shieldsTitle());
        CosmeticsManager mgr = plugin.getCosmeticsManager();
        UUID uid = player.getUniqueId();
        int selectedSlot = mgr.getSelection(uid).shieldSlot;

        java.util.List<Integer> indices = mgr.getShieldIndices(category);
        int pageCount = Math.max(1, (int) Math.ceil(indices.size() / (double) SHIELDS_PER_CATEGORY));
        // Clamp the requested page into range.
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;
        int base = page * SHIELDS_PER_CATEGORY;

        // Content: this page's shields, packed left-to-right into slots 0..44.
        for (int slot = 0; slot < SHIELDS_PER_CATEGORY; slot++) {
            int global = base + slot;
            if (global >= indices.size()) break;
            int storeIndex = indices.get(global);
            ItemStack s = mgr.getShields().get(storeIndex);
            if (s != null) inv.setItem(slot, decorate(s, storeIndex == selectedSlot));
        }

        // Reset (BARRIER) + gray-glass filler.
        inv.setItem(SHIELDS_RESET_SLOT, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().cosStr("sub.reset-name")).build());
        ItemStack glass = new ItemBuilder(Material.GRAY_STAINED_GLASS_PANE)
                .name(plugin.getConfigManager().cosStr("filler-name")).build();
        for (int g : SHIELDS_GLASS_SLOTS) inv.setItem(g, glass);

        // Page arrows — only shown when that direction exists; otherwise the slot
        // stays plain glass so the row still looks complete.
        if (page > 0) {
            inv.setItem(SHIELDS_PREV_SLOT, pageArrow("sub.prev-name",
                    "shields-prev-cmd", SHIELDS_PREV_CMD_DEFAULT));
        } else {
            inv.setItem(SHIELDS_PREV_SLOT, glass);
        }
        if (page < pageCount - 1) {
            inv.setItem(SHIELDS_NEXT_SLOT, pageArrow("sub.next-name",
                    "shields-next-cmd", SHIELDS_NEXT_CMD_DEFAULT));
        } else {
            inv.setItem(SHIELDS_NEXT_SLOT, glass);
        }

        // Category buttons — each renders its own preview shield; the active one
        // is enchant-glowed.
        inv.setItem(SHIELDS_CAT1_SLOT, categoryButton("plain",  category, "cat-plain-name"));
        inv.setItem(SHIELDS_CAT2_SLOT, categoryButton("banner", category, "cat-banner-name"));
        inv.setItem(SHIELDS_CAT3_SLOT, categoryButton("flag",   category, "cat-flag-name"));

        // Red dye → back to main cosmetics menu.
        inv.setItem(SHIELDS_BACK_SLOT, new ItemBuilder(Material.RED_DYE)
                .name(plugin.getConfigManager().cosStr("sub.exit-name")).build());

        player.openInventory(inv);
        shieldsCategory.put(uid, category);
        shieldsPage.put(uid, page);
    }

    /** Builds a page-arrow item with a custom-model-data for the resource pack. */
    private ItemStack pageArrow(String nameKey, String cmdKey, int cmdDefault) {
        int cmd = plugin.getConfigManager().cosInt(cmdKey, cmdDefault);
        ItemBuilder b = new ItemBuilder(Material.ARROW)
                .name(plugin.getConfigManager().cosStr(nameKey));
        if (cmd > 0) b.customModelData(cmd);
        return b.build();
    }

    private String normalizeShieldCategory(String category) {
        for (String c : CosmeticsManager.SHIELD_CATEGORIES) if (c.equals(category)) return category;
        return CosmeticsManager.SHIELD_CATEGORIES[0];
    }

    /** Builds the preview-shield category button; enchant-glows the active one. */
    private ItemStack categoryButton(String cat, String activeCat, String nameKey) {
        ItemStack it = new ItemStack(Material.SHIELD);
        ItemMeta meta = it.getItemMeta();
        if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta bsm
                && bsm.getBlockState() instanceof org.bukkit.block.Banner banner) {
            switch (cat) {
                case "plain" -> banner.setBaseColor(org.bukkit.DyeColor.ORANGE);
                case "banner" -> {
                    banner.setBaseColor(org.bukkit.DyeColor.LIME);
                    banner.setPatterns(java.util.List.of(catPat("creeper", org.bukkit.DyeColor.BLACK)));
                }
                case "flag" -> {
                    banner.setBaseColor(org.bukkit.DyeColor.BLACK);
                    banner.setPatterns(java.util.List.of(
                            catPat("stripe_right",  org.bukkit.DyeColor.YELLOW),
                            catPat("stripe_center", org.bukkit.DyeColor.RED)));
                }
            }
            banner.update();
            bsm.setBlockState(banner);
        }
        if (meta != null) {
            meta.displayName(ColorUtil.component(plugin.getConfigManager().cosStr(nameKey)));
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            if (cat.equals(activeCat)) {
                meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static org.bukkit.block.banner.Pattern catPat(String key, org.bukkit.DyeColor color) {
        return new org.bukkit.block.banner.Pattern(color,
                org.bukkit.Registry.BANNER_PATTERN.get(org.bukkit.NamespacedKey.minecraft(key)));
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
        ItemMeta meta = out.getItemMeta();
        if (meta == null) return out;

        // Hide the banner-pattern / attribute tooltip on every rendered shield so
        // the menu icon stays clean (not the long "orange stripe, black border…"
        // list vanilla shows for a patterned shield).
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.values());

        if (selected) {
            // Selected shield: enchant-glow + name suffix "&f- &a✔ O'rnatilgan".
            String selectedSuffix = plugin.getConfigManager().cosStr("selected-suffix");

            Component current = meta.displayName();
            if (current == null) {
                current = Component.text(out.getType().name().toLowerCase().replace('_', ' '));
            }
            Component suffixed = current.append(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(ColorUtil.color(selectedSuffix)));
            meta.displayName(suffixed);

            meta.addEnchant(org.bukkit.enchantments.Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        }

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
