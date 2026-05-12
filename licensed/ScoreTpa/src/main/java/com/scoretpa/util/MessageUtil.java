package com.scoretpa.util;

import com.scoretpa.ScoreTpa;
import com.scoretpa.manager.ConfigManager;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.entity.Player;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MessageUtil {

    private static final Pattern HEX_PATTERN = Pattern.compile("#([A-Fa-f0-9]{6})");

    // Minecraft default font chat width in pixels
    private static final int CHAT_WIDTH = 320;
    // Space character width in pixels (default font)
    private static final int SPACE_WIDTH = 4;

    private MessageUtil() {}

    public static String color(String text) {
        if (text == null) return "";
        Matcher matcher = HEX_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String hex = matcher.group();
            ChatColor hexColor = ChatColor.of(hex);
            matcher.appendReplacement(sb, hexColor.toString());
        }
        matcher.appendTail(sb);
        return ChatColor.translateAlternateColorCodes('&', sb.toString());
    }

    public static void send(Player player, String message) {
        player.sendMessage(color(ScoreTpa.getInstance().getConfigManager().getPrefix() + message));
    }

    public static void sendRaw(Player player, String message) {
        player.sendMessage(color(message));
    }

    public static void sendRequestButtons(Player sender, Player target) {
        ConfigManager cfg = ScoreTpa.getInstance().getConfigManager();

        // --- Buttons for target: [ACCEPT] [DENY] ---
        String acceptText = color(cfg.getMessage("button-accept"));
        String denyText = color(cfg.getMessage("button-deny"));

        int buttonsWidth = getPixelWidth(stripColor(acceptText)) + SPACE_WIDTH + getPixelWidth(stripColor(denyText));
        int padding = (CHAT_WIDTH - buttonsWidth) / 2;
        String spaces = buildSpaces(padding);

        TextComponent pad = new TextComponent(spaces);
        TextComponent accept = createButton(
                cfg.getMessage("button-accept"),
                cfg.getMessage("hover-accept"),
                "/tpaccept " + sender.getName()
        );
        TextComponent space = new TextComponent(" ");
        TextComponent deny = createButton(
                cfg.getMessage("button-deny"),
                cfg.getMessage("hover-deny"),
                "/tpdeny " + sender.getName()
        );

        target.spigot().sendMessage(pad, accept, space, deny);

        // --- Cancel button for sender ---
        String cancelText = color(cfg.getMessage("button-cancel"));
        int cancelWidth = getPixelWidth(stripColor(cancelText));
        int cancelPadding = (CHAT_WIDTH - cancelWidth) / 2;
        String cancelSpaces = buildSpaces(cancelPadding);

        TextComponent cancelPad = new TextComponent(cancelSpaces);
        TextComponent cancel = createButton(
                cfg.getMessage("button-cancel"),
                cfg.getMessage("hover-cancel"),
                "/tpcancel"
        );

        sender.spigot().sendMessage(cancelPad, cancel);
    }

    @SuppressWarnings("deprecation")
    private static TextComponent createButton(String text, String hover, String command) {
        TextComponent component = new TextComponent(TextComponent.fromLegacyText(color(text)));
        component.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        component.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder(color(hover)).create()));
        return component;
    }

    /**
     * Strip color codes (&x, section sign) from text for width calculation
     */
    private static String stripColor(String text) {
        return ChatColor.stripColor(text);
    }

    /**
     * Build padding string of approximately the given pixel width using spaces
     */
    private static String buildSpaces(int pixels) {
        if (pixels <= 0) return "";
        int count = pixels / (SPACE_WIDTH + 1); // space = 4px + 1px gap
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(' ');
        }
        return sb.toString();
    }

    /**
     * Calculate pixel width of a string using Minecraft default font character widths.
     * Each character has a width + 1px gap between characters.
     */
    private static int getPixelWidth(String text) {
        if (text == null || text.isEmpty()) return 0;
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            width += getCharWidth(c) + 1; // +1 for gap between chars
        }
        return width > 0 ? width - 1 : 0; // remove last gap
    }

    /**
     * Minecraft default font character widths in pixels.
     */
    private static int getCharWidth(char c) {
        switch (c) {
            // 1px
            case '!': case ',': case '.': case ':': case ';': case 'i': case '|':
                return 2;
            // 2px
            case '\'': case 'l': case '`':
                return 3;
            // 3px
            case ' ': case 'I': case '[': case ']': case 't':
            case '"':
                return 4;
            // 4px
            case 'f': case 'k': case '(': case ')': case '{': case '}':
            case '<': case '>':
                return 5;
            // 6px
            case '@': case '~':
                return 7;
            default:
                // Most characters (A-Z, a-z, 0-9, etc.) are 6px
                return 6;
        }
    }
}
