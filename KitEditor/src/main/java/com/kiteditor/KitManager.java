package com.kiteditor;

import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;

public class KitManager {

    private final JavaPlugin plugin;
    private final File kitsFolder;
    private final File dataFolder;

    public KitManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.kitsFolder = new File(plugin.getDataFolder(), "kits");
        this.dataFolder = new File(plugin.getDataFolder(), "data");

        if (!kitsFolder.exists()) kitsFolder.mkdirs();
        if (!dataFolder.exists()) dataFolder.mkdirs();

        copyBundledKits();
    }

    private void copyBundledKits() {
        String[] bundledKits = {
            "axe.yml", "diamondsmp.yml", "mace.yml", "netherite.yml",
            "nodebuff.yml", "op.yml", "pot.yml", "potion.yml",
            "smp.yml", "sword.yml", "uhc.yml", "vanilla.yml"
        };
        for (String kitFile : bundledKits) {
            File dest = new File(kitsFolder, kitFile);
            if (!dest.exists()) {
                try (java.io.InputStream in = plugin.getResource("kits/" + kitFile)) {
                    if (in != null) {
                        java.nio.file.Files.copy(in, dest.toPath());
                    }
                } catch (IOException e) {
                    plugin.getLogger().warning("Failed to extract bundled kit: " + kitFile);
                }
            }
        }
    }

    public List<String> getKitNames() {
        List<String> names = new ArrayList<>();
        File[] files = kitsFolder.listFiles((d, n) -> n.endsWith(".yml"));
        if (files != null) {
            for (File file : files) {
                names.add(file.getName().replace(".yml", ""));
            }
        }
        Collections.sort(names);
        return names;
    }

    public ItemStack[] loadDefaultKit(String kitName) {
        File file = new File(kitsFolder, kitName + ".yml");
        if (!file.exists()) return new ItemStack[54];

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        return parseItemsFromConfig(config);
    }

    public ItemStack[] loadPlayerKit(UUID uuid, String kitName) {
        File file = new File(dataFolder, uuid.toString() + "-" + kitName + ".yml");
        if (!file.exists()) return loadDefaultKit(kitName);

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);
        ItemStack[] items = new ItemStack[54];

        for (int i = 0; i < 54; i++) {
            if (config.contains("slot." + i)) {
                Object obj = config.get("slot." + i);
                if (obj instanceof ItemStack) {
                    items[i] = (ItemStack) obj;
                }
            }
        }

        return items;
    }

    public void savePlayerKit(UUID uuid, String kitName, ItemStack[] items) {
        File file = new File(dataFolder, uuid.toString() + "-" + kitName + ".yml");
        FileConfiguration config = new YamlConfiguration();

        for (int i = 0; i < 54; i++) {
            if (items[i] != null && items[i].getType() != Material.AIR) {
                config.set("slot." + i, items[i]);
            }
        }

        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save kit for " + uuid);
        }
    }

    public void resetPlayerKit(UUID uuid, String kitName) {
        File file = new File(dataFolder, uuid.toString() + "-" + kitName + ".yml");
        if (file.exists()) file.delete();
    }

    private ItemStack[] parseItemsFromConfig(FileConfiguration config) {
        ItemStack[] items = new ItemStack[54];

        if (!config.contains("items")) return items;

        List<Map<?, ?>> itemList = config.getMapList("items");
        for (Map<?, ?> itemData : itemList) {
            int slot = toInt(itemData.get("slot"));
            if (slot < 0 || slot > 53) continue;

            String materialName = (String) itemData.get("material");
            if (materialName == null) continue;

            Material material = Material.matchMaterial(materialName);
            if (material == null) {
                plugin.getLogger().warning("Unknown material: " + materialName);
                continue;
            }

            int amount = itemData.containsKey("amount") ? toInt(itemData.get("amount")) : 1;
            ItemStack item = new ItemStack(material, amount);
            ItemMeta meta = item.getItemMeta();

            if (meta == null) {
                items[slot] = item;
                continue;
            }

            if (itemData.containsKey("name")) {
                meta.setDisplayName(colorize((String) itemData.get("name")));
            }

            if (itemData.containsKey("lore")) {
                List<String> rawLore = (List<String>) itemData.get("lore");
                List<String> lore = new ArrayList<>();
                for (String line : rawLore) {
                    lore.add(colorize(line));
                }
                meta.setLore(lore);
            }

            if (itemData.containsKey("enchantments")) {
                Map<String, Object> enchants = (Map<String, Object>) itemData.get("enchantments");
                for (Map.Entry<String, Object> ench : enchants.entrySet()) {
                    Enchantment enchantment = getEnchantment(ench.getKey());
                    if (enchantment != null) {
                        int level = toInt(ench.getValue());
                        meta.addEnchant(enchantment, level, true);
                    }
                }
            }

            if (itemData.containsKey("custom-model-data")) {
                meta.setCustomModelData(toInt(itemData.get("custom-model-data")));
            }

            if (itemData.containsKey("unbreakable")) {
                meta.setUnbreakable(Boolean.parseBoolean(itemData.get("unbreakable").toString()));
            }

            if (itemData.containsKey("flags")) {
                List<String> flags = (List<String>) itemData.get("flags");
                for (String flagName : flags) {
                    try {
                        meta.addItemFlags(ItemFlag.valueOf(flagName.toUpperCase()));
                    } catch (IllegalArgumentException ignored) {}
                }
            }

            if (itemData.containsKey("potion-base-type") && meta instanceof PotionMeta) {
                PotionMeta potionMeta = (PotionMeta) meta;
                String potionStr = ((String) itemData.get("potion-base-type")).toUpperCase();
                try {
                    PotionType potionType = PotionType.valueOf(potionStr);
                    boolean extended = potionStr.startsWith("LONG_");
                    boolean upgraded = potionStr.startsWith("STRONG_");

                    PotionType baseType = potionType;
                    if (potionStr.startsWith("LONG_") || potionStr.startsWith("STRONG_")) {
                        baseType = PotionType.valueOf(potionStr.substring(potionStr.indexOf("_") + 1));
                    }

                    potionMeta.setBasePotionData(new PotionData(baseType, extended, upgraded));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Unknown potion type: " + potionStr);
                }
            }

            if (itemData.containsKey("skull-texture") && meta instanceof SkullMeta) {
                SkullMeta skullMeta = (SkullMeta) meta;
                String textureUrl = (String) itemData.get("skull-texture");
                applySkullTexture(skullMeta, textureUrl);
            }

            if (itemData.containsKey("custom-item-id")) {
                meta.getPersistentDataContainer().set(
                    new NamespacedKey(plugin, "custom_item_id"),
                    org.bukkit.persistence.PersistentDataType.STRING,
                    (String) itemData.get("custom-item-id")
                );
            }

            item.setItemMeta(meta);
            items[slot] = item;
        }

        return items;
    }

    private Enchantment getEnchantment(String name) {
        String upper = name.toUpperCase();

        Enchantment ench = Enchantment.getByName(upper);
        if (ench != null) return ench;

        ench = Enchantment.getByKey(NamespacedKey.minecraft(name.toLowerCase()));
        if (ench != null) return ench;

        Map<String, String> aliases = new HashMap<>();
        aliases.put("SHARPNESS", "DAMAGE_ALL");
        aliases.put("SMITE", "DAMAGE_UNDEAD");
        aliases.put("BANE_OF_ARTHROPODS", "DAMAGE_ARTHROPODS");
        aliases.put("KNOCKBACK", "KNOCKBACK");
        aliases.put("FIRE_ASPECT", "FIRE_ASPECT");
        aliases.put("LOOTING", "LOOT_BONUS_MOBS");
        aliases.put("SWEEPING_EDGE", "SWEEPING_EDGE");
        aliases.put("EFFICIENCY", "DIG_SPEED");
        aliases.put("SILK_TOUCH", "SILK_TOUCH");
        aliases.put("UNBREAKING", "DURABILITY");
        aliases.put("FORTUNE", "LOOT_BONUS_BLOCKS");
        aliases.put("POWER", "ARROW_DAMAGE");
        aliases.put("PUNCH", "ARROW_KNOCKBACK");
        aliases.put("FLAME", "ARROW_FIRE");
        aliases.put("INFINITY", "ARROW_INFINITE");
        aliases.put("PROTECTION", "PROTECTION_ENVIRONMENTAL");
        aliases.put("FIRE_PROTECTION", "PROTECTION_FIRE");
        aliases.put("FEATHER_FALLING", "PROTECTION_FALL");
        aliases.put("BLAST_PROTECTION", "PROTECTION_EXPLOSIONS");
        aliases.put("PROJECTILE_PROTECTION", "PROTECTION_PROJECTILE");
        aliases.put("RESPIRATION", "OXYGEN");
        aliases.put("AQUA_AFFINITY", "WATER_WORKER");
        aliases.put("THORNS", "THORNS");
        aliases.put("DEPTH_STRIDER", "DEPTH_STRIDER");
        aliases.put("FROST_WALKER", "FROST_WALKER");
        aliases.put("MENDING", "MENDING");
        aliases.put("CURSE_OF_VANISHING", "VANISHING_CURSE");
        aliases.put("CURSE_OF_BINDING", "BINDING_CURSE");
        aliases.put("MULTISHOT", "MULTISHOT");
        aliases.put("PIERCING", "PIERCING");
        aliases.put("QUICK_CHARGE", "QUICK_CHARGE");
        aliases.put("SOUL_SPEED", "SOUL_SPEED");
        aliases.put("SWIFT_SNEAK", "SWIFT_SNEAK");
        aliases.put("DENSITY", "DENSITY");
        aliases.put("BREACH", "BREACH");
        aliases.put("WIND_BURST", "WIND_BURST");

        String alias = aliases.get(upper);
        if (alias != null) {
            ench = Enchantment.getByName(alias);
            if (ench != null) return ench;
        }

        plugin.getLogger().warning("Unknown enchantment: " + name);
        return null;
    }

    private void applySkullTexture(SkullMeta skullMeta, String textureUrl) {
        try {
            String textureValue = Base64.getEncoder().encodeToString(
                String.format("{\"textures\":{\"SKIN\":{\"url\":\"%s\"}}}", textureUrl).getBytes()
            );

            GameProfile profile = new GameProfile(UUID.randomUUID(), null);
            profile.addProperty("textures", new Property("textures", textureValue));

            Field profileField = skullMeta.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            profileField.set(skullMeta, profile);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to apply skull texture: " + textureUrl);
        }
    }

    private int toInt(Object obj) {
        if (obj instanceof Integer) return (Integer) obj;
        if (obj instanceof Long) return ((Long) obj).intValue();
        if (obj instanceof Double) return ((Double) obj).intValue();
        if (obj instanceof String) return Integer.parseInt((String) obj);
        return 0;
    }

    private String colorize(String text) {
        if (text == null) return "";
        return text.replace("&", "§");
    }

    private static class GameProfile {
        private final UUID id;
        private final String name;
        private final Map<String, java.util.Collection<Property>> properties = new HashMap<>();

        GameProfile(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        java.util.Collection<Property> getProperties() {
            return properties.computeIfAbsent("textures", k -> new ArrayList<>());
        }

        void addProperty(String name, Property property) {
            getProperties().add(property);
        }
    }

    private static class Property {
        private final String name;
        private final String value;

        Property(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }
}
