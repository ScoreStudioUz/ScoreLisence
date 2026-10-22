package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /tags — TierTag selector.
 *
 * <p>Two screens, both 27-slot:
 * <ol>
 *   <li>{@link #openSelect(Player)} — a list of the configured display modes
 *       (sword/smp/…); clicking one whose tier the player HOLDS opens the
 *       confirm screen. Clicking a mode they have no tier in closes the menu
 *       with a "villager unhappy" sound + message. The reset button clears the
 *       chosen tag (falls back to highest tier).</li>
 *   <li>{@link #openConfirm(Player, String)} — shows the player's own skin head
 *       with the chosen mode's tier; confirm saves it, cancel returns.</li>
 * </ol>
 *
 * Layout / decoration is read from item_menus/tags_select.yml and
 * item_menus/tags_confirm.yml (sane defaults if absent).
 */
public class TierTagGUI {

    private final ScorePvP plugin;

    // Select screen
    private String selectTitle;
    private int selectSize;
    private Material selectFiller;
    private String selectFillerName;
    private int selectInfoSlot;
    private Material selectInfoMaterial;
    private String selectInfoName;
    private List<String> selectInfoLore;
    private int selectResetSlot;
    private Material selectResetMaterial;
    private String selectResetName;
    private List<String> selectResetLore;
    /** Ordered: GUI slot → mode id. */
    private final Map<Integer, String> selectModeSlots = new LinkedHashMap<>();
    private List<String> selectModeLore;

    // Confirm screen
    private String confirmTitle;
    private int confirmSize;
    private Material confirmFiller;
    private String confirmFillerName;
    private int confirmInfoSlot;
    private Material confirmInfoMaterial;
    private String confirmInfoName;
    private int confirmHeadSlot;
    private String confirmHeadName;
    private int confirmCancelSlot;
    private Material confirmCancelMaterial;
    private String confirmCancelName;
    private int confirmAcceptSlot;
    private Material confirmAcceptMaterial;
    private String confirmAcceptName;
    private List<Integer> confirmFillerSlots;

    /** uuid → mode currently being confirmed. */
    private final Map<UUID, String> confirming = new ConcurrentHashMap<>();

    /** uuid → which screen they currently have open (1 = select, 2 = confirm). */
    private static final int SCREEN_SELECT = 1, SCREEN_CONFIRM = 2;
    private final Map<UUID, Integer> screen = new ConcurrentHashMap<>();

    public TierTagGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        loadSelect();
        loadConfirm();
    }

    private void loadSelect() {
        selectModeSlots.clear();
        File f = new File(plugin.getDataFolder(), "item_menus/tags_select.yml");
        YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        selectTitle  = ColorUtil.color(cfg.getString("title", "&8TierTag"));
        selectSize   = clampSize(cfg.getInt("size", 27));
        selectFiller = mat(cfg.getString("filler.material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        selectFillerName = cfg.getString("filler.name", " ");

        selectInfoSlot     = cfg.getInt("info.slot", 4);
        selectInfoMaterial = mat(cfg.getString("info.material", "PINK_CANDLE"), Material.PINK_CANDLE);
        selectInfoName     = cfg.getString("info.name", "&eTierTag &e♠");
        selectInfoLore     = cfg.getStringList("info.lore");
        if (selectInfoLore.isEmpty())
            selectInfoLore = List.of("&6Spawnda&f va &6Tabda&f darajangizni ko'rsating");

        selectResetSlot     = cfg.getInt("reset.slot", 26);
        selectResetMaterial = mat(cfg.getString("reset.material", "RED_DYE"), Material.RED_DYE);
        selectResetName     = cfg.getString("reset.name", "&fTiklash");
        selectResetLore     = cfg.getStringList("reset.lore");
        if (selectResetLore.isEmpty())
            selectResetLore = List.of("&7Darajani tiklash uchun bosing");

        selectModeLore = cfg.getStringList("mode-lore");

        // mode entries: list of {mode, slot, material, name}
        ConfigurationSection modes = cfg.getConfigurationSection("modes");
        if (modes != null) {
            for (String key : modes.getKeys(false)) {
                ConfigurationSection m = modes.getConfigurationSection(key);
                if (m == null) continue;
                int slot = m.getInt("slot", -1);
                String modeId = m.getString("mode", key);
                if (slot >= 0) selectModeSlots.put(slot, modeId);
            }
        }
        if (selectModeSlots.isEmpty()) seedDefaultSelectModes();
    }

    /** Default 7 display modes if the config has none. */
    private void seedDefaultSelectModes() {
        selectModeSlots.put(10, "sword");
        selectModeSlots.put(11, "smp");
        selectModeSlots.put(12, "netherite");
        selectModeSlots.put(13, "vanilla");
        selectModeSlots.put(14, "uhc");
        selectModeSlots.put(15, "mace");
        selectModeSlots.put(16, "axe");
    }

    private void loadConfirm() {
        File f = new File(plugin.getDataFolder(), "item_menus/tags_confirm.yml");
        YamlConfiguration cfg = f.exists() ? YamlConfiguration.loadConfiguration(f) : new YamlConfiguration();
        confirmTitle  = ColorUtil.color(cfg.getString("title", "&8TierTag"));
        confirmSize   = clampSize(cfg.getInt("size", 27));
        confirmFiller = mat(cfg.getString("filler.material", "GRAY_STAINED_GLASS_PANE"), Material.GRAY_STAINED_GLASS_PANE);
        confirmFillerName = cfg.getString("filler.name", " ");
        confirmFillerSlots = cfg.getIntegerList("filler.slots");

        confirmInfoSlot     = cfg.getInt("info.slot", 4);
        confirmInfoMaterial = mat(cfg.getString("info.material", "BAMBOO_HANGING_SIGN"), Material.BAMBOO_HANGING_SIGN);
        confirmInfoName     = cfg.getString("info.name", "&7{mode} darajangiz:");

        confirmHeadSlot = cfg.getInt("head.slot", 13);
        confirmHeadName = cfg.getString("head.name", "&f{player} &7| {tier}");

        confirmCancelSlot     = cfg.getInt("cancel.slot", 12);
        confirmCancelMaterial = mat(cfg.getString("cancel.material", "RED_STAINED_GLASS_PANE"), Material.RED_STAINED_GLASS_PANE);
        confirmCancelName     = cfg.getString("cancel.name", "&cBekor qilish ☒");

        confirmAcceptSlot     = cfg.getInt("accept.slot", 14);
        confirmAcceptMaterial = mat(cfg.getString("accept.material", "LIME_STAINED_GLASS_PANE"), Material.LIME_STAINED_GLASS_PANE);
        confirmAcceptName     = cfg.getString("accept.name", "&aTasdiqlash ☑");
    }

    // ── Titles (listener routing) ──────────────────────────────────────────────

    public String selectTitle()  { return selectTitle; }
    public String confirmTitle() { return confirmTitle; }

    public int selectResetSlot()  { return selectResetSlot; }
    public int confirmCancelSlot(){ return confirmCancelSlot; }
    public int confirmAcceptSlot(){ return confirmAcceptSlot; }

    /** Mode id at a select-screen slot, or null. */
    public String modeAtSelectSlot(int slot) { return selectModeSlots.get(slot); }

    public String getConfirming(UUID uuid) { return confirming.get(uuid); }
    public void clearConfirming(UUID uuid)  { confirming.remove(uuid); }

    /** True if this player currently has the select screen open. */
    public boolean isSelectScreen(UUID uuid)  { return screen.getOrDefault(uuid, 0) == SCREEN_SELECT; }
    /** True if this player currently has the confirm screen open. */
    public boolean isConfirmScreen(UUID uuid) { return screen.getOrDefault(uuid, 0) == SCREEN_CONFIRM; }
    /** Called on inventory close to forget which screen was open. */
    public void clearScreen(UUID uuid) { screen.remove(uuid); }

    // ── Select screen ───────────────────────────────────────────────────────────

    public void openSelect(Player player) {
        Inventory inv = Bukkit.createInventory(null, selectSize, selectTitle);
        ItemStack filler = new ItemBuilder(selectFiller).name(selectFillerName).build();
        for (int i = 0; i < selectSize; i++) inv.setItem(i, filler);

        if (inSize(selectInfoSlot, selectSize)) {
            inv.setItem(selectInfoSlot, new ItemBuilder(selectInfoMaterial)
                    .name(selectInfoName).lore(selectInfoLore).build());
        }

        for (Map.Entry<Integer, String> e : selectModeSlots.entrySet()) {
            int slot = e.getKey();
            if (!inSize(slot, selectSize)) continue;
            var mode = plugin.getConfigManager().getMode(e.getValue());
            ItemStack icon;
            if (mode != null) {
                ItemBuilder b = new ItemBuilder(mode.getIcon()).name(mode.getDisplayName());
                if (!selectModeLore.isEmpty()) b.lore(selectModeLore);
                if (mode.isIconEnchanted()) b.glow();
                icon = b.build();
            } else {
                icon = new ItemBuilder(Material.BARRIER).name("&c" + e.getValue()).build();
            }
            inv.setItem(slot, icon);
        }
        player.openInventory(inv);
        // Set AFTER openInventory: opening closes the previous view and fires
        // InventoryCloseEvent → clearScreen, which would otherwise wipe this.
        screen.put(player.getUniqueId(), SCREEN_SELECT);
    }

    // ── Confirm screen ──────────────────────────────────────────────────────────

    public void openConfirm(Player player, String modeId) {
        confirming.put(player.getUniqueId(), modeId);
        Inventory inv = Bukkit.createInventory(null, confirmSize, confirmTitle);

        ItemStack filler = new ItemBuilder(confirmFiller).name(confirmFillerName).build();
        if (confirmFillerSlots != null && !confirmFillerSlots.isEmpty()) {
            for (int s : confirmFillerSlots) if (inSize(s, confirmSize)) inv.setItem(s, filler);
        } else {
            for (int i = 0; i < confirmSize; i++) inv.setItem(i, filler);
        }

        var mode = plugin.getConfigManager().getMode(modeId);
        String modeName = mode != null ? mode.getDisplayName() : modeId;
        String tier = plugin.getTierManager().getTier(player.getUniqueId(), modeId);
        String tierDisplay = tier == null ? plugin.getConfigManager().rawStr("profile.unranked")
                : plugin.getConfigManager().getTierDisplay(tier, modeId);

        if (inSize(confirmInfoSlot, confirmSize)) {
            inv.setItem(confirmInfoSlot, new ItemBuilder(confirmInfoMaterial)
                    .name(confirmInfoName.replace("{mode}", modeName)).build());
        }
        if (inSize(confirmHeadSlot, confirmSize)) {
            inv.setItem(confirmHeadSlot, playerHead(player,
                    confirmHeadName.replace("{player}", player.getName())
                            .replace("{tier}", tierDisplay)));
        }
        if (inSize(confirmCancelSlot, confirmSize)) {
            inv.setItem(confirmCancelSlot, new ItemBuilder(confirmCancelMaterial).name(confirmCancelName).build());
        }
        if (inSize(confirmAcceptSlot, confirmSize)) {
            inv.setItem(confirmAcceptSlot, new ItemBuilder(confirmAcceptMaterial).name(confirmAcceptName).build());
        }
        player.openInventory(inv);
        // Set AFTER openInventory (see openSelect): the open closes the previous
        // select view and fires InventoryCloseEvent → clearScreen.
        screen.put(player.getUniqueId(), SCREEN_CONFIRM);
    }

    private ItemStack playerHead(Player player, String name) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(player);
            meta.setDisplayName(ColorUtil.color(name));
            head.setItemMeta(meta);
        }
        return head;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────

    private static int clampSize(int s) {
        if (s < 9) return 9;
        if (s > 54) return 54;
        return (s / 9) * 9;
    }

    private static boolean inSize(int slot, int size) { return slot >= 0 && slot < size; }

    private Material mat(String name, Material def) {
        if (name == null) return def;
        try { return Material.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return def; }
    }
}
