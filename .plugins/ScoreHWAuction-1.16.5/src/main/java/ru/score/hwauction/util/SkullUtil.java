package ru.score.hwauction.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.Base64;

/**
 * Helpers for PLAYER_HEAD / custom skull items.
 * Compatible with Paper 1.17+ (uses reflection for the 1.18+ PlayerProfile API).
 *
 * Config format examples:
 *   PLAYER_HEAD               → matches any player head
 *   PLAYER_HEAD:eyJ0ZXh0dXJlc  → matches skulls whose texture starts with that base64 prefix
 */
public final class SkullUtil {

    private SkullUtil() {}

    /** Returns true if item is a PLAYER_HEAD (any skull) */
    public static boolean isSkull(ItemStack item) {
        return item != null && item.getType() == Material.PLAYER_HEAD;
    }

    /**
     * Extract the texture hash from a skull (last path segment of the texture URL).
     * Works on 1.17+ via reflection, uses 1.18+ PlayerProfile API when available.
     */
    public static String getTextureHash(ItemStack item) {
        if (!isSkull(item)) return "";
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return "";

        // Try 1.18+ PlayerProfile API via reflection
        try {
            Method getOwnerProfile = meta.getClass().getMethod("getOwnerProfile");
            Object profile = getOwnerProfile.invoke(meta);
            if (profile != null) {
                Method getTextures = profile.getClass().getMethod("getTextures");
                Object textures = getTextures.invoke(profile);
                Method getSkin = textures.getClass().getMethod("getSkin");
                URL skin = (URL) getSkin.invoke(textures);
                if (skin != null) {
                    String path = skin.getPath();
                    int lastSlash = path.lastIndexOf('/');
                    return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
                }
            }
        } catch (Exception ignored) {}

        // 1.17 fallback: read GameProfile from CraftMetaSkull via reflection
        return extractHashFromGameProfile(meta);
    }

    /**
     * Checks whether a skull's texture starts with the given prefix.
     */
    public static boolean matchesPrefix(ItemStack item, String prefix) {
        if (!isSkull(item)) return false;
        if (prefix == null || prefix.isBlank()) return true;

        String hash = getTextureHash(item);
        if (!hash.isEmpty() && hash.toLowerCase().startsWith(prefix.toLowerCase())) return true;

        String raw = getRawBase64(item);
        return !raw.isEmpty() && raw.toLowerCase().startsWith(prefix.toLowerCase());
    }

    /** Returns the raw base64 texture value as Minecraft stores it. */
    public static String getRawBase64(ItemStack item) {
        if (!isSkull(item)) return "";
        if (!(item.getItemMeta() instanceof SkullMeta meta)) return "";

        // 1.18+ PlayerProfile via reflection
        try {
            Method getOwnerProfile = meta.getClass().getMethod("getOwnerProfile");
            Object profile = getOwnerProfile.invoke(meta);
            if (profile != null) {
                Method getTextures = profile.getClass().getMethod("getTextures");
                Object textures = getTextures.invoke(profile);
                Method getSkin = textures.getClass().getMethod("getSkin");
                URL skin = (URL) getSkin.invoke(textures);
                if (skin != null) {
                    String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + skin + "\"}}}";
                    return Base64.getEncoder().encodeToString(json.getBytes());
                }
            }
        } catch (Exception ignored) {}

        return extractRawBase64FromGameProfile(meta);
    }

    /** Returns a short human-readable identifier for display. */
    public static String getSkullDisplayName(ItemStack item) {
        if (!isSkull(item)) return "";
        if (item.hasItemMeta() && item.getItemMeta() != null
                && item.getItemMeta().hasDisplayName()) {
            return ColorUtil.strip(item.getItemMeta().getDisplayName());
        }
        if (item.getItemMeta() instanceof SkullMeta meta && meta.getOwningPlayer() != null) {
            return meta.getOwningPlayer().getName() != null
                    ? meta.getOwningPlayer().getName() + "'s Head"
                    : "Player Head";
        }
        return "Custom Skull";
    }

    // ── Internal helpers ─────────────────────────────────────────────────────

    private static String extractHashFromGameProfile(SkullMeta meta) {
        String raw = extractRawBase64FromGameProfile(meta);
        if (raw.isEmpty()) return "";
        try {
            String decoded = new String(Base64.getDecoder().decode(raw));
            int urlStart = decoded.indexOf("http");
            if (urlStart < 0) return raw;
            int urlEnd = decoded.indexOf('"', urlStart);
            if (urlEnd <= urlStart) return raw;
            String textureUrl = decoded.substring(urlStart, urlEnd);
            int lastSlash = textureUrl.lastIndexOf('/');
            return lastSlash >= 0 ? textureUrl.substring(lastSlash + 1) : textureUrl;
        } catch (Exception e) {
            return raw;
        }
    }

    /**
     * Reads the raw base64 "textures" property from the GameProfile stored
     * inside CraftMetaSkull via reflection. Works on 1.17.x and 1.18+.
     */
    private static String extractRawBase64FromGameProfile(SkullMeta meta) {
        try {
            Field profileField = null;
            Class<?> clazz = meta.getClass();
            while (clazz != null && profileField == null) {
                try { profileField = clazz.getDeclaredField("profile"); }
                catch (NoSuchFieldException ignored) { clazz = clazz.getSuperclass(); }
            }
            if (profileField == null) return "";
            profileField.setAccessible(true);
            Object gameProfile = profileField.get(meta);
            if (gameProfile == null) return "";

            Method getProperties = gameProfile.getClass().getMethod("getProperties");
            Object propertyMap = getProperties.invoke(gameProfile);

            Method getMethod = propertyMap.getClass().getMethod("get", Object.class);
            Iterable<?> props = (Iterable<?>) getMethod.invoke(propertyMap, "textures");
            for (Object prop : props) {
                Method getValue = prop.getClass().getMethod("getValue");
                String value = (String) getValue.invoke(prop);
                if (value != null && !value.isEmpty()) return value;
            }
        } catch (Exception ignored) {}
        return "";
    }
}
