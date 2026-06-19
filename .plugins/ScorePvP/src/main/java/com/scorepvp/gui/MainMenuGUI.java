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
 * Main PvP mode selection menu.
 * Reads layout from item_menus/main_menu.yml.
 */
public class MainMenuGUI {

    private String title;
    private int guiSize;
    private Material fillerMaterial;
    private String fillerName;
    private int[] modeSlots;
    private Material unavailableMaterial;
    private boolean useOriginalIcon;
    private String unavailableNameSuffix;
    private List<String> unavailableLore;

    private final ScorePvP plugin;

    public MainMenuGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "item_menus/main_menu.yml");
        if (!file.exists()) {
            title = ColorUtil.color("&0Выбор режима PvP");
            guiSize = 36;
            fillerMaterial = Material.BLUE_STAINED_GLASS_PANE;
            fillerName = "&r";
            modeSlots = new int[]{10,11,12,13,14,15,16,19,20,21,22,23,24,25};
            unavailableMaterial = Material.RED_STAINED_GLASS_PANE;
            useOriginalIcon = false;
            unavailableNameSuffix = " &8[&cНедоступно]";
            unavailableLore = List.of("&cТребуется версия: &e{version}+", "&7Ваша версия: &c{your_version}");
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        title = ColorUtil.color(cfg.getString("title", "&0Выбор режима PvP"));
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
        useOriginalIcon = cfg.getBoolean("unavailable.use-original-icon", false);
        unavailableNameSuffix = cfg.getString("unavailable.name-suffix", " &8[&cНедоступно]");
        unavailableLore = cfg.getStringList("unavailable.lore");
    }

    public String getTitle() { return title; }

    public void open(Player player) {
        // Block if in party
        if (plugin.getPartyManager().isInParty(player.getUniqueId())) {
            player.sendMessage(plugin.getConfigManager().msg("party.pvp-blocked"));
            return;
        }

        Inventory inv = Bukkit.createInventory(null, guiSize, title);
        ItemStack glass = new ItemBuilder(fillerMaterial).name(fillerName).build();

        for (int i = 0; i < guiSize; i++) inv.setItem(i, glass);
        for (int slot : modeSlots) inv.setItem(slot, null);

        String playerVer = plugin.getClientVersionManager().getClientVersion(player);

        List<PvPMode> modes = plugin.getConfigManager().getModes();
        int idx = 0;
        for (PvPMode mode : modes) {
            if (idx >= modeSlots.length) break;
            if (!mode.isEnabled()) continue;

            boolean canPlay = plugin.getClientVersionManager().canPlay(player, mode);
            inv.setItem(modeSlots[idx], canPlay
                    ? buildAvailableSlot(mode)
                    : buildUnavailableSlot(mode, playerVer));
            idx++;
        }

        player.openInventory(inv);
    }

    // ── Slot resolution ───────────────────────────────────────────────────────

    /**
     * Returns mode id for the clicked slot (null if glass or no mode).
     * Also returns null if the mode is version-incompatible for this player.
     */
    public String getModeAtSlot(Player player, int slot) {
        int mi = 0;
        for (PvPMode mode : plugin.getConfigManager().getModes()) {
            if (mi >= modeSlots.length) break;
            if (!mode.isEnabled()) continue;
            if (modeSlots[mi] == slot) {
                if (!plugin.getClientVersionManager().canPlay(player, mode)) return null;
                return mode.getId();
            }
            mi++;
        }
        return null;
    }

    /** Static version kept for GUIClickListener compatibility. */
    public static String getModeAtSlot(int slot, List<PvPMode> modes) {
        int[] defaultSlots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25};
        int mi = 0;
        for (PvPMode mode : modes) {
            if (mi >= defaultSlots.length) break;
            if (!mode.isEnabled()) continue;
            if (defaultSlots[mi] == slot) return mode.getId();
            mi++;
        }
        return null;
    }

    public boolean isGlassSlot(int slot) {
        for (int s : modeSlots) if (s == slot) return false;
        return true;
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private ItemStack buildAvailableSlot(PvPMode mode) {
        int queued = plugin.getDuelManager().getQueueSize(mode.getId());
        List<String> lore = new ArrayList<>();
        lore.add(plugin.getConfigManager().rawStr("menu.mode-lore"));
        lore.add(plugin.getConfigManager().rawStr("menu.queue-count-lore")
                .replace("{count}", String.valueOf(queued)));
        ItemBuilder b = new ItemBuilder(mode.getIcon())
                .name(mode.getDisplayName())
                .lore(lore);
        if (mode.isIconEnchanted()) b.glow();
        return b.build();
    }

    private ItemStack buildUnavailableSlot(PvPMode mode, String playerVer) {
        Material mat = useOriginalIcon ? mode.getIcon() : unavailableMaterial;
        List<String> lore = new ArrayList<>();
        for (String line : unavailableLore) {
            lore.add(line.replace("{version}", mode.getMinClientVersion())
                    .replace("{your_version}", playerVer));
        }
        return new ItemBuilder(mat)
                .name(mode.getDisplayName() + ColorUtil.color(unavailableNameSuffix))
                .lore(lore)
                .build();
    }
}
