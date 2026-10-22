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
                "%lp_prefix%%tier_color%%tier% %team_color%| ");
        this.ffaFormat = plugin.getConfig().getString("nametag.ffa-format",
                "%lp_prefix%%tier_color%%tier% &f| ");
        this.noTierDuelFormat = plugin.getConfig().getString("nametag.no-tier-duel-format",
                "%lp_prefix%%team_color%");
        this.noTierFfaFormat = plugin.getConfig().getString("nametag.no-tier-ffa-format",
                "%lp_prefix%");
    }

    /**
     * Builds the prefix string for a player in a match.
     *
     * <p>In combat the nick is NOT tinted by a team color and the LuckPerms
     * "donat" prefix is NOT shown — the nick is plain WHITE by default. If the
     * player holds a {@code colors.yml} permission color, the nick is painted
     * that color instead (resolved into the {@code %nick_color%} token). The
     * {@code teamColorCode} argument is kept for API compatibility but ignored.
     *
     * @param uuid          player UUID
     * @param modeId        game mode id (for tier lookup)
     * @param teamColorCode legacy color code (IGNORED — nick is white / combat-color)
     * @param ffa           true if FFA match
     * @return formatted prefix string with legacy color codes
     */
    public String buildPrefix(UUID uuid, String modeId, String teamColorCode, boolean ffa) {
        // No donat prefix and no team tint in combat.
        String lpPrefix = "";
        String tier = plugin.getTierManager().getTier(uuid, modeId);
        String nickColor = combatNickColor(uuid);

        if (tier == null || tier.isBlank()) {
            String template = ffa ? noTierFfaFormat : noTierDuelFormat;
            return applyPlaceholders(template, lpPrefix, "", "", nickColor);
        }

        String tierColor = plugin.getConfigManager().getTierColorSpec(tier, modeId);
        String template = ffa ? ffaFormat : duelFormat;
        return applyPlaceholders(template, lpPrefix, tierColor, tier, nickColor);
    }

    /**
     * The nick color a player is entitled to IN COMBAT: their {@code colors.yml}
     * permission color, or WHITE (&f) if they hold none. A gradient spec is
     * collapsed to a single solid leading color because a vanilla nametag can
     * only show one color (full gradients live in the lobby via /cosmetics).
     */
    private String combatNickColor(UUID uuid) {
        Player p = plugin.getServer().getPlayer(uuid);
        if (p == null) return "&f";
        String spec = plugin.getColorsManager().colorSpecFor(p);
        if (spec == null || spec.isBlank()) return "&f";
        // Render the spec against an empty string so a gradient/hex collapses to
        // its leading legacy color sequence; a plain "&x" spec passes through.
        String rendered = com.scorepvp.util.ColorUtil.applyColorSpec(spec, "");
        return rendered.isBlank() ? "&f" : rendered;
    }

    /**
     * The nick color a player carries IN THE LOBBY: their {@code /cosmetics}
     * name-color choice, or {@code ""} (TAB's own color) when they have none.
     * Counterpart to {@link #combatNickColor} — cosmetics colors are a lobby-only
     * cosmetic, while colors.yml owns the nick during a match.
     *
     * <p>Collapsed to a single leading color because a nametag can only render
     * one color; the full gradient still shows in chat/Tab via
     * {@code %scorepvp_name%}.
     */
    public String lobbyNickColor(Player p) {
        if (p == null) return "";
        var st = plugin.getSettingsManager().get(p.getUniqueId());
        if (st == null) return "";
        var entry = plugin.getNameStyleManager().getColor(st.getNameColor());
        if (entry == null || entry.colorSpec().isBlank()) return "";
        if (!plugin.getNameStyleManager().canUse(p, entry)) return "";
        String rendered = com.scorepvp.util.ColorUtil.applyColorSpec(entry.colorSpec(), "");
        return rendered.isBlank() ? "" : rendered;
    }

    private String applyPlaceholders(String template, String lpPrefix,
                                     String tierColor, String tier, String nickColor) {
        // For a MiniMessage gradient spec the color must wrap the whole tier
        // label, not sit in front of it — so collapse the common
        // "%tier_color%%tier%" segment into a single pre-rendered gradient run.
        // Plain legacy specs fall through to the literal placeholder swap below.
        if (com.scorepvp.util.ColorUtil.isMiniMessage(tierColor) && !tier.isEmpty()) {
            String rendered = com.scorepvp.util.ColorUtil.applyColorSpec(tierColor, tier);
            template = template
                    .replace("%tier_color%%tier%", rendered)
                    .replace("%tier_color%", "")          // any stray color tokens
                    .replace("%tier%", rendered);
            // %nick_color% is the new name; %team_color% kept as an alias so old
            // configs keep working — both now carry the WHITE/combat nick color.
            return template
                    .replace("%lp_prefix%", lpPrefix)
                    .replace("%nick_color%", nickColor)
                    .replace("%team_color%", nickColor);
        }
        return template
                .replace("%lp_prefix%", lpPrefix)
                .replace("%tier_color%", tierColor)
                .replace("%tier%", tier)
                .replace("%nick_color%", nickColor)
                .replace("%team_color%", nickColor);
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
