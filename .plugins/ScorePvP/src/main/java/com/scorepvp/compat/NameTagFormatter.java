package com.scorepvp.compat;

import com.scorepvp.ScorePvP;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Shared prefix builder that reads format templates from config.yml
 * and replaces placeholders: %lp_prefix%, %tier_color%, %tier%, %team_color%.
 */
public class NameTagFormatter {

    private final ScorePvP plugin;
    private final LuckPermsHook luckPerms;
    private final boolean includeLpPrefix;

    private final String duelFormat;
    private final String ffaFormat;
    private final String noTierDuelFormat;
    private final String noTierFfaFormat;

    public NameTagFormatter(ScorePvP plugin, LuckPermsHook luckPerms) {
        this.plugin = plugin;
        this.luckPerms = luckPerms;
        this.includeLpPrefix = plugin.getConfig().getBoolean("nametag.include-luckperms-prefix", true);

        this.duelFormat = plugin.getConfig().getString("nametag.duel-format",
                "%lp_prefix%%tier_color%[%tier%] %team_color%| ");
        this.ffaFormat = plugin.getConfig().getString("nametag.ffa-format",
                "%lp_prefix%%tier_color%[%tier%] &f| ");
        this.noTierDuelFormat = plugin.getConfig().getString("nametag.no-tier-duel-format",
                "%lp_prefix%%team_color%");
        this.noTierFfaFormat = plugin.getConfig().getString("nametag.no-tier-ffa-format",
                "%lp_prefix%");
    }

    /**
     * Builds the prefix string for a player in a match.
     *
     * @param uuid          player UUID
     * @param modeId        game mode id (for tier lookup)
     * @param teamColorCode legacy color code for team color (e.g. "&9", "&c"), ignored in FFA
     * @param ffa           true if FFA match
     * @return formatted prefix string with legacy color codes
     */
    public String buildPrefix(UUID uuid, String modeId, String teamColorCode, boolean ffa) {
        String lpPrefix = getLpPrefix(uuid);
        String tier = plugin.getTierManager().getTier(uuid, modeId);

        if (tier == null || tier.isBlank()) {
            String template = ffa ? noTierFfaFormat : noTierDuelFormat;
            return applyPlaceholders(template, lpPrefix, "", "", teamColorCode);
        }

        String tierColor = plugin.getConfig().getString("tiers.colors." + tier, "&f");
        String template = ffa ? ffaFormat : duelFormat;
        return applyPlaceholders(template, lpPrefix, tierColor, tier, teamColorCode);
    }

    private String applyPlaceholders(String template, String lpPrefix,
                                     String tierColor, String tier, String teamColor) {
        return template
                .replace("%lp_prefix%", lpPrefix)
                .replace("%tier_color%", tierColor)
                .replace("%tier%", tier)
                .replace("%team_color%", teamColor);
    }

    private String getLpPrefix(UUID uuid) {
        if (!includeLpPrefix || luckPerms == null) return "";
        String prefix = luckPerms.getPrefix(uuid);
        return prefix.isEmpty() ? "" : prefix + " ";
    }

    /**
     * Returns the team suffix shown after the player's name, e.g. {@code " &a40ms"}.
     * Each fighter sees the OPPONENT's ping next to their name above their head —
     * the whole point of putting it here instead of in the attacker's own action
     * bar. Ping is color-graded by the same thresholds the HUD uses.
     */
    public String buildPingSuffix(Player p) {
        if (p == null) return "";
        // Whole feature can be disabled by clearing the template in config.
        String template = plugin.getConfig().getString("nametag.ping-suffix-format", " {color}{ping}ms");
        if (template == null || template.isEmpty()) return "";
        int ping = p.getPing();
        int goodMax = plugin.getConfig().getInt("hud.ping.good-max", 80);
        int midMax  = plugin.getConfig().getInt("hud.ping.mid-max", 150);
        String good = plugin.getConfig().getString("hud.ping.good-color", "&a");
        String mid  = plugin.getConfig().getString("hud.ping.mid-color", "&6");
        String bad  = plugin.getConfig().getString("hud.ping.bad-color", "&c");
        String colour = ping <= goodMax ? good : (ping <= midMax ? mid : bad);
        return template
                .replace("{color}", colour)
                .replace("{ping}", Integer.toString(ping));
    }
}
