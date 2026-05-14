package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PvPMode;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Checks whether a player's client version meets the minimum required by a PvP mode.
 *
 * Version format: "1.21.0", "1.20.x", "1.8.0", "" = no restriction.
 * Uses ViaVersion API if available; falls back to server version otherwise.
 */
public class ClientVersionManager {

    private final ScorePvP plugin;
    private final boolean viaVersionPresent;

    public ClientVersionManager(ScorePvP plugin) {
        this.plugin = plugin;
        this.viaVersionPresent = Bukkit.getPluginManager().getPlugin("ViaVersion") != null;
        if (viaVersionPresent) {
            plugin.getLogger().info("[ScorePvP] ViaVersion detected — client version checks enabled.");
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns true if the player's client meets the mode's min-client-version requirement.
     */
    public boolean canPlay(Player player, PvPMode mode) {
        if (!mode.hasVersionRestriction()) return true;
        String playerVer = getClientVersion(player);
        return meetsMinimum(playerVer, mode.getMinClientVersion());
    }

    /** Returns the player's client version string, e.g. "1.21.1". */
    public String getClientVersion(Player player) {
        boolean useVia = plugin.getConfig().getBoolean("version-check.use-viaversion", true);
        if (useVia && viaVersionPresent) {
            try {
                int protocolId = com.viaversion.viaversion.api.Via.getAPI()
                        .getPlayerVersion(player.getUniqueId());
                String name = com.viaversion.viaversion.api.protocol.version.ProtocolVersion
                        .getProtocol(protocolId).getName();
                return sanitize(name);
            } catch (Exception ignored) {}
        }
        // Fallback: server version
        boolean fallback = plugin.getConfig().getBoolean("version-check.fallback-to-server-version", true);
        return fallback ? Bukkit.getMinecraftVersion() : "unknown";
    }

    // ── Version comparison ────────────────────────────────────────────────────

    /**
     * Returns true if `playerVersion` >= `minVersion`.
     * Supports wildcard: "1.20.x" matches any 1.20.* version.
     */
    public boolean meetsMinimum(String playerVersion, String minVersion) {
        if (minVersion == null || minVersion.isEmpty()) return true;
        if (playerVersion == null || playerVersion.equalsIgnoreCase("unknown")) return false;

        // Wildcard: "1.20.x" → minimum is 1.20.0
        String effectiveMin = minVersion.replace(".x", ".0").replace("*", "0");
        String effectivePlayer = playerVersion;

        int[] min    = parseVersion(effectiveMin);
        int[] player = parseVersion(effectivePlayer);

        for (int i = 0; i < Math.max(min.length, player.length); i++) {
            int m = i < min.length    ? min[i]    : 0;
            int p = i < player.length ? player[i] : 0;
            if (p > m) return true;
            if (p < m) return false;
        }
        return true; // equal
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int[] parseVersion(String version) {
        String clean = version.replaceAll("[^0-9.]", "");
        String[] parts = clean.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { result[i] = Integer.parseInt(parts[i]); }
            catch (NumberFormatException e) { result[i] = 0; }
        }
        return result;
    }

    private String sanitize(String version) {
        if (version == null) return "unknown";
        return version.trim().replace("Java ", "").replace("-Pre", "").split(" ")[0];
    }
}
