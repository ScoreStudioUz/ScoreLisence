package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Manages the 4 protected hotbar items.
 *
 * Slot assignments (0-based):
 *   0 → Iron Sword  (Main PvP menu)
 *   1 → Nether Star  (Party create)
 *   7 → Grindstone  (Settings)
 *   8 → Ender Pearl  (Vanilla warp)
 *
 * Party leader hotbar:
 *   0 → Diamond Sword  (Party FFA)
 *   8 → Barrier  (Закрыть пати)
 *
 * Party member hotbar:
 *   4 → Barrier  (Покинуть пати)
 */
public class HotbarManager {

    public static final int SLOT_MENU     = 0;
    public static final int SLOT_PARTY    = 1;
    public static final int SLOT_SETTINGS = 7;
    public static final int SLOT_WARP     = 8;

    /** Slot 4 (game slot 5) — queue cancel button / party member leave. */
    public static final int SLOT_QUEUE = 4;

    private final ScorePvP plugin;

    public HotbarManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Give normal hotbar (with duplicate guard) ───────────────────────────

    /** Gives all 4 protected hotbar items. Skips if player is in an active match or in a party. */
    public void giveHotbar(Player player) {
        if (plugin.getMatchManager() != null
                && plugin.getMatchManager().isInMatch(player.getUniqueId())) return;

        // Don't overwrite party hotbar with lobby items
        if (plugin.getPartyManager() != null
                && plugin.getPartyManager().isInParty(player.getUniqueId())) return;

        clearProtectedSlots(player);
        player.getInventory().setItem(SLOT_QUEUE, null);
        player.getInventory().setItem(SLOT_MENU,     buildSlot("hotbar.slot-0", Material.IRON_SWORD));
        player.getInventory().setItem(SLOT_PARTY,    buildSlot("hotbar.slot-1", Material.NETHER_STAR));
        player.getInventory().setItem(SLOT_SETTINGS, buildSlot("hotbar.slot-7", Material.GRINDSTONE));
        player.getInventory().setItem(SLOT_WARP,     buildSlot("hotbar.slot-8", Material.ENDER_PEARL));
        player.updateInventory();
    }

    /** Clears all 4 protected slots AND the queue slot before a kit is given in a match. */
    public void removeHotbar(Player player) {
        clearProtectedSlots(player);
        player.getInventory().setItem(SLOT_QUEUE, null);
    }

    /** Restores normal hotbar after leaving a match/region/party. Fully clears inventory. */
    public void restoreHotbar(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.getActivePotionEffects().forEach(e -> player.removePotionEffect(e.getType()));
        player.setFireTicks(0);

        // Give party hotbar if in a party, otherwise give normal lobby hotbar
        if (plugin.getPartyManager() != null
                && plugin.getPartyManager().isInParty(player.getUniqueId())) {
            if (plugin.getPartyManager().isLeader(player.getUniqueId())) {
                givePartyLeaderHotbar(player);
            } else {
                givePartyMemberHotbar(player);
            }
        } else {
            giveHotbar(player);
        }
        player.updateInventory();
    }

    /** Updates only the party slot (slot 1) based on current party state. */
    public void updatePartySlot(Player player, boolean inParty) {
        if (inParty) {
            // If in party, apply full party hotbar instead of just updating one slot
            if (plugin.getPartyManager().isLeader(player.getUniqueId())) {
                givePartyLeaderHotbar(player);
            } else {
                givePartyMemberHotbar(player);
            }
        } else {
            player.getInventory().setItem(SLOT_PARTY, buildSlot("hotbar.slot-1", Material.NETHER_STAR));
        }
    }

    // ── Party hotbar ────────────────────────────────────────────────────────

    /**
     * Gives party leader hotbar. Clears inventory completely, then gives:
     * Slot 0: Diamond Sword "Party FFA"
     * Slot 8: Barrier "Закрыть пати"
     */
    public void givePartyLeaderHotbar(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);

        player.getInventory().setItem(SLOT_MENU, new ItemBuilder(Material.DIAMOND_SWORD)
                .name(plugin.getConfigManager().rawStr("hotbar.party-ffa-name"))
                .lore(plugin.getConfigManager().rawStr("hotbar.party-ffa-lore"))
                .flag(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE)
                .unbreakable()
                .build());

        player.getInventory().setItem(SLOT_WARP, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().rawStr("hotbar.party-close-name"))
                .lore(plugin.getConfigManager().rawStr("hotbar.party-close-lore"))
                .flag(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE)
                .unbreakable()
                .build());

        player.updateInventory();
    }

    /**
     * Gives party member hotbar. Clears inventory completely, then gives:
     * Slot 4: Barrier "Покинуть пати"
     */
    public void givePartyMemberHotbar(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);

        player.getInventory().setItem(SLOT_QUEUE, new ItemBuilder(Material.BARRIER)
                .name(plugin.getConfigManager().rawStr("hotbar.party-leave-name"))
                .lore(plugin.getConfigManager().rawStr("hotbar.party-leave-lore"))
                .flag(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE)
                .unbreakable()
                .build());

        player.updateInventory();
    }

    // ── Queue state ───────────────────────────────────────────────────────────

    /**
     * Clears slots 0,1,7,8 and places a Barrier at slot 4.
     * Player can RMB on it to cancel queue search.
     */
    public void showQueueMode(Player player, String modeName) {
        // Clear the 4 protected hotbar slots
        player.getInventory().setItem(SLOT_MENU,     null);
        player.getInventory().setItem(SLOT_PARTY,    null);
        player.getInventory().setItem(SLOT_SETTINGS, null);
        player.getInventory().setItem(SLOT_WARP,     null);
        // Place Barrier at slot 4
        player.getInventory().setItem(SLOT_QUEUE,
                new ItemBuilder(Material.BARRIER)
                        .name(plugin.getConfigManager().rawStr("hotbar.queue-name"))
                        .lore(plugin.getConfigManager().rawStr("hotbar.queue-mode-lore").replace("{mode}", modeName),
                              "",
                              plugin.getConfigManager().rawStr("hotbar.queue-cancel-lore"))
                        .flag(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE)
                        .unbreakable().build());
        player.getInventory().setHeldItemSlot(SLOT_QUEUE);
        player.updateInventory();
    }

    /** Restores normal hotbar, removes queue barrier. */
    public void showNormalMode(Player player) {
        player.getInventory().setItem(SLOT_QUEUE, null);
        restoreHotbar(player);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void clearProtectedSlots(Player player) {
        player.getInventory().setItem(SLOT_MENU,     null);
        player.getInventory().setItem(SLOT_PARTY,    null);
        player.getInventory().setItem(SLOT_SETTINGS, null);
        player.getInventory().setItem(SLOT_WARP,     null);
    }

    private ItemStack buildSlot(String configPath, Material fallback) {
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection sec = cfg.getConfigurationSection(configPath);
        if (sec == null) return new ItemBuilder(fallback)
                .flag(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS).unbreakable().build();

        Material mat;
        try { mat = Material.valueOf(sec.getString("material", fallback.name()).toUpperCase()); }
        catch (IllegalArgumentException e) { mat = fallback; }

        List<String> lore = sec.getStringList("lore");
        return new ItemBuilder(mat)
                .name(sec.getString("name", "&f" + mat.name()))
                .lore(lore)
                .flag(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE)
                .unbreakable()
                .build();
    }
}
