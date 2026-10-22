package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PlayerSettings;
import com.scorepvp.util.ColorUtil;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and resolves cosmetic name styles:
 * <ul>
 *   <li><b>NameColors</b> — a single color or gradient applied to the whole
 *       nick (namecolors/standart.yml = legacy/hex, namecolors/premium.yml =
 *       MiniMessage gradients).</li>
 *   <li><b>NameTags</b> — an emoji/symbol placed BEFORE the nick
 *       (nametags.yml), producing "(emoji) NICK".</li>
 * </ul>
 *
 * <p>The resolved style is applied everywhere a nick is shown (chat via the
 * {@code %scorepvp_name%} placeholder for ChatEx, and TAB). The chosen ids live
 * in {@link PlayerSettings} ({@code nameColor} / {@code nameTag}) and persist in
 * the player_settings table.
 */
public class NameStyleManager {

    /** A selectable name-color (standart or premium). */
    public record ColorEntry(String id, String colorSpec, String permission, Material icon, boolean premium) {}
    /** A selectable name-tag / badge (emoji prefix). price 0 = free; permission overrides price. */
    public record TagEntry(String id, String name, String tag, int price, int slot, String permission, Material icon) {}

    private final ScorePvP plugin;

    /** id → color entry, in file order (standart first, then premium). */
    private final Map<String, ColorEntry> colors = new LinkedHashMap<>();
    /** id → tag entry, in file order. */
    private final Map<String, TagEntry> tags = new LinkedHashMap<>();
    private String tagSeparator = " ";
    private int namecolorUnlockAfter = 21;

    /** uuid → set of owned badge ids (loaded on join, mutated on purchase). */
    private final Map<UUID, java.util.Set<String>> ownedBadges = new ConcurrentHashMap<>();

    public NameStyleManager(ScorePvP plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        colors.clear();
        tags.clear();
        loadColors("namecolors/standart.yml", false);
        loadColors("namecolors/premium.yml", true);
        loadTags("nametags.yml");
    }

