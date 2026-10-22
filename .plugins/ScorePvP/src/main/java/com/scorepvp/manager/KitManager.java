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

    /**
     * Per-mode personal kit cache, keyed by "{uuid}:{kitType}". Each player can
     * customise a separate loadout for every non-vanilla mode (axe, smp, …) via
     * the in-game kit editor GUI. Backed by data-player/{uuid}-{kitType}.yml.
     * An entry of length 0 means "checked, none saved → use the default kit".
     */
    private final Map<String, ItemStack[]> modeKits = new ConcurrentHashMap<>();

    private static String modeKitKey(UUID uuid, String kitType) {
        return uuid + ":" + kitType;
    }

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
        unloadModeKits(uuid);
    }

    // ── Give Kit ──────────────────────────────────────────────────────────────

    /**
     * Kit-settings region give: NO cosmetics. The player edits / saves this
     * exact inventory, so applying cosmetic shields/armor here would bake them
     * into the saved personal kit and corrupt the save logic.
     */
    public void giveVanillaKit(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        // Multi-kit selected kit wins; then legacy personal store; then default.
        ItemStack[] selected = plugin.getVanillaKitManager() != null
                ? plugin.getVanillaKitManager().getSelectedItems(player.getUniqueId()) : null;
        if (selected != null) {
            applyAllContents(player, selected);
            return;
        }
        ItemStack[] personal = personalKits.get(player.getUniqueId());
        if (personal != null && personal.length > 0) {
            applyAllContents(player, personal);
        } else {
            applyDefaultKit(player, "vanilla", false);
        }
    }

    /** Kit-settings region give of the default kit (no cosmetics — see above). */
    public void giveDefaultVanillaKit(Player player) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        applyDefaultKit(player, "vanilla", false);
    }

    /**
     * Match give: cosmetics ARE applied (this is what the player fights with).
     * Every mode now honours the player's per-mode personal kit edited via the
     * in-game kit editor; if none is saved the bundled default kit is used.
     * Cosmetics (shield / armor skins) are layered on top of whichever wins.
     */
    public void giveKitByType(Player player, String kitType) {
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);

        if (kitType.equals("vanilla")) {
            // Vanilla now uses the multi-kit system: the player's SELECTED kit
            // wins. Falls back to the legacy personal-kit store, then the bundled
            // default, so old saves still work until re-selected.
            ItemStack[] items = null;
            if (plugin.getVanillaKitManager() != null) {
                items = plugin.getVanillaKitManager().getSelectedItems(player.getUniqueId());
            }
            if (items == null) {
                ItemStack[] personal = personalKits.get(player.getUniqueId());
                items = (personal != null && personal.length > 0)
                        ? cloneItems(personal)
                        : loadDefaultKit("vanilla");
            }
            applyCosmeticsToKit(player, items);
            applyAllContents(player, items);
            return;
        }

        // Other modes: per-mode personal kit (GUI editor) → else bundled default.
        ItemStack[] modeKit = getModeKit(player.getUniqueId(), kitType);
        ItemStack[] items = (modeKit != null && modeKit.length > 0)
                ? cloneItems(modeKit)
                : loadDefaultKit(kitType);
        applyCosmeticsToKit(player, items);
        applyAllContents(player, items);
    }

    // ── Per-mode personal kits (in-game GUI editor) ────────────────────────────

    /**
     * Returns the player's saved 41-slot kit for {@code kitType}, loading from
     * disk on first access. Returns a length-0 array when nothing is saved (so
     * the caller falls back to the bundled default kit). Never returns null.
     */
    public ItemStack[] getModeKit(UUID uuid, String kitType) {
        String key = modeKitKey(uuid, kitType);
        ItemStack[] cached = modeKits.get(key);
        if (cached != null) return cached;
        ItemStack[] loaded = loadModeKitFromFile(uuid, kitType);
        ItemStack[] result = loaded != null ? loaded : new ItemStack[0];
        modeKits.put(key, result);
        return result;
    }

    /** True if the player has a non-empty personal kit saved for this mode. */
    public boolean hasModeKit(UUID uuid, String kitType) {
        ItemStack[] kit = getModeKit(uuid, kitType);
        return kit.length > 0;
    }

    /**
     * Returns the editor's starting contents for a mode: the player's saved kit
     * if present, otherwise a clone of the bundled default so they edit a real
     * loadout rather than an empty box. Always a fresh 41-slot array.
     */
    public ItemStack[] getModeKitOrDefault(UUID uuid, String kitType) {
        ItemStack[] saved = getModeKit(uuid, kitType);
        ItemStack[] base = (saved.length > 0) ? saved : loadDefaultKit(kitType);
        ItemStack[] out = new ItemStack[41];
        for (int i = 0; i < 41 && i < base.length; i++) {
            out[i] = base[i] == null ? null : base[i].clone();
        }
        return out;
    }

    /** Saves the player's edited 41-slot kit for this mode to cache + disk. */
    public void saveModeKit(UUID uuid, String kitType, ItemStack[] items) {
        ItemStack[] stored = new ItemStack[41];
        for (int i = 0; i < 41 && i < items.length; i++) {
            stored[i] = items[i] == null ? null : items[i].clone();
        }
        modeKits.put(modeKitKey(uuid, kitType), stored);
        try {
            File file = modeKitFile(uuid, kitType);
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("items", serializeKitItems(stored));
            cfg.save(file);
        } catch (Exception e) {
            plugin.getLogger().warning("[ScorePvP] saveModeKit failed for "
                    + uuid + "/" + kitType + ": " + e.getMessage());
        }
    }

    /** Clears the player's personal kit for this mode (falls back to default). */
    public void resetModeKit(UUID uuid, String kitType) {
        modeKits.put(modeKitKey(uuid, kitType), new ItemStack[0]);
        File file = modeKitFile(uuid, kitType);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("[ScorePvP] could not delete mode kit file " + file.getName());
        }
    }

    private File modeKitFile(UUID uuid, String kitType) {
        File dir = new File(plugin.getDataFolder(), DATA_DIR);
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, uuid.toString() + "-" + kitType + ".yml");
    }

    private ItemStack[] loadModeKitFromFile(UUID uuid, String kitType) {
        File file = modeKitFile(uuid, kitType);
        if (!file.exists()) return null;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
            ItemStack[] result = buildItemsFromConfig(cfg);
            for (ItemStack it : result) if (it != null) return result;
            return null;
        } catch (Exception e) {
            plugin.getLogger().warning("[ScorePvP] loadModeKitFromFile failed for "
                    + uuid + "/" + kitType + ": " + e.getMessage());
            return null;
        }
    }

    /** Drops cached per-mode kits for a player on quit. */
    public void unloadModeKits(UUID uuid) {
        modeKits.keySet().removeIf(k -> k.startsWith(uuid + ":"));
    }

    private ItemStack[] cloneItems(ItemStack[] src) {
        ItemStack[] out = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) out[i] = src[i] == null ? null : src[i].clone();
        return out;
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

    // ── Public helpers shared with the vanilla multi-kit system ────────────────

    /** Serialises a 41-slot kit array to the YAML list schema (public wrapper). */
    public List<Map<String, Object>> serializeKitPublic(ItemStack[] items) {
        return serializeKitItems(items);
    }

    /** Rebuilds a 41-slot kit array from a serialized {@code items:} list (public wrapper). */
    public ItemStack[] deserializeKitItems(List<?> raw) {
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set("items", raw);
        return buildItemsFromConfig(tmp);
    }

    /** Bundled default kit contents for {@code kitName} (public wrapper). */
    public ItemStack[] loadDefaultKitPublic(String kitName) {
        return loadDefaultKit(kitName);
    }

    /** Snapshots a player's full inventory to a 41-slot array (public wrapper). */
    public ItemStack[] snapshotInventoryPublic(Player p) {
        return snapshotInventory(p);
    }

    /** Lays a 41-slot kit array back onto a player (public wrapper). */
    public void applyAllContentsPublic(Player player, ItemStack[] items) {
        applyAllContents(player, items);
    }

    /** Applies the player's cosmetic shield/trim selections onto a kit (public wrapper). */
    public void applyCosmeticsToKitPublic(Player player, ItemStack[] items) {
        applyCosmeticsToKit(player, items);
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
                // Custom-item marker (e.g. golden_head). Without this the
                // PersistentDataContainer is dropped on YAML round-trip and the
                // item comes back as a plain head with no special behaviour.
                String customId = com.scorepvp.item.CustomItemRegistry.getCustomItemId(item);
                if (customId != null) {
                    entry.put("custom-item-id", customId);
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
                // Shulker box contents — without this a shulker saved in a kit
                // came back empty. Serialise the contained inventory recursively
                // using the same schema (slot/material/…), tagged by inner slot.
                if (meta instanceof org.bukkit.inventory.meta.BlockStateMeta bsm
                        && bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox shulker) {
                    ItemStack[] contents = shulker.getInventory().getContents();
                    boolean any = false;
                    for (ItemStack c : contents) if (c != null && !c.getType().isAir()) { any = true; break; }
                    if (any) {
                        entry.put("shulker-contents", serializeKitItems(contents));
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

    private void applyDefaultKit(Player player, String kitName, boolean withCosmetics) {
        ItemStack[] items = loadDefaultKit(kitName);
        if (withCosmetics) applyCosmeticsToKit(player, items);
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
     * Applies the player's cosmetic selections to the resolved kit:
     *   • the chosen cosmetic SHIELD replaces any kit shield, and
     *   • the chosen ARMOR TRIM (pattern + material, per piece) is stamped onto
     *     whatever armor the kit already provides for that slot.
     * Only runs when the player has {@code duels.cosmetics} permission.
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

        // Bukkit kit array armor layout (slots 36..39):
        //   36 = boots, 37 = leggings, 38 = chestplate, 39 = helmet
        applyTrimToSlot(items, 36, uid, CosmeticsManager.ArmorPiece.BOOTS);
        applyTrimToSlot(items, 37, uid, CosmeticsManager.ArmorPiece.LEGGINGS);
        applyTrimToSlot(items, 38, uid, CosmeticsManager.ArmorPiece.CHESTPLATE);
        applyTrimToSlot(items, 39, uid, CosmeticsManager.ArmorPiece.HELMET);

        // Cosmetic hats are handled separately (see stampDuelHatOnHelmet) — only
        // stamped when the duel enabled the "custom hats" opt-in.
    }

    /**
     * Duel-hats opt-in: swaps the fighter's kit helmet for their cosmetic hat so
     * the hat renders on the head in third-person / to other players.
     *
     * <p><b>Why a COAL swap instead of stamping custom_model_data on the netherite/
     * diamond helmet:</b> Minecraft renders WORN armor through the equipment/armor
     * layer, which ignores {@code custom_model_data} entirely — so a stamped helmet
     * shows only in the inventory/hand, never on the head. The active resource pack
     * has no {@code equipment/} definitions, so a real armor helmet physically
     * cannot carry a custom worn model. A non-armor item (COAL), however, renders
     * its own item-model on the head — this is exactly how the lobby hats already
     * work.
     *
     * <p>To keep combat identical, the COAL is grafted with the original kit
     * helmet's protection: every enchantment (Protection, etc. — these still apply
     * because their vanilla enchant slot is "armor") plus the source material's
     * default HEAD attribute modifiers (armor / armor-toughness / knockback-
     * resistance). So the fighter sees a hat but is defended exactly like the
     * netherite/diamond helmet they'd otherwise wear.
     *
     * <p>No-op if the player owns no selected hat, so everyone else keeps the plain
     * kit helmet.
     */
    public void stampDuelHatOnHelmet(Player player) {
        if (player == null) return;
        var hats = plugin.getHatManager();
        if (hats == null) return;
        String hatId = hats.getSelected(player);
        com.scorepvp.hats.HatDefinition hat = hats.getHat(hatId);
        if (hat == null) return;
        if (!hats.owns(player, hat)) return; // no ownership → plain helmet
        ItemStack original = player.getInventory().getHelmet();
        if (original == null || original.getType().isAir()) return;

        // COAL base carrying the hat model (renders on the head; worn armor would
        // ignore the model — see javadoc).
        ItemStack hatHelmet = new ItemStack(Material.COAL, 1);
        ItemMeta meta = hatHelmet.getItemMeta();
        if (meta == null) return;
        meta.setCustomModelData(hat.getCustomModelData());
        if (original.hasItemMeta() && original.getItemMeta().hasDisplayName()) {
            meta.displayName(original.getItemMeta().displayName());
        }

        // 1) Graft the kit helmet's enchantments. Protection & co. still take
        //    effect on a COAL in the helmet slot because their vanilla enchant
        //    slot is "armor", which activates for whatever sits in an armor slot.
        for (var en : original.getEnchantments().entrySet()) {
            meta.addEnchant(en.getKey(), en.getValue(), true);
        }

        // 2) Graft the source material's default HEAD attributes (armor,
        //    armor-toughness, knockback-resistance) so the COAL defends exactly
        //    like the netherite/diamond helmet did.
        var defaults = original.getType()
                .getDefaultAttributeModifiers(org.bukkit.inventory.EquipmentSlot.HEAD);
        for (var entry : defaults.entries()) {
            meta.addAttributeModifier(entry.getKey(), entry.getValue());
        }
        // Hide the attribute/enchant lines so it reads as a hat, not armor.
        meta.addItemFlags(org.bukkit.inventory.ItemFlag.HIDE_ATTRIBUTES,
                org.bukkit.inventory.ItemFlag.HIDE_ENCHANTS);
        // Force NO enchant glint while keeping the enchants themselves — the hat
        // should look like a plain hat, not a glowing item. The enchantments
        // still apply (Protection etc.); only the visual shimmer is suppressed.
        meta.setEnchantmentGlintOverride(false);

        hatHelmet.setItemMeta(meta);
        player.getInventory().setHelmet(hatHelmet);
        player.updateInventory();
    }

    /**
     * Stamps the player's chosen armor trim onto the kit's existing armor piece
     * in the given slot. Leaves the piece untouched if it isn't trimmable armor
     * or the player has selected no trim for that piece.
     */
    private void applyTrimToSlot(ItemStack[] items, int slot, UUID uid,
                                  CosmeticsManager.ArmorPiece piece) {
        if (slot < 0 || slot >= items.length) return;
        ItemStack it = items[slot];
        if (it == null || it.getType().isAir()) return;
        // Diamond and netherite trims are stored separately; pick the set that
        // matches the kit's actual armor material so a netherite chestplate
        // never gets the diamond trim and vice-versa.
        CosmeticsManager.ArmorMaterial mat = armorMaterialOf(it.getType());
        if (mat == null) return;
        org.bukkit.inventory.meta.trim.ArmorTrim trim =
                plugin.getCosmeticsManager().getArmorTrim(uid, mat, piece);
        if (trim == null) return;
        if (!(it.getItemMeta() instanceof org.bukkit.inventory.meta.ArmorMeta am)) return;
        am.setTrim(trim);
        it.setItemMeta(am);
    }

    private CosmeticsManager.ArmorMaterial armorMaterialOf(Material m) {
        String n = m.name();
        if (n.startsWith("DIAMOND_"))   return CosmeticsManager.ArmorMaterial.DIAMOND;
        if (n.startsWith("NETHERITE_")) return CosmeticsManager.ArmorMaterial.NETHERITE;
        return null;
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

            // Custom item (e.g. golden_head) — rebuild from the registry so its
            // texture, food component and PersistentDataContainer marker are all
            // restored exactly. Honour the saved amount.
            Object customId = map.get("custom-item-id");
            if (customId != null && plugin.getCustomItemRegistry() != null) {
                ItemStack custom = plugin.getCustomItemRegistry().buildItem(String.valueOf(customId));
                if (custom != null) {
                    int amt = 1;
                    if (map.containsKey("amount")) {
                        try { amt = Integer.parseInt(String.valueOf(map.get("amount"))); }
                        catch (NumberFormatException ignored) {}
                    }
                    custom.setAmount(Math.max(1, amt));
                    result[slot] = custom;
                    continue;
                }
            }

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

            // Kit items are handed to the player in combat — keep their vanilla
            // tooltip (attack damage, armor, enchants) visible, unlike GUI icons.
            ItemBuilder builder = new ItemBuilder(mat, amount).showTooltip();

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
            // Restore shulker box contents (round-trips items inside the box).
            if (map.get("shulker-contents") instanceof List<?> sc) {
                applyShulkerContents(built, sc);
            }
            result[slot] = built;
        }

        return result;
    }

    /** Rebuilds a shulker box's inner inventory from its serialized contents. */
    private void applyShulkerContents(ItemStack stack, List<?> serialized) {
        if (!(stack.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta bsm)) return;
        if (!(bsm.getBlockState() instanceof org.bukkit.block.ShulkerBox shulker)) return;
        YamlConfiguration tmp = new YamlConfiguration();
        tmp.set("items", serialized);
        ItemStack[] inner = buildItemsFromConfig(tmp); // 41-slot array; shulker uses 0..26
        ItemStack[] box = new ItemStack[27];
        for (int i = 0; i < 27 && i < inner.length; i++) box[i] = inner[i];
        shulker.getInventory().setContents(box);
        shulker.update(true, false);
        bsm.setBlockState(shulker);
        stack.setItemMeta(bsm);
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
