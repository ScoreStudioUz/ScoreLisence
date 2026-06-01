package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PlayerStats;
import com.scorepvp.model.PvPMode;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import com.scorepvp.model.Match;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Profile GUI — 36 slots (4×9)
 * Reads layout from item_menus/profile.yml.
 */
public class ProfileGUI {

    private String titleTemplate;
    private int guiSize;
    private Material fillerMaterial;
    private String fillerName;
    private int skullSlot;
    private String skullName;
    private List<String> skullLore;
    private int playtimeSlot;
    private Material playtimeMaterial;
    private String playtimeName;
    private List<String> playtimeLore;
    private int statsSlot;
    private Material statsMaterial;
    private String statsName;
    private List<String> statsLoreTpl;
    private List<String> modeLore;
    private int[] modeSlots;

    private final ScorePvP plugin;

    public ProfileGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "item_menus/profile.yml");
        if (!file.exists()) {
            applyDefaults();
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        applyDefaults();
        titleTemplate = cfg.getString("title", titleTemplate);
        guiSize = cfg.getInt("size", guiSize);
        try { fillerMaterial = Material.valueOf(cfg.getString("filler.material", fillerMaterial.name())); }
        catch (IllegalArgumentException ignored) {}
        fillerName = cfg.getString("filler.name", fillerName);
        skullSlot = cfg.getInt("skull.slot", skullSlot);
        skullName = cfg.getString("skull.name", skullName);
        if (cfg.contains("skull.lore")) skullLore = cfg.getStringList("skull.lore");
        playtimeSlot = cfg.getInt("playtime.slot", playtimeSlot);
        try { playtimeMaterial = Material.valueOf(cfg.getString("playtime.material", playtimeMaterial.name())); }
        catch (IllegalArgumentException ignored) {}
        playtimeName = cfg.getString("playtime.name", playtimeName);
        if (cfg.contains("playtime.lore")) playtimeLore = cfg.getStringList("playtime.lore");
        statsSlot = cfg.getInt("stats.slot", statsSlot);
        try { statsMaterial = Material.valueOf(cfg.getString("stats.material", statsMaterial.name())); }
        catch (IllegalArgumentException ignored) {}
        statsName = cfg.getString("stats.name", statsName);
        if (cfg.contains("stats.lore")) statsLoreTpl = cfg.getStringList("stats.lore");
        if (cfg.contains("mode-lore")) modeLore = cfg.getStringList("mode-lore");
        List<Integer> slots = cfg.getIntegerList("mode-slots");
        if (!slots.isEmpty()) modeSlots = slots.stream().mapToInt(Integer::intValue).toArray();
    }

    private void applyDefaults() {
        titleTemplate = "&0Профиль: &b{player}";
        guiSize = 36;
        fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
        fillerName = "&r";
        skullSlot = 4;   skullName = "&b{player}";   skullLore = null;
        playtimeSlot = 3; playtimeMaterial = Material.CLOCK; playtimeName = "&eВремя в игре"; playtimeLore = null;
        statsSlot = 5;   statsMaterial = Material.PAPER; statsName = "&eОбщая статистика"; statsLoreTpl = null;
        modeLore = null;
        modeSlots = new int[]{10,11,12,13,14,15,16,19,20,21,22,23,24,25};
    }

    public void open(Player viewer, OfflinePlayer target) {
        UUID targetUuid = target.getUniqueId();
        String targetName = target.getName() != null ? target.getName() : "Unknown";

        // Check visibility
        if (!canView(viewer, targetUuid)) {
            viewer.sendMessage(plugin.getConfigManager().msg("profile.private",
                    "{player}", targetName));
            return;
        }

        Inventory inv = Bukkit.createInventory(null, guiSize,
                ColorUtil.color(titleTemplate.replace("{player}", targetName)));

        ItemStack glass = new ItemBuilder(fillerMaterial).name(fillerName).build();
        // Fill all with glass first
        for (int i = 0; i < guiSize; i++) inv.setItem(i, glass);

        // Clear mode slots (will be filled or left as AIR)
        for (int slot : modeSlots) inv.setItem(slot, null);

        // Skull (player head with skin)
        inv.setItem(skullSlot, buildSkull(target, viewer));

        // Clock (playtime)
        long minutes = plugin.getStatsManager().getPlaytimeMinutes(targetUuid);
        long hours   = minutes / 60;
        long mins    = minutes % 60;
        List<String> ptLore = playtimeLore != null ? playtimeLore
                : List.of(plugin.getConfigManager().rawStr("profile.playtime-lore"));
        inv.setItem(playtimeSlot, new ItemBuilder(playtimeMaterial)
                .name(playtimeName)
                .lore(ptLore.stream()
                        .map(l -> l.replace("{hours}", String.valueOf(hours))
                                   .replace("{minutes}", String.valueOf(mins)))
                        .toList())
                .build());

        // Slot 5 — paper (overall stats: wins, losses, highest tier)
        int totalWins = 0, totalLosses = 0;
        String highestTier = null;
        int highestTierIndex = -1;
        List<String> tierOrder = plugin.getConfigManager().getTierOrder();

        for (PvPMode mode : plugin.getConfigManager().getModes()) {
            PlayerStats s = plugin.getStatsManager().get(targetUuid, mode.getId());
            totalWins   += s.getWins();
            totalLosses += s.getLosses();

            // Find highest tier across all modes
            String tier = plugin.getTierManager().getTier(targetUuid, mode.getId());
            if (tier != null && !tier.isBlank()) {
                int idx = tierOrder.indexOf(tier);
                if (idx > highestTierIndex) {
                    highestTierIndex = idx;
                    highestTier = tier;
                }
            }
        }

        String highestTierDisplay = highestTier != null
                ? plugin.getConfigManager().getTierColor(highestTier) + "[" + highestTier + "]"
                : plugin.getConfigManager().rawStr("profile.unranked");

        List<String> statsTpl = statsLoreTpl != null ? statsLoreTpl : List.of(
                plugin.getConfigManager().rawStr("profile.stats-wins"),
                plugin.getConfigManager().rawStr("profile.stats-losses"),
                plugin.getConfigManager().rawStr("profile.highest-tier"));
        final int fWins = totalWins, fLosses = totalLosses;
        List<String> statsLore = statsTpl.stream()
                .map(l -> l.replace("{wins}", String.valueOf(fWins))
                           .replace("{losses}", String.valueOf(fLosses))
                           .replace("{tier}", highestTierDisplay))
                .toList();

        inv.setItem(statsSlot, new ItemBuilder(statsMaterial)
                .name(statsName)
                .lore(statsLore)
                .build());

        // Per-mode tier items — compact layout: only modes with tiers fill slots sequentially
        List<PvPMode> modes = plugin.getConfigManager().getModes();
        int slotIdx = 0;
        for (PvPMode mode : modes) {
            if (slotIdx >= modeSlots.length) break;

            String tier = plugin.getTierManager().getTier(targetUuid, mode.getId());

            // Skip modes without a tier — do NOT advance slotIdx
            if (tier == null || tier.isBlank()) continue;

            PlayerStats stats = plugin.getStatsManager().get(targetUuid, mode.getId());
            String tierDisplay = plugin.getConfigManager().getTierColor(tier) + "[" + tier + "]";

            List<String> modeTpl = modeLore != null ? modeLore : List.of(
                    plugin.getConfigManager().rawStr("profile.tier-label"),
                    plugin.getConfigManager().rawStr("profile.wins-label"),
                    plugin.getConfigManager().rawStr("profile.losses-label"),
                    plugin.getConfigManager().rawStr("profile.kd-label"));
            List<String> lore = modeTpl.stream()
                    .map(l -> ColorUtil.color(l
                            .replace("{tier}", tierDisplay)
                            .replace("{wins}", String.valueOf(stats.getWins()))
                            .replace("{losses}", String.valueOf(stats.getLosses()))
                            .replace("{kd}", String.valueOf(stats.getKdRatio()))))
                    .toList();

            ItemBuilder b = new ItemBuilder(mode.getIcon())
                    .name(tierDisplay + " &f" + mode.getDisplayName())
                    .lore(lore);
            if (mode.isIconEnchanted()) b.glow();

            inv.setItem(modeSlots[slotIdx++], b.build());
        }

        viewer.openInventory(inv);
    }

    private ItemStack buildSkull(OfflinePlayer target, Player viewer) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) skull.getItemMeta();
        meta.setOwningPlayer(target);

        boolean online = target.isOnline();
        String status = online
                ? plugin.getConfigManager().rawStr("profile.status-online")
                : plugin.getConfigManager().rawStr("profile.status-offline");
        String name   = target.getName() != null ? target.getName() : "Unknown";

        String uuidShort = target.getUniqueId().toString().substring(0, 8);
        meta.setDisplayName(ColorUtil.color(skullName.replace("{player}", name)));
        List<String> skLore = skullLore != null ? skullLore : List.of(
                plugin.getConfigManager().rawStr("profile.status-label"),
                plugin.getConfigManager().rawStr("profile.uuid-label"));
        meta.setLore(skLore.stream()
                .map(l -> ColorUtil.color(l
                        .replace("{status}", status)
                        .replace("{uuid}", uuidShort)
                        .replace("{player}", name)))
                .toList());
        skull.setItemMeta(meta);
        return skull;
    }

    private boolean canView(Player viewer, UUID targetUuid) {
        if (viewer.getUniqueId().equals(targetUuid)) return true;
        if (viewer.hasPermission("scorepvp.admin")) return true;

        var settings = plugin.getSettingsManager().get(targetUuid);
        return settings.getProfileVisibility()
                == com.scorepvp.model.PlayerSettings.ProfileVisibility.PUBLIC;
    }

    public boolean isThisGUI(String title) {
        // Match the title template up to the player name placeholder
        String prefix = ColorUtil.color(titleTemplate.split("\\{player}")[0]);
        return title.startsWith(prefix);
    }
}
