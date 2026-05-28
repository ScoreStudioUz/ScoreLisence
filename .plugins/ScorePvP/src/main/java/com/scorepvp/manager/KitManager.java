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
 * Personal kits are stored as YAML files at {@code plugins/ScorePvP/data/{uuid}.yml}.
 * The schema mirrors the named-kit format ({@code items: [{slot, material, …}]})
 * so the same loader handles both.
 *
 * No SQLite is used for personal kits — the database path was deprecated due to
 * Bukkit ItemStack binary deserialization failing across server versions
 * ("Failed to deserialize ItemStack array").
 *
 * Section 4:
 *   /spvp kit create <name>   — create a named kit file
 *   /spvp kit save <name>     — save current inventory into the named kit
 *   /spvp kit set vanilla     — set current admin inventory as the default vanilla kit
 *   /kit reset                — reset player inventory to default vanilla kit (blocked in region)
 */
public class KitManager {

    private static final String DATA_DIR = "data-player";

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
        // Personal kits live as YAML files now — load synchronously off the main
        // thread so we don't block joins; YAML parsing is fast enough.
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            ItemStack[] items = loadKitFromFile(uuid);
            personalKits.put(uuid, items != null ? items : new ItemStack[0]);
        });
    }

    public void unload(UUID uuid) {
        personalKits.remove(uuid);
    }

    // ── Give Kit ──────────────────────────────────────────────────────────────

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

    public void giveDefaultVanillaKit(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        applyDefaultKit(player, "vanilla");
    }

    public void giveKitByType(Player player, String kitType) {
        player.getInventory().clear();
        if (kitType.equals("vanilla")) {
            giveVanillaKit(player);
            return;
        }
        applyDefaultKit(player, kitType);
    }

    // ── Personal kit reset ─────────────────────────────────────────────────────

    public void resetPersonalKit(Player player) {
        personalKits.put(player.getUniqueId(), new ItemStack[0]);
        deleteKitFile(player.getUniqueId());
        plugin.getPlayerDataManager().setKitResetPending(player.getUniqueId(), true);
        player.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("kit.reset-done"));
    }

    public boolean consumeResetPending(UUID uuid) {
        boolean p = plugin.getPlayerDataManager().isKitResetPending(uuid);
        if (p) plugin.getPlayerDataManager().setKitResetPending(uuid, false);
        return p;
    }

    public void resetToDefault(Player player) { resetPersonalKit(player); }

    // ── Named kit management ─────────────────────────────────────────────────

    /**
     * Normalises a user-supplied kit name so we never write garbage filenames.
     * Lowercase, trim, replace whitespace with {@code _}, strip anything that
     * isn't {@code [a-z0-9_-]}. Returns {@code null} when the result is empty
     * (so the caller can send a usage hint instead of writing a blank file).
     */
    public static String sanitizeKitName(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("\\s+", "_")
                .replaceAll("[^a-z0-9_-]", "");
        // Reserved Windows / FS names that have caused issues in the past.
        if (s.isEmpty() || s.equals(".") || s.equals("..")) return null;
        return s;
    }

    public boolean createNamedKit(String name) {
        String safe = sanitizeKitName(name);
        if (safe == null) return false;
        File kitFile = new File(plugin.getDataFolder(), "kits/" + safe + ".yml");
        if (kitFile.exists()) return false;
        kitFile.getParentFile().mkdirs();
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("items", new ArrayList<>());
        try {
            cfg.save(kitFile);
            plugin.getLogger().info("[ScorePvP] Kit '" + safe + "' created.");
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[ScorePvP] Failed to create kit '" + safe + "': " + e.getMessage());
            return false;
        }
    }

    public boolean saveNamedKit(Player admin, String name) {
        String safe = sanitizeKitName(name);
        if (safe == null) return false;
        File kitFile = new File(plugin.getDataFolder(), "kits/" + safe + ".yml");
        kitFile.getParentFile().mkdirs();

        ItemStack[] full = snapshotInventory(admin);
        List<Map<String, Object>> itemList = serializeKitItems(full);

        YamlConfiguration cfg = new YamlConfiguration();
        cfg.set("items", itemList);
        try {
            cfg.save(kitFile);
            namedKitCache.put(safe, full);
            plugin.getLogger().info("[ScorePvP] Kit '" + safe + "' saved by " + admin.getName());
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("[ScorePvP] Failed to save kit '" + safe + "': " + e.getMessage());
            return false;
        }
    }

    public boolean setVanillaDefault(Player admin) {
        return saveNamedKit(admin, "vanilla");
    }

    // ── Save personal kit ─────────────────────────────────────────────────────

    public boolean savePersonalKit(Player player) {
        if (!plugin.getConfigManager().isInChestRegion(player.getLocation())) return false;

        ItemStack[] contents = snapshotInventory(player);
        personalKits.put(player.getUniqueId(), contents);
        saveKitToFile(player.getUniqueId(), contents);
        return true;
    }

    public boolean hasPersonalKit(UUID uuid) {
        ItemStack[] kit = personalKits.get(uuid);
        return kit != null && kit.length > 0;
    }

    // ── Snapshot inventory → 41-slot array ───────────────────────────────────

    private ItemStack[] snapshotInventory(Player p) {
        ItemStack[] main    = p.getInventory().getContents().clone();
        ItemStack[] armor   = p.getInventory().getArmorContents().clone();
        ItemStack   offhand = p.getInventory().getItemInOffHand();

        ItemStack[] full = new ItemStack[41];
        System.arraycopy(main, 0, full, 0, Math.min(main.length, 36));
        System.arraycopy(armor, 0, full, 36, Math.min(armor.length, 4));
        if (offhand != null && !offhand.getType().isAir()) full[40] = offhand.clone();
        return full;
    }

    // ── Serialization (rich YAML schema, shared with named kits) ─────────────

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
                if (meta.hasCustomModelData()) {
                    entry.put("custom-model-data", meta.getCustomModelData());
                }
                if (meta.isUnbreakable()) {
                    entry.put("unbreakable", true);
                }
                // Potion (incl. splash / lingering / tipped arrow) metadata —
                // without this strength II splash was saved as a plain water
                // bottle. Persists the base potion type + custom effects so
                // the deserializer can rebuild the exact stack.
                if (meta instanceof org.bukkit.inventory.meta.PotionMeta pm) {
                    try {
                        org.bukkit.potion.PotionType baseType = pm.getBasePotionType();
                        if (baseType != null) {
                            entry.put("potion-base-type", baseType.name());
                        }
                    } catch (Throwable ignored) { /* base type may be absent on some Paper builds */ }
                    if (pm.hasColor() && pm.getColor() != null) {
                        entry.put("potion-color", pm.getColor().asRGB());
                    }
                    if (pm.hasCustomEffects()) {
                        List<Map<String, Object>> effList = new ArrayList<>();
                        for (org.bukkit.potion.PotionEffect ef : pm.getCustomEffects()) {
                            Map<String, Object> ee = new LinkedHashMap<>();
                            ee.put("type", ef.getType().getKey().getKey());
                            ee.put("duration", ef.getDuration());
                            ee.put("amplifier", ef.getAmplifier());
                            ee.put("ambient", ef.isAmbient());
                            ee.put("particles", ef.hasParticles());
                            ee.put("icon", ef.hasIcon());
                            effList.add(ee);
                        }
                        entry.put("potion-effects", effList);
                    }
                }
            }
            list.add(entry);
        }
        return list;
    }

    // ── File-based personal kit storage (data/{uuid}.yml) ────────────────────

    private File personalKitFile(UUID uuid) {
        File dir = new File(plugin.getDataFolder(), DATA_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, uuid.toString() + ".yml");
    }

    private void saveKitToFile(UUID uuid, ItemStack[] items) {
        try {
            File file = personalKitFile(uuid);
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("items", serializeKitItems(items));
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[ScorePvP] saveKitToFile failed for " + uuid + ": " + e.getMessage());
        }
    }

    private void deleteKitFile(UUID uuid) {
        File file = personalKitFile(uuid);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("[ScorePvP] could not delete kit file " + file.getName());
        }
    }

    private ItemStack[] loadKitFromFile(UUID uuid) {
        File file = personalKitFile(uuid);
        if (!file.exists()) return null;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            ItemStack[] result = buildItemsFromConfig(cfg);
            // Empty or all-null result → treat as "no kit".
            for (ItemStack it : result) if (it != null) return result;
            return null;
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
        // applyAllContents lays out the 41-slot array correctly:
        //   slots  0..35 → storage (hotbar + main inv)
        //   slots 36..39 → armor (boots, leggings, chestplate, helmet)
        //   slot     40  → offhand
        // The old setContents(items) call truncated to 36 slots on some
        // Paper builds, so swords/bows/arrows in slots 0..8 silently
        // disappeared while armor (already in 36..39) appeared fine.
        applyAllContents(player, items);
    }

    /**
     * Replaces vanilla shield/diamond/netherite armor in the resolved kit with
     * the player's selected cosmetic counterparts (if any). Only runs when the
     * player has {@code duels.cosmetics} permission.
     */
    private void applyCosmeticsToKit(Player player, ItemStack[] items) {
        if (!player.hasPermission("duels.cosmetics")) return;
        CosmeticsManager mgr = plugin.getCosmeticsManager();
        if (mgr == null) return;
        UUID uid = player.getUniqueId();

        ItemStack cosShield = mgr.getSelectedShield(uid);
        if (cosShield != null) {
            for (int i = 0; i < items.length; i++) {
                ItemStack it = items[i];
                if (it != null && it.getType() == Material.SHIELD) {
                    items[i] = cosShield.clone();
                }
            }
        }

        // Bukkit PlayerInventory armor layout: slots 36..39 are
        // [boots, leggings, chestplate, helmet] — NOT [helmet, chestplate,
        // leggings, boots]. The previous mapping caused "I picked a helmet
        // skin but in-game my boots changed" because slot 36 actually
        // resolves to the boots slot on the live player inventory.
        replaceArmorSlot(items, 36, uid, CosmeticsManager.ArmorPiece.BOOTS);
        replaceArmorSlot(items, 37, uid, CosmeticsManager.ArmorPiece.LEGGINGS);
        replaceArmorSlot(items, 38, uid, CosmeticsManager.ArmorPiece.CHESTPLATE);
        replaceArmorSlot(items, 39, uid, CosmeticsManager.ArmorPiece.HELMET);
    }

    private void replaceArmorSlot(ItemStack[] items, int slot, UUID uid,
                                   CosmeticsManager.ArmorPiece piece) {
        ItemStack it = items[slot];
        if (it == null || it.getType().isAir()) return;
        Material m = it.getType();
        CosmeticsManager.ArmorMaterial mat;
        if (m == Material.DIAMOND_HELMET || m == Material.DIAMOND_CHESTPLATE
                || m == Material.DIAMOND_LEGGINGS || m == Material.DIAMOND_BOOTS) {
            mat = CosmeticsManager.ArmorMaterial.DIAMOND;
        } else if (m == Material.NETHERITE_HELMET || m == Material.NETHERITE_CHESTPLATE
                || m == Material.NETHERITE_LEGGINGS || m == Material.NETHERITE_BOOTS) {
            mat = CosmeticsManager.ArmorMaterial.NETHERITE;
        } else return;
        ItemStack cos = plugin.getCosmeticsManager().getSelectedArmor(uid, mat, piece);
        if (cos != null) {
            ItemStack out = cos.clone();
            ItemMeta meta = out.getItemMeta();
            if (meta != null && it.getItemMeta() != null) {
                for (Map.Entry<Enchantment, Integer> ench : it.getItemMeta().getEnchants().entrySet()) {
                    meta.addEnchant(ench.getKey(), ench.getValue(), true);
                }
                out.setItemMeta(meta);
            }
            items[slot] = out;
        }
    }

    private ItemStack[] loadDefaultKit(String kitName) {
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
        return buildItemsFromConfig(cfg);
    }

    /** Shared YAML→ItemStack[41] decoder used by personal & named kits. */
    private ItemStack[] buildItemsFromConfig(YamlConfiguration cfg) {
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
                plugin.getLogger().warning("[ScorePvP] Unknown material in kit slot " + slot);
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
            if (map.get("lore") instanceof List<?> loreList) {
                List<String> lore = new ArrayList<>();
                for (Object l : loreList) lore.add(String.valueOf(l));
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
                    else plugin.getLogger().warning("[ScorePvP] Unknown enchantment: " + enchName);
                }
            }

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

            ItemStack built = builder.build();
            // Restore potion data if present so strength II / splash strength
            // etc. round-trip correctly — without this we lost everything past
            // the material and the stack came back as plain water.
            if (map.containsKey("potion-base-type")
                    || map.containsKey("potion-effects")
                    || map.containsKey("potion-color")) {
                applyPotionMeta(built, map);
            }
            result[slot] = built;
        }

        return result;
    }

    private void applyPotionMeta(ItemStack stack, Map<?, ?> map) {
        if (!(stack.getItemMeta() instanceof org.bukkit.inventory.meta.PotionMeta pm)) return;
        Object baseType = map.get("potion-base-type");
        if (baseType != null) {
            try {
                pm.setBasePotionType(org.bukkit.potion.PotionType.valueOf(
                        String.valueOf(baseType).toUpperCase()));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("[ScorePvP] Unknown potion-base-type: " + baseType);
            }
        }
        Object colorObj = map.get("potion-color");
        if (colorObj != null) {
            try { pm.setColor(org.bukkit.Color.fromRGB(Integer.parseInt(String.valueOf(colorObj)))); }
            catch (NumberFormatException ignored) {}
        }
        if (map.get("potion-effects") instanceof List<?> effs) {
            for (Object o : effs) {
                if (!(o instanceof Map<?, ?> em)) continue;
                Object typeKey = em.get("type");
                if (typeKey == null) continue;
                org.bukkit.potion.PotionEffectType type;
                try {
                    type = org.bukkit.Registry.EFFECT.get(
                            org.bukkit.NamespacedKey.minecraft(String.valueOf(typeKey).toLowerCase()));
                } catch (Throwable ignored) { type = null; }
                if (type == null) {
                    plugin.getLogger().warning("[ScorePvP] Unknown potion effect type: " + typeKey);
                    continue;
                }
                int duration = readInt(em.get("duration"), 200);
                int amplifier = readInt(em.get("amplifier"), 0);
                boolean ambient   = Boolean.TRUE.equals(em.get("ambient"));
                boolean particles = !Boolean.FALSE.equals(em.get("particles"));
                boolean icon      = !Boolean.FALSE.equals(em.get("icon"));
                pm.addCustomEffect(new org.bukkit.potion.PotionEffect(
                        type, duration, amplifier, ambient, particles, icon), true);
            }
        }
        stack.setItemMeta(pm);
    }

    private static int readInt(Object o, int fallback) {
        if (o == null) return fallback;
        try { return Integer.parseInt(String.valueOf(o)); }
        catch (NumberFormatException ex) { return fallback; }
    }

    private ItemStack buildSkullFromUrl(String textureUrl, ItemBuilder builder) {
        try {
            ItemStack skull = builder.build();
            org.bukkit.inventory.meta.SkullMeta skullMeta =
                    (org.bukkit.inventory.meta.SkullMeta) skull.getItemMeta();
            if (skullMeta == null) return skull;

            UUID fakeUUID = UUID.nameUUIDFromBytes(textureUrl.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    public static ItemStack buildSkullFromBase64(String base64Value) {
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        org.bukkit.inventory.meta.SkullMeta meta = (org.bukkit.inventory.meta.SkullMeta) skull.getItemMeta();
        if (meta == null) return skull;
        try {
            String decoded = new String(java.util.Base64.getDecoder().decode(base64Value), java.nio.charset.StandardCharsets.UTF_8);
            String skinUrl = decoded.replaceAll(".*\"url\"\\s*:\\s*\"([^\"]+)\".*", "$1");
            UUID fakeUUID = UUID.nameUUIDFromBytes(base64Value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
