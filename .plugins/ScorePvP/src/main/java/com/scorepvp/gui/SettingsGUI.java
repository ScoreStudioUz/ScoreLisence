package com.scorepvp.gui;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PlayerSettings;
import com.scorepvp.model.PlayerSettings.ProfileVisibility;
import com.scorepvp.model.PlayerSettings.Visibility;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.List;

/**
 * Settings menu.
 * Reads layout from item_menus/settings.yml.
 */
public class SettingsGUI {

    private String title;
    private int guiSize;
    private Material fillerMaterial;
    private String fillerName;

    // Per-setting slot + material from config
    private int slotDuelRequests;     private Material matDuelRequests;
    private int slotPartyInvites;     private Material matPartyInvites;
    private int slotShowOwnTier;      private Material matShowOwnTier;
    private int slotFriendRequests;   private Material matFriendRequests;
    private int slotDirectMessages;   private Material matDirectMessages;
    private int slotFriendJoinNotify; private Material matFriendJoinNotify;
    private int slotProfileVisibility;private Material matProfileVisibility;

    private final ScorePvP plugin;

    public SettingsGUI(ScorePvP plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        File file = new File(plugin.getDataFolder(), "item_menus/settings.yml");
        if (!file.exists()) {
            applyDefaults();
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        title = ColorUtil.color(cfg.getString("title", "&0Настройки"));
        guiSize = cfg.getInt("size", 27);
        try { fillerMaterial = Material.valueOf(cfg.getString("filler.material", "GRAY_STAINED_GLASS_PANE")); }
        catch (IllegalArgumentException e) { fillerMaterial = Material.GRAY_STAINED_GLASS_PANE; }
        fillerName = cfg.getString("filler.name", "&r");

        ConfigurationSection items = cfg.getConfigurationSection("items");
        slotDuelRequests     = readSlot(items, "duel-requests", 10);
        matDuelRequests      = readMat(items, "duel-requests", Material.WOODEN_SWORD);
        slotPartyInvites     = readSlot(items, "party-invites", 11);
        matPartyInvites      = readMat(items, "party-invites", Material.NETHER_STAR);
        slotShowOwnTier      = readSlot(items, "show-own-tier", 12);
        matShowOwnTier       = readMat(items, "show-own-tier", Material.NAME_TAG);
        slotFriendRequests   = readSlot(items, "friend-requests", 13);
        matFriendRequests    = readMat(items, "friend-requests", Material.PAPER);
        slotDirectMessages   = readSlot(items, "direct-messages", 14);
        matDirectMessages    = readMat(items, "direct-messages", Material.BOOK);
        slotFriendJoinNotify = readSlot(items, "friend-join-notifier", 15);
        matFriendJoinNotify  = readMat(items, "friend-join-notifier", Material.BELL);
        slotProfileVisibility= readSlot(items, "profile-visibility", 16);
        matProfileVisibility = readMat(items, "profile-visibility", Material.SPYGLASS);
    }

    private void applyDefaults() {
        title = ColorUtil.color("&0Настройки");
        guiSize = 27;
        fillerMaterial = Material.GRAY_STAINED_GLASS_PANE;
        fillerName = "&r";
        slotDuelRequests = 10;     matDuelRequests = Material.WOODEN_SWORD;
        slotPartyInvites = 11;     matPartyInvites = Material.NETHER_STAR;
        slotShowOwnTier = 12;      matShowOwnTier = Material.NAME_TAG;
        slotFriendRequests = 13;   matFriendRequests = Material.PAPER;
        slotDirectMessages = 14;   matDirectMessages = Material.BOOK;
        slotFriendJoinNotify = 15; matFriendJoinNotify = Material.BELL;
        slotProfileVisibility = 16;matProfileVisibility = Material.SPYGLASS;
    }

    private int readSlot(ConfigurationSection sec, String key, int def) {
        if (sec == null) return def;
        return sec.getInt(key + ".slot", def);
    }

    private Material readMat(ConfigurationSection sec, String key, Material def) {
        if (sec == null) return def;
        try { return Material.valueOf(sec.getString(key + ".material", def.name())); }
        catch (IllegalArgumentException e) { return def; }
    }

    public String getTitle() { return title; }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, guiSize, title);

        ItemStack glass = new ItemBuilder(fillerMaterial).name(fillerName).build();
        for (int i = 0; i < guiSize; i++) inv.setItem(i, glass);

        PlayerSettings s = plugin.getSettingsManager().get(player.getUniqueId());
        buildSettings(inv, s);

