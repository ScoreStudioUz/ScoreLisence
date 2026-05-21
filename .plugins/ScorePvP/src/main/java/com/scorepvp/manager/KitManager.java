package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.util.ItemBuilder;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages vanilla kits.
 *
 * Kit-type in modes.yml: "vanilla"  → kits/vanilla.yml (default kit)
 *
 * Each player can have one personal vanilla kit saved in the DB.
 * The "default" kit is the admin-set vanilla kit (/spvp kit set vanilla).
 *
 * Section 4 additions:
 *   /spvp kit create <name>   — create a named kit file
 *   /spvp kit save <name>     — save current inventory into the named kit
 *   /spvp kit set vanilla     — set current admin inventory as the default vanilla kit
 *   /kit reset                — reset player inventory to default vanilla kit (blocked in region)
 */
public class KitManager {

    private final ScorePvP plugin;

    /** Personal kit cache: uuid → inventory contents (41 slots). Empty array = not saved. */
    private final Map<UUID, ItemStack[]> personalKits = new ConcurrentHashMap<>();

    /** Named kits created via /spvp kit create (held in memory, backed by files). */
    private final Map<String, ItemStack[]> namedKitCache = new ConcurrentHashMap<>();

    public KitManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void loadAsync(UUID uuid) {
        plugin.getDatabase().loadPersonalKitAsync(uuid)
                .thenAccept(items -> {
                    if (items != null && items.length > 0) {
                        personalKits.put(uuid, items);
                    } else {
                        personalKits.put(uuid, new ItemStack[0]);
                    }
                });
    }

    public void unload(UUID uuid) {
        personalKits.remove(uuid);
    }

    // ── Give Kit ──────────────────────────────────────────────────────────────

    /**
     * Gives the vanilla kit to the player.
     * Personal kit → default kit fallback.
     */
    public void giveVanillaKit(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        ItemStack[] personal = personalKits.get(player.getUniqueId());
        if (personal != null && personal.length > 0) {
            applyAllContents(player, personal);
        } else {
            applyDefaultKit(player, "vanilla");
        }
    }

    /** Gives strictly the default vanilla kit, ignoring any personal kit. */
    public void giveDefaultVanillaKit(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        applyDefaultKit(player, "vanilla");
    }

    public void giveKitByType(Player player, String kitType) {
        player.getInventory().clear();
        String kitName = kitType;
        if (kitName.equals("vanilla")) {
            giveVanillaKit(player);
            return;
        }
        applyDefaultKit(player, kitName);
    }

    // ── Section 2: Kit Reset — data-only ─────────────────────────────────────

