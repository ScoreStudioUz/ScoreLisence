package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The vanilla multi-kit system.
 *
 * <p>Each player owns up to {@code SLOTS} named vanilla kits (one per menu slot).
 * A slot is permission-gated (slot 0 is free by default; the rest are handed out
 * per LuckPerms rank). Exactly one kit may be <i>selected</i> at a time — that is
 * the loadout the player fights with in the vanilla mode and the one
 * {@code /kit save} writes back to.
 *
 * <p>Persisted as {@code data-player/{uuid}-vanilla.yml}:
 * <pre>
 * selected: 0
 * kits:
 *   '0': { name: "My Kit", items: [ ... ] }
 * </pre>
 */
public class VanillaKitManager {

    /** Number of kit slots in the menu. */
    public static final int SLOTS = 6;

    /** Number of ender-chest storage slots that ride along with each kit. */
    public static final int ENDER_SLOTS = 27;

    /** A single named vanilla kit (41-slot inventory + display name + 27-slot ender box). */
    public static final class Kit {
        private String name;
        private ItemStack[] items;
        /** Per-kit ender-chest contents (27 slots). Never null. */
        private ItemStack[] enderItems;
        Kit(String name, ItemStack[] items) { this(name, items, null); }
        Kit(String name, ItemStack[] items, ItemStack[] enderItems) {
            this.name = name;
            this.items = items;
            this.enderItems = enderItems != null ? enderItems : new ItemStack[ENDER_SLOTS];
        }
        public String getName() { return name; }
        public ItemStack[] getItems() { return items; }
        public ItemStack[] getEnderItems() { return enderItems; }
    }

    /** All of a player's vanilla kits + the selected index (-1 = none). */
    public static final class PlayerKits {
        int selected = -1;
        final Map<Integer, Kit> kits = new LinkedHashMap<>();
    }

    private final ScorePvP plugin;
    private final Map<UUID, PlayerKits> cache = new ConcurrentHashMap<>();
    /** Per-slot permission node; index 0 is free (default true). */
    private final String[] slotPermissions = new String[SLOTS];
    /** uuid → kit index currently being edited in the vanilla room (else absent). */
    private final Map<UUID, Integer> editing = new ConcurrentHashMap<>();
    /** uuid → pending rename request (slot index) awaiting a chat name. */
    private final Map<UUID, Integer> pendingRename = new ConcurrentHashMap<>();
    /** uuid → pending create request (slot index) awaiting a chat name. */
    private final Map<UUID, Integer> pendingCreate = new ConcurrentHashMap<>();

    public VanillaKitManager(ScorePvP plugin) {
        this.plugin = plugin;
        loadPermissions();
    }

    private void loadPermissions() {
        List<String> perms = plugin.getConfig().getStringList("vanilla-kits.slot-permissions");
        for (int i = 0; i < SLOTS; i++) {
            slotPermissions[i] = (i < perms.size() && perms.get(i) != null && !perms.get(i).isBlank())
                    ? perms.get(i) : null; // null = free
        }
    }

    // ── Permissions ────────────────────────────────────────────────────────────

