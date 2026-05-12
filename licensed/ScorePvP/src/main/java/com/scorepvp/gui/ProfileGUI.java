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
    private int playtimeSlot;
    private Material playtimeMaterial;
    private String playtimeName;
    private int statsSlot;
    private Material statsMaterial;
    private String statsName;
    private int[] modeSlots;

    private final ScorePvP plugin;

    public ProfileGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "item_menus/profile.yml");
        if (!file.exists()) {
            titleTemplate = "&0Профиль: &b{player}";
            guiSize = 36;
            fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
            fillerName = "&r";
            skullSlot = 4; skullName = "&b{player}";
            playtimeSlot = 3; playtimeMaterial = Material.CLOCK; playtimeName = "&eВремя в игре";
            statsSlot = 5; statsMaterial = Material.PAPER; statsName = "&eОбщая статистика";
            modeSlots = new int[]{10,11,12,13,14,15,16,19,20,21,22,23,24,25};
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        titleTemplate = cfg.getString("title", "&0Профиль: &b{player}");
        guiSize = cfg.getInt("size", 36);
        try { fillerMaterial = Material.valueOf(cfg.getString("filler.material", "GRAY_STAINED_GLASS_PANE")); }
        catch (IllegalArgumentException e) { fillerMaterial = Material.GRAY_STAINED_GLASS_PANE; }
        fillerName = cfg.getString("filler.name", "&r");
        skullSlot = cfg.getInt("skull.slot", 4);
        skullName = cfg.getString("skull.name", "&b{player}");
        playtimeSlot = cfg.getInt("playtime.slot", 3);
        try { playtimeMaterial = Material.valueOf(cfg.getString("playtime.material", "CLOCK")); }
        catch (IllegalArgumentException e) { playtimeMaterial = Material.CLOCK; }
        playtimeName = cfg.getString("playtime.name", "&eВремя в игре");
        statsSlot = cfg.getInt("stats.slot", 5);
        try { statsMaterial = Material.valueOf(cfg.getString("stats.material", "PAPER")); }
        catch (IllegalArgumentException e) { statsMaterial = Material.PAPER; }
        statsName = cfg.getString("stats.name", "&eОбщая статистика");
        List<Integer> slots = cfg.getIntegerList("mode-slots");
        if (slots.isEmpty()) {
            modeSlots = new int[]{10,11,12,13,14,15,16,19,20,21,22,23,24,25};
        } else {
            modeSlots = slots.stream().mapToInt(Integer::intValue).toArray();
        }
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
        inv.setItem(playtimeSlot, new ItemBuilder(playtimeMaterial)
                .name(plugin.getConfigManager().rawStr("profile.playtime-name"))
                .lore(plugin.getConfigManager().rawStr("profile.playtime-lore")
                        .replace("{hours}", String.valueOf(hours))
                        .replace("{minutes}", String.valueOf(mins)))
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

        List<String> statsLore = new ArrayList<>();
        statsLore.add(plugin.getConfigManager().rawStr("profile.stats-wins").replace("{wins}", String.valueOf(totalWins)));
        statsLore.add(plugin.getConfigManager().rawStr("profile.stats-losses").replace("{losses}", String.valueOf(totalLosses)));
        statsLore.add(plugin.getConfigManager().rawStr("profile.highest-tier").replace("{tier}", highestTierDisplay));

        inv.setItem(statsSlot, new ItemBuilder(statsMaterial)
                .name(plugin.getConfigManager().rawStr("profile.stats-name"))
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

            List<String> lore = new ArrayList<>();
            lore.add(ColorUtil.color(plugin.getConfigManager().rawStr("profile.tier-label").replace("{tier}", tierDisplay)));
            lore.add(ColorUtil.color(plugin.getConfigManager().rawStr("profile.wins-label").replace("{wins}", String.valueOf(stats.getWins()))));
            lore.add(ColorUtil.color(plugin.getConfigManager().rawStr("profile.losses-label").replace("{losses}", String.valueOf(stats.getLosses()))));
            lore.add(ColorUtil.color(plugin.getConfigManager().rawStr("profile.kd-label").replace("{kd}", String.valueOf(stats.getKdRatio()))));

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

        meta.setDisplayName(ColorUtil.color(skullName.replace("{player}", name)));
        meta.setLore(List.of(
                ColorUtil.color(plugin.getConfigManager().rawStr("profile.status-label").replace("{status}", status)),
                ColorUtil.color(plugin.getConfigManager().rawStr("profile.uuid-label")
                        .replace("{uuid}", target.getUniqueId().toString().substring(0, 8)))
        ));
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