    private void loadColors(String path, boolean premium) {
        File f = new File(plugin.getDataFolder(), path);
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        ConfigurationSection sec = cfg.getConfigurationSection("colors");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection e = sec.getConfigurationSection(id);
            if (e == null) continue;
            colors.put(id, new ColorEntry(
                    id,
                    e.getString("color", ""),
                    emptyToNull(e.getString("permission", "")),
                    mat(e.getString("icon", ""), Material.NAME_TAG),
                    premium));
        }
    }

    private void loadTags(String path) {
        File f = new File(plugin.getDataFolder(), path);
        if (!f.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(f);
        tagSeparator = cfg.getString("separator", " ");
        namecolorUnlockAfter = cfg.getInt("namecolor-unlock-after", 21);
        // New format uses "badges:"; keep "tags:" as a fallback for old files.
        ConfigurationSection sec = cfg.getConfigurationSection("badges");
        if (sec == null) sec = cfg.getConfigurationSection("tags");
        if (sec == null) return;
        for (String id : sec.getKeys(false)) {
            ConfigurationSection e = sec.getConfigurationSection(id);
            if (e == null) continue;
            tags.put(id, new TagEntry(
                    id,
                    e.getString("name", id),
                    e.getString("tag", ""),
                    e.getInt("price", 0),
                    e.getInt("slot", -1),
                    emptyToNull(e.getString("permission", "")),
                    mat(e.getString("icon", ""), Material.NAME_TAG)));
        }
    }

    public int getNamecolorUnlockAfter() { return namecolorUnlockAfter; }

    // ── Lookups ──────────────────────────────────────────────────────────────

    public Map<String, ColorEntry> getColors() { return colors; }
    public Map<String, TagEntry>   getTags()   { return tags; }
    public ColorEntry getColor(String id) { return id == null ? null : colors.get(id); }
    public TagEntry   getTag(String id)   { return id == null ? null : tags.get(id); }

    /** True if the player may select this color (no permission set = free). */
    public boolean canUse(Player p, ColorEntry c) {
        return c != null && (c.permission() == null || p.hasPermission(c.permission()));
    }
    public boolean canUse(Player p, TagEntry t) {
        return t != null && (t.permission() == null || p.hasPermission(t.permission()));
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * The player's fully-styled nick as a legacy '&'-coded string: the chosen
     * emoji prefix (if any) + the nick painted with the chosen color/gradient
     * (or plain white if none chosen). Safe to feed into a ChatEx format via the
     * {@code %scorepvp_name%} placeholder, or into TAB.
     *
     * <p>This ALWAYS reflects the /cosmetics choice, in the lobby and in a match
     * alike — colors.yml only governs the nametag above the player's head during
     * a match (see {@code NameTagFormatter}), never this placeholder.
     */
    public String styledName(Player p) {
        PlayerSettings st = plugin.getSettingsManager().get(p.getUniqueId());
        String colorId = st != null ? st.getNameColor() : null;
        ColorEntry c = getColor(colorId);
        String colored = (c != null && !c.colorSpec().isBlank())
                ? ColorUtil.applyColorSpec(c.colorSpec(), p.getName())
                : p.getName();
        // tagPrefix already verifies ownership + adds the separator.
        return tagPrefix(p) + colored;
    }

    /** Builds a styled nick from explicit ids (used by previews and offline paths). */
    public String styledName(String name, String colorId, String tagId) {
        ColorEntry c = getColor(colorId);
        String colored = (c != null && !c.colorSpec().isBlank())
                ? ColorUtil.applyColorSpec(c.colorSpec(), name)
                : name;
        TagEntry t = getTag(tagId);
        if (t != null && !t.tag().isBlank()) {
            return ColorUtil.color(t.tag()) + tagSeparator + colored;
        }
        return colored;
    }

    /** Just the emoji prefix (with trailing separator), or "" if none / not owned. */
    public String tagPrefix(Player p) {
        PlayerSettings st = plugin.getSettingsManager().get(p.getUniqueId());
        TagEntry t = st != null ? getTag(st.getNameTag()) : null;
        if (t == null || t.tag().isBlank()) return "";
        // Defence: never render a badge the player no longer owns.
        if (!ownsBadge(p, t)) return "";
        // If the player picked a badge color, recolor the emoji glyph with it;
        // otherwise fall back to the badge's own configured color.
        ColorEntry bc = getColor(st.getBadgeColor());
        if (bc != null && !bc.colorSpec().isBlank()) {
            String glyph = ColorUtil.strip(t.tag()); // drop the badge's own & codes
            return ColorUtil.applyColorSpec(bc.colorSpec(), glyph) + tagSeparator;
        }
        return ColorUtil.color(t.tag()) + tagSeparator;
    }

    // ── Mutators ─────────────────────────────────────────────────────────────

    public void setColor(UUID uuid, String id) {
        PlayerSettings st = plugin.getSettingsManager().get(uuid);
        if (st == null) return;
        st.setNameColor(id);
        plugin.getSettingsManager().persist(uuid);
    }
    public void setTag(UUID uuid, String id) {
        PlayerSettings st = plugin.getSettingsManager().get(uuid);
        if (st == null) return;
        st.setNameTag(id);
        plugin.getSettingsManager().persist(uuid);
    }
    public void setBadgeColor(UUID uuid, String id) {
        PlayerSettings st = plugin.getSettingsManager().get(uuid);
        if (st == null) return;
        st.setBadgeColor(id);
        plugin.getSettingsManager().persist(uuid);
    }
    public String getBadgeColorId(UUID uuid) {
        PlayerSettings st = plugin.getSettingsManager().get(uuid);
        return st != null ? st.getBadgeColor() : null;
    }

    // ── Badge ownership ───────────────────────────────────────────────────────

    public void loadBadgesAsync(UUID uuid) {
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> plugin.getStorage().getMain().loadBadges(uuid))
                .thenAccept(set -> {
                    java.util.Set<String> owned = java.util.concurrent.ConcurrentHashMap.newKeySet();
                    owned.addAll(set);
                    ownedBadges.put(uuid, owned);
                });
    }

    public void unloadBadges(UUID uuid) { ownedBadges.remove(uuid); }

    /** A free (permission-unlocked or price 0) or already-purchased badge counts as owned. */
    public boolean ownsBadge(org.bukkit.entity.Player p, TagEntry t) {
        if (t == null) return false;
        if (t.price() <= 0) return true;
        if (t.permission() != null && p.hasPermission(t.permission())) return true;
        java.util.Set<String> set = ownedBadges.get(p.getUniqueId());
        return set != null && set.contains(t.id());
    }

    /** Number of badges the player owns (for the name-color unlock gate). */
    public int ownedBadgeCount(org.bukkit.entity.Player p) {
        int n = 0;
        for (TagEntry t : tags.values()) if (ownsBadge(p, t)) n++;
        return n;
    }

    /** Records a badge as purchased (cache + DB). */
    public void grantBadge(UUID uuid, String id) {
        ownedBadges.computeIfAbsent(uuid, k -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add(id);
        java.util.concurrent.CompletableFuture.runAsync(
                () -> plugin.getStorage().getMain().addBadge(uuid, id))
                .exceptionally(ex -> {
                    // Silent failure here made purchased badges "reset" after a
                    // restart — silver spent but the row never persisted.
                    plugin.getLogger().severe("[ScorePvP] Failed to persist purchased badge "
                            + id + " for " + uuid + ": " + ex.getMessage());
                    return null;
                });
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static String emptyToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    private static Material mat(String name, Material def) {
        if (name == null || name.isBlank()) return def;
        try { return Material.valueOf(name.toUpperCase()); }
        catch (IllegalArgumentException e) { return def; }
    }
}