    /** True if the player may use kit slot {@code index}. Slot 0 (null perm) is free. */
    public boolean canUseSlot(Player p, int index) {
        if (index < 0 || index >= SLOTS) return false;
        String perm = slotPermissions[index];
        return perm == null || p.hasPermission(perm);
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────────

    public void loadAsync(UUID uuid) {
        org.bukkit.Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PlayerKits pk = loadFromFile(uuid);
            // Only seed the cache if a main-thread access (create/select/…)
            // hasn't already loaded + possibly mutated it — an unconditional
            // put would clobber a just-created kit with stale file state.
            cache.putIfAbsent(uuid, pk);
        });
    }

    public void unload(UUID uuid) {
        cache.remove(uuid);
        editing.remove(uuid);
        pendingRename.remove(uuid);
        pendingCreate.remove(uuid);
    }

    // ── Transient edit / rename / create state ─────────────────────────────────

    public void setEditing(UUID uuid, int index) { editing.put(uuid, index); }
    public Integer getEditing(UUID uuid)          { return editing.get(uuid); }
    public void clearEditing(UUID uuid)           { editing.remove(uuid); }

    // Only ONE chat prompt may be live at a time — setting one clears the other
    // so a stale prompt can't silently consume the player's next chat message.
    public void setPendingRename(UUID uuid, int index) { pendingCreate.remove(uuid); pendingRename.put(uuid, index); }
    public Integer consumePendingRename(UUID uuid)     { return pendingRename.remove(uuid); }

    public void setPendingCreate(UUID uuid, int index) { pendingRename.remove(uuid); pendingCreate.put(uuid, index); }
    public Integer consumePendingCreate(UUID uuid)     { return pendingCreate.remove(uuid); }
    public boolean hasPendingChat(UUID uuid) {
        return pendingRename.containsKey(uuid) || pendingCreate.containsKey(uuid);
    }
    public void cancelPendingChat(UUID uuid) {
        pendingRename.remove(uuid);
        pendingCreate.remove(uuid);
    }

    private PlayerKits get(UUID uuid) {
        return cache.computeIfAbsent(uuid, this::loadFromFile);
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public Kit getKit(UUID uuid, int index) { return get(uuid).kits.get(index); }
    public boolean hasKit(UUID uuid, int index) { return get(uuid).kits.containsKey(index); }
    public int getSelected(UUID uuid) { return get(uuid).selected; }
    public boolean isSelected(UUID uuid, int index) { return get(uuid).selected == index; }

    /** The selected kit's 41-slot contents, or null if nothing is selected. */
    public ItemStack[] getSelectedItems(UUID uuid) {
        PlayerKits pk = get(uuid);
        if (pk.selected < 0) return null;
        Kit k = pk.kits.get(pk.selected);
        return k == null ? null : cloneItems(k.items);
    }

    /** True if the player has at least one vanilla kit. */
    public boolean hasAnyKit(UUID uuid) { return !get(uuid).kits.isEmpty(); }

    // ── Mutations ────────────────────────────────────────────────────────────────

    /** Creates a new kit in the slot with the default vanilla loadout. Selects it. */
    public void createKit(UUID uuid, int index, String name) {
        PlayerKits pk = get(uuid);
        ItemStack[] def = plugin.getKitManager().loadDefaultKitPublic("vanilla");
        pk.kits.put(index, new Kit(sanitize(name), cloneItems(def)));
        pk.selected = index;
        save(uuid, pk);
    }

    /** Marks a kit as the active one. No-op if the slot is empty. */
    public void selectKit(UUID uuid, int index) {
        PlayerKits pk = get(uuid);
        if (!pk.kits.containsKey(index)) return;
        pk.selected = index;
        save(uuid, pk);
    }

    /** Renames a kit. No-op if the slot is empty. */
    public void renameKit(UUID uuid, int index, String name) {
        PlayerKits pk = get(uuid);
        Kit k = pk.kits.get(index);
        if (k == null) return;
        k.name = sanitize(name);
        save(uuid, pk);
    }

    /** Resets a kit's contents back to the bundled default vanilla loadout. */
    public void resetKit(UUID uuid, int index) {
        PlayerKits pk = get(uuid);
        Kit k = pk.kits.get(index);
        if (k == null) return;
        k.items = cloneItems(plugin.getKitManager().loadDefaultKitPublic("vanilla"));
        save(uuid, pk);
    }

    /** Overwrites kit {@code index}'s contents. Returns false if the slot is empty. */
    public boolean saveKit(UUID uuid, int index, ItemStack[] items) {
        PlayerKits pk = get(uuid);
        Kit k = pk.kits.get(index);
        if (k == null) return false;
        k.items = cloneItems(items);
        save(uuid, pk);
        return true;
    }

    /** A clone of kit {@code index}'s 41-slot contents, or null if empty. */
    public ItemStack[] getKitItems(UUID uuid, int index) {
        Kit k = get(uuid).kits.get(index);
        return k == null ? null : cloneItems(k.items);
    }

    /** A clone of kit {@code index}'s 27-slot ender-chest contents, or null if empty. */
    public ItemStack[] getEnderItems(UUID uuid, int index) {
        Kit k = get(uuid).kits.get(index);
        return k == null ? null : cloneEnder(k.enderItems);
    }

    /** The SELECTED kit's ender-chest contents (27 slots), or null if nothing selected. */
    public ItemStack[] getSelectedEnderItems(UUID uuid) {
        PlayerKits pk = get(uuid);
        if (pk.selected < 0) return null;
        Kit k = pk.kits.get(pk.selected);
        return k == null ? null : cloneEnder(k.enderItems);
    }

    /** Overwrites kit {@code index}'s ender-chest contents. Returns false if the slot is empty. */
    public boolean saveEnderItems(UUID uuid, int index, ItemStack[] items) {
        PlayerKits pk = get(uuid);
        Kit k = pk.kits.get(index);
        if (k == null) return false;
        k.enderItems = cloneEnder(items);
        save(uuid, pk);
        return true;
    }

    // ── Persistence ──────────────────────────────────────────────────────────────

    private File file(UUID uuid) {
        File dir = new File(plugin.getDataFolder(), "data-player");
        if (!dir.exists()) dir.mkdirs();
        // Distinct from KitManager's per-mode files ({uuid}-{kitType}.yml) to
        // avoid clashing with a hypothetical "vanilla" mode-kit file.
        return new File(dir, uuid + "-vanillakits.yml");
    }

    private PlayerKits loadFromFile(UUID uuid) {
        PlayerKits pk = new PlayerKits();
        File f = file(uuid);
        if (!f.exists()) return pk;
        try {
            YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
            pk.selected = cfg.getInt("selected", -1);
            ConfigurationSection ks = cfg.getConfigurationSection("kits");
            if (ks != null) {
                for (String key : ks.getKeys(false)) {
                    int idx;
                    try { idx = Integer.parseInt(key); } catch (NumberFormatException e) { continue; }
                    ConfigurationSection e = ks.getConfigurationSection(key);
                    if (e == null) continue;
                    String name = e.getString("name", "Kit " + (idx + 1));
                    ItemStack[] items = plugin.getKitManager().deserializeKitItems(e.getList("items", new ArrayList<>()));
                    ItemStack[] ender = enderFromList(plugin.getKitManager()
                            .deserializeKitItems(e.getList("ender", new ArrayList<>())));
                    pk.kits.put(idx, new Kit(name, items, ender));
                }
            }
            // Guard: selected must point at an existing kit.
            if (!pk.kits.containsKey(pk.selected)) pk.selected = -1;
        } catch (Exception e) {
            plugin.getLogger().warning("[ScorePvP] loadVanillaKits(" + uuid + ") failed: " + e.getMessage());
        }
        return pk;
    }

    private void save(UUID uuid, PlayerKits pk) {
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("selected", pk.selected);
            for (Map.Entry<Integer, Kit> en : pk.kits.entrySet()) {
                String base = "kits." + en.getKey();
                cfg.set(base + ".name", en.getValue().name);
                cfg.set(base + ".items", plugin.getKitManager().serializeKitPublic(en.getValue().items));
                cfg.set(base + ".ender", plugin.getKitManager().serializeKitPublic(en.getValue().enderItems));
            }
            cfg.save(file(uuid));
        } catch (Exception e) {
            plugin.getLogger().warning("[ScorePvP] saveVanillaKits(" + uuid + ") failed: " + e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static ItemStack[] cloneItems(ItemStack[] src) {
        ItemStack[] out = new ItemStack[41];
        if (src == null) return out; // missing/misconfigured default kit → empty
        for (int i = 0; i < 41 && i < src.length; i++) out[i] = src[i] == null ? null : src[i].clone();
        return out;
    }

    private static ItemStack[] cloneEnder(ItemStack[] src) {
        ItemStack[] out = new ItemStack[ENDER_SLOTS];
        if (src == null) return out;
        for (int i = 0; i < ENDER_SLOTS && i < src.length; i++) out[i] = src[i] == null ? null : src[i].clone();
        return out;
    }

    /**
     * KitManager's serializer always returns a 41-slot array; the ender box only
     * uses the first 27. Trim it down without losing item metadata.
     */
    private static ItemStack[] enderFromList(ItemStack[] full) {
        return cloneEnder(full);
    }

    private static String sanitize(String raw) {
        if (raw == null) return "Kit";
        String s = raw.trim();
        if (s.length() > 32) s = s.substring(0, 32);
        return s.isEmpty() ? "Kit" : s;
    }
}
