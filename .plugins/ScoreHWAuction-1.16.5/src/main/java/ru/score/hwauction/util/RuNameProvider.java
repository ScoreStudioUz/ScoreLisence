package ru.score.hwauction.util;

import org.bukkit.Material;
import ru.score.hwauction.ScoreHWAuction;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Maps a {@link Material} to its official Russian name (from Mojang ru_ru, MC 1.16.5).
 * Backed by the bundled {@code items_ru.properties} resource, keyed by the vanilla
 * minecraft id (which equals {@code Material.name().toLowerCase()} for vanilla items).
 */
public final class RuNameProvider {

    private final ScoreHWAuction plugin;
    private Map<Material, String> byMaterial = Collections.emptyMap();
    /** Prebuilt, sorted, deduped catalog of every searchable name (EN + RU vanilla). */
    private List<String> vanillaCatalog = Collections.emptyList();

    public RuNameProvider(ScoreHWAuction plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        Map<Material, String> map = new EnumMap<>(Material.class);
        try (InputStream in = plugin.getResource("items_ru.properties")) {
            if (in == null) {
                plugin.getLogger().warning("items_ru.properties not found in jar — Russian search disabled.");
                byMaterial = Collections.emptyMap();
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    int eq = line.indexOf('=');
                    if (eq <= 0) continue;
                    String id  = line.substring(0, eq).trim();
                    String ru  = line.substring(eq + 1).trim();
                    if (id.isEmpty() || ru.isEmpty()) continue;
                    Material mat = Material.getMaterial(id.toUpperCase());
                    // Silently skip ids that don't exist as a Material on this server version
                    if (mat != null) map.put(mat, ru);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load items_ru.properties: " + e.getMessage());
        }
        byMaterial = map;
        plugin.getLogger().info("Loaded " + byMaterial.size() + " Russian item names.");
        buildCatalog();
    }

    private void buildCatalog() {
        TreeSet<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isItem()) continue;
            set.add(ItemUtil.materialName(m));           // English
            String ru = byMaterial.get(m);
            if (ru != null && !ru.isEmpty()) set.add(ru); // Russian
        }
        vanillaCatalog = List.copyOf(set);
    }

    /** Russian name for this material, or empty string if unknown. */
    public String getRussian(Material material) {
        if (material == null) return "";
        return byMaterial.getOrDefault(material, "");
    }

    /** Prebuilt sorted catalog of all searchable vanilla names (EN + RU). Unmodifiable. */
    public List<String> vanillaCatalog() {
        return vanillaCatalog;
    }

    /** All known Russian names (for tab-complete catalog). Unmodifiable. */
    public Iterable<String> allRussianNames() {
        return Collections.unmodifiableCollection(byMaterial.values());
    }
}
