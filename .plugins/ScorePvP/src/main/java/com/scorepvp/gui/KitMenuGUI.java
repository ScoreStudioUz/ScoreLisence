package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PvPMode;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Kit selection menu — opened by right-clicking the "Kit sozlamalari" hotbar
 * item (slot 8). Lays out every PvP mode as its configured icon material, plus
 * a dedicated "vanilla" slot. Clicking a mode opens that mode's personal kit
 * editor ({@link KitEditorGUI}); clicking vanilla teleports to the editor room
 * (the legacy flow), which is handled by the click listener.
 *
 * Layout is read from item_menus/kit_menu.yml (falls back to sane defaults).
 */
public class KitMenuGUI {

    private String title;
    private int guiSize;
    private Material fillerMaterial;
    private String fillerName;
    private int[] modeSlots;
    private List<String> modeLore;
    private int vanillaSlot;
    private Material vanillaMaterial;
    private String vanillaName;
    private List<String> vanillaLore;
    private boolean fillEmpty;

    private final ScorePvP plugin;

    public KitMenuGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "item_menus/kit_menu.yml");
        if (!file.exists()) {
            applyDefaults();
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        title = ColorUtil.color(cfg.getString("title", "&6Kit tanlash"));
        guiSize = cfg.getInt("size", 45);
        try { fillerMaterial = Material.valueOf(cfg.getString("filler.material", "GRAY_STAINED_GLASS_PANE")); }
        catch (IllegalArgumentException e) { fillerMaterial = Material.GRAY_STAINED_GLASS_PANE; }
        fillerName = cfg.getString("filler.name", "&r");
        // Bo'sh slotlarni shisha bilan to'ldirmaslik — chinakam bo'sh qoladi.
        fillEmpty = cfg.getBoolean("fill-empty", false);

        // Explicit mode-slot list takes priority; otherwise fall back to range.
        List<Integer> explicit = cfg.getIntegerList("mode-slots.list");
        if (!explicit.isEmpty()) {
            modeSlots = explicit.stream().mapToInt(Integer::intValue).toArray();
        } else {
            int start = cfg.getInt("mode-slots.start", 10);
            int end = cfg.getInt("mode-slots.end", 25);
            List<Integer> slots = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                int col = i % 9;
                if (col == 0 || col == 8) continue;
                slots.add(i);
            }
            modeSlots = slots.stream().mapToInt(Integer::intValue).toArray();
        }
        modeLore = cfg.getStringList("mode-lore");
        if (modeLore.isEmpty()) modeLore = List.of("&7Kitni tahrirlash uchun bosing");

        vanillaSlot = cfg.getInt("vanilla.slot", 40);
        try { vanillaMaterial = Material.valueOf(cfg.getString("vanilla.material", "DIAMOND")); }
        catch (IllegalArgumentException e) { vanillaMaterial = Material.DIAMOND; }
        vanillaName = cfg.getString("vanilla.name", "&b&lVanilla");
        vanillaLore = cfg.getStringList("vanilla.lore");
        if (vanillaLore.isEmpty()) vanillaLore = List.of("&7Vanilla xonasiga o'tish");
    }

    private void applyDefaults() {
        title = ColorUtil.color("&6Kit tanlash");
        guiSize = 45;
        fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
        fillerName = "&r";
        fillEmpty = false;
        modeSlots = new int[]{10,11,12,13,14,15,16,19,20,21,22,23,24,25};
        modeLore = List.of("&7Kitni tahrirlash uchun bosing");
        vanillaSlot = 40;
        vanillaMaterial = Material.DIAMOND;
        vanillaName = "&b&lVanilla";
        vanillaLore = List.of("&7Vanilla xonasiga o'tish");
    }

    public String getTitle() { return title; }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, guiSize, title);
        if (fillEmpty) {
            ItemStack glass = new ItemBuilder(fillerMaterial).name(fillerName).build();
            for (int i = 0; i < guiSize; i++) inv.setItem(i, glass);
        }

        int idx = 0;
        for (PvPMode mode : plugin.getConfigManager().getModes()) {
            if (idx >= modeSlots.length) break;
            if (!mode.isEnabled()) continue;
            // Vanilla has its own dedicated slot below — don't also list it as a
            // regular mode, otherwise it appears twice.
            if (isVanillaMode(mode)) continue;
            inv.setItem(modeSlots[idx], buildModeSlot(mode));
            idx++;
        }

        // Dedicated vanilla slot (legacy editor-room teleport).
        if (vanillaSlot >= 0 && vanillaSlot < guiSize) {
            inv.setItem(vanillaSlot, new ItemBuilder(vanillaMaterial)
                    .name(vanillaName)
                    .lore(vanillaLore)
                    .build());
        }

        player.openInventory(inv);
    }

    private ItemStack buildModeSlot(PvPMode mode) {
        ItemBuilder b = new ItemBuilder(mode.getIcon())
                .name(mode.getDisplayName())
                .lore(modeLore);
        if (mode.isIconEnchanted()) b.glow();
        return b.build();
    }

    /** True if the clicked slot is the vanilla teleport slot. */
    public boolean isVanillaSlot(int slot) {
        return slot == vanillaSlot;
    }

    /**
     * Resolves the mode id for a clicked slot, or null if the slot is filler /
     * the vanilla slot / out of range.
     */
    public String getModeAtSlot(int slot) {
        int mi = 0;
        for (PvPMode mode : plugin.getConfigManager().getModes()) {
            if (mi >= modeSlots.length) break;
            if (!mode.isEnabled()) continue;
            if (isVanillaMode(mode)) continue;
            if (modeSlots[mi] == slot) return mode.getId();
            mi++;
        }
        return null;
    }

    /** True if this mode is the dedicated vanilla mode (shown only in its own slot). */
    private static boolean isVanillaMode(PvPMode mode) {
        return "vanilla".equalsIgnoreCase(mode.getId());
    }
}
