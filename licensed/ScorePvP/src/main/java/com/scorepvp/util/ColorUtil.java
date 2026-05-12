package com.scorepvp.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.List;
import java.util.stream.Collectors;

public final class ColorUtil {

    private static final LegacyComponentSerializer SERIALIZER =
            LegacyComponentSerializer.legacyAmpersand();

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
}
