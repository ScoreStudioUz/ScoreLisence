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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Duel flow — Step 1: challenger picks PvP mode (36 slots).
 * Title embeds target name so isThisGUI() can detect it.
 * Reads layout from item_menus/duel.yml.
 */
public class DuelModeGUI {

    private String titlePrefix;
    private int guiSize;
    private Material fillerMaterial;
    private String fillerName;
    private int[] fillerSlots; // null → fill whole menu as background
    private int[] modeSlots;
    private Material unavailableMaterial;
    private String unavailableNameSuffix;
    private List<String> unavailableLore;

    private final ScorePvP plugin;

    /** challenger uuid → target uuid */
    private final Map<UUID, UUID> sessions = new ConcurrentHashMap<>();

    public DuelModeGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "item_menus/duel.yml");
        if (!file.exists()) {
            titlePrefix = ColorUtil.color("&0Дуэль: ");
            guiSize = 36;
            fillerMaterial = Material.BLUE_STAINED_GLASS_PANE;
            fillerName = "&r";
            modeSlots = new int[]{10,11,12,13,14,15,16,19,20,21,22,23,24,25};
            unavailableMaterial = Material.RED_STAINED_GLASS_PANE;
            unavailableNameSuffix = " &8[&cНедоступно]";
            unavailableLore = List.of("&cТребуется версия: &e{version}+", "&7Ваша версия: &c{your_version}");
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String rawTitle = cfg.getString("title", "&0Выбор режима дуэли");
        // The title uses {player} placeholder — prefix is everything before {player}
        int placeholderIdx = rawTitle.indexOf("{player}");
        if (placeholderIdx >= 0) {
            titlePrefix = ColorUtil.color(rawTitle.substring(0, placeholderIdx));
        } else {
            titlePrefix = ColorUtil.color(rawTitle + " ");
        }
        guiSize = cfg.getInt("size", 36);
        try { fillerMaterial = Material.valueOf(cfg.getString("filler.material", "BLUE_STAINED_GLASS_PANE")); }
        catch (IllegalArgumentException e) { fillerMaterial = Material.BLUE_STAINED_GLASS_PANE; }
        fillerName = cfg.getString("filler.name", "&r");
        fillerSlots = readSlotList(cfg, "filler.slots");
        modeSlots = readModeSlots(cfg);
        try { unavailableMaterial = Material.valueOf(cfg.getString("unavailable.material", "RED_STAINED_GLASS_PANE")); }
        catch (IllegalArgumentException e) { unavailableMaterial = Material.RED_STAINED_GLASS_PANE; }
        unavailableNameSuffix = cfg.getString("unavailable.name-suffix", " &8[&cНедоступно]");
        unavailableLore = cfg.getStringList("unavailable.lore");
    }

    /**
     * Reads {@code mode-slots} either as a direct list ({@code [10,11,12]}) or as
     * the legacy {@code {start, end}} range (edge columns 0/8 skipped). The list
     * form lets admins place mode icons in any slot they like.
     */
    private int[] readModeSlots(YamlConfiguration cfg) {
        if (cfg.isList("mode-slots")) {
            List<Integer> l = cfg.getIntegerList("mode-slots");
            if (!l.isEmpty()) return l.stream().mapToInt(Integer::intValue).toArray();
        }
        int start = cfg.getInt("mode-slots.start", 10);
        int end   = cfg.getInt("mode-slots.end", 25);
        List<Integer> slots = new java.util.ArrayList<>();
        for (int i = start; i <= end; i++) {
            int col = i % 9;
            if (col == 0 || col == 8) continue; // skip edges
            slots.add(i);
        }
        return slots.stream().mapToInt(Integer::intValue).toArray();
    }

    /** Reads an int-list config key, or null if absent/empty. */
    private int[] readSlotList(YamlConfiguration cfg, String path) {
        if (!cfg.isList(path)) return null;
        List<Integer> l = cfg.getIntegerList(path);
        if (l.isEmpty()) return null;
        return l.stream().mapToInt(Integer::intValue).toArray();
    }

    public String getTitlePrefix() { return titlePrefix; }

    // ── Open ──────────────────────────────────────────────────────────────────

    public void open(Player challenger, Player target) {
        sessions.put(challenger.getUniqueId(), target.getUniqueId());

        Inventory inv = Bukkit.createInventory(null, guiSize,
                titlePrefix + target.getName());

        ItemStack glass = new ItemBuilder(fillerMaterial).name(fillerName).build();
        if (fillerSlots != null) {
            // Explicit filler slots — only these get glass, everything else stays empty.
            for (int slot : fillerSlots) if (slot >= 0 && slot < guiSize) inv.setItem(slot, glass);
        } else {
            // No explicit list — fill the whole menu as background, then clear mode slots.
            for (int i = 0; i < guiSize; i++) inv.setItem(i, glass);
            for (int slot : modeSlots) if (slot >= 0 && slot < guiSize) inv.setItem(slot, null);
        }

        List<PvPMode> modes = plugin.getConfigManager().getModes();
        int idx = 0;
        for (PvPMode mode : modes) {
            if (idx >= modeSlots.length) break;
            if (!mode.isEnabled()) continue;

            // Check if player's version supports this mode
            if (!plugin.getClientVersionManager().canPlay(challenger, mode)) {
                String playerVersion = plugin.getClientVersionManager().getClientVersion(challenger);
                List<String> lore = new java.util.ArrayList<>();
                for (String line : unavailableLore) {
                    lore.add(line.replace("{version}", mode.getMinClientVersion())
                            .replace("{your_version}", playerVersion));
                }
                inv.setItem(modeSlots[idx++], new ItemBuilder(unavailableMaterial)
                        .name(mode.getDisplayName() + unavailableNameSuffix)
                        .lore(lore).build());
                continue;
            }

            ItemBuilder b = new ItemBuilder(mode.getIcon())
                    .name(mode.getDisplayName())
                    .lore(plugin.getConfigManager().rawStr("menu.mode-lore"));
            if (mode.isIconEnchanted()) b.glow();
            inv.setItem(modeSlots[idx++], b.build());
        }

        challenger.openInventory(inv);
    }

    // ── Slot → mode ───────────────────────────────────────────────────────────

    public String getModeAtSlot(int slot) {
        int mi = 0;
        for (PvPMode mode : plugin.getConfigManager().getModes()) {
            if (mi >= modeSlots.length) break;
            if (!mode.isEnabled()) continue;
            if (modeSlots[mi] == slot) return mode.getId();
            mi++;
        }
        return null;
    }

    // ── Session helpers ───────────────────────────────────────────────────────

    public UUID getTarget(UUID challenger)   { return sessions.get(challenger); }
    public void clearTarget(UUID challenger) { sessions.remove(challenger); }

    public boolean isThisGUI(String title) {
        return title.startsWith(titlePrefix);
    }
}
