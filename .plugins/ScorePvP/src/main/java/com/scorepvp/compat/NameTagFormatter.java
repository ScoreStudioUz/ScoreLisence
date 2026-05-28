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
     * @deprecated Suffix больше не используется — и HP, и пинг переехали на
     * BELOW_NAME через {@link #buildBelowName(Player)}. Метод оставлен только
     * чтобы провайдеры могли явно "очистить" хвост у ника (вернуть пустую
     * строку), пока полностью не перейдут на новый API.
     */
    @Deprecated
    public String buildPingSuffix(Player p) {
        return "";
    }

    /**
     * Строит то, что игрок видит ПОД своим ником (BELOW_NAME slot).
     * Один и тот же шаблон используется и в duel-, и в FFA-режиме — каждый
     * наблюдатель видит HP+ping ТОГО игрока, над чьим телом висит строка
     * (а не свой собственный пинг). Технически это достигается через
     * {@code Score.numberFormat(FixedFormat)} — на каждое имя в objective
     * вешается готовый Component, маскирующий score-число.
     *
     * Возвращает legacy-string с &-кодами (потребитель сам гонит её через
     * {@code LegacyComponentSerializer.legacyAmpersand().deserialize(...)}).
     *
     * Плейсхолдеры:
     *   {hp}, {max_hp}, {hp_color}, {hp_symbol}, {ping}, {ping_color}.
     */
    public String buildBelowName(Player p) {
        if (p == null) return "";
        String template = plugin.getConfig().getString("nametag.below-name-format",
                "{hp_color}{hp}{hp_symbol} &7| {ping_color}{ping}ms");
        if (template == null || template.isEmpty()) return "";

        // HP
        int hp    = (int) Math.ceil(p.getHealth());
        int maxHp = (int) Math.ceil(p.getMaxHealth());
        String hpColor = hpColorFor(p);
        String hpSymbol = plugin.getConfig().getString("nametag.health-symbol", "&c❤");

        // Ping
        int ping = p.getPing();
        int pGoodMax = plugin.getConfig().getInt("hud.ping.good-max", 80);
        int pMidMax  = plugin.getConfig().getInt("hud.ping.mid-max", 150);
        String pGood = plugin.getConfig().getString("hud.ping.good-color", "&a");
        String pMid  = plugin.getConfig().getString("hud.ping.mid-color", "&6");
        String pBad  = plugin.getConfig().getString("hud.ping.bad-color", "&c");
        String pingColor = ping <= pGoodMax ? pGood : (ping <= pMidMax ? pMid : pBad);

        return template
                .replace("{hp}",         Integer.toString(hp))
                .replace("{max_hp}",     Integer.toString(maxHp))
                .replace("{hp_color}",   hpColor)
                .replace("{hp_symbol}",  hpSymbol)
                .replace("{ping}",       Integer.toString(ping))
                .replace("{ping_color}", pingColor);
    }

    /** HP-цвет по порогам hud.health.* (зелёный → жёлтый → красный). */
    private String hpColorFor(Player p) {
        double frac = p.getHealth() / Math.max(1.0, p.getMaxHealth());
        double full = plugin.getConfig().getDouble("hud.health.full-min", 0.66);
        double mid  = plugin.getConfig().getDouble("hud.health.mid-min",  0.33);
        String fullColor = plugin.getConfig().getString("hud.health.full-color", "&a");
        String midColor  = plugin.getConfig().getString("hud.health.mid-color",  "&e");
        String lowColor  = plugin.getConfig().getString("hud.health.low-color",  "&c");
        if (frac >= full) return fullColor;
        if (frac >= mid)  return midColor;
        return lowColor;
    }
}
