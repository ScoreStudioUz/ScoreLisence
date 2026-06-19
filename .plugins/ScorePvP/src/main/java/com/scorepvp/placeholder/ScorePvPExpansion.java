package com.scorepvp.placeholder;

import com.scorepvp.ScorePvP;
import com.scorepvp.manager.PlaceholderCache;
import com.scorepvp.model.PlayerStats;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * PlaceholderAPI expansion for ScorePvP.
 *
 * Identifier: scorepvp
 *
 * Player placeholders:
 *   %scorepvp_tier_<mode>%           e.g. [LT3]
 *   %scorepvp_tier_<mode>_raw%       e.g. LT3
 *   %scorepvp_wins_<mode>%
 *   %scorepvp_losses_<mode>%
 *   %scorepvp_kills_<mode>%
 *   %scorepvp_deaths_<mode>%
 *   %scorepvp_kd_<mode>%
 *   %scorepvp_winrate_<mode>%
 *
 * Overall placeholders (aggregate across ALL modes):
 *   %scorepvp_wins_total%
 *   %scorepvp_losses_total%
 *   %scorepvp_kills_total%
 *   %scorepvp_deaths_total%
 *   %scorepvp_kd_total%
 *   %scorepvp_winrate_total%
 *   %scorepvp_highest_tier%            e.g. [LT3]
 *   %scorepvp_highest_tier_raw%        e.g. LT3
 *
 *   %scorepvp_playtime%
 *   %scorepvp_playtime_hours%
 *   %scorepvp_status%
 *   %scorepvp_current_mode%
 *   %scorepvp_party_size%
 *   %scorepvp_party_leader%
 *   %scorepvp_friends_count%
 *
 * Top placeholders (global):
 *   %scorepvp_<mode>_top_wins_<n>%
 *   %scorepvp_<mode>_top_wins_<n>_value%
 *   %scorepvp_<mode>_top_kd_<n>%
 *   %scorepvp_<mode>_top_kd_<n>_value%
 *   %scorepvp_<mode>_top_kills_<n>%
 *   %scorepvp_<mode>_top_kills_<n>_value%
 */
public class ScorePvPExpansion extends PlaceholderExpansion {

    private final ScorePvP plugin;
    private final String unknown;

    public ScorePvPExpansion(ScorePvP plugin) {
        this.plugin  = plugin;
        this.unknown = plugin.getConfig().getString("placeholders.placeholder-unknown-value", "—");
    }

    @Override public @NotNull String getIdentifier() { return "scorepvp"; }
    @Override public @NotNull String getAuthor()     { return "ScorePvP"; }
    @Override public @NotNull String getVersion()    { return plugin.getDescription().getVersion(); }
    @Override public boolean persist()               { return true; }
    @Override public boolean canRegister()           { return true; }

    @Override
    public @Nullable String onPlaceholderRequest(Player player, @NotNull String params) {
        if (player == null) return unknown;

        // ── Top leaderboard (%scorepvp_<mode>_top_<metric>_<n>[_value]%) ────────
        String[] parts = params.split("_");
        if (parts.length >= 4 && "top".equals(parts[1])) {
            return resolveTop(parts);
        }

        // ── Overall (aggregate across all modes) ─────────────────────────────────
        switch (params) {
            case "highest_tier"     -> { return resolveHighestTier(player, false); }
            case "highest_tier_raw" -> { return resolveHighestTier(player, true);  }
            case "wins_total"       -> { return String.valueOf(totalStat(player, "wins"));   }
            case "losses_total"     -> { return String.valueOf(totalStat(player, "losses")); }
            case "kills_total"      -> { return String.valueOf(totalStat(player, "kills"));  }
            case "deaths_total"     -> { return String.valueOf(totalStat(player, "deaths")); }
            case "kd_total"         -> { return resolveKdTotal(player);      }
            case "winrate_total"    -> { return resolveWinrateTotal(player); }
            default -> { /* fall through to per-mode + simple placeholders */ }
        }

        // ── Player-specific ──────────────────────────────────────────────────────
        // _raw must be checked before the generic tier_ prefix, otherwise
        // tier_<mode>_raw would be swallowed and resolved as a colored tier.
        if (params.startsWith("tier_") && params.endsWith("_raw")) {
            String mode = params.substring(5, params.length() - 4);
            return resolveTier(player, mode, true);
        }
        if (params.startsWith("tier_"))          return resolveTier(player,    params.substring(5), false);
        if (params.startsWith("wins_"))          return resolveStat(player,    params.substring(5), "wins");
        if (params.startsWith("losses_"))        return resolveStat(player,    params.substring(7), "losses");
        if (params.startsWith("kills_"))         return resolveStat(player,    params.substring(6), "kills");
        if (params.startsWith("deaths_"))        return resolveStat(player,    params.substring(7), "deaths");
        if (params.startsWith("kd_"))            return resolveKd(player,      params.substring(3));
        if (params.startsWith("winrate_"))       return resolveWinrate(player,  params.substring(8));

        return switch (params) {
            case "playtime"       -> resolvePlaytime(player, false);
            case "playtime_hours" -> resolvePlaytime(player, true);
            case "status"         -> resolveStatus(player);
            case "current_mode"   -> resolveCurrentMode(player);
            case "party_size"     -> resolvePartySize(player);
            case "party_leader"   -> resolvePartyLeader(player);
            case "friends_count"  -> String.valueOf(plugin.getFriendsManager().getFriendCount(player.getUniqueId()));
            default               -> unknown;
        };
    }

    // ── Resolvers ─────────────────────────────────────────────────────────────

