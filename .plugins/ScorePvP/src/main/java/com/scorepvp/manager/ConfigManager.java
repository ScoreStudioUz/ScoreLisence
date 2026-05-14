package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PvPMode;
import com.scorepvp.util.ColorUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;
import java.util.logging.Logger;

public class ConfigManager {

    private final ScorePvP plugin;
    private final Logger log;

    private FileConfiguration lang;
    private FileConfiguration modesConfig;

    private final List<PvPMode> pvpModes   = new ArrayList<>();
    private final Map<String, PvPMode> modeById = new LinkedHashMap<>();

    // ── Protected hotbar slots (0-based inventory indices) ────────────────────
    // Spec "slots 1,2,8,9" are 1-based → 0-based: 0, 1, 7, 8
    public static final int[] PROTECTED_SLOTS = {0, 1, 7, 8};
    public static final Set<Integer> PROTECTED_SET = Set.of(0, 1, 7, 8);

    public ConfigManager(ScorePvP plugin) {
        this.plugin = plugin;
        this.log    = plugin.getLogger();
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        String langCode = plugin.getConfig().getString("language", "ru");
        lang        = loadYaml("lang/" + langCode + ".yml");
        modesConfig = loadYaml("modes.yml");
        loadModes();
    }

    private FileConfiguration loadYaml(String relativePath) {
        File file = new File(plugin.getDataFolder(), relativePath);
        if (!file.exists()) {
            try { plugin.saveResource(relativePath, false); }
            catch (IllegalArgumentException e) {
                log.warning("[ScorePvP] Resource not found in jar: " + relativePath);
            }
        }
        return YamlConfiguration.loadConfiguration(file);
    }

    private void loadModes() {
        pvpModes.clear();
        modeById.clear();
        if (modesConfig == null) return;

        for (String id : modesConfig.getKeys(false)) {
            var sec = modesConfig.getConfigurationSection(id);
            if (sec == null) continue;

            Material icon;
            try {
                icon = Material.valueOf(sec.getString("icon", "PAPER").toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warning("[ScorePvP] Invalid icon material for mode '" + id + "', using PAPER.");
                icon = Material.PAPER;
            }

            PvPMode mode = new PvPMode(
                    id,
                    sec.getString("display-name", id),
                    icon,
                    sec.getBoolean("icon-enchanted", false),
                    sec.getBoolean("enabled", true),
                    sec.getString("kit-type", "vanilla"),
                    sec.getStringList("allowed-arenas"),
                    sec.getInt("default-rounds", 3),
                    sec.getInt("countdown-seconds", 5),
                    sec.getString("min-client-version", "")
            );
            pvpModes.add(mode);
            modeById.put(id, mode);
        }
        log.info("[ScorePvP] Loaded " + pvpModes.size() + " PvP modes.");
    }

    // ── Warp ──────────────────────────────────────────────────────────────────

    public Location getWarpLocation() {
        FileConfiguration cfg = plugin.getConfig();
        String worldName = cfg.getString("warp.world", "world");
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) {
            log.warning("[ScorePvP] Warp world '" + worldName + "' not found!");
            return null;
        }
        return new Location(world,
                cfg.getDouble("warp.x", 0.5),
                cfg.getDouble("warp.y", 64.0),
                cfg.getDouble("warp.z", 0.5),
                (float) cfg.getDouble("warp.yaw", 0.0),
                (float) cfg.getDouble("warp.pitch", 0.0));
    }

    // ── TP Spawn (post-match teleport point) ────────────────────────────────

    /**
     * Returns the tp-spawn location used after match ends.
     * Falls back to the warp location if tp-spawn is not configured.
     */
    public Location getTpSpawnLocation() {
        FileConfiguration cfg = plugin.getConfig();
        String worldName = cfg.getString("tp-spawn.world", "");
        if (worldName.isEmpty()) return getWarpLocation(); // fallback
        World world = plugin.getServer().getWorld(worldName);
        if (world == null) return getWarpLocation(); // fallback
        return new Location(world,
                cfg.getDouble("tp-spawn.x", 0.5),
                cfg.getDouble("tp-spawn.y", 64.0),
                cfg.getDouble("tp-spawn.z", 0.5),
                (float) cfg.getDouble("tp-spawn.yaw", 0.0),
                (float) cfg.getDouble("tp-spawn.pitch", 0.0));
    }

