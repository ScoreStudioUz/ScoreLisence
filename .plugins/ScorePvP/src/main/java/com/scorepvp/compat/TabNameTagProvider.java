package com.scorepvp.compat;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.Match;
import com.scorepvp.model.MatchPlayer;
import me.neznamy.tab.api.TabAPI;
import me.neznamy.tab.api.TabPlayer;
import me.neznamy.tab.api.nametag.NameTagManager;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.*;

/**
 * NameTag provider that uses the TAB plugin API for prefix/suffix management.
 * <p>
 * Key design principles:
 * - NEVER calls player.setScoreboard() — avoids overriding TAB's scoreboard
 * - NEVER calls player.playerListName() — lets TAB manage the tab list
 * - Health BELOW_NAME is injected into the player's EXISTING scoreboard
 * - On setup: saves original TAB prefix/suffix, then sets match-specific ones
 * - On clear: restores original TAB values, unregisters health objective
 */
public class TabNameTagProvider implements NameTagProvider {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacyAmpersand();

    private static final String HEALTH_OBJ_NAME = "scorepvp_hp";

    private final ScorePvP plugin;
    private final NameTagFormatter formatter;
    private final boolean healthEnabled;
    private final String healthSymbol;

    /** matchId → { playerUUID → [originalPrefix, originalSuffix] } */
    private final Map<UUID, Map<UUID, String[]>> savedTags = new HashMap<>();

    /** matchId → set of player UUIDs that had health objective injected */
    private final Map<UUID, Set<UUID>> healthInjected = new HashMap<>();

    public TabNameTagProvider(ScorePvP plugin, LuckPermsHook luckPerms) {
        this.plugin = plugin;
        this.formatter = new NameTagFormatter(plugin, luckPerms);
        this.healthEnabled = plugin.getConfig().getBoolean("nametag.health-below-name", true);
        this.healthSymbol = plugin.getConfig().getString("nametag.health-symbol", "&c\u2764");
    }

    @Override
    public void setupMatchNametags(Match match) {
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        if (ntm == null) return;

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

        // Inject health objective into each player's EXISTING scoreboard
        if (healthEnabled) {
            Set<UUID> injected = new HashSet<>();
            for (UUID uuid : match.getAllUuids()) {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    injectHealthObjective(player);
                    injected.add(uuid);
                }
            }
            healthInjected.put(match.getMatchId(), injected);

            // Force a Criteria.HEALTH update — without this, the BELOW_NAME
            // score reads "0" until the player takes damage. Schedule a
            // no-op setHealth() one tick later so the value lands after the
            // kit / teleport have settled.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (UUID uuid : match.getAllUuids()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p == null || !p.isOnline()) continue;
                    double h = p.getHealth();
                    double max = p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH) != null
                            ? p.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()
                            : 20.0;
                    if (h > max) h = max;
                    if (h <= 0) continue;
                    p.setHealth(h);
                }
            }, 1L);
        }
    }

    @Override
    public void clearMatchNametags(Match match) {
        NameTagManager ntm = TabAPI.getInstance().getNameTagManager();
        Map<UUID, String[]> saved = savedTags.remove(match.getMatchId());
        Set<UUID> injected = healthInjected.remove(match.getMatchId());

        for (UUID uuid : match.getAllUuids()) {
            Player player = Bukkit.getPlayer(uuid);

            // Remove health objective from player's scoreboard
            if (player != null && player.isOnline() && injected != null && injected.contains(uuid)) {
                removeHealthObjective(player);
            }

            // Restore TAB prefix/suffix
            if (ntm != null && saved != null && saved.containsKey(uuid)) {
                TabPlayer tp = TabAPI.getInstance().getPlayer(uuid);
                if (tp != null) {
                    String[] orig = saved.get(uuid);
                    ntm.setPrefix(tp, orig[0]);
                    ntm.setSuffix(tp, orig[1]);
                }
            }
        }
    }

    @Override
    public void updateHealth(Player player) {
        if (!healthEnabled) return;

        Match match = plugin.getMatchManager().getMatch(player.getUniqueId());
        if (match == null) return;

        Set<UUID> injected = healthInjected.get(match.getMatchId());
        if (injected == null || !injected.contains(player.getUniqueId())) return;

        // Re-inject if the objective was lost (e.g. scoreboard was swapped by another plugin)
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective(HEALTH_OBJ_NAME);
        if (obj == null) {
            injectHealthObjective(player);
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

            // Re-inject health objectives
            if (healthEnabled) {
                Set<UUID> injected = healthInjected.get(matchId);
                if (injected != null) {
                    for (UUID uuid : injected) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null && player.isOnline()) {
                            injectHealthObjective(player);
                        }
                    }
                }
            }
        }
    }

    // ── Health objective helpers ──────────────────────────────────────────────────

    private void injectHealthObjective(Player player) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective(HEALTH_OBJ_NAME);
        if (obj == null) {
            obj = board.registerNewObjective(
                    HEALTH_OBJ_NAME, Criteria.HEALTH,
                    LEGACY.deserialize(healthSymbol)
            );
        }
        obj.setDisplaySlot(DisplaySlot.BELOW_NAME);
    }

    private void removeHealthObjective(Player player) {
        Scoreboard board = player.getScoreboard();
        Objective obj = board.getObjective(HEALTH_OBJ_NAME);
        if (obj != null) {
            obj.unregister();
        }
    }
}