    private String resolveTop(String[] parts) {
        // parts[0]=mode, parts[1]="top", parts[2]=metric, parts[3]=n, parts[4]=optional"value"
        try {
            String mode    = parts[0];
            String metric  = parts[2].toUpperCase();
            int    n       = Integer.parseInt(parts[3]);
            boolean isValue = parts.length >= 5 && "value".equals(parts[4]);

            PlaceholderCache.Metric m = switch (metric) {
                case "WINS"  -> PlaceholderCache.Metric.WINS;
                case "KD"    -> PlaceholderCache.Metric.KD;
                case "KILLS" -> PlaceholderCache.Metric.KILLS;
                default      -> null;
            };
            if (m == null) return unknown;

            return isValue
                    ? plugin.getPlaceholderCache().getTopValue(mode, m, n)
                    : plugin.getPlaceholderCache().getTopName(mode, m, n);
        } catch (Exception e) { return unknown; }
    }

    private String resolveTier(Player p, String mode, boolean raw) {
        String tier = plugin.getTierManager().getTier(p.getUniqueId(), mode);
        if (tier == null) return plugin.getConfigManager().rawStr("profile.unranked");
        if (raw) return tier;
        return plugin.getConfigManager().getTierDisplay(tier, mode);
    }

    private String resolveStat(Player p, String mode, String field) {
        PlayerStats s = plugin.getStatsManager().get(p.getUniqueId(), mode);
        return switch (field) {
            case "wins"   -> String.valueOf(s.getWins());
            case "losses" -> String.valueOf(s.getLosses());
            case "kills"  -> String.valueOf(s.getKills());
            case "deaths" -> String.valueOf(s.getDeaths());
            default       -> unknown;
        };
    }

    private String resolveKd(Player p, String mode) {
        PlayerStats s = plugin.getStatsManager().get(p.getUniqueId(), mode);
        return String.format("%.2f", s.getKdRatio());
    }

    private String resolveWinrate(Player p, String mode) {
        PlayerStats s = plugin.getStatsManager().get(p.getUniqueId(), mode);
        int total = s.getWins() + s.getLosses();
        if (total == 0) return "0%";
        return (int) Math.round((double) s.getWins() / total * 100) + "%";
    }

    // ── Overall aggregates ──────────────────────────────────────────────────────

    private long totalStat(Player p, String field) {
        long sum = 0;
        for (var mode : plugin.getConfigManager().getModes()) {
            PlayerStats s = plugin.getStatsManager().get(p.getUniqueId(), mode.getId());
            sum += switch (field) {
                case "wins"   -> s.getWins();
                case "losses" -> s.getLosses();
                case "kills"  -> s.getKills();
                case "deaths" -> s.getDeaths();
                default       -> 0;
            };
        }
        return sum;
    }

    private String resolveKdTotal(Player p) {
        long kills  = totalStat(p, "kills");
        long deaths = totalStat(p, "deaths");
        if (deaths == 0) return String.format("%.2f", (double) kills);
        return String.format("%.2f", (double) kills / deaths);
    }

    private String resolveWinrateTotal(Player p) {
        long wins   = totalStat(p, "wins");
        long losses = totalStat(p, "losses");
        long total  = wins + losses;
        if (total == 0) return "0%";
        return (int) Math.round((double) wins / total * 100) + "%";
    }

    /** Highest tier the player holds across all modes, ranked by configured tier order. */
    private String resolveHighestTier(Player p, boolean raw) {
        var tiers = plugin.getTierManager().getAllTiers(p.getUniqueId());
        var order = plugin.getConfigManager().getTierOrder();
        String best = null;
        String bestMode = null;
        int bestIdx = -1;
        for (var e : tiers.entrySet()) {
            String tier = e.getValue();
            if (tier == null || tier.isBlank()) continue;
            int idx = order.indexOf(tier);
            if (idx > bestIdx) { bestIdx = idx; best = tier; bestMode = e.getKey(); }
        }
        if (best == null) return plugin.getConfigManager().rawStr("profile.unranked");
        if (raw) return best;
        // Color the highest tier using the colors of the mode it came from, so
        // the lobby/tab badge matches that mode's gradient.
        return plugin.getConfigManager().getTierDisplay(best, bestMode);
    }

    private String resolvePlaytime(Player p, boolean hoursOnly) {
        long total = plugin.getStatsManager().getPlaytimeMinutes(p.getUniqueId());
        long hours = total / 60;
        long mins  = total % 60;
        if (hoursOnly) return String.valueOf(hours);
        return plugin.getConfigManager().rawStr("placeholders.playtime-format")
                .replace("{hours}", String.valueOf(hours))
                .replace("{minutes}", String.valueOf(mins));
    }

    private String resolveStatus(Player p) {
        var uuid = p.getUniqueId();
        if (plugin.getMatchManager().isFighter(uuid))   return "in-match";
        if (plugin.getMatchManager().isInMatch(uuid))   return "spectating";
        if (plugin.getDuelManager().isInQueue(uuid))    return "in-queue";
        return "online";
    }

    private String resolveCurrentMode(Player p) {
        var match = plugin.getMatchManager().getMatch(p.getUniqueId());
        return match != null ? match.getMode().getDisplayName() : unknown;
    }

    private String resolvePartySize(Player p) {
        var party = plugin.getPartyManager().getParty(p.getUniqueId());
        return party != null ? String.valueOf(party.size()) : "0";
    }

    private String resolvePartyLeader(Player p) {
        var party = plugin.getPartyManager().getParty(p.getUniqueId());
        if (party == null) return unknown;
        var leader = org.bukkit.Bukkit.getPlayer(party.getLeader());
        return leader != null ? leader.getName() : unknown;
    }
}
