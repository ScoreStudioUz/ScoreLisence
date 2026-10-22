package com.scorepvp.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.List;
import java.util.stream.Collectors;

public final class ColorUtil {

    private static final LegacyComponentSerializer SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();
    // Hex-aware serializer for MiniMessage output: emits Bukkit's
    // "&x&R&R&G&G&B&B" hex sequences so gradients survive as true RGB instead of
    // being downsampled to the 16 named colors. This exact format is understood
    // by BOTH downstream paths: ChatColor.translateAlternateColorCodes (item
    // names/lore) AND LegacyComponentSerializer.legacyAmpersand().deserialize
    // (nametag prefixes). The plain "&#rrggbb" form would only work for the
    // latter, so the unusual X-repeated format is required.
    @SuppressWarnings("deprecation")
    private static final LegacyComponentSerializer HEX_SERIALIZER =
            LegacyComponentSerializer.builder().character('&').hexColors()
                    .useUnusualXRepeatedCharacterHexFormat().build();
    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private ColorUtil() {}

    public static String color(String text) {
        if (text == null) return "";
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public static List<String> color(List<String> lines) {
        return lines.stream().map(ColorUtil::color).collect(Collectors.toList());
    }

    public static Component component(String text) {
        return SERIALIZER.deserialize(text);
    }

    public static String strip(String text) {
        return ChatColor.stripColor(color(text));
    }

    /** True if the string looks like MiniMessage markup (contains a {@code <tag>}). */
    public static boolean isMiniMessage(String text) {
        if (text == null) return false;
        int lt = text.indexOf('<');
        return lt >= 0 && text.indexOf('>', lt) > lt;
    }

    /**
     * Wraps a raw text with a tier color spec and returns a legacy string.
     * If the spec is MiniMessage (e.g. {@code <gradient:#a:#b>}) the gradient is
     * applied to the WHOLE wrapped text; otherwise the legacy color is simply
     * prefixed. {@code content} is inserted literally — callers pass e.g. "[LT3]".
     */
    public static String applyColorSpec(String spec, String content) {
        if (spec == null || spec.isBlank()) return content;
        // Bare hex color ("#RRGGBB" or "&#RRGGBB") — translateAlternateColorCodes
        // does NOT understand this form, so it would leak the literal "#RRGGBB"
        // in front of the text. Convert it to the "&x&R&R&G&G&B&B" sequence so it
        // renders as a real color in both the item-name and nametag paths.
        String hex = spec.startsWith("&#") ? spec.substring(1) : spec;
        if (hex.matches("#[0-9a-fA-F]{6}")) {
            try {
                return HEX_SERIALIZER.serialize(MINI.deserialize("<" + hex + ">" + content));
            } catch (Exception ex) {
                return content;
            }
        }
        if (isMiniMessage(spec)) {
            // MiniMessage treats '[' / ']' as plain text, so this is safe.
            // A malformed spec (bad hex, unknown tag) throws ParsingException —
            // never let that abort a GUI build or nametag render. Fall back to
            // the plain bracketed text so the tier is still readable.
            try {
                // A spec may carry a leading LEGACY segment before the first
                // MiniMessage tag, e.g. "&b🗡 <gradient:#9E9E9E:#FFFFFF>". That
                // lets admins put a differently-coloured icon in front of a
                // gradient-coloured tier. MiniMessage can't parse the "&b" part,
                // so split it off: the legacy head is kept verbatim (still in '&'
                // form) and only the "<...>" tail + content go through MiniMessage.
                int lt = spec.indexOf('<');
                String legacyHead = spec.substring(0, lt);
                String miniTail   = spec.substring(lt);
                // Output is "&x&R&R&G&G&B&B…" — kept in '&' form (NOT translated
                // to '§') because the legacyAmpersand deserializer used by the
                // nametag path only reads '&', while the item-name path runs its
                // own translateAlternateColorCodes('&', …). Both handle this.
                String rendered = HEX_SERIALIZER.serialize(MINI.deserialize(miniTail + content));
                return legacyHead + rendered;
            } catch (Exception ex) {
                return content;
            }
        }
        return color(spec) + content;
    }
}
