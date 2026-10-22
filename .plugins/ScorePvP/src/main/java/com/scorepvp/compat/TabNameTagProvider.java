package com.scorepvp.compat;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.nametag.NameTagManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

/**
 * NameTag provider that uses the TAB plugin API for prefix management.
 * <p>
 * Layout above each fighter's head:
 *   line 1 — nickname with match prefix (delegated to TAB's NameTagManager)
 *   line 2 — BELOW_NAME, formatted by {@link NameTagFormatter#buildBelowName}
 *            into "{hp}{hp_symbol} | {ping}ms" — same string for every viewer
 *            (per-entry rendering achieved via Paper Score#numberFormat).
 * <p>
 * Why we mix TAB (prefix) and Bukkit (BELOW_NAME): TAB owns line 1 because
 * it carries our tier / team color and we don't want to fight TAB for the
 * Team packet. Line 2 is a vanilla scoreboard objective injected into each
 * player's EXISTING scoreboard, so it doesn't touch TAB's state.
 * <p>
 * Key design principles:
 * - NEVER calls player.setScoreboard() — avoids overriding TAB's scoreboard
 * - NEVER calls player.playerListName() — lets TAB manage the tab list
 * - On setup: saves original TAB prefix, then sets match-specific one; TAB
 *   suffix is forced to empty (HP/ping live on BELOW_NAME now).
 * - On clear: restores original TAB values, unregisters BELOW_NAME objective.
 */
public class TabNameTagProvider implements NameTagProvider {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private static final String HEALTH_OBJ_NAME = "scorepvp_hp";

    private final ScorePvP plugin;
    private final NameTagFormatter formatter;
    private final boolean belowNameEnabled;

    /** matchId → { playerUUID → [originalPrefix, originalSuffix] } */
    private final Map<UUID, Map<UUID, String[]>> savedTags = new HashMap<>();

    /** matchId → set of player UUIDs that had the BELOW_NAME objective injected */
    private final Map<UUID, Set<UUID>> belowInjected = new HashMap<>();

    /** matchId → periodic task that refreshes each fighter's BELOW_NAME line. */
    private final Map<UUID, BukkitTask> refreshTasks = new HashMap<>();

    /** targetUUID → last BELOW_NAME text written for that player. Skips
     *  numberFormat() writes when the rendered text hasn't changed. */
    private final Map<UUID, String> lastBelowByPlayer = new HashMap<>();

    /** playerUUID → last LOBBY suffix pushed. Skips redundant setSuffix packets. */
    private final Map<UUID, String> lastLobbySuffix = new HashMap<>();

    /** playerUUID → last LOBBY prefix pushed. Skips redundant setPrefix packets. */
    private final Map<UUID, String> lastLobbyPrefix = new HashMap<>();

    public TabNameTagProvider(ScorePvP plugin, LuckPermsHook luckPerms) {
        this.plugin = plugin;
        this.formatter = new NameTagFormatter(plugin, luckPerms);
        this.belowNameEnabled = plugin.getConfig().getBoolean("nametag.health-below-name", true);
    }

    @Override
    public void setupMatchNametags(Match match) {
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        if (ntm == null) return;

        // The match owns the suffix now (forced to ""), so forget any lobby
        // suffix cache for these players — it must re-apply fresh after the match.
        for (UUID uuid : match.getAllUuids()) { lastLobbySuffix.remove(uuid); lastLobbyPrefix.remove(uuid); }

        Map<UUID, String[]> saved = new HashMap<>();
        String modeId = match.getMode().getId();

        if (match.isFfa()) {
            for (MatchPlayer mp : match.getFfaFighters()) {
                TabPlayer tp = TabAPI.getInstance().getPlayer(mp.getUuid());
                if (tp == null) continue;

                saved.put(mp.getUuid(), new String[]{
                        ntm.getOriginalPrefix(tp),
                        ntm.getOriginalSuffix(tp)
                });

                String prefix = formatter.buildPrefix(mp.getUuid(), modeId, "", true);
                ntm.setPrefix(tp, prefix);
                // Suffix forced empty — HP+ping moved to BELOW_NAME.
                ntm.setSuffix(tp, "");
            }
        } else {
            MatchPlayer f1 = match.getFighter1();
            MatchPlayer f2 = match.getFighter2();

            if (f1 != null) {
                TabPlayer tp1 = TabAPI.getInstance().getPlayer(f1.getUuid());
                if (tp1 != null) {
                    saved.put(f1.getUuid(), new String[]{
                            ntm.getOriginalPrefix(tp1),
                            ntm.getOriginalSuffix(tp1)
                    });
                    String prefix = formatter.buildPrefix(f1.getUuid(), modeId, "&9", false);
                    ntm.setPrefix(tp1, prefix);
                    ntm.setSuffix(tp1, "");
                }
            }
            if (f2 != null) {
                TabPlayer tp2 = TabAPI.getInstance().getPlayer(f2.getUuid());
                if (tp2 != null) {
                    saved.put(f2.getUuid(), new String[]{
                            ntm.getOriginalPrefix(tp2),
                            ntm.getOriginalSuffix(tp2)
                    });
                    String prefix = formatter.buildPrefix(f2.getUuid(), modeId, "&c", false);
                    ntm.setPrefix(tp2, prefix);
                    ntm.setSuffix(tp2, "");
                }
            }
        }

        savedTags.put(match.getMatchId(), saved);

        // Inject BELOW_NAME objective into each player's EXISTING scoreboard
        if (belowNameEnabled) {
            Set<UUID> injected = new HashSet<>();
            for (UUID uuid : match.getAllUuids()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    injectBelowName(player, match);
                    injected.add(uuid);
                }
            }
            belowInjected.put(match.getMatchId(), injected);
        }

