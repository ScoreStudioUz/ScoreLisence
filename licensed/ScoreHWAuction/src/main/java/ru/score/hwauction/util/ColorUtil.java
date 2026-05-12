package ru.score.hwauction.util;

import net.md_5.bungee.api.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ColorUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("&#([A-Fa-f0-9]{6})");

    private ColorUtil() {}

    public static String translate(String text) {
        if (text == null) return "";
        // Hex colors &#RRGGBB → Paper 1.16+
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, ChatColor.of("#" + matcher.group(1)).toString());
        }
        matcher.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    public static String strip(String text) {
        return ChatColor.stripColor(translate(text));
    }

    public static String placeholder(String text, String key, String value) {
        return text.replace(key, value);
    }
}
