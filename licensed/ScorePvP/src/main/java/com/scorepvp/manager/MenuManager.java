package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.util.ColorUtil;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages dynamic admin-created menus stored as YAML files in the menus/ directory.
 *
 * Each menu is a template: players can take items, but reopening always restores
 * the original contents from config.
 */
public class MenuManager {

    private final ScorePvP plugin;
    private final Map<String, MenuDefinition> menus = new ConcurrentHashMap<>();

    public MenuManager(ScorePvP plugin) {
        this.plugin = plugin;
        loadAll();
    }

    // ── Menu Definition ──────────────────────────────────────────────────────

    public static class MenuDefinition {
        private final String name;
        private String title;
        private int size;
        private final Map<Integer, ItemStack> items;

        public MenuDefinition(String name, String title, int size, Map<Integer, ItemStack> items) {
            this.name  = name;
            this.title = title;
            this.size  = size;
            this.items = items;
        }

        public String getName()                  { return name; }
        public String getTitle()                 { return title; }
        public int    getSize()                  { return size; }
        public Map<Integer, ItemStack> getItems() { return items; }
    }

    // ── Load/Reload ──────────────────────────────────────────────────────────

    public void loadAll() {
        menus.clear();
        File dir = new File(plugin.getDataFolder(), "menus");
        if (!dir.exists()) dir.mkdirs();

        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;

        for (File file : files) {
            String name = file.getName().replace(".yml", "").toLowerCase();
            MenuDefinition def = loadMenu(file, name);
            if (def != null) menus.put(name, def);
        }
        plugin.getLogger().info("[ScorePvP] MenuManager: загружено " + menus.size() + " меню.");
    }

    private MenuDefinition loadMenu(File file, String name) {
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        String title = ColorUtil.color(cfg.getString("title", "&8" + name));
        int size = cfg.getInt("size", 27);
        // Clamp size to valid chest sizes
        if (size < 9) size = 9;
        if (size > 54) size = 54;
        size = (int) (Math.ceil(size / 9.0) * 9);

        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        for (Object obj : cfg.getMapList("items")) {
            if (!(obj instanceof Map<?, ?> map)) continue;
            int slot;
            try { slot = Integer.parseInt(String.valueOf(map.get("slot"))); }
            catch (NumberFormatException ex) { continue; }
            if (slot < 0 || slot >= size) continue;

            Material mat;
            try { mat = Material.valueOf(String.valueOf(map.get("material")).toUpperCase()); }
            catch (IllegalArgumentException ex) { continue; }

            int amount = 1;
            if (map.containsKey("amount")) {
                try { amount = Integer.parseInt(String.valueOf(map.get("amount"))); }
                catch (NumberFormatException ignored) {}
            }

            ItemBuilder builder = new ItemBuilder(mat, amount);
            if (map.containsKey("name")) builder.name(String.valueOf(map.get("name")));

            if (map.get("lore") instanceof List<?> loreList) {
                List<String> lore = new ArrayList<>();
                for (Object line : loreList) lore.add(String.valueOf(line));
                builder.lore(lore);
            }

            if (map.get("enchantments") instanceof Map<?, ?> encMap) {
                for (Map.Entry<?, ?> entry : encMap.entrySet()) {
                    String enchName = String.valueOf(entry.getKey()).toUpperCase();
                    int level;
                    try { level = Integer.parseInt(String.valueOf(entry.getValue())); }
                    catch (NumberFormatException ex) { continue; }
                    Enchantment ench = Enchantment.getByName(enchName);
                    if (ench == null) {
                        try {
                            ench = org.bukkit.Registry.ENCHANTMENT.get(
                                    org.bukkit.NamespacedKey.minecraft(String.valueOf(entry.getKey()).toLowerCase()));
                        } catch (Exception ignored) {}
                    }
                    if (ench != null) builder.enchant(ench, level);
                }
            }

            if (map.containsKey("custom-model-data")) {
                try { builder.customModelData(Integer.parseInt(String.valueOf(map.get("custom-model-data")))); }
                catch (NumberFormatException ignored) {}
            }
            if (Boolean.TRUE.equals(map.get("unbreakable"))) builder.unbreakable();

            // Player head texture
            if (mat == Material.PLAYER_HEAD && map.containsKey("skull-texture")) {
                String base64 = String.valueOf(map.get("skull-texture"));
                ItemStack skull = KitManager.buildSkullFromBase64(base64);
                ItemMeta skullMeta = skull.getItemMeta();
                ItemMeta builtMeta = builder.build().getItemMeta();
                if (skullMeta != null && builtMeta != null) {
                    if (builtMeta.hasDisplayName()) skullMeta.setDisplayName(builtMeta.getDisplayName());
                    if (builtMeta.hasLore()) skullMeta.setLore(builtMeta.getLore());
                    skull.setItemMeta(skullMeta);
                }
                skull.setAmount(amount);
                items.put(slot, skull);
                continue;
            }

            items.put(slot, builder.build());
        }

        return new MenuDefinition(name, title, size, items);
    }

    // ── Create ───────────────────────────────────────────────────────────────

