package com.scorepvp.hats;

import com.scorepvp.util.ColorUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * A single cosmetic hat: a base item ({@code icon-material}, usually COAL)
 * carrying a {@code custom_model_data} value that the ScoreCosmetics resource
 * pack renders as a 3D model when worn in the helmet slot.
 *
 * <p>Each hat is gated by {@code scorepvp.hat.<id>} (or the {@code scorepvp.hats.all}
 * wildcard). The chosen hat id lives in player_settings.hat.
 */
public class HatDefinition {

    public static final String PERMISSION_PREFIX = "scorepvp.hat.";
    public static final String PERMISSION_ALL    = "scorepvp.hats.all";

    private final String id;
    private final String displayName;
    private final int customModelData;
    private final Material iconMaterial;
    private final List<String> lore;
    /** Shop category id (uncommon / rare / legendary). Never null. */
    private final String category;

    public HatDefinition(String id, String displayName, int customModelData,
                         Material iconMaterial, List<String> lore, String category) {
        this.id = id;
        this.displayName = displayName;
        this.customModelData = customModelData;
        this.iconMaterial = iconMaterial;
        this.lore = lore;
        this.category = category;
    }

    public String getId()             { return id; }
    public String getDisplayName()    { return displayName; }
    public int getCustomModelData()   { return customModelData; }
    public Material getIconMaterial() { return iconMaterial; }
    public List<String> getLore()     { return lore; }
    public String getCategory()       { return category; }

    public String getPermission() { return PERMISSION_PREFIX + id; }

    /** The wearable hat item (COAL base + custom_model_data). */
    public ItemStack buildHatItem() {
        ItemStack item = new ItemStack(iconMaterial, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(customModelData);
            meta.displayName(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(displayName)
                    .decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    /** The GUI icon (with lore + equipped/locked state). */
    public ItemStack buildMenuIcon(boolean equipped, boolean unlocked, HatConfig cfg) {
        ItemStack item = buildHatItem();
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            List<Component> loreComponents = new ArrayList<>();
            for (String line : lore) {
                loreComponents.add(LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            if (!lore.isEmpty()) loreComponents.add(Component.empty());
            String stateLine = equipped ? cfg.loreEquipped()
                    : unlocked ? cfg.loreUnlocked() : cfg.loreLocked();
            loreComponents.add(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(ColorUtil.color(stateLine)).decoration(TextDecoration.ITALIC, false));
            meta.lore(loreComponents);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Small holder for the three GUI state-lines (avoids passing the whole manager). */
    public record HatConfig(String loreEquipped, String loreUnlocked, String loreLocked) {}
}