        // Re-stamp BELOW_NAME every N ticks so ping (and regenerating HP)
        // stay live for every viewer.
        UUID matchId = match.getMatchId();
        long period = Math.max(1, plugin.getConfig().getLong("nametag.ping-refresh-ticks", 20L));
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> refreshBelowName(matchId), period, period);
        refreshTasks.put(matchId, task);
    }

    /** Per-second refresh of every fighter's BELOW_NAME line on every viewer's board. */
    private void refreshBelowName(UUID matchId) {
        Match match = plugin.getMatchManager().getMatchById(matchId);
        if (match == null || match.getState() == Match.State.FINISHED) return;
        Set<UUID> injected = belowInjected.get(matchId);
        if (injected == null) return;

        for (UUID viewer : injected) {
            Player viewerPlayer = Bukkit.getPlayer(viewer);
            if (viewerPlayer == null) continue;
            Scoreboard board = viewerPlayer.getScoreboard();
            Objective obj = board.getObjective(HEALTH_OBJ_NAME);
            if (obj == null) {
                injectBelowName(viewerPlayer, match);
                obj = board.getObjective(HEALTH_OBJ_NAME);
                if (obj == null) continue;
            }
            for (UUID uuid : match.getAllUuids()) {
                Player target = Bukkit.getPlayer(uuid);
                if (target == null) continue;
                writeBelowName(obj, target);
            }
        }
    }

    /** Pushes one player's BELOW_NAME line into ONE objective (one viewer's scoreboard). */
    private void writeBelowName(Objective obj, Player target) {
        String next = formatter.buildBelowName(target);
        String prev = lastBelowByPlayer.get(target.getUniqueId());
        Score score = obj.getScore(target.getName());
        int hp = (int) Math.ceil(target.getHealth());
        if (score.getScore() != hp) score.setScore(hp);
        if (next.equals(prev)) return;
        lastBelowByPlayer.put(target.getUniqueId(), next);
        score.numberFormat(NumberFormat.fixed(LEGACY.deserialize(next)));
    }

    @Override
    public void clearMatchNametagsForPlayer(Match match, Player player) {
        UUID id = player.getUniqueId();
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        Map<UUID, String[]> saved = savedTags.get(match.getMatchId());
        Set<UUID> injected = belowInjected.get(match.getMatchId());

        // Remove the BELOW_NAME objective UNCONDITIONALLY (not gated on the
        // injected set) — otherwise a player whose injection tracking got out
        // of sync kept a stray "0"/HP line under their nick back in the lobby.
        if (player.isOnline()) removeBelowName(player);
        if (injected != null) injected.remove(id);

        if (ntm != null) {
            String[] orig = saved != null ? saved.remove(id) : null;
            TabPlayer tp = TabAPI.getInstance().getPlayer(id);
            if (tp != null) {
                // Restore the saved lobby prefix/suffix, or blank them if we
                // somehow lost the original — never leave the duel prefix glued
                // on after the player leaves the match.
                ntm.setPrefix(tp, orig != null ? orig[0] : "");
                ntm.setSuffix(tp, orig != null ? orig[1] : "");
            }
        }
        lastBelowByPlayer.remove(id);
        // Re-apply the lobby tier prefix+suffix next tick (periodic task drives it).
        lastLobbySuffix.remove(id);
        lastLobbyPrefix.remove(id);
    }

    @Override
    public void clearMatchNametags(Match match) {
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        Map<UUID, String[]> saved = savedTags.remove(match.getMatchId());
        Set<UUID> injected = belowInjected.remove(match.getMatchId());
        BukkitTask task = refreshTasks.remove(match.getMatchId());
        if (task != null) task.cancel();
        for (UUID uuid : match.getAllUuids()) lastBelowByPlayer.remove(uuid);

        for (UUID uuid : match.getAllUuids()) {
            Player player = Bukkit.getPlayer(uuid);

            // Always strip the BELOW_NAME objective if the player is online —
            // a desynced `injected` set used to leave a "0" under their nick.
            if (player != null && player.isOnline()) {
                removeBelowName(player);
            }

            if (ntm != null) {
                TabPlayer tp = TabAPI.getInstance().getPlayer(uuid);
                if (tp != null) {
                    String[] orig = saved != null ? saved.get(uuid) : null;
                    ntm.setPrefix(tp, orig != null ? orig[0] : "");
                    ntm.setSuffix(tp, orig != null ? orig[1] : "");
                }
            }
        }
        // Re-apply lobby tier prefix+suffix after the match (periodic task drives it).
        for (UUID uuid : match.getAllUuids()) { lastLobbySuffix.remove(uuid); lastLobbyPrefix.remove(uuid); }
    }

    @Override
    public void updateHealth(Player player) {
        Match match = plugin.getMatchManager().getMatch(player.getUniqueId());
        if (match == null) return;
        if (!belowNameEnabled) return;
        Set<UUID> injected = belowInjected.get(match.getMatchId());
        if (injected == null) return;

        // Push this player's HP+ping line into every viewer's scoreboard.
        for (UUID viewer : injected) {
            Player viewerPlayer = Bukkit.getPlayer(viewer);
            if (viewerPlayer == null) continue;
            Scoreboard board = viewerPlayer.getScoreboard();
            Objective obj = board.getObjective(HEALTH_OBJ_NAME);
            if (obj == null) {
                injectBelowName(viewerPlayer, match);
                obj = board.getObjective(HEALTH_OBJ_NAME);
                if (obj == null) continue;
            }
            writeBelowName(obj, player);
        }
    }

    @Override
    public void onExternalReload() {
        // TAB was reloaded — re-apply prefixes for all active matches
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        if (ntm == null) return;

        for (Map.Entry<UUID, Map<UUID, String[]>> entry : savedTags.entrySet()) {
            UUID matchId = entry.getKey();
            Match match = plugin.getMatchManager().getMatchById(matchId);
            if (match == null || match.getState() == Match.State.FINISHED) continue;

            String modeId = match.getMode().getId();

            if (match.isFfa()) {
                for (MatchPlayer mp : match.getFfaFighters()) {
                    TabPlayer tp = TabAPI.getInstance().getPlayer(mp.getUuid());
                    if (tp == null) continue;
                    String prefix = formatter.buildPrefix(mp.getUuid(), modeId, "", true);
                    ntm.setPrefix(tp, prefix);
                    ntm.setSuffix(tp, "");
                }
            } else {
                MatchPlayer f1 = match.getFighter1();
                MatchPlayer f2 = match.getFighter2();
                if (f1 != null) {
                    TabPlayer tp1 = TabAPI.getInstance().getPlayer(f1.getUuid());
                    if (tp1 != null) {
                        String prefix = formatter.buildPrefix(f1.getUuid(), modeId, "&9", false);
                        ntm.setPrefix(tp1, prefix);
                        ntm.setSuffix(tp1, "");
                    }
                }
                if (f2 != null) {
                    TabPlayer tp2 = TabAPI.getInstance().getPlayer(f2.getUuid());
                    if (tp2 != null) {
                        String prefix = formatter.buildPrefix(f2.getUuid(), modeId, "&c", false);
                        ntm.setPrefix(tp2, prefix);
                        ntm.setSuffix(tp2, "");
                    }
                }
            }

            if (belowNameEnabled) {
                Set<UUID> injected = belowInjected.get(matchId);
                if (injected != null) {
                    for (UUID uuid : injected) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null && player.isOnline()) {
                            injectBelowName(player, match);
                        }
                    }
                }
            }
        }
    }

    // ── BELOW_NAME helpers ────────────────────────────────────────────────────

    /** Registers (or refreshes) the BELOW_NAME objective on a single viewer's
     *  scoreboard and seeds every match-member's line. */
    private void injectBelowName(Player viewer, Match match) {
        Scoreboard board = viewer.getScoreboard();
        Objective obj = board.getObjective(HEALTH_OBJ_NAME);
        if (obj == null) {
            // Title is a single space — irrelevant, every score has its own
            // numberFormat that overrides the rendering.
            obj = board.registerNewObjective(
                    HEALTH_OBJ_NAME, Criteria.DUMMY,
                    net.kyori.adventure.text.Component.text(" ")
            );
        }
        obj.setDisplaySlot(DisplaySlot.BELOW_NAME);
        for (UUID uuid : match.getAllUuids()) {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) continue;
            writeBelowName(obj, p);
        }
    }

    private void removeBelowName(Player player) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective(HEALTH_OBJ_NAME);
        if (obj != null) {
            obj.unregister();
        }
    }

    @Override
    public void clearStrayState(Player player) {
        // Defensive: a player who is NOT in a match should carry neither the
        // BELOW_NAME HP objective NOR a duel match-prefix. Both can linger if an
        // opponent left during countdown / spectate and the match teardown
        // raced the prefix restore. Reset them to TAB's own configured values
        // so the lobby nametag goes back to normal WITHOUT a relog.
        if (player == null || !player.isOnline()) return;
        UUID id = player.getUniqueId();
        if (plugin.getMatchManager().isInMatch(id)) return;

        // Cheap gate: only act if a leftover match objective is actually present
        // on this player's board. Keeps the every-3s sweep nearly free for the
        // many lobby players who have no ghost.
        Objective obj = player.getScoreboard().getObjective(HEALTH_OBJ_NAME);
        if (obj == null) return;

        removeBelowName(player);
        lastBelowByPlayer.remove(id);

        // If we still hold saved lobby tags for this player from some match,
        // forget them — they're stale once the player is out of all matches.
        for (Map<UUID, String[]> saved : savedTags.values()) saved.remove(id);

        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        if (ntm == null) return;
        TabPlayer tp = TabAPI.getInstance().getPlayer(id);
        if (tp == null) return;
        // getOriginalPrefix/Suffix return TAB's own configured values (not our
        // match override), so this restores the lobby nametag cleanly.
        ntm.setPrefix(tp, ntm.getOriginalPrefix(tp));
        ntm.setSuffix(tp, ntm.getOriginalSuffix(tp));
        // Force the lobby prefix+suffix to re-apply on the next tick (we just blew
        // them away) instead of being skipped by the diff check.
        lastLobbySuffix.remove(id);
        lastLobbyPrefix.remove(id);
    }

    @Override
    public void applyLobbyNametag(Player player) {
        if (player == null || !player.isOnline()) return;
        if (!plugin.getConfig().getBoolean("nametag.lobby-enabled", true)) return;
        // Match nametags own the head while fighting/spectating — never override.
        if (plugin.getMatchManager().isInMatch(player.getUniqueId())) return;

        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        if (ntm == null) return;
        TabPlayer tp = TabAPI.getInstance().getPlayer(player.getUniqueId());
        if (tp == null) return;

        UUID id = player.getUniqueId();

        // Start from TAB's own configured prefix, never our match override: a
        // duel prefix carries a tier/nick color and would otherwise stick to the
        // nick in the lobby if teardown raced the restore.
        String origPrefix = ntm.getOriginalPrefix(tp);
        if (origPrefix == null) origPrefix = "";
        // Append the player's /cosmetics name color so the lobby nametag above
        // their head takes that color. A nametag prefix's trailing color bleeds
        // into the nick itself, which is exactly what we want here. In a match
        // the colors.yml color takes over instead (see NameTagFormatter).
        String lobbyPrefix = origPrefix + formatter.lobbyNickColor(player);
        String prevPrefix = lastLobbyPrefix.get(id);
        if (!lobbyPrefix.equals(prevPrefix)) {
            lastLobbyPrefix.put(id, lobbyPrefix);
            ntm.setPrefix(tp, lobbyPrefix);
        }

        String suffix = buildLobbySuffix(id);
        // Only push a packet when the suffix actually changed — TAB's setSuffix
        // is a network write per call.
        String prev = lastLobbySuffix.get(id);
        if (suffix.equals(prev)) return;
        lastLobbySuffix.put(id, suffix);
        ntm.setSuffix(tp, suffix);
    }

    /** "&7| HT1" (colored) or "" when the player has no tier / lobby disabled. */
    private String buildLobbySuffix(UUID uuid) {
        // TierTag is a permission-gated cosmetic: the tier only shows next to the
        // nick in spawn/Tab for players who own the cosmetic.
        org.bukkit.entity.Player pp = plugin.getServer().getPlayer(uuid);
        if (pp == null || !pp.hasPermission("duels.cosmetics.tiertag")) return "";
        String[] hi = plugin.getTierManager().getDisplayTier(uuid);
        if (hi == null) {
            String fmt = plugin.getConfig().getString("nametag.lobby-suffix-unranked", " &7| {tier}");
            if (fmt == null || fmt.isEmpty()) return "";
            return fmt.replace("{tier}", plugin.getConfigManager().rawStr("profile.unranked"));
        }
        String tierDisplay = plugin.getConfigManager().getTierDisplay(hi[0], hi[1]);
        String fmt = plugin.getConfig().getString("nametag.lobby-suffix-format", " &7| {tier}");
        return fmt.replace("{tier}", tierDisplay);
    }
}
