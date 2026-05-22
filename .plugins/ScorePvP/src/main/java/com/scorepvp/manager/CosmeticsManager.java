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

    private final ScorePvP plugin;

    /** slot -> shield ItemStack (slots 0..35). */
    private final Map<Integer, ItemStack> shields = new ConcurrentHashMap<>();
    /** "diamond"|"netherite" -> piece -> ItemStack (only one stored per piece). */
    private final Map<String, Map<ArmorPiece, ItemStack>> armor = new ConcurrentHashMap<>();
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
    public Map<ArmorPiece, ItemStack> getArmor(ArmorMaterial mat) {
        return armor.computeIfAbsent(mat.key(), k -> new ConcurrentHashMap<>());
    }

    /**
     * Stores the given shield in the first free 0..35 slot and returns that
     * slot, or {@code -1} if all slots are occupied or the item is not a
     * shield. The stored stack is a defensive clone — modifying the player's
     * inventory afterwards will not mutate the stored cosmetic.
     */
    public int addShield(ItemStack shield) {
        if (shield == null || shield.getType() != Material.SHIELD) return -1;
        for (int i = 0; i < 36; i++) {
            if (!shields.containsKey(i)) {
                shields.put(i, shield.clone());
                saveShields();
                return i;
            }
        }
        return -1;
    }

    public boolean removeShield(int slot) {
        if (shields.remove(slot) == null) return false;
        // Invalidate any selection pointing to this slot
        for (PlayerSelection sel : selections.values()) {
            if (sel.shieldSlot == slot) sel.shieldSlot = -1;
        }
        saveShields();
        savePlayers();
        return true;
    }

    public void setArmorPiece(ArmorMaterial mat, ArmorPiece piece, ItemStack item) {
        if (item == null) return;
        Map<ArmorPiece, ItemStack> m = getArmor(mat);
        m.put(piece, item.clone());
        saveArmor();
    }

    /**
     * Removes the configured armor piece and invalidates any per-player
     * selection that still references it. Returns true if a piece was
     * actually removed.
     */
    public boolean removeArmorPiece(ArmorMaterial mat, ArmorPiece piece) {
        Map<ArmorPiece, ItemStack> m = getArmor(mat);
        if (m.remove(piece) == null) return false;
        String key = mat.key() + "." + piece.name().toLowerCase();
        for (PlayerSelection sel : selections.values()) {
            sel.armor.remove(key);
        }
        saveArmor();
        savePlayers();
        return true;
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

    /** Returns the configured cosmetic armor piece for the player, or null. */
    public ItemStack getSelectedArmor(UUID uuid, ArmorMaterial mat, ArmorPiece piece) {
        PlayerSelection sel = selections.get(uuid);
        if (sel == null) return null;
        Boolean picked = sel.armor.get(mat.key() + "." + piece.name().toLowerCase());
        if (picked == null || !picked) return null;
        ItemStack item = getArmor(mat).get(piece);
        return item == null ? null : item.clone();
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

    public void toggleArmor(UUID uuid, ArmorMaterial mat, ArmorPiece piece) {
        PlayerSelection sel = getSelection(uuid);
        String key = mat.key() + "." + piece.name().toLowerCase();
        Boolean cur = sel.armor.get(key);
        sel.armor.put(key, !Boolean.TRUE.equals(cur));
        savePlayers();
    }

    public void clearArmor(UUID uuid, ArmorMaterial mat) {
        PlayerSelection sel = getSelection(uuid);
        sel.armor.keySet().removeIf(k -> k.startsWith(mat.key() + "."));
        savePlayers();
    }

    public boolean isArmorSelected(UUID uuid, ArmorMaterial mat, ArmorPiece piece) {
        PlayerSelection sel = selections.get(uuid);
        if (sel == null) return false;
        Boolean v = sel.armor.get(mat.key() + "." + piece.name().toLowerCase());
        return Boolean.TRUE.equals(v);
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
        if (!shieldsFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(shieldsFile);
        ConfigurationSection sec = cfg.getConfigurationSection("shields");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                ItemStack s = sec.getItemStack(key + ".item");
                if (s != null) shields.put(slot, s);
            } catch (NumberFormatException ignored) {}
        }
    }

    private void saveShields() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<Integer, ItemStack> e : shields.entrySet()) {
            cfg.set("shields." + e.getKey() + ".item", e.getValue());
        }
        try { cfg.save(shieldsFile); }
        catch (IOException ex) { plugin.getLogger().warning("cosmetics shields save failed: " + ex.getMessage()); }
    }

    private void loadArmor() {
        if (!armorFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(armorFile);
        for (ArmorMaterial mat : ArmorMaterial.values()) {
            ConfigurationSection sec = cfg.getConfigurationSection(mat.key());
            if (sec == null) continue;
            Map<ArmorPiece, ItemStack> m = getArmor(mat);
            for (ArmorPiece piece : ArmorPiece.values()) {
                ItemStack s = sec.getItemStack(piece.name().toLowerCase());
                if (s != null) m.put(piece, s);
            }
        }
    }

    private void saveArmor() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (ArmorMaterial mat : ArmorMaterial.values()) {
            Map<ArmorPiece, ItemStack> m = getArmor(mat);
            for (Map.Entry<ArmorPiece, ItemStack> e : m.entrySet()) {
                cfg.set(mat.key() + "." + e.getKey().name().toLowerCase(), e.getValue());
            }
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
                ConfigurationSection ds = cfg.getConfigurationSection(key + ".diamond");
                if (ds != null) for (String p : ds.getKeys(false))
                    sel.armor.put("diamond." + p, ds.getBoolean(p));
                ConfigurationSection ns = cfg.getConfigurationSection(key + ".netherite");
                if (ns != null) for (String p : ns.getKeys(false))
                    sel.armor.put("netherite." + p, ns.getBoolean(p));
                selections.put(uuid, sel);
            } catch (IllegalArgumentException ignored) {}
        }
    }

    private void savePlayers() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerSelection> e : selections.entrySet()) {
            PlayerSelection sel = e.getValue();
            // Skip empty selections — keeps the file lean
            if (sel.shieldSlot < 0 && sel.armor.values().stream().noneMatch(Boolean.TRUE::equals)) {
                continue;
            }
            String k = e.getKey().toString();
            cfg.set(k + ".shield", sel.shieldSlot);
            for (Map.Entry<String, Boolean> a : sel.armor.entrySet()) {
                if (!Boolean.TRUE.equals(a.getValue())) continue;
                // a.getKey() is already "material.piece" — set directly so
                // it nests under the player UUID as a configuration section.
                cfg.set(k + "." + a.getKey(), true);
            }
        }
        try { cfg.save(playersFile); }
        catch (IOException ex) { plugin.getLogger().warning("cosmetics players save failed: " + ex.getMessage()); }
    }

    // ── Inner type ───────────────────────────────────────────────────────────

    public static final class PlayerSelection {
        public int shieldSlot = -1;
        public final Map<String, Boolean> armor = new LinkedHashMap<>();
    }
}