    public boolean createMenu(String name) {
        name = name.toLowerCase();
        File file = new File(plugin.getDataFolder(), "menus/" + name + ".yml");
        if (file.exists()) return false;
        file.getParentFile().mkdirs();

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("title", "&8" + name);
        cfg.set("size", 27);
        cfg.set("items", new ArrayList<>());
        try {
            cfg.save(file);
            menus.put(name, new MenuDefinition(name, ColorUtil.color("&8" + name), 27, new LinkedHashMap<>()));
            plugin.getLogger().info("[ScorePvP] Menu '" + name + "' created.");
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[ScorePvP] Failed to create menu '" + name + "': " + e.getMessage());
            return false;
        }
    }

    // ── Open for Player (template — player can take items) ───────────────────

    public boolean openForPlayer(Player player, String name) {
        MenuDefinition def = menus.get(name.toLowerCase());
        if (def == null) return false;
        Inventory inv = Bukkit.createInventory(null, def.getSize(), def.getTitle());
        for (Map.Entry<Integer, ItemStack> entry : def.getItems().entrySet()) {
            inv.setItem(entry.getKey(), entry.getValue().clone());
        }
        player.openInventory(inv);
        return true;
    }

    // ── Open Editor (admin — saves on close) ─────────────────────────────────

    public boolean openEditor(Player admin, String name) {
        MenuDefinition def = menus.get(name.toLowerCase());
        if (def == null) return false;
        String editorTitle = ColorUtil.color("&4[Ред] ") + def.getTitle();
        Inventory inv = Bukkit.createInventory(null, def.getSize(), editorTitle);
        for (Map.Entry<Integer, ItemStack> entry : def.getItems().entrySet()) {
            inv.setItem(entry.getKey(), entry.getValue().clone());
        }
        admin.openInventory(inv);
        return true;
    }

    /** Returns the editor title prefix for matching in listeners. */
    public static String getEditorPrefix() {
        return ColorUtil.color("&4[Ред] ");
    }

    // ── Save from Inventory ──────────────────────────────────────────────────

    public void saveFromInventory(String name, Inventory inv) {
        name = name.toLowerCase();
        File file = new File(plugin.getDataFolder(), "menus/" + name + ".yml");
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        Map<Integer, ItemStack> items = new LinkedHashMap<>();
        List<Map<String, Object>> itemList = new ArrayList<>();

        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || item.getType().isAir()) continue;
            items.put(i, item.clone());
            itemList.add(serializeItem(i, item));
        }

        cfg.set("items", itemList);
        try {
            cfg.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("[ScorePvP] Failed to save menu '" + name + "': " + e.getMessage());
        }

        // Update cache
        String title = ColorUtil.color(cfg.getString("title", "&8" + name));
        int size = cfg.getInt("size", 27);
        menus.put(name, new MenuDefinition(name, title, size, items));
    }

    private Map<String, Object> serializeItem(int slot, ItemStack item) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("slot", slot);
        entry.put("material", item.getType().name());
        entry.put("amount", item.getAmount());

        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            if (meta.hasDisplayName()) {
                entry.put("name", net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                        .legacyAmpersand().serialize(meta.displayName()));
            }
            if (meta.hasLore() && meta.lore() != null) {
                List<String> loreStrings = new ArrayList<>();
                for (net.kyori.adventure.text.Component line : Objects.requireNonNull(meta.lore())) {
                    loreStrings.add(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacyAmpersand().serialize(line));
                }
                entry.put("lore", loreStrings);
            }
            if (!meta.getEnchants().isEmpty()) {
                Map<String, Integer> enchMap = new LinkedHashMap<>();
                meta.getEnchants().forEach((ench, level) -> enchMap.put(ench.getKey().getKey(), level));
                entry.put("enchantments", enchMap);
            }
            if (item.getType() == Material.PLAYER_HEAD
                    && meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                org.bukkit.profile.PlayerProfile profile = skullMeta.getOwnerProfile();
                if (profile != null && profile.getTextures().getSkin() != null) {
                    entry.put("skull-texture", profile.getTextures().getSkin().toString());
                }
            }
            if (meta.hasCustomModelData()) entry.put("custom-model-data", meta.getCustomModelData());
            if (meta.isUnbreakable()) entry.put("unbreakable", true);
        }
        return entry;
    }

    // ── Queries ──────────────────────────────────────────────────────────────

    public MenuDefinition getMenu(String name) {
        return menus.get(name.toLowerCase());
    }

    public Collection<String> getMenuNames() {
        return menus.keySet();
    }

    public boolean isMenuTitle(String title) {
        for (MenuDefinition def : menus.values()) {
            if (def.getTitle().equals(title)) return true;
        }
        return false;
    }

    public boolean isEditorTitle(String title) {
        return title.startsWith(getEditorPrefix());
    }

    public String getMenuNameByEditorTitle(String title) {
        String prefix = getEditorPrefix();
        if (!title.startsWith(prefix)) return null;
        String menuTitle = title.substring(prefix.length());
        for (MenuDefinition def : menus.values()) {
            if (def.getTitle().equals(menuTitle)) return def.getName();
        }
        return null;
    }
}