    // ── Chest Region ──────────────────────────────────────────────────────────

    public boolean isInChestRegion(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        FileConfiguration cfg = plugin.getConfig();
        String regionWorld = cfg.getString("chest-region.world", "world");
        if (!loc.getWorld().getName().equals(regionWorld)) return false;
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        return x >= cfg.getInt("chest-region.min-x") && x <= cfg.getInt("chest-region.max-x")
            && y >= cfg.getInt("chest-region.min-y") && y <= cfg.getInt("chest-region.max-y")
            && z >= cfg.getInt("chest-region.min-z") && z <= cfg.getInt("chest-region.max-z");
    }

    // ── Messages ──────────────────────────────────────────────────────────────

    /** Returns the plugin prefix respecting use-plugin-prefix config, or empty string. */
    public String prefix() {
        if (!plugin.getConfig().getBoolean("use-plugin-prefix", true)) return "";
        return ColorUtil.color(lang.getString("prefix", "&8[&bScorePvP&8] "));
    }

    /** Returns colored message with prefix from lang.yml. */
    public String msg(String path, String... placeholders) {
        String prefix = prefix();
        String raw    = lang.getString(path, "&c[Missing lang key: " + path + "]");
        String result = prefix + raw;
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            result = result.replace(placeholders[i], placeholders[i + 1]);
        }
        return ColorUtil.color(result);
    }

    /** Returns colored message WITHOUT prefix. */
    public String rawMsg(String path, String... placeholders) {
        String raw = lang.getString(path, "&c[Missing: " + path + "]");
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace(placeholders[i], placeholders[i + 1]);
        }
        return ColorUtil.color(raw);
    }

    /** Returns a colored list of strings from lang.yml. */
    /** Returns color-translated list. Use rawList() when ItemBuilder will translate. */
    public List<String> msgList(String path) {
        return ColorUtil.color(lang.getStringList(path));
    }

    /** Bug #8: returns RAW strings (& codes, not translated) for use with ItemBuilder.lore(). */
    public List<String> rawList(String path) {
        return lang.getStringList(path);
    }

    /** Bug #8: returns a single raw string (& codes, not translated). */
    public String rawStr(String path) {
        return lang.getString(path, "");
    }

    /** Returns colored multi-line message with placeholders replaced. */
    public List<String> msgLines(String path, String... placeholders) {
        List<String> lines = lang.getStringList(path);
        if (lines.isEmpty()) {
            // Fallback: try as single string
            String single = lang.getString(path, null);
            if (single != null) lines = List.of(single);
            else return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        for (String line : lines) {
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                line = line.replace(placeholders[i], placeholders[i + 1]);
            }
            result.add(ColorUtil.color(line));
        }
        return result;
    }

    /** Sends a multi-line block message to a player with placeholders replaced. */
    public void sendBlockMessage(org.bukkit.entity.Player player, String path, String... placeholders) {
        if (player == null) return;
        for (String line : msgLines(path, placeholders)) {
            player.sendMessage(line);
        }
    }

    // ── Tiers ─────────────────────────────────────────────────────────────────

    public List<String> getTierOrder() {
        return plugin.getConfig().getStringList("tiers.order");
    }

    public String getTierColor(String tier) {
        return ColorUtil.color(plugin.getConfig().getString("tiers.colors." + tier, "&f"));
    }

    // ── Modes ─────────────────────────────────────────────────────────────────

    public List<PvPMode>         getModes()   { return Collections.unmodifiableList(pvpModes); }
    public PvPMode               getMode(String id) { return modeById.get(id); }
    public Map<String, PvPMode>  getModeMap() { return Collections.unmodifiableMap(modeById); }

    public FileConfiguration getLang()        { return lang; }
    public FileConfiguration getModesConfig() { return modesConfig; }
}
