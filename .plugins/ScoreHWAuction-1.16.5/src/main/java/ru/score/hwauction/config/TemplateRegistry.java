package ru.score.hwauction.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import ru.score.hwauction.ScoreHWAuction;
import ru.score.hwauction.model.ItemTemplate;
import ru.score.hwauction.util.ItemSerializer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores admin-registered {@link ItemTemplate}s in {@code templates.yml}.
 * Templates let an admin pin an "original" item's category and searchable name
 * by its characteristics (NBT/enchants/lore), immune to player renaming.
 */
public final class TemplateRegistry {

    private final ScoreHWAuction plugin;
    private final File file;
    private final Map<String, ItemTemplate> templates = new LinkedHashMap<>();

    public TemplateRegistry(ScoreHWAuction plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "templates.yml");
        reload();
    }

    public void reload() {
        templates.clear();
        if (!file.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = cfg.getConfigurationSection("templates");
        if (root == null) return;
        for (String id : root.getKeys(false)) {
            try {
                String name     = root.getString(id + ".search-name", id);
                String category = root.getString(id + ".category", "misc");
                String modeStr  = root.getString(id + ".match-mode", "MATERIAL_ENCHANTS");
                String data     = root.getString(id + ".item");
                if (data == null || data.isBlank()) continue;
                ItemStack sample = ItemSerializer.deserialize(data);
                ItemTemplate.MatchMode mode;
                try { mode = ItemTemplate.MatchMode.valueOf(modeStr.toUpperCase()); }
                catch (IllegalArgumentException ex) { mode = ItemTemplate.MatchMode.MATERIAL_ENCHANTS; }
                templates.put(id, new ItemTemplate(id, name, category.toLowerCase(), mode, sample));
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping invalid template '" + id + "': " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + templates.size() + " item templates.");
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (ItemTemplate t : templates.values()) {
            String base = "templates." + t.getId();
            cfg.set(base + ".search-name", t.getSearchName());
            cfg.set(base + ".category",    t.getCategory());
            cfg.set(base + ".match-mode",  t.getMatchMode().name());
            cfg.set(base + ".item",        ItemSerializer.serialize(t.getSample()));
        }
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save templates.yml: " + e.getMessage());
        }
    }

    /** Adds and persists a template. Overwrites any existing template with the same id. */
    public void add(ItemTemplate template) {
        templates.put(template.getId(), template);
        save();
    }

    /** Removes a template by id. Returns true if it existed. */
    public boolean remove(String id) {
        boolean existed = templates.remove(id) != null;
        if (existed) save();
        return existed;
    }

    public Optional<ItemTemplate> get(String id) {
        return Optional.ofNullable(templates.get(id));
    }

    /** First template whose characteristics match the item, or empty. */
    public Optional<ItemTemplate> findMatching(ItemStack item) {
        if (item == null) return Optional.empty();
        for (ItemTemplate t : templates.values()) {
            if (t.matches(item)) return Optional.of(t);
        }
        return Optional.empty();
    }

    public List<ItemTemplate> all() {
        return new ArrayList<>(templates.values());
    }

    /** Whether an id is already taken. */
    public boolean exists(String id) {
        return templates.containsKey(id);
    }

    /** Search names of all templates (for tab-complete catalog). */
    public List<String> allSearchNames() {
        List<String> names = new ArrayList<>();
        for (ItemTemplate t : templates.values()) names.add(t.getSearchName());
        return Collections.unmodifiableList(names);
    }
}
