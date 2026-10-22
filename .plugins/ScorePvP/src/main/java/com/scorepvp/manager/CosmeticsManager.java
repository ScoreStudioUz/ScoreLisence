package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persists cosmetic shields + armor and per-player selections.
 *
 * Files (under plugin data folder):
 *   cosmetics/shields.yml  — admin-added shield items by slot (0..35).
 *   cosmetics/armor.yml    — admin-set armor pieces, keyed by
 *                            "diamond.helmet" / "netherite.boots" etc.
 *   cosmetics/players.yml  — per-player selections:
 *                            {uuid}.shield        = slot or -1
 *                            {uuid}.diamond.*     = slot or -1
 *                            {uuid}.netherite.*   = slot or -1
 *
 * Selections are slot indices into shields.yml / armor.yml — when an admin
 * removes a slot the player's selection silently becomes "no longer valid"
 * and {@link #getSelectedShield(UUID)} returns null.
 */
public class CosmeticsManager {

    public enum ArmorPiece { HELMET, CHESTPLATE, LEGGINGS, BOOTS }
    public enum ArmorMaterial {
        DIAMOND("diamond"),
        NETHERITE("netherite");
        final String key;
        ArmorMaterial(String key) { this.key = key; }
        public String key() { return key; }
    }

    /** How many armor sets (columns) each material can hold. */
    public static final int ARMOR_SETS = 9;

    private final ScorePvP plugin;

    /** Shield category ids (fixed order = menu slots 48/49/50). */
    public static final String[] SHIELD_CATEGORIES = {"plain", "banner", "flag"};
    /** How many shields fit on one category page (menu slots 0..44). */
    public static final int SHIELDS_PER_CATEGORY = 45;

    /** stable index -> shield ItemStack. */
    private final Map<Integer, ItemStack> shields = new ConcurrentHashMap<>();
    /** stable index -> category id (one of {@link #SHIELD_CATEGORIES}). */
    private final Map<Integer, String> shieldCategory = new ConcurrentHashMap<>();
    /**
     * Armor storage keyed by {@code <material>.<setIndex>.<piece>} → ItemStack.
     * Example: {@code "diamond.0.helmet"}. setIndex is 0..{@link #ARMOR_SETS}-1.
     * Switched away from the old per-piece map so admins can ship up to 9
     * distinct sets per material (helmet+chestplate+leggings+boots × 9).
     */
    private final Map<String, ItemStack> armorBySet = new ConcurrentHashMap<>();
    /** uuid -> selections */
    private final Map<UUID, PlayerSelection> selections = new ConcurrentHashMap<>();

    private File shieldsFile;
    private File armorFile;
    private File playersFile;

    public CosmeticsManager(ScorePvP plugin) {
        this.plugin = plugin;
        load();
    }

    // ── Public API ───────────────────────────────────────────────────────────

    public Map<Integer, ItemStack> getShields() { return shields; }

    /** Returns the stored armor item for the given (material, setIndex, piece), or null. */
    public ItemStack getArmorPiece(ArmorMaterial mat, int setIndex, ArmorPiece piece) {
        if (setIndex < 0 || setIndex >= ARMOR_SETS) return null;
        return armorBySet.get(armorKey(mat, setIndex, piece));
    }

    /**
     * Returns every (setIndex, piece) → ItemStack currently stored for the given
     * material. Useful for the GUI which renders all 9 sets at once.
     */
    public Map<String, ItemStack> getAllArmorEntries(ArmorMaterial mat) {
        Map<String, ItemStack> out = new LinkedHashMap<>();
        for (Map.Entry<String, ItemStack> e : armorBySet.entrySet()) {
            if (e.getKey().startsWith(mat.key() + ".")) out.put(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * Legacy compatibility — old code asked for "the" armor map per material.
     * Returns the FIRST set (index 0) so existing call-sites keep compiling;
     * new code should use {@link #getArmorPiece(ArmorMaterial, int, ArmorPiece)}.
     */
    @Deprecated
    public Map<ArmorPiece, ItemStack> getArmor(ArmorMaterial mat) {
        Map<ArmorPiece, ItemStack> out = new LinkedHashMap<>();
        for (ArmorPiece p : ArmorPiece.values()) {
            ItemStack it = getArmorPiece(mat, 0, p);
            if (it != null) out.put(p, it);
        }
        return out;
    }

    public static String armorKey(ArmorMaterial mat, int setIndex, ArmorPiece piece) {
        return mat.key() + "." + setIndex + "." + piece.name().toLowerCase();
    }

    /**
     * Stores the given shield in the first free 0..35 slot and returns that
     * slot, or {@code -1} if all slots are occupied or the item is not a
     * shield. The stored stack is a defensive clone — modifying the player's
     * inventory afterwards will not mutate the stored cosmetic.
     */
    /** Adds a shield to the "plain" category (legacy default). */
    public int addShield(ItemStack shield) {
        return addShield(shield, SHIELD_CATEGORIES[0]);
    }

    /**
     * Stores the given shield under {@code category} at the first free stable
     * index and returns that index, or {@code -1} if the item is not a shield.
     * Shields are hidden-attributes on store so the menu icon stays clean.
     */
    public int addShield(ItemStack shield, String category) {
        if (shield == null || shield.getType() != Material.SHIELD) return -1;
        String cat = normalizeCategory(category);
        ItemStack stored = shield.clone();
        hideShieldTooltip(stored);
        for (int i = 0; ; i++) {
            if (!shields.containsKey(i)) {
                shields.put(i, stored);
                shieldCategory.put(i, cat);
                saveShields();
                return i;
            }
        }
    }

    public boolean removeShield(int slot) {
        if (shields.remove(slot) == null) return false;
        shieldCategory.remove(slot);
        // Invalidate any selection pointing to this slot
        for (PlayerSelection sel : selections.values()) {
            if (sel.shieldSlot == slot) sel.shieldSlot = -1;
        }
        saveShields();
        savePlayers();
        return true;
    }

    /** The category id a stored shield belongs to (defaults to "plain"). */
    public String getShieldCategory(int index) {
        return shieldCategory.getOrDefault(index, SHIELD_CATEGORIES[0]);
    }

    /** Stored shield indices in ascending order for one category. */
    public java.util.List<Integer> getShieldIndices(String category) {
        String cat = normalizeCategory(category);
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (Integer idx : shields.keySet()) {
            if (getShieldCategory(idx).equals(cat)) out.add(idx);
        }
        java.util.Collections.sort(out);
        return out;
    }

    private static String normalizeCategory(String c) {
        if (c == null) return SHIELD_CATEGORIES[0];
        String lc = c.toLowerCase();
        for (String k : SHIELD_CATEGORIES) if (k.equals(lc)) return k;
        return SHIELD_CATEGORIES[0];
    }

    /** Hides banner-pattern/attribute tooltip text on a stored shield stack. */
    public static void hideShieldTooltip(ItemStack shield) {
        org.bukkit.inventory.meta.ItemMeta m = shield.getItemMeta();
        if (m != null) {
            m.addItemFlags(org.bukkit.inventory.ItemFlag.values());
            shield.setItemMeta(m);
        }
    }

    /**
     * Stores an armor piece for (material, setIndex, piece). Used by admins
     * via the new {@code /cosmetics admin armor set <set> <piece>} command.
     */
    public void setArmorPiece(ArmorMaterial mat, int setIndex, ArmorPiece piece, ItemStack item) {
        if (item == null) return;
        if (setIndex < 0 || setIndex >= ARMOR_SETS) return;
        armorBySet.put(armorKey(mat, setIndex, piece), item.clone());
        saveArmor();
    }

    /** Legacy single-set entry point. Stores into set 0. */
    @Deprecated
    public void setArmorPiece(ArmorMaterial mat, ArmorPiece piece, ItemStack item) {
        setArmorPiece(mat, 0, piece, item);
    }

    /**
     * Removes a single piece from a single set. Returns true if a piece was
     * actually removed. Also clears any player selection that referenced
     * that set (since the set is now incomplete).
     */
    public boolean removeArmorPiece(ArmorMaterial mat, int setIndex, ArmorPiece piece) {
        if (setIndex < 0 || setIndex >= ARMOR_SETS) return false;
        if (armorBySet.remove(armorKey(mat, setIndex, piece)) == null) return false;
        for (PlayerSelection sel : selections.values()) {
            Integer cur = sel.armorSet.get(mat.key());
            if (cur != null && cur == setIndex) sel.armorSet.remove(mat.key());
        }
        saveArmor();
        savePlayers();
        return true;
    }

    /** Legacy variant — removes from set 0. */
    @Deprecated
    public boolean removeArmorPiece(ArmorMaterial mat, ArmorPiece piece) {
        return removeArmorPiece(mat, 0, piece);
    }

    public PlayerSelection getSelection(UUID uuid) {
        return selections.computeIfAbsent(uuid, k -> new PlayerSelection());
    }

    /** Returns the configured cosmetic shield for the player, or null. */
    public ItemStack getSelectedShield(UUID uuid) {
        PlayerSelection sel = selections.get(uuid);
        if (sel == null || sel.shieldSlot < 0) return null;
        ItemStack s = shields.get(sel.shieldSlot);
        return s == null ? null : s.clone();
    }

    /**
     * Returns the cosmetic ItemStack the player should receive for the given
     * armor slot — pulled from whichever set they currently have selected for
     * this material, or null if no set is selected / the stored slot is empty.
     */
    public ItemStack getSelectedArmor(UUID uuid, ArmorMaterial mat, ArmorPiece piece) {
        int setIdx = getSelectedSet(uuid, mat);
        if (setIdx < 0) return null;
        ItemStack item = getArmorPiece(mat, setIdx, piece);
        return item == null ? null : item.clone();
    }

    /** Returns the selected armor-set index for this player/material, or -1. */
    public int getSelectedSet(UUID uuid, ArmorMaterial mat) {
        PlayerSelection sel = selections.get(uuid);
        if (sel == null) return -1;
        Integer v = sel.armorSet.get(mat.key());
        return v == null ? -1 : v;
    }

    /** Sets the active armor set for this player/material. */
    public void selectArmorSet(UUID uuid, ArmorMaterial mat, int setIndex) {
        if (setIndex < 0 || setIndex >= ARMOR_SETS) return;
        PlayerSelection sel = getSelection(uuid);
        sel.armorSet.put(mat.key(), setIndex);
        savePlayers();
    }

    public void selectShield(UUID uuid, int slot) {
        PlayerSelection sel = getSelection(uuid);
        sel.shieldSlot = slot;
        savePlayers();
    }

    public void clearShield(UUID uuid) {
        PlayerSelection sel = getSelection(uuid);
        sel.shieldSlot = -1;
        savePlayers();
    }

    /**
     * Toggles selection for a whole armor set: clicking any piece of a set
     * either selects that set entirely or deselects it. Per-piece selection
     * is gone — UX confusion was the bigger cost than the lost flexibility.
     */
    public void toggleArmorSet(UUID uuid, ArmorMaterial mat, int setIndex) {
        if (setIndex < 0 || setIndex >= ARMOR_SETS) return;
        PlayerSelection sel = getSelection(uuid);
        Integer cur = sel.armorSet.get(mat.key());
        if (cur != null && cur == setIndex) {
            sel.armorSet.remove(mat.key());
        } else {
            sel.armorSet.put(mat.key(), setIndex);
        }
        savePlayers();
    }

    /** Legacy single-piece toggle — now toggles set 0 as a best-effort fallback. */
    @Deprecated
    public void toggleArmor(UUID uuid, ArmorMaterial mat, ArmorPiece piece) {
        toggleArmorSet(uuid, mat, 0);
    }

    public void clearArmor(UUID uuid, ArmorMaterial mat) {
        PlayerSelection sel = getSelection(uuid);
        sel.armorSet.remove(mat.key());
        savePlayers();
    }

    /** True if this set is currently selected for the player on this material. */
    public boolean isSetSelected(UUID uuid, ArmorMaterial mat, int setIndex) {
        return getSelectedSet(uuid, mat) == setIndex;
    }

    /** Legacy per-piece check — returns true iff that piece exists in the selected set. */
    @Deprecated
    public boolean isArmorSelected(UUID uuid, ArmorMaterial mat, ArmorPiece piece) {
        int setIdx = getSelectedSet(uuid, mat);
        if (setIdx < 0) return false;
        return getArmorPiece(mat, setIdx, piece) != null;
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private void load() {
        File dir = new File(plugin.getDataFolder(), "cosmetics");
        if (!dir.exists()) dir.mkdirs();
        shieldsFile = new File(dir, "shields.yml");
        armorFile   = new File(dir, "armor.yml");
        playersFile = new File(dir, "players.yml");
        loadShields();
        loadArmor();
        loadPlayers();
    }

    private void loadShields() {
        // No default seeding — the menu starts empty; admins add shields with
        // /cosmetics admin shield add <category>.
        if (!shieldsFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(shieldsFile);
        ConfigurationSection sec = cfg.getConfigurationSection("shields");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                ItemStack s = sec.getItemStack(key + ".item");
                if (s != null) {
                    hideShieldTooltip(s);
                    shields.put(slot, s);
                    shieldCategory.put(slot, normalizeCategory(cfg.getString("shields." + key + ".category")));
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    private void saveShields() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<Integer, ItemStack> e : shields.entrySet()) {
            cfg.set("shields." + e.getKey() + ".item", e.getValue());
            cfg.set("shields." + e.getKey() + ".category", getShieldCategory(e.getKey()));
        }
        try { cfg.save(shieldsFile); }
        catch (IOException ex) { plugin.getLogger().warning("cosmetics shields save failed: " + ex.getMessage()); }
    }

    private void loadArmor() {
        if (!armorFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(armorFile);
        for (ArmorMaterial mat : ArmorMaterial.values()) {
            ConfigurationSection matSec = cfg.getConfigurationSection(mat.key());
            if (matSec == null) continue;

            // New layout: <mat>.<setIndex>.<piece>  →  ItemStack
            // Legacy layout (pre-9-sets):  <mat>.<piece>  →  ItemStack
            // Probe by trying to parse a child key as an int. If it parses,
            // assume new layout; otherwise treat as legacy and migrate to set 0.
            boolean migrated = false;
            for (String childKey : matSec.getKeys(false)) {
                try {
                    int setIdx = Integer.parseInt(childKey);
                    ConfigurationSection setSec = matSec.getConfigurationSection(childKey);
                    if (setSec == null) continue;
                    for (ArmorPiece piece : ArmorPiece.values()) {
                        ItemStack it = setSec.getItemStack(piece.name().toLowerCase());
                        if (it != null) armorBySet.put(armorKey(mat, setIdx, piece), it);
                    }
                } catch (NumberFormatException nfe) {
                    // legacy piece-level key e.g. "helmet" — migrate to set 0
                    for (ArmorPiece piece : ArmorPiece.values()) {
                        if (!piece.name().toLowerCase().equals(childKey)) continue;
                        ItemStack it = matSec.getItemStack(childKey);
                        if (it != null) {
                            armorBySet.put(armorKey(mat, 0, piece), it);
                            migrated = true;
                        }
                    }
                }
            }
            if (migrated) {
                plugin.getLogger().info("[ScorePvP] Migrated legacy armor entries for "
                        + mat.key() + " → set 0.");
            }
        }
    }

    private void saveArmor() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<String, ItemStack> e : armorBySet.entrySet()) {
            cfg.set(e.getKey(), e.getValue());
        }
        try { cfg.save(armorFile); }
        catch (IOException ex) { plugin.getLogger().warning("cosmetics armor save failed: " + ex.getMessage()); }
    }

    private void loadPlayers() {
        if (!playersFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(playersFile);
        for (String key : cfg.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                PlayerSelection sel = new PlayerSelection();
                sel.shieldSlot = cfg.getInt(key + ".shield", -1);

                // New format:  <uuid>.<material>.set = <int>
                // Legacy:      <uuid>.<material>.<piece> = true   (per-piece toggles)
                for (ArmorMaterial mat : ArmorMaterial.values()) {
                    String base = key + "." + mat.key();
                    if (cfg.isInt(base + ".set")) {
                        sel.armorSet.put(mat.key(), cfg.getInt(base + ".set"));
                    } else if (cfg.isConfigurationSection(base)) {
                        // Legacy fall-through — if ANY piece was selected, migrate
                        // to "set 0 active".
                        ConfigurationSection ms = cfg.getConfigurationSection(base);
                        if (ms != null) for (String p : ms.getKeys(false)) {
                            if (ms.getBoolean(p, false)) {
                                sel.armorSet.put(mat.key(), 0);
                                break;
                            }
                        }
                    }
                }
                // Per-(material,piece) trim selections. Stored nested as
                // trim.<material>.<piece>.{pattern,material}; the in-memory map
                // key is "<material>.<piece>" (dotted). Deep-walk to rebuild it.
                ConfigurationSection trimSec = cfg.getConfigurationSection(key + ".trim");
                if (trimSec != null) {
                    for (String deep : trimSec.getKeys(true)) {
                        if (deep.endsWith(".pattern")) {
                            String combo = deep.substring(0, deep.length() - ".pattern".length());
                            String v = trimSec.getString(deep);
                            if (v != null) sel.trimPattern.put(combo, v);
                        } else if (deep.endsWith(".material")) {
                            String combo = deep.substring(0, deep.length() - ".material".length());
                            String v = trimSec.getString(deep);
                            if (v != null) sel.trimMaterial.put(combo, v);
                        }
                    }
                }

                selections.put(uuid, sel);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void savePlayers() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerSelection> e : selections.entrySet()) {
            PlayerSelection sel = e.getValue();
            // Skip empty selections — keeps the file lean. Trims count too:
            // a player who only picked a trim (no shield/set) must still persist.
            if (sel.shieldSlot < 0 && sel.armorSet.isEmpty()
                    && sel.trimPattern.isEmpty() && sel.trimMaterial.isEmpty()) continue;

            String k = e.getKey().toString();
            cfg.set(k + ".shield", sel.shieldSlot);
            for (Map.Entry<String, Integer> a : sel.armorSet.entrySet()) {
                if (a.getValue() == null || a.getValue() < 0) continue;
                cfg.set(k + "." + a.getKey() + ".set", a.getValue());
            }
            for (Map.Entry<String, String> t : sel.trimPattern.entrySet()) {
                if (t.getValue() != null) cfg.set(k + ".trim." + t.getKey() + ".pattern", t.getValue());
            }
            for (Map.Entry<String, String> t : sel.trimMaterial.entrySet()) {
                if (t.getValue() != null) cfg.set(k + ".trim." + t.getKey() + ".material", t.getValue());
            }
        }
        try { cfg.save(playersFile); }
        catch (IOException ex) { plugin.getLogger().warning("cosmetics players save failed: " + ex.getMessage()); }
    }

    // ── Inner type ───────────────────────────────────────────────────────────

    public static final class PlayerSelection {
        public int shieldSlot = -1;
        /** material-key (e.g. "diamond") → selected set index, or absent = none. */
        public final Map<String, Integer> armorSet = new LinkedHashMap<>();
        /** piece (helmet/chestplate/leggings/boots) → trim pattern registry key (minecraft name). */
        public final Map<String, String> trimPattern = new LinkedHashMap<>();
        /** piece → trim material registry key (minecraft name). */
        public final Map<String, String> trimMaterial = new LinkedHashMap<>();
    }

    // ── Armor trims (per-piece, player-chosen) ─────────────────────────────────
    //
    // Replaces the old admin-curated armor SETS. Each player now picks a trim
    // pattern + trim material for every armor piece independently; at match
    // start those trims are stamped onto the kit's own armor (KitManager).

    private static String pieceKey(ArmorPiece piece) { return piece.name().toLowerCase(); }

    /**
     * Trim selections are keyed per (armor-material, piece) so DIAMOND and
     * NETHERITE keep entirely separate trims — picking a diamond-leggings trim
     * never bleeds onto netherite. Key form: {@code "diamond.leggings"}.
     */
    private static String trimKey(ArmorMaterial mat, ArmorPiece piece) {
        return mat.key() + "." + pieceKey(piece);
    }

    /** Stores the chosen trim PATTERN (minecraft registry key) for a piece. */
    public void setTrimPattern(UUID uuid, ArmorMaterial mat, ArmorPiece piece, String patternKey) {
        getSelection(uuid).trimPattern.put(trimKey(mat, piece), patternKey);
        savePlayers();
    }

    /** Stores the chosen trim MATERIAL (minecraft registry key) for a piece. */
    public void setTrimMaterial(UUID uuid, ArmorMaterial mat, ArmorPiece piece, String materialKey) {
        getSelection(uuid).trimMaterial.put(trimKey(mat, piece), materialKey);
        savePlayers();
    }

    public String getTrimPatternKey(UUID uuid, ArmorMaterial mat, ArmorPiece piece) {
        PlayerSelection sel = selections.get(uuid);
        return sel == null ? null : sel.trimPattern.get(trimKey(mat, piece));
    }

    public String getTrimMaterialKey(UUID uuid, ArmorMaterial mat, ArmorPiece piece) {
        PlayerSelection sel = selections.get(uuid);
        return sel == null ? null : sel.trimMaterial.get(trimKey(mat, piece));
    }

    /** Clears the trim selection for one (material, piece). */
    public void resetTrim(UUID uuid, ArmorMaterial mat, ArmorPiece piece) {
        PlayerSelection sel = selections.get(uuid);
        if (sel == null) return;
        sel.trimPattern.remove(trimKey(mat, piece));
        sel.trimMaterial.remove(trimKey(mat, piece));
        savePlayers();
    }

    /**
     * Resolves the player's chosen trim for a (material, piece) into a live
     * {@link org.bukkit.inventory.meta.trim.ArmorTrim}, or null if either the
     * pattern or material isn't set / no longer exists in the registry.
     */
    public org.bukkit.inventory.meta.trim.ArmorTrim getArmorTrim(UUID uuid, ArmorMaterial mat, ArmorPiece piece) {
        String patKey = getTrimPatternKey(uuid, mat, piece);
        String matKey = getTrimMaterialKey(uuid, mat, piece);
        if (patKey == null || matKey == null) return null;
        try {
            org.bukkit.inventory.meta.trim.TrimPattern pat =
                    org.bukkit.Registry.TRIM_PATTERN.get(org.bukkit.NamespacedKey.minecraft(patKey));
            org.bukkit.inventory.meta.trim.TrimMaterial trimMat =
                    org.bukkit.Registry.TRIM_MATERIAL.get(org.bukkit.NamespacedKey.minecraft(matKey));
            if (pat == null || trimMat == null) return null;
            return new org.bukkit.inventory.meta.trim.ArmorTrim(trimMat, pat);
        } catch (Throwable t) {
            return null;
        }
    }
}
