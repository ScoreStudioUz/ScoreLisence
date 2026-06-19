package uz.score.scorecheck.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles both legacy (&f, &c) and hex (#RRGGBB or &#RRGGBB) color codes.
 * Supports MiniMessage as well for boss bar titles.
 */
public final class ColorUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");
    private static final Pattern HEX_PATTERN_PLAIN = Pattern.compile("#([A-Fa-f0-9]{6})");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ColorUtil() {}

    /**
     * Translate a string with &codes and #RRGGBB / &#RRGGBB hex codes into a Component.
     */
    public static Component colorize(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return LEGACY.deserialize(translateHex(text));
    }

    /**
     * Translate and send to player.
     */
    public static void send(Player player, String text) {
        if (text == null || text.isBlank()) return;
        player.sendMessage(colorize(text));
    }

    /**
     * Translate and send to any CommandSender.
     */
    public static void send(org.bukkit.command.CommandSender sender, String text) {
        if (text == null || text.isBlank()) return;
        sender.sendMessage(colorize(text));
    }

    /**
     * Send multi-line message (splits on \n).
     */
    public static void sendLines(Player player, String text) {
        if (text == null || text.isBlank()) return;
        for (String line : text.split("\n")) {
            send(player, line);
        }
    }

    public static void sendLines(org.bukkit.command.CommandSender sender, String text) {
        if (text == null || text.isBlank()) return;
        for (String line : text.split("\n")) {
            send(sender, line);
        }
    }

    /**
     * Convert &#RRGGBB and #RRGGBB to §x§R§R§G§G§B§B legacy format.
     */
    public static String translateHex(String text) {
        // Handle &#RRGGBB
        Matcher m1 = HEX_PATTERN.matcher(text);
        StringBuffer sb1 = new StringBuffer();
        while (m1.find()) {
            m1.appendReplacement(sb1, hexToLegacy(m1.group(1)));
        }
        m1.appendTail(sb1);
        text = sb1.toString();

        // Handle plain #RRGGBB (not preceded by &)
        Matcher m2 = HEX_PATTERN_PLAIN.matcher(text);
        StringBuffer sb2 = new StringBuffer();
        while (m2.find()) {
            int start = m2.start();
            // Skip if it was already processed (preceded by §)
            if (start > 0 && text.charAt(start - 1) == '§') {
                m2.appendReplacement(sb2, Matcher.quoteReplacement(m2.group(0)));
                continue;
            }
            m2.appendReplacement(sb2, hexToLegacy(m2.group(1)));
        }
        m2.appendTail(sb2);
        text = sb2.toString();

        // Translate regular & codes
        return text.replace("&", "§").replace("§§", "&");
    }

    /**
     * Strip all color codes and return plain text (for boss bar raw title).
     */
    public static String stripColors(String text) {
        if (text == null) return "";
        return translateHex(text).replaceAll("§[0-9a-fk-orxA-FK-ORX]", "");
    }

    /**
     * Convert 6-char hex string to §x§R§R§G§G§B§B format.
     */
    private static String hexToLegacy(String hex) {
        StringBuilder sb = new StringBuilder("§x");
        for (char c : hex.toCharArray()) {
            sb.append('§').append(c);
        }
        return sb.toString();
    }

    /**
     * Parse a MiniMessage string (for boss bar Adventure API title).
     */
    public static Component parseMiniMessage(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        // If it contains & codes, convert first to minimessage-friendly form
        if (text.contains("&") || text.contains("#")) {
            // Pass through legacy serializer
            return colorize(text);
        }
        return MM.deserialize(text);
    }
}