        player.openInventory(inv);
    }

    private void buildSettings(Inventory inv, PlayerSettings s) {
        inv.setItem(slotDuelRequests,     duelRequests(s));
        inv.setItem(slotPartyInvites,     partyInvites(s));
        inv.setItem(slotShowOwnTier,      showOwnTier(s));
        inv.setItem(slotFriendRequests,   friendRequests(s));
        inv.setItem(slotDirectMessages,   directMessages(s));
        inv.setItem(slotFriendJoinNotify, friendJoinNotifier(s));
        inv.setItem(slotProfileVisibility,profileVisibility(s));
    }

    // ── Refresh a single slot without closing ─────────────────────────────────

    public void refresh(Player player) {
        if (player.getOpenInventory().getTitle().equals(title)) {
            Inventory inv = player.getOpenInventory().getTopInventory();
            PlayerSettings s = plugin.getSettingsManager().get(player.getUniqueId());
            buildSettings(inv, s);
        }
    }

    // ── Handle click ─────────────────────────────────────────────────────────

    public boolean handleClick(Player player, int slot) {
        PlayerSettings s = plugin.getSettingsManager().get(player.getUniqueId());
        boolean handled = true;

        if (slot == slotDuelRequests)           s.cycleDuelRequests();
        else if (slot == slotPartyInvites)      s.cyclePartyInvites();
        else if (slot == slotShowOwnTier)       s.cycleShowOwnTier();
        else if (slot == slotFriendRequests)    s.toggleFriendRequests();
        else if (slot == slotDirectMessages)    s.cycleDirectMessages();
        else if (slot == slotFriendJoinNotify)  s.toggleFriendJoin();
        else if (slot == slotProfileVisibility) s.cycleProfileVisibility();
        else handled = false;

        if (handled) {
            plugin.getSettingsManager().persist(player.getUniqueId());
            refresh(player);
        }
        return handled;
    }

    // ── Item builders ─────────────────────────────────────────────────────────

    private ItemStack duelRequests(PlayerSettings s) {
        return new ItemBuilder(matDuelRequests)
                .name(plugin.getConfigManager().rawMsg("settings.duel-requests.name"))
                .lore(buildLore("settings.duel-requests.lore", visibilityLabel(s.getDuelRequests())))
                .build();
    }

    private ItemStack partyInvites(PlayerSettings s) {
        return new ItemBuilder(matPartyInvites)
                .name(plugin.getConfigManager().rawMsg("settings.party-invites.name"))
                .lore(buildLore("settings.party-invites.lore", visibilityLabel(s.getPartyInvites())))
                .build();
    }

    private ItemStack showOwnTier(PlayerSettings s) {
        return new ItemBuilder(matShowOwnTier)
                .name(plugin.getConfigManager().rawMsg("settings.show-own-tier.name"))
                .lore(buildLore("settings.show-own-tier.lore", visibilityLabel(s.getShowOwnTier())))
                .build();
    }

    private ItemStack friendRequests(PlayerSettings s) {
        return new ItemBuilder(matFriendRequests)
                .name(plugin.getConfigManager().rawMsg("settings.friend-requests.name"))
                .lore(buildLore("settings.friend-requests.lore", boolLabel(s.isFriendRequests())))
                .build();
    }

    private ItemStack directMessages(PlayerSettings s) {
        return new ItemBuilder(matDirectMessages)
                .name(plugin.getConfigManager().rawMsg("settings.direct-messages.name"))
                .lore(buildLore("settings.direct-messages.lore", visibilityLabel(s.getDirectMessages())))
                .build();
    }

    private ItemStack friendJoinNotifier(PlayerSettings s) {
        return new ItemBuilder(matFriendJoinNotify)
                .name(plugin.getConfigManager().rawMsg("settings.friend-join-notifier.name"))
                .lore(buildLore("settings.friend-join-notifier.lore", boolLabel(s.isFriendJoinNotifier())))
                .build();
    }

    private ItemStack profileVisibility(PlayerSettings s) {
        return new ItemBuilder(matProfileVisibility)
                .name(plugin.getConfigManager().rawMsg("settings.profile-visibility.name"))
                .lore(buildLore("settings.profile-visibility.lore", profileLabel(s.getProfileVisibility())))
                .build();
    }

    // ── Label helpers ─────────────────────────────────────────────────────────

    private List<String> buildLore(String langPath, String value) {
        return plugin.getConfigManager().rawList(langPath).stream()
                .map(l -> l.replace("{value}", value))
                .toList();
    }

    private String visibilityLabel(Visibility v) {
        return v == Visibility.ANYONE
                ? plugin.getConfigManager().rawStr("settings-values.anyone")
                : plugin.getConfigManager().rawStr("settings-values.only-friends");
    }

    private String boolLabel(boolean b) {
        return b ? plugin.getConfigManager().rawStr("settings-values.enabled")
                 : plugin.getConfigManager().rawStr("settings-values.disabled");
    }

    private String profileLabel(ProfileVisibility pv) {
        return pv == ProfileVisibility.PUBLIC
                ? plugin.getConfigManager().rawStr("settings-values.public")
                : plugin.getConfigManager().rawStr("settings-values.private");
    }
}