    /**
     * Clears the player's personal kit DATA from memory and DB.
     * Does NOT give a kit immediately. On next vanilla region entry,
     * the player receives the default kit.
     */
    public void resetPersonalKit(Player player) {
        personalKits.put(player.getUniqueId(), new ItemStack[0]);
        plugin.getDatabase().savePersonalKitAsync(player.getUniqueId(), new ItemStack[0]);
        saveKitToFile(player.getUniqueId(), new ItemStack[0]);
        plugin.getPlayerDataManager().setKitResetPending(player.getUniqueId(), true);
        player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("kit.reset-done"));
    }

    public boolean consumeResetPending(java.util.UUID uuid) {
        boolean p = plugin.getPlayerDataManager().isKitResetPending(uuid);
        if (p) plugin.getPlayerDataManager().setKitResetPending(uuid, false);
        return p;
    }

    /** Compat alias. */
    public void resetToDefault(Player player) { resetPersonalKit(player); }

    // ── Section 4: Named kit management ──────────────────────────────────────

    /**
     * Creates an empty kit file with the given name.
     * /spvp kit create <name>
     */
    public boolean createNamedKit(String name) {
        File kitFile = new File(plugin.getDataFolder(), "kits/" + name + ".yml");
        if (kitFile.exists()) return false;
        kitFile.getParentFile().mkdirs();
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("items", new ArrayList<>());
        try {
            cfg.save(kitFile);
            plugin.getLogger().info("[ScorePvP] Kit '" + name + "' created.");
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[ScorePvP] Failed to create kit '" + name + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Saves the admin's current inventory into the named kit file.
     * Includes: all main slots, armor, offhand, custom items, all enchantments.
     * /spvp kit save <name>
     */
    public boolean saveNamedKit(Player admin, String name) {
        File kitFile = new File(plugin.getDataFolder(), "kits/" + name + ".yml");
        kitFile.getParentFile().mkdirs();

        ItemStack[] main    = admin.getInventory().getContents().clone();
        ItemStack[] armor   = admin.getInventory().getArmorContents().clone();
        ItemStack   offhand = admin.getInventory().getItemInOffHand();

        ItemStack[] full = new ItemStack[41];
        System.arraycopy(main, 0, full, 0, Math.min(main.length, 36));
        System.arraycopy(armor, 0, full, 36, Math.min(armor.length, 4));
        if (offhand != null && !offhand.getType().isAir()) full[40] = offhand.clone();

        // Build YAML items list from inventory
        List<Map<String, Object>> itemList = serializeKitItems(full);

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("items", itemList);
        try {
            cfg.save(kitFile);
            namedKitCache.put(name, full);
            plugin.getLogger().info("[ScorePvP] Kit '" + name + "' saved by " + admin.getName());
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[ScorePvP] Failed to save kit '" + name + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Sets the current admin inventory as the default vanilla kit.
     * /spvp kit set vanilla
     */
    public boolean setVanillaDefault(Player admin) {
        return saveNamedKit(admin, "vanilla");
    }

    // ── Save personal kit ─────────────────────────────────────────────────────

    public boolean savePersonalKit(Player player) {
        if (!plugin.getConfigManager().isInChestRegion(player.getLocation())) return false;

        ItemStack[] main    = player.getInventory().getContents().clone();
        ItemStack[] armor   = player.getInventory().getArmorContents().clone();
        ItemStack   offhand = player.getInventory().getItemInOffHand();

        ItemStack[] contents = new ItemStack[41];
        System.arraycopy(main, 0, contents, 0, Math.min(main.length, 36));
        System.arraycopy(armor, 0, contents, 36, Math.min(armor.length, 4));
        if (offhand != null && !offhand.getType().isAir()) contents[40] = offhand.clone();

        personalKits.put(player.getUniqueId(), contents);
        plugin.getDatabase().savePersonalKitAsync(player.getUniqueId(), contents);
        saveKitToFile(player.getUniqueId(), contents);  // Section 2: file mirror
        return true;
    }

    public boolean hasPersonalKit(UUID uuid) {
        ItemStack[] kit = personalKits.get(uuid);
        return kit != null && kit.length > 0;
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    /**
     * Serializes a full 41-slot kit into a List<Map> suitable for YAML storage.
     * Preserves: material, amount, slot, custom name, lore, enchantments.
     * Also handles player_head custom texture via item serialization.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> serializeKitItems(ItemStack[] items) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int slot = 0; slot < items.length; slot++) {
            ItemStack item = items[slot];
            if (item == null || item.getType().isAir()) continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slot", slot);
            entry.put("material", item.getType().name());
            entry.put("amount", item.getAmount());

            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                if (meta.hasDisplayName()) {
                    // Store as legacy string
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
                // Player head custom texture
                if (item.getType() == Material.PLAYER_HEAD
                        && meta instanceof org.bukkit.inventory.meta.SkullMeta skullMeta) {
                    org.bukkit.profile.PlayerProfile profile = skullMeta.getOwnerProfile();
                    if (profile != null && profile.getTextures().getSkin() != null) {
                        entry.put("skull-texture", profile.getTextures().getSkin().toString());
                    }
                }
                // Custom model data
                if (meta.hasCustomModelData()) {
                    entry.put("custom-model-data", meta.getCustomModelData());
                }
                // Unbreakable
                if (meta.isUnbreakable()) {
                    entry.put("unbreakable", true);
                }
            }
            list.add(entry);
        }
        return list;
    }


    // ── Section 2: File-based kit storage (fallback) ─────────────────────────

    private void saveKitToFile(java.util.UUID uuid, ItemStack[] items) {
        try {
            java.io.File dir = new java.io.File(plugin.getDataFolder(), "userdata");
            dir.mkdirs();
            java.io.File file = new java.io.File(dir, uuid.toString() + ".yml");
            org.bukkit.configuration.file.YamlConfiguration cfg = new org.bukkit.configuration.file.YamlConfiguration();
            java.util.List<java.util.Map<String, Object>> serialized = new java.util.ArrayList<>();
            for (int i = 0; i < items.length; i++) {
                if (items[i] == null || items[i].getType().isAir()) continue;
                java.util.Map<String, Object> entry = new java.util.LinkedHashMap<>();
                entry.put("slot", i);
                entry.put("item", items[i].serialize());
                serialized.add(entry);
            }
            cfg.set("kit", serialized);
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[ScorePvP] saveKitToFile failed for " + uuid + ": " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private ItemStack[] loadKitFromFile(java.util.UUID uuid) {
        try {
            java.io.File file = new java.io.File(plugin.getDataFolder(), "userdata/" + uuid.toString() + ".yml");
            if (!file.exists()) return null;
            org.bukkit.configuration.file.YamlConfiguration cfg =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
            java.util.List<?> list = cfg.getList("kit");
            if (list == null || list.isEmpty()) return null;
            ItemStack[] result = new ItemStack[41];
            for (Object obj : list) {
                if (!(obj instanceof java.util.Map<?,?> map)) continue;
                int slot;
                try { slot = Integer.parseInt(String.valueOf(map.get("slot"))); } catch (NumberFormatException ex) { continue; }
                if (slot < 0 || slot >= result.length) continue;
                Object itemData = map.get("item");
                if (itemData instanceof java.util.Map) {
                    try { result[slot] = ItemStack.deserialize((java.util.Map<String,Object>)itemData); }
                    catch (Exception ex) { plugin.getLogger().warning("[ScorePvP] Kit slot " + slot + " deserialization error"); }
                }
            }
            return result;
        } catch (Exception e) {
            plugin.getLogger().warning("[ScorePvP] loadKitFromFile failed for " + uuid + ": " + e.getMessage());
            return null;
        }
    }

    // ── Default Kit Loader ────────────────────────────────────────────────────

    private void applyAllContents(Player player, ItemStack[] items) {
        org.bukkit.inventory.PlayerInventory inv = player.getInventory();
        if (items.length > 36) {
            ItemStack[] armor = new ItemStack[]{
                items.length > 36 ? items[36] : null,
                items.length > 37 ? items[37] : null,
                items.length > 38 ? items[38] : null,
                items.length > 39 ? items[39] : null
            };
            inv.setArmorContents(armor);
        }
        if (items.length > 40) inv.setItemInOffHand(items[40]);
        for (int i = 0; i < Math.min(36, items.length); i++) {
            inv.setItem(i, items[i]);
        }
    }

    private void applyDefaultKit(Player player, String kitName) {
        ItemStack[] items = loadDefaultKit(kitName);
        applyCosmeticsToKit(player, items);
        player.getInventory().setContents(items);
    }

    /**
     * Walks the resolved kit contents and replaces vanilla shield/diamond/
     * netherite armor items with the player's selected cosmetic counterparts,
     * if any. Only runs when the player has {@code duels.cosmetics} permission.
     *
     * The kit array layout matches what {@link #loadDefaultKit} returns:
     *   0..35 = main inventory, 36..39 = armor (helmet, chest, leggings, boots),
     *   40 = offhand. We scan the main inventory for shields (often offhand
     *   appears in slot 40 too) and replace by exact material match.
     */
    private void applyCosmeticsToKit(Player player, ItemStack[] items) {
        if (!player.hasPermission("duels.cosmetics")) return;
        com.scorepvp.manager.CosmeticsManager mgr = plugin.getCosmeticsManager();
        if (mgr == null) return;
        java.util.UUID uid = player.getUniqueId();

        // Shield (any slot containing a SHIELD)
        ItemStack cosShield = mgr.getSelectedShield(uid);
        if (cosShield != null) {
            for (int i = 0; i < items.length; i++) {
                ItemStack it = items[i];
                if (it != null && it.getType() == Material.SHIELD) {
                    items[i] = cosShield.clone();
                }
            }
        }

        // Armor slots: 36=helmet, 37=chest, 38=leggings, 39=boots
        replaceArmorSlot(items, 36, uid, com.scorepvp.manager.CosmeticsManager.ArmorPiece.HELMET);
        replaceArmorSlot(items, 37, uid, com.scorepvp.manager.CosmeticsManager.ArmorPiece.CHESTPLATE);
        replaceArmorSlot(items, 38, uid, com.scorepvp.manager.CosmeticsManager.ArmorPiece.LEGGINGS);
        replaceArmorSlot(items, 39, uid, com.scorepvp.manager.CosmeticsManager.ArmorPiece.BOOTS);
    }

    private void replaceArmorSlot(ItemStack[] items, int slot, java.util.UUID uid,
                                   com.scorepvp.manager.CosmeticsManager.ArmorPiece piece) {
        ItemStack it = items[slot];
        if (it == null || it.getType().isAir()) return;
        Material m = it.getType();
        com.scorepvp.manager.CosmeticsManager.ArmorMaterial mat;
        if (m == Material.DIAMOND_HELMET || m == Material.DIAMOND_CHESTPLATE
                || m == Material.DIAMOND_LEGGINGS || m == Material.DIAMOND_BOOTS) {
            mat = com.scorepvp.manager.CosmeticsManager.ArmorMaterial.DIAMOND;
        } else if (m == Material.NETHERITE_HELMET || m == Material.NETHERITE_CHESTPLATE
                || m == Material.NETHERITE_LEGGINGS || m == Material.NETHERITE_BOOTS) {
            mat = com.scorepvp.manager.CosmeticsManager.ArmorMaterial.NETHERITE;
        } else return;
        ItemStack cos = plugin.getCosmeticsManager().getSelectedArmor(uid, mat, piece);
        if (cos != null) {
            // Preserve original enchantments by copying them onto the cosmetic clone
            ItemStack out = cos.clone();
            var meta = out.getItemMeta();
            if (meta != null && it.getItemMeta() != null) {
                for (var ench : it.getItemMeta().getEnchants().entrySet()) {
                    meta.addEnchant(ench.getKey(), ench.getValue(), true);
                }
                out.setItemMeta(meta);
            }
            items[slot] = out;
        }
    }

    private ItemStack[] loadDefaultKit(String kitName) {
        // Check in-memory cache for admin-saved kits first
        if (namedKitCache.containsKey(kitName)) {
            return namedKitCache.get(kitName).clone();
        }

        File kitFile = new File(plugin.getDataFolder(), "kits/" + kitName + ".yml");
        if (!kitFile.exists()) {
            try {
                plugin.saveResource("kits/" + kitName + ".yml", false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("[ScorePvP] Kit file not found: kits/" + kitName + ".yml");
                return new ItemStack[41];
            }
        }

        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(kitFile);
        ItemStack[] result = new ItemStack[41];

        for (Object obj : cfg.getMapList("items")) {
            if (!(obj instanceof Map<?, ?> map)) continue;

            int slot;
            try { slot = Integer.parseInt(String.valueOf(map.get("slot"))); }
            catch (NumberFormatException ex) { continue; }
            if (slot < 0 || slot >= result.length) continue;

            Material mat;
            try { mat = Material.valueOf(String.valueOf(map.get("material")).toUpperCase()); }
            catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[ScorePvP] Unknown material in kit " + kitName + " slot " + slot);
                continue;
            }

            int amount = 1;
            if (map.containsKey("amount")) {
                try { amount = Integer.parseInt(String.valueOf(map.get("amount"))); }
                catch (NumberFormatException ignored) {}
            }

            ItemBuilder builder = new ItemBuilder(mat, amount);

            if (map.containsKey("name")) {
                builder.name(String.valueOf(map.get("name")));
            }

            if (map.get("enchantments") instanceof Map<?, ?> encMap) {
                for (Map.Entry<?, ?> entry : encMap.entrySet()) {
                    String enchName = String.valueOf(entry.getKey()).toUpperCase();
                    int level;
                    try { level = Integer.parseInt(String.valueOf(entry.getValue())); }
                    catch (NumberFormatException ex) { continue; }
                    Enchantment ench = Enchantment.getByName(enchName);
                    if (ench == null) {
                        // Try registry key lookup
                        try {
                            ench = org.bukkit.Registry.ENCHANTMENT.get(
                                    org.bukkit.NamespacedKey.minecraft(String.valueOf(entry.getKey()).toLowerCase()));
                        } catch (Exception ignored) {}
                    }
                    if (ench != null) builder.enchant(ench, level);
                    else plugin.getLogger().warning("[ScorePvP] Unknown enchantment: " + enchName);
                }
            }

            // Skull texture
            if (mat == Material.PLAYER_HEAD && map.containsKey("skull-texture")) {
                String textureUrl = String.valueOf(map.get("skull-texture"));
                result[slot] = buildSkullFromUrl(textureUrl, builder);
                continue;
            }

            if (map.containsKey("custom-model-data")) {
                try { builder.customModelData(Integer.parseInt(String.valueOf(map.get("custom-model-data")))); }
                catch (NumberFormatException ignored) {}
            }
            if (Boolean.TRUE.equals(map.get("unbreakable"))) {
                builder.unbreakable();
            }

            result[slot] = builder.build();
        }

        return result;
    }

    /** Builds a PLAYER_HEAD ItemStack with a skin texture URL. */
    private ItemStack buildSkullFromUrl(String textureUrl, ItemBuilder builder) {
        try {
            ItemStack skull = builder.build();
            org.bukkit.inventory.meta.SkullMeta skullMeta =
                    (org.bukkit.inventory.meta.SkullMeta) skull.getItemMeta();
            if (skullMeta == null) return skull;

            // Use PlayerProfile to set texture
            java.util.UUID fakeUUID = java.util.UUID.nameUUIDFromBytes(textureUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.bukkit.profile.PlayerProfile profile =
                    org.bukkit.Bukkit.createPlayerProfile(fakeUUID, "ScorePvPHead");
            org.bukkit.profile.PlayerTextures textures = profile.getTextures();
            textures.setSkin(java.net.URI.create(textureUrl).toURL());
            profile.setTextures(textures);
            skullMeta.setOwnerProfile(profile);
            skull.setItemMeta(skullMeta);
            return skull;
        } catch (Exception e) {
            plugin.getLogger().warning("[ScorePvP] Failed to apply skull texture: " + e.getMessage());
            return builder.build();
        }
    }

    /** Builds a PLAYER_HEAD with a raw Base64 texture value. */
    public static ItemStack buildSkullFromBase64(String base64Value) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) skull.getItemMeta();
        if (meta == null) return skull;
        try {
            // Decode base64 to get the URL from the JSON
            String decoded = new String(java.util.Base64.getDecoder().decode(base64Value), java.nio.charset.StandardCharsets.UTF_8);
            // JSON looks like: {"textures":{"SKIN":{"url":"http://..."}}}
            String skinUrl = decoded.replaceAll(".*\"url\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            java.util.UUID fakeUUID = java.util.UUID.nameUUIDFromBytes(base64Value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.bukkit.profile.PlayerProfile profile =
                    org.bukkit.Bukkit.createPlayerProfile(fakeUUID, "ScorePvPCustom");
            org.bukkit.profile.PlayerTextures textures = profile.getTextures();
            textures.setSkin(java.net.URI.create(skinUrl).toURL());
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
        } catch (Exception e) {
            // fallback — plain skull
        }
        skull.setItemMeta(meta);
        return skull;
    }
}
