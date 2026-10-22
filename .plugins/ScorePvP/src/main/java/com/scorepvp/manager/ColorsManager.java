package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.PluginManager;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Loads {@code colors.yml} — permission-gated nick colors that apply to a
 * player's in-combat nametag (duel / queue / FFA).
 *
 * <p>Distinct from the {@code /cosmetics} gradient colors, which apply ONLY in
 * the lobby/afk room. These colors apply in COMBAT: a fighter whose permission
 * matches an entry has their nick painted that color above their head during
 * the match; a player with no matching permission keeps a plain WHITE nick.
 *
 * <p>When a player holds several color permissions, the FIRST matching entry in
 * file order wins (admins order the file by priority, strongest on top).
 */
public class ColorsManager {

    public record ColorEntry(String id, String colorSpec, String permission) {}

    private final ScorePvP plugin;
    /** id → entry, in file order (priority order). */
    private final Map<String, ColorEntry> colors = new LinkedHashMap<>();
    /** Permission nodes WE registered, so a reload can unregister them again. */
    private final java.util.Set<String> registered = new java.util.LinkedHashSet<>();

    public ColorsManager(ScorePvP plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        colors.clear();
        File file = new File(plugin.getDataFolder(), "colors.yml");
        if (!file.exists()) {
            try { plugin.saveResource("colors.yml", false); } catch (IllegalArgumentException ignored) {}
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = cfg.getConfigurationSection("colors");
        if (sec != null) {
            for (String id : sec.getKeys(false)) {
                ConfigurationSection e = sec.getConfigurationSection(id);
                if (e == null) continue;
                String color = e.getString("color", "");
                String perm  = e.getString("permission", "");
                if (color.isBlank()) continue;
                colors.put(id, new ColorEntry(id, color, perm.isBlank() ? null : perm));
            }
        }
        syncPermissions();
    }

    /**
     * Mirrors the colors.yml permission nodes into the server's permission
     * registry as {@link PermissionDefault#FALSE}. Without this an admin adding
     * a color to colors.yml would ALSO have to declare the node in plugin.yml —
     * an undeclared node defaults to OP, so every op would silently pick up the
     * first entry's color. Nodes we registered for colors that are now gone are
     * removed, so a reload doesn't leave stale permissions behind.
     */
    private void syncPermissions() {
        PluginManager pm = plugin.getServer().getPluginManager();
        for (String node : registered) pm.removePermission(node);
        registered.clear();
        for (ColorEntry c : colors.values()) {
            if (c.permission() == null) continue;
            if (pm.getPermission(c.permission()) != null) continue;
            pm.addPermission(new Permission(c.permission(),
                    "Combat nick color from colors.yml: " + c.id(),
                    PermissionDefault.FALSE));
            registered.add(c.permission());
        }
    }

    /**
     * The color SPEC this player is entitled to in combat (first permission
     * match in file order), or {@code null} if they hold none → caller renders
     * a plain white nick. A null permission on an entry means "everyone".
     */
    public String colorSpecFor(Player p) {
        if (p == null) return null;
        for (ColorEntry c : colors.values()) {
            // Every node is registered as default:FALSE by syncPermissions(), so
            // hasPermission() is only true when it was actually granted.
            if (c.permission() == null || p.hasPermission(c.permission())) return c.colorSpec();
        }
        return null;
    }

    public Map<String, ColorEntry> getColors() { return colors; }
}
