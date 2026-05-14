package ru.score.hwauction.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.score.hwauction.util.SkullUtil;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Represents one auction category.
 *
 * Matching rules:
 *  - "all"    → always matches everything
 *  - "donate" → matches if item has ANY custom display name (hasDisplayName() == true)
 *  - others   → material list + skull prefix matching
 *
 * An item can match multiple categories (multi-category browsing is supported by FilterService).
 */
public final class Category {

    private final String key;
    private final String displayName;
    private final Material icon;
    private final Set<Material> materials;
    private final List<String> skullPrefixes;
    private final boolean acceptsAnySkulls;

    public Category(String key, String displayName, Material icon,
                    Set<Material> materials, List<String> skullPrefixes) {
        this.key = key;
        this.displayName = displayName;
        this.icon = icon;
        this.materials = materials.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(materials));
        this.skullPrefixes    = List.copyOf(skullPrefixes);
        this.acceptsAnySkulls = materials.contains(Material.PLAYER_HEAD) && skullPrefixes.isEmpty();
    }

    public Category(String key, String displayName, Material icon, Set<Material> materials) {
        this(key, displayName, icon, materials, List.of());
    }

    /**
     * Does the given ItemStack belong to this category?
     *
     * "all"    → always true
     * "donate" → item has ANY custom display name
     * "spheres"→ PLAYER_HEAD skulls  OR  TOTEM_OF_UNDYING with enchantments (= talisman)
     * others   → material list + skull prefix matching
     */
    public boolean matches(ItemStack item) {
        if (item == null) return false;
        if (key.equals("all")) return true;

        // "donate" — any item with a custom display name
        if (key.equals("donate")) {
            return item.hasItemMeta()
                    && item.getItemMeta() != null
                    && item.getItemMeta().hasDisplayName();
        }

        // "spheres" — skull (sphere) OR enchanted totem (talisman)
        if (key.equals("spheres")) {
            if (item.getType() == Material.PLAYER_HEAD) return true;
            if (item.getType() == Material.TOTEM_OF_UNDYING) {
                // Enchanted totem = talisman → belongs in spheres
                return item.hasItemMeta()
                        && item.getItemMeta() != null
                        && item.getItemMeta().hasEnchants();
            }
            return false;
        }

        // "unique" — TOTEM_OF_UNDYING only if NOT enchanted (regular totem)
        if (key.equals("unique") && item.getType() == Material.TOTEM_OF_UNDYING) {
            boolean hasEnchants = item.hasItemMeta()
                    && item.getItemMeta() != null
                    && item.getItemMeta().hasEnchants();
            return !hasEnchants; // regular totem only
        }

        // Skull / PLAYER_HEAD matching for other categories
        if (item.getType() == Material.PLAYER_HEAD) {
            if (acceptsAnySkulls) return true;
            for (String prefix : skullPrefixes) {
                if (prefix.equalsIgnoreCase("PLAYER_HEAD")) return true;
                if (SkullUtil.matchesPrefix(item, prefix)) return true;
            }
            return false;
        }

        // Material match
        return materials.contains(item.getType());
    }

    /** Material-only match (no ItemStack available) */
    public boolean matches(Material material) {
        if (key.equals("all") || key.equals("donate")) return true;
        if (material == Material.PLAYER_HEAD) return acceptsAnySkulls || !skullPrefixes.isEmpty();
        return materials.contains(material);
    }

    public boolean isAll()              { return key.equals("all"); }
    public boolean isDonate()           { return key.equals("donate"); }
    public String getKey()              { return key; }
    public String getDisplayName()      { return displayName; }
    public Material getIcon()           { return icon; }
    public Set<Material> getMaterials() { return materials; }
    public List<String> getSkullPrefixes() { return skullPrefixes; }
    public boolean acceptsAnySkulls()   { return acceptsAnySkulls; }
}
