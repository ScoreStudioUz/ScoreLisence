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

    /** Base material of the golden head. HONEY_BOTTLE is NOT a placeable block,
     *  which is the ROOT-CAUSE fix for the recurring "head places on the ground"
     *  bug — you simply cannot place a honey bottle. The resource pack maps its
     *  custom_model_data to the 3D "oltin_bosh" head model so it still LOOKS like
     *  a golden head in-hand / on the ground / in F5. */
    public static final Material GOLDEN_HEAD_MATERIAL = Material.HONEY_BOTTLE;

    /**
     * Builds the Golden Head item.
     * - HONEY_BOTTLE base (non-placeable) + custom_model_data → RP head model
     * - On consume: Regeneration III (5s) + Absorption I (2min)
     * - 10-second cooldown
     * - Eating animation (uses Food + Consumable components)
     */
    private ScorePvP plugin;

    public void setPlugin(ScorePvP plugin) { this.plugin = plugin; }

    public ItemStack buildGoldenHead() {
        ItemStack skull = new ItemStack(GOLDEN_HEAD_MATERIAL);
        ItemMeta meta = skull.getItemMeta();
        if (meta == null) return skull;

        // Resource-pack model selector: the HONEY_BOTTLE item def maps this CMD
        // to civilization/minecraft:item/oltin_bosh (the 3D head model).
        int cmd = plugin != null
                ? plugin.getConfig().getInt("custom-items.golden-head.custom-model-data", 1)
                : 1;
        meta.setCustomModelData(cmd);

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

        // Make it edible. On 1.21.2+ a FOOD component alone does NOT make an
        // item consumable — it also needs a CONSUMABLE component. We set both
        // via the Paper data-component API: EAT animation (golden-apple style)
        // at kelp speed (~0.865s), always eatable, zero nutrition (the real
        // effects come from CustomItemListener on PlayerItemConsumeEvent).
        applyEdible(skull);
        return skull;
    }

    /**
     * Adds FOOD + CONSUMABLE data components so the head plays the eat
     * animation, can be eaten at full hunger, and finishes at kelp speed.
     * Wrapped in try/catch so a missing API on an unexpected build degrades
     * gracefully (item just won't be edible) rather than crashing item build.
     */
    public static void applyEdible(ItemStack stack) {
        try {
            io.papermc.paper.datacomponent.item.FoodProperties food =
                    io.papermc.paper.datacomponent.item.FoodProperties.food()
                            .nutrition(0)
                            .saturation(0f)
                            .canAlwaysEat(true)
                            .build();
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.FOOD, food);

            io.papermc.paper.datacomponent.item.Consumable consumable =
                    io.papermc.paper.datacomponent.item.Consumable.consumable()
                            .consumeSeconds(0.865f) // kelp speed
                            .animation(io.papermc.paper.datacomponent.item.consumable.ItemUseAnimation.EAT)
                            .sound(net.kyori.adventure.key.Key.key("minecraft:entity.generic.eat"))
                            .hasConsumeParticles(true)
                            .build();
            stack.setData(io.papermc.paper.datacomponent.DataComponentTypes.CONSUMABLE, consumable);

            // HONEY_BOTTLE ships a vanilla USE_REMAINDER of GLASS_BOTTLE, so eating
            // the head handed the player an empty bottle. Unset it so nothing is
            // left behind.
            stack.unsetData(io.papermc.paper.datacomponent.DataComponentTypes.USE_REMAINDER);
        } catch (Throwable ignored) {
            // Data-component API absent — item simply won't be edible.
        }
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
