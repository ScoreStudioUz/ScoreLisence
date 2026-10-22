package ru.score.hwauction.model;

import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ru.score.hwauction.util.ColorUtil;

import java.util.Map;
import java.util.Objects;

/**
 * An admin-registered "original" item. Auction listings that match this template
 * (by {@link MatchMode}) are forced into {@link #category} and are searchable by
 * {@link #searchName}, regardless of any custom display name a player sets.
 *
 * Matching deliberately ignores the display name, so renaming an item on the
 * anvil cannot hide it from the template's category/search.
 */
public final class ItemTemplate {

    public enum MatchMode {
        /** Material + enchantments (levels) must match. Ignores lore & name. */
        MATERIAL_ENCHANTS,
        /** Material + full lore (color-stripped) must match. Ignores name. */
        MATERIAL_LORE,
        /** Material + enchantments + lore must all match (strictest, still ignores name). */
        EXACT
    }

    private final String id;             // stable key for storage / removal
    private final String searchName;     // original name shown & searchable
    private final String category;       // category key this item is forced into
    private final MatchMode matchMode;
    private final ItemStack sample;      // captured snapshot (for reference/icon)

    public ItemTemplate(String id, String searchName, String category,
                        MatchMode matchMode, ItemStack sample) {
        this.id = Objects.requireNonNull(id);
        this.searchName = Objects.requireNonNull(searchName);
        this.category = Objects.requireNonNull(category);
        this.matchMode = Objects.requireNonNull(matchMode);
        this.sample = Objects.requireNonNull(sample).clone();
    }

    public String getId()            { return id; }
    public String getSearchName()    { return searchName; }
    public String getCategory()      { return category; }
    public MatchMode getMatchMode()  { return matchMode; }
    public ItemStack getSample()     { return sample.clone(); }

    /** Does the given listed item match this template's characteristics? */
    public boolean matches(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != sample.getType()) return false;

        switch (matchMode) {
            case MATERIAL_ENCHANTS -> {
                return enchantsEqual(item, sample);
            }
            case MATERIAL_LORE -> {
                return loreEqual(item, sample);
            }
            case EXACT -> {
                return enchantsEqual(item, sample) && loreEqual(item, sample);
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean enchantsEqual(ItemStack a, ItemStack b) {
        Map<Enchantment, Integer> ea = a.getEnchantments();
        Map<Enchantment, Integer> eb = b.getEnchantments();
        // Stored enchants (from books) live in meta, not ItemStack#getEnchantments — merge them.
        Map<Enchantment, Integer> ma = mergeStored(a, ea);
        Map<Enchantment, Integer> mb = mergeStored(b, eb);
        return ma.equals(mb);
    }

    private static Map<Enchantment, Integer> mergeStored(ItemStack item, Map<Enchantment, Integer> base) {
        java.util.HashMap<Enchantment, Integer> out = new java.util.HashMap<>(base);
        if (item.hasItemMeta() && item.getItemMeta()
                instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta esm) {
            out.putAll(esm.getStoredEnchants());
        }
        return out;
    }

    private static boolean loreEqual(ItemStack a, ItemStack b) {
        return strippedLore(a).equals(strippedLore(b));
    }

    private static java.util.List<String> strippedLore(ItemStack item) {
        if (!item.hasItemMeta()) return java.util.List.of();
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) return java.util.List.of();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : meta.getLore()) out.add(ColorUtil.strip(line == null ? "" : line));
        return out;
    }
}
