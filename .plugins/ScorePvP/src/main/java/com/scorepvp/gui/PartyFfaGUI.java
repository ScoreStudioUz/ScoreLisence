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
 * Party FFA mode selection GUI.
 * Only the party leader can interact; opens via /party ffa.
 * Reads layout from item_menus/party.yml.
 */
public class PartyFfaGUI {

    private String title;
    private int guiSize;
    private Material fillerMaterial;
    private String fillerName;
    private int[] modeSlots;
    private Material unavailableMaterial;
    private String unavailableNameSuffix;
    private List<String> unavailableLore;

    private final ScorePvP plugin;

    public PartyFfaGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "item_menus/party.yml");
        if (!file.exists()) {
            title = ColorUtil.color("&0Выбор режима Party FFA");
            guiSize = 36;
            fillerMaterial = Material.BLUE_STAINED_GLASS_PANE;
            fillerName = "&r";
            modeSlots = new int[]{10,11,12,13,14,15,16,19,20,21,22,23,24,25};
            unavailableMaterial = Material.RED_STAINED_GLASS_PANE;
            unavailableNameSuffix = " &8[&cНедоступно]";
            unavailableLore = List.of("&cТребуется версия: &e{version}+");
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        title = ColorUtil.color(cfg.getString("title", "&0Выбор режима Party FFA"));
        guiSize = cfg.getInt("size", 36);
        try { fillerMaterial = Material.valueOf(cfg.getString("filler.material", "BLUE_STAINED_GLASS_PANE")); }
        catch (IllegalArgumentException e) { fillerMaterial = Material.BLUE_STAINED_GLASS_PANE; }
        fillerName = cfg.getString("filler.name", "&r");
        int start = cfg.getInt("mode-slots.start", 10);
        int end = cfg.getInt("mode-slots.end", 25);
        List<Integer> slots = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            int col = i % 9;
            if (col == 0 || col == 8) continue;
            slots.add(i);
        }
        modeSlots = slots.stream().mapToInt(Integer::intValue).toArray();
        try { unavailableMaterial = Material.valueOf(cfg.getString("unavailable.material", "RED_STAINED_GLASS_PANE")); }
        catch (IllegalArgumentException e) { unavailableMaterial = Material.RED_STAINED_GLASS_PANE; }
        unavailableNameSuffix = cfg.getString("unavailable.name-suffix", " &8[&cНедоступно]");
        unavailableLore = cfg.getStringList("unavailable.lore");
    }

    public String getTitle() { return title; }

    public void open(Player leader) {
        Inventory inv = Bukkit.createInventory(null, guiSize, title);

        ItemStack glass = new ItemBuilder(fillerMaterial).name(fillerName).build();
        for (int i = 0; i < guiSize; i++) inv.setItem(i, glass);
        for (int slot : modeSlots) inv.setItem(slot, null);

        List<PvPMode> modes = plugin.getConfigManager().getModes();
        int idx = 0;
        for (PvPMode mode : modes) {
            if (idx >= modeSlots.length) break;
            if (!mode.isEnabled()) continue;

            // Check version compatibility for leader
            if (!plugin.getClientVersionManager().canPlay(leader, mode)) {
                List<String> lore = new ArrayList<>();
                for (String line : unavailableLore) {
                    lore.add(line.replace("{version}", mode.getMinClientVersion()));
                }
                inv.setItem(modeSlots[idx++], new ItemBuilder(unavailableMaterial)
                        .name(mode.getDisplayName() + unavailableNameSuffix)
                        .lore(lore).build());
                continue;
            }

            ItemBuilder b = new ItemBuilder(mode.getIcon())
                    .name(mode.getDisplayName())
                    .lore(plugin.getConfigManager().rawMsg("gui.click-to-start"));
            if (mode.isIconEnchanted()) b.glow();
            inv.setItem(modeSlots[idx++], b.build());
        }

        leader.openInventory(inv);
    }

    /** Returns mode id for clicked slot, null if glass/empty. */
    public String getModeAtSlot(int slot) {
        for (int i = 0; i < modeSlots.length; i++) {
            if (modeSlots[i] == slot) {
                int mi = 0;
                for (PvPMode m : plugin.getConfigManager().getModes()) {
                    if (!m.isEnabled()) continue;
                    if (mi == i) return m.getId();
                    mi++;
                }
                return null;
            }
        }
        return null;
    }

    public boolean isThisGUI(String guiTitle) {
        return guiTitle.equals(title);
    }
}
