package ru.score.hwauction.config;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.Category;

import java.util.*;

public final class CategoryRegistry {

    private final ScoreHWAuction plugin;
    private final LinkedHashMap<String, Category> categories = new LinkedHashMap<>();

    public CategoryRegistry(ScoreHWAuction plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        categories.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("categories");
        if (root == null) {
            plugin.getLogger().warning("No 'categories' section found in config.yml!");
            addDefault();
            return;
        }
        for (String key : root.getKeys(false)) {
            String name   = root.getString(key + ".name", key);
            String matStr = root.getString(key + ".material", "PAPER");
            Material icon = parseMaterial(matStr, Material.PAPER);

            List<String> rawMats      = root.getStringList(key + ".materials");
            Set<Material> matSet       = new HashSet<>();
            List<String> skullPrefixes = new ArrayList<>();

            for (String m : rawMats) {
                if (m.toUpperCase().startsWith("PLAYER_HEAD")) {
                    matSet.add(Material.PLAYER_HEAD);
                    String[] parts = m.split(":", 2);
                    if (parts.length == 2 && !parts[1].isBlank()) skullPrefixes.add(parts[1].trim());
                } else {
                    Material mat = parseMaterial(m, null);
                    if (mat != null) matSet.add(mat);
                }
            }
            categories.put(key.toLowerCase(),
                    new Category(key.toLowerCase(), name, icon, matSet, skullPrefixes));
        }
        plugin.getLogger().info("Loaded " + categories.size() + " auction categories.");
    }

    /**
     * Detect the PRIMARY category for an item (used when listing — stored in DB).
     * Priority: specific categories first, "donate" last (catches custom-named items),
     * "misc" as final fallback.
     */
    public String detectCategory(ItemStack item) {
        if (item == null) return "misc";
        for (Category cat : categories.values()) {
            if (cat.isAll() || cat.isDonate() || cat.getKey().equals("misc")) continue;
            if (cat.matches(item)) return cat.getKey();
        }
        // Donate: item has a custom name and wasn't matched by any vanilla category
        Category donate = categories.get("donate");
        if (donate != null && donate.matches(item)) return "donate";
        return "misc";
    }

    public String detectCategory(Material material) {
        for (Category cat : categories.values()) {
            if (cat.isAll() || cat.isDonate() || cat.getKey().equals("misc")) continue;
            if (cat.matches(material)) return cat.getKey();
        }
        return "misc";
    }

    /**
     * Check if an item matches a given category filter.
     * Supports multi-category: an item matches a category if Category.matches(item) returns true.
     * E.g., ENCHANTED_GOLDEN_APPLE matches both "food" and "gems".
     * "donate" matches ANY item with a custom display name.
     */
    public boolean itemMatchesFilter(ItemStack item, String categoryKey) {
        if (categoryKey == null || categoryKey.equalsIgnoreCase("all")) return true;
        Category cat = categories.get(categoryKey.toLowerCase());
        if (cat == null) return false;
        return cat.matches(item);
    }

    public Optional<Category> get(String key) {
        return Optional.ofNullable(categories.get(key == null ? "all" : key.toLowerCase()));
    }

    public Map<String, Category> all() {
        return Collections.unmodifiableMap(categories);
    }

    public boolean exists(String key) {
        return categories.containsKey(key == null ? "" : key.toLowerCase());
    }

    private Material parseMaterial(String name, Material fallback) {
        if (name == null || name.isBlank()) return fallback;
        String raw = name.split(":")[0].toUpperCase().trim();
        Material m = Material.getMaterial(raw);
        // Silently ignore materials that don't exist in this server version
        // (e.g. 1.21 items on a 1.17 server, or 1.19 items on a 1.16 server)
        return m != null ? m : fallback;
    }

    private void addDefault() {
        String allName  = plugin.getMessages().raw("gui.category-default-all");
        String miscName = plugin.getMessages().raw("gui.category-default-misc");
        categories.put("all",  new Category("all",  allName,  Material.COMPASS, new HashSet<>()));
        categories.put("misc", new Category("misc", miscName, Material.PAPER,   new HashSet<>()));
    }
}
