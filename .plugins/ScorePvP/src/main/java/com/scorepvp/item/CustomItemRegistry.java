package com.scorepvp.item;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.KitManager;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;

/**
 * Registry for custom items that can be given via /spvp giveitem.
 *
 * Section 5: "golden_head" — PLAYER_HEAD with custom Base64 texture.
 *   Effects on right-click use:
 *     - Regeneration III for 5 seconds
 *     - Absorption I for 2 minutes (4 extra hearts)
 *   Mechanics:
 *     - 10-second cooldown with Minecraft's white cooldown bar
 *     - Eating animation identical to normal food
 *
 * Item ID: "golden_head"
 */
public class CustomItemRegistry {

    // Base64 texture for the golden head.
    // This is a golden-textured player head skin.
    // Admins can replace this value in config; for now it is hardcoded as the
    // standard "golden apple" head texture used in PvP servers.
    public static final String GOLDEN_HEAD_BASE64 =
            "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvMWU1YmY2MDM2Mzg2YjJlYjAwZmMyZTYzNWU1NzViNzRlMmI1YTBjNjljYmI1MzY5MWFlZGZkYjZhMGMzNjAwOSJ9fX0=";

    public static final String GOLDEN_HEAD_ID = "golden_head";

    /** NBT key used to identify custom items in interaction events. */
    public static final org.bukkit.NamespacedKey CUSTOM_ITEM_KEY =
            new org.bukkit.NamespacedKey("scorepvp", "custom_item_id");

    private static final List<String> REGISTERED_IDS = List.of(GOLDEN_HEAD_ID);

    public static List<String> getRegisteredIds() {
        return REGISTERED_IDS;
    }

    /**
     * Builds the custom item ItemStack for the given item ID.
     * Returns null if the ID is unknown.
     */
    public ItemStack buildItem(String itemId) {
        return switch (itemId.toLowerCase()) {
            case GOLDEN_HEAD_ID -> buildGoldenHead();
            default -> null;
        };
    }

    /**
     * Builds the Golden Head item.
     * - PLAYER_HEAD with custom Base64 texture
     * - On consume: Regeneration III (5s) + Absorption I (2min)
     * - 10-second cooldown
     * - Eating animation (uses Food component / GOLDEN_APPLE food type)
     */
    private ScorePvP plugin;

    public void setPlugin(ScorePvP plugin) { this.plugin = plugin; }

    public ItemStack buildGoldenHead() {
        // Read texture from config if available, fall back to hardcoded
        String base64 = GOLDEN_HEAD_BASE64;
        if (plugin != null) {
            String cfgTexture = plugin.getConfig().getString(
                    "custom-items.golden-head.base64-texture", "");
            if (!cfgTexture.isBlank()) base64 = cfgTexture;
        }
        ItemStack skull = KitManager.buildSkullFromBase64(base64);
        ItemMeta meta = skull.getItemMeta();
        if (meta == null) return skull;

        // Custom display name and lore
        meta.setDisplayName(com.scorepvp.util.ColorUtil.color(
                plugin.getConfigManager().rawStr("custom-item.golden-head-name")));
        List<String> loreLines = plugin.getConfigManager().rawList("custom-item.golden-head-lore");
        meta.setLore(com.scorepvp.util.ColorUtil.color(loreLines));

        // Section 5: store custom item ID in PersistentDataContainer for event detection
        meta.getPersistentDataContainer().set(
                CUSTOM_ITEM_KEY,
                org.bukkit.persistence.PersistentDataType.STRING,
                GOLDEN_HEAD_ID
        );

        skull.setItemMeta(meta);
        return skull;
    }

    /**
     * Checks whether an ItemStack is a specific custom item.
     */
    public static boolean isCustomItem(ItemStack item, String itemId) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String stored = meta.getPersistentDataContainer().get(
                CUSTOM_ITEM_KEY, org.bukkit.persistence.PersistentDataType.STRING);
        return itemId.equalsIgnoreCase(stored);
    }

    /**
     * Returns the custom item ID stored in an item, or null if not a custom item.
     */
    public static String getCustomItemId(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(
                CUSTOM_ITEM_KEY, org.bukkit.persistence.PersistentDataType.STRING);
    }
}
