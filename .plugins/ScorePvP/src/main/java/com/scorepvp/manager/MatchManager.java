package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MatchManager {

    private final ScorePvP plugin;

    private final Map<UUID, Match> byPlayer        = new ConcurrentHashMap<>();
    private final Map<UUID, Match> byId            = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> countdownTasks = new ConcurrentHashMap<>();

    /**
     * Players whose match just ended but who are still awaiting the deferred
     * "teleport to spawn + restore lobby hotbar" pass. They're already removed
     * from byPlayer (so the match is fully detached), but they must NOT receive
     * lobby items yet — the HotbarIntegrityTask skips anyone in this set so the
     * spawn kit is only handed out AFTER they actually arrive at spawn (Bug 5).
     */
    private final Set<UUID> pendingSpawnTp = ConcurrentHashMap.newKeySet();

    public boolean isPendingSpawnTeleport(UUID uuid) { return pendingSpawnTp.contains(uuid); }

    public MatchManager(ScorePvP plugin) { this.plugin = plugin; }

    // ── Factory ───────────────────────────────────────────────────────────────

    public Match createMatch(Arena arena, PvPMode mode, int rounds, Player p1, Player p2) {
        return createMatch(arena, mode, rounds, p1, p2, false);
    }

    public Match createMatch(Arena arena, PvPMode mode, int rounds, Player p1, Player p2, boolean withHats) {
        // Guard: never put a player into a second match. Without this a queued
        // arena-search race could overwrite byPlayer and orphan the old match,
        // which is what produced the repeated "opponent left" win spam.
        if (byPlayer.containsKey(p1.getUniqueId()) || byPlayer.containsKey(p2.getUniqueId())) {
            return null;
        }
        // Validate arena is ready
        if (!arena.isFullyConfigured() || !arena.isFree()) return null;
        arena.setBusy();
        MatchPlayer mp1 = new MatchPlayer(p1.getUniqueId(), p1.getName(), MatchPlayer.Role.FIGHTER);
        MatchPlayer mp2 = new MatchPlayer(p2.getUniqueId(), p2.getName(), MatchPlayer.Role.FIGHTER);
        Match match = new Match(arena, mode, rounds, mp1, mp2);
        match.setWithHats(withHats);
        byPlayer.put(p1.getUniqueId(), match);
        byPlayer.put(p2.getUniqueId(), match);
        byId.put(match.getMatchId(), match);
        startRound(match);
        // Duel-only sidebar scoreboard (never FFA). Conflict-free with TAB.
        plugin.getDuelScoreboardManager().show(match);
        return match;
    }

    public Match createFfaMatch(Arena arena, PvPMode mode, java.util.List<Player> players) {
        // Guard: don't start if any participant is already in a match.
        for (Player p : players) if (byPlayer.containsKey(p.getUniqueId())) return null;
        // Validate arena
        if (!arena.isFree()) return null;
        arena.setBusy();
        java.util.List<MatchPlayer> fighters = new java.util.ArrayList<>();
        for (Player p : players)
            fighters.add(new MatchPlayer(p.getUniqueId(), p.getName(), MatchPlayer.Role.FIGHTER));
        Match match = new Match(arena, mode, fighters);
        for (Player p : players) byPlayer.put(p.getUniqueId(), match);
        byId.put(match.getMatchId(), match);
        startFfaRound(match, players);
        return match;
    }

    // ── FFA Round ──────────────────────────────────────────────────────────────

    private void startFfaRound(Match match, java.util.List<Player> players) {
        match.setState(Match.State.COUNTDOWN);

        // Reset all fighters before giving kits
        for (MatchPlayer mp : match.getFfaFighters()) {
            mp.setFrozen(true);
            mp.setDead(false);
        }

        // Give kits and teleport — all fighters get kit
        for (Player p : players) {
            if (p == null || !p.isOnline()) continue;
            resetFighter(p);
            clearInventory(p);
            plugin.getHotbarManager().removeHotbar(p);
            plugin.getKitManager().giveKitByType(p, match.getMode().getKitType());
            applyStartSaturation(p, match);
            safeTP(p, match.getArena().getCentre());
        }

        // Setup nametags + health IMMEDIATELY (before countdown ends)
        plugin.getNameTagProvider().setupMatchNametags(match);

        int countdown = plugin.getConfig().getInt("match.countdown", 5);
        int[] n = {countdown};
        BukkitTask[] taskRef = {null};
        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (n[0] > 0) {
                String color = n[0] <= 2 ? "&c" : "&e";
                Title t = Title.title(comp(color + n[0]),
                        comp(plugin.getConfigManager().rawStr("match.ffa-subtitle")
                                .replace("{count}", String.valueOf(match.getAliveFfaFighters().size()))),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO));
                broadcastTitle(match, t);
                n[0]--;
            } else {
                match.setState(Match.State.ACTIVE);
                for (MatchPlayer mp : match.getFfaFighters()) mp.setFrozen(false);
                broadcastTitle(match, Title.title(comp(plugin.getConfigManager().rawStr("match.fight")), comp(""),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(500))));
                if (taskRef[0] != null) { taskRef[0].cancel(); countdownTasks.remove(match.getMatchId()); }
            }
        }, 0L, 20L);
        countdownTasks.put(match.getMatchId(), taskRef[0]);
    }

    // ── FFA Death ──────────────────────────────────────────────────────────────

    public void handleFfaDeath(Match match, MatchPlayer dead, MatchPlayer killer) {
        if (match.getState() != Match.State.ACTIVE) return;
        match.markFfaFighterDead(dead.getUuid());

        if (killer != null) plugin.getStatsManager().recordKill(killer.getUuid(), match.getMode().getId());
        plugin.getStatsManager().recordDeath(dead.getUuid(), match.getMode().getId());

        String killMsg;
        if (killer != null) {
            killMsg = plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("match.ffa-kill-by",
                    "{dead}", dead.getName(), "{killer}", killer.getName());
        } else {
            killMsg = plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("match.ffa-kill",
                    "{dead}", dead.getName());
        }
        broadcastToMatch(match, killMsg);

        // Show killer's remaining HP in FFA
        if (killer != null) {
            Player killerPlayer = player(killer.getUuid());
            if (killerPlayer != null) {
                String hpMsg = plugin.getConfigManager().rawMsg("prefix")
                        + " " + plugin.getConfigManager().rawMsg("match.hp-remaining",
                        "{player}", killer.getName(),
                        "{hp}", String.format("%.1f", killerPlayer.getHealth()));
                broadcastToMatch(match, hpMsg);
            }
        }

        // Dead player becomes spectator
        Player deadPlayer = player(dead.getUuid());
        if (deadPlayer != null) deadPlayer.setGameMode(GameMode.SPECTATOR);

        if (match.isFinished()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> endFfaMatch(match),
                    plugin.getConfig().getLong("match.finish-delay-ticks", 60));
        } else {
            broadcastToMatch(match, plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("match.players-remaining",
                    "{count}", String.valueOf(match.getAliveFfaFighters().size())));
        }
    }

    // ── End FFA Match ──────────────────────────────────────────────────────────

    private void endFfaMatch(Match match) {
        if (match.getState() == Match.State.FINISHED) return;

        BukkitTask ct = countdownTasks.remove(match.getMatchId());
        if (ct != null) ct.cancel();
        match.setState(Match.State.FINISHED);
        match.getArena().setFree();

        plugin.getNameTagProvider().clearMatchNametags(match);
        // Arena cleanup (block-restore + drop wipe + projectiles) is deferred
        // to AFTER the players are teleported back to the lobby — see the
        // runTaskLater further down. Otherwise the blocks were popping back
        // under their feet while they were still reading the victory title.

        MatchPlayer winner = match.getWinner();
        if (winner != null) plugin.getStatsManager().recordWin(winner.getUuid(), match.getMode().getId());

        if (plugin.getConfig().getBoolean("placeholders.update-on-match-end", true))
            plugin.getPlaceholderCache().refreshMode(match.getMode().getId());

        if (winner != null) {
            showTitle(player(winner.getUuid()),
                    plugin.getConfigManager().rawStr("match.victory"),
                    plugin.getConfigManager().rawStr("match.ffa-victory-subtitle"));
            // FFA victory block to winner
            sendBlockToPlayer(player(winner.getUuid()), "ffa-victory-block",
                    "{mode}", match.getMode().getDisplayName(),
                    "{total}", String.valueOf(match.getFfaFighters().size()));
            broadcastToMatch(match, plugin.getConfigManager().msg("match.ffa-winner",
                    "{player}", winner.getName()));
        }

        // Detach from both lookup maps synchronously — see endMatch for why
        // (closes the stale-FINISHED-match re-entry window that caused the
        // forfeit/auto-win spam spiral).
        java.util.List<UUID> participants = new java.util.ArrayList<>(match.getAllUuids());
        for (UUID uuid : participants) { byPlayer.remove(uuid); pendingSpawnTp.add(uuid); }
        byId.remove(match.getMatchId());

        Location spawn = plugin.getConfigManager().getTpSpawnLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID uuid : participants) {
                Player p = player(uuid);
                if (p == null) { pendingSpawnTp.remove(uuid); continue; }
                clearInventory(p);
                resetFighter(p);
                if (spawn != null) p.teleport(spawn);
                pendingSpawnTp.remove(uuid);
                boolean inParty = plugin.getPartyManager().isInParty(uuid);
                if (inParty) plugin.getHotbarManager().updatePartySlot(p, true);
                else         plugin.getHotbarManager().restoreHotbar(p);
                p.updateInventory();
            }
            // Players are now on spawn — wipe drops / arrows / projectiles AND
            // restore arena blocks. See endMatch for the rationale.
            cleanupArenaDrops(match.getArena());
            if (match.getArena().isVanilla()) {
                plugin.getArenaRestoreManager().startRestore(match, null);
            }
            plugin.getRoundStatsManager().clearMatch(match.getMatchId());
        }, 60L);
    }

    // ── Convert fighter to spectator (FFA /leave) ──────────────────────────────

    public void convertToSpectator(Player player, Match match) {
        if (!match.isFfa()) return;
        MatchPlayer mp = match.getFighter(player.getUniqueId());
        if (mp == null || mp.isDead()) return;
        match.markFfaFighterDead(mp.getUuid());
        player.setGameMode(GameMode.SPECTATOR);
        safeTP(player, match.getArena().getCentre());
        clearInventory(player);
        broadcastToMatch(match, plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("match.player-left",
                "{player}", mp.getName()));
        if (match.isFinished()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> endFfaMatch(match),
                    plugin.getConfig().getLong("match.finish-delay-ticks", 60));
        }
    }

    // ── Round lifecycle ───────────────────────────────────────────────────────

    void startRound(Match match) {
        match.setState(Match.State.COUNTDOWN);
        MatchPlayer mp1 = match.getFighter1();
        MatchPlayer mp2 = match.getFighter2();
        mp1.setFrozen(true);  mp2.setFrozen(true);
        mp1.setDead(false);   mp2.setDead(false);

        Player p1 = player(mp1.getUuid());
        Player p2 = player(mp2.getUuid());

        // Send duel-start block on first round only
        if (match.getCurrentRound() == 1) {
            String modeName = match.getMode().getDisplayName();
            String rounds = String.valueOf(match.getTotalRounds());
            sendBlockToPlayer(p1, "duel-start-block",
                    "{opponent}", mp2.getName(), "{mode}", modeName, "{rounds}", rounds);
            sendBlockToPlayer(p2, "duel-start-block",
                    "{opponent}", mp1.getName(), "{mode}", modeName, "{rounds}", rounds);
        }

        resetFighter(p1); resetFighter(p2);
        giveKit(p1, match); giveKit(p2, match);
        applyStartSaturation(p1, match); applyStartSaturation(p2, match);
        safeTP(p1, match.getArena().getPos1());
        safeTP(p2, match.getArena().getPos2());

        match.getSpectators().values().forEach(sp -> {
            Player spec = player(sp.getUuid());
            if (spec != null) { spec.setGameMode(GameMode.SPECTATOR); safeTP(spec, match.getArena().getCentre()); }
        });

        // Setup nametags + health IMMEDIATELY (before countdown ends)
        plugin.getNameTagProvider().setupMatchNametags(match);

        // Only the FIRST round has a countdown — subsequent rounds start the
        // fight instantly so there's no dead time between rounds.
        if (match.getCurrentRound() > 1) {
            match.setState(Match.State.ACTIVE);
            mp1.setFrozen(false); mp2.setFrozen(false);
            broadcastTitle(match, Title.title(comp(plugin.getConfigManager().rawStr("match.fight")), comp(""),
                    Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(500))));
            return;
        }

        int countdown = plugin.getConfig().getInt("match.countdown", 3);
        int[] n = {countdown};
        BukkitTask[] taskRef = {null};
        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (n[0] > 0) {
                String color = n[0] <= 2 ? "&c" : "&e";
                Title t = Title.title(comp(color + n[0]),
                        comp(plugin.getConfigManager().rawStr("match.round-subtitle")
                                .replace("{current}", String.valueOf(match.getCurrentRound()))
                                .replace("{total}", String.valueOf(match.getTotalRounds()))),
                        Title.Times.times(Duration.ZERO, Duration.ofMillis(1100), Duration.ZERO));
                broadcastTitle(match, t);
                n[0]--;
            } else {
                match.setState(Match.State.ACTIVE);
                mp1.setFrozen(false); mp2.setFrozen(false);
                broadcastTitle(match, Title.title(comp(plugin.getConfigManager().rawStr("match.fight")), comp(""),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(1), Duration.ofMillis(500))));
                if (taskRef[0] != null) { taskRef[0].cancel(); countdownTasks.remove(match.getMatchId()); }
            }
        }, 0L, 20L);
        countdownTasks.put(match.getMatchId(), taskRef[0]);
    }

    // ── Death ─────────────────────────────────────────────────────────────────

    public void handleFighterDeath(Match match, MatchPlayer dead, MatchPlayer killer) {
        if (match.getState() != Match.State.ACTIVE) return;
        match.setState(Match.State.ROUND_END);

        dead.setDead(true);
        killer.addWin();

        plugin.getStatsManager().recordKill(killer.getUuid(), match.getMode().getId());
        plugin.getStatsManager().recordDeath(dead.getUuid(),  match.getMode().getId());

        String killMsg = plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("match.ffa-kill-by",
                "{dead}", dead.getName(), "{killer}", killer.getName());
        broadcastToMatch(match, killMsg);

        // Show winner's remaining HP
        Player killerPlayer = player(killer.getUuid());
        if (killerPlayer != null) {
            String hpMsg = plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("match.hp-remaining",
                    "{player}", killer.getName(),
                    "{hp}", String.format("%.1f", killerPlayer.getHealth()));
            broadcastToMatch(match, hpMsg);
        }

        // Does this kill end the whole duel (first-to-N reached)? If so we skip
        // the per-round "round finished" block entirely — endMatch sends the
        // single "duel finished" message instead, so the winner doesn't get a
        // round-win AND a duel-win banner stacked on the last round (Bug 2).
        boolean matchFinished = match.isFinished();

        showTitle(player(dead.getUuid()),
                plugin.getConfigManager().rawStr("match.round-loss"),
                "&7" + match.getScoreFor(dead.getUuid()));
        showTitle(player(killer.getUuid()),
                plugin.getConfigManager().rawStr("match.round-win"),
                "&7" + match.getScoreFor(killer.getUuid()));

        // Send round-finished block message — per-player rendering so we can
        // gate the accuracy/stats lines on permissions and embed a hoverable
        // [посмотреть] component with each viewer's own stats tooltip. Only when
        // the duel is NOT over yet (the final round shows duel-finished only).
        String killerHp = killerPlayer != null ? String.format("%.2f", killerPlayer.getHealth()) : "?";
        if (!matchFinished) {
            for (UUID uuid : match.getAllUuids()) {
                Player p = player(uuid);
                if (p == null) continue;
                // The "viewer" for the stats lines is the recipient themselves.
                com.scorepvp.util.StatsMessageBuilder.sendRoundFinished(plugin, p,
                        match.getMatchId(), uuid,
                        match.getCurrentRound(),
                        killer.getName(),
                        killerHp,
                        killer.getWins(),
                        dead.getWins());
            }
        }

        // Snapshot the round into history so totals() can average all rounds.
        plugin.getRoundStatsManager().rolloverRound(match.getMatchId());

        // Advance the round counter, then end if first-to-N reached.
        match.nextRound();
        if (matchFinished) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> endMatch(match, false),
                    plugin.getConfig().getLong("match.finish-delay-ticks", 60));
        } else {
            Player deadPlayer = player(dead.getUuid());
            if (deadPlayer != null) deadPlayer.setGameMode(GameMode.SPECTATOR);

            // Alive winner gets adventure mode between rounds so they can't
            // grief the arena (place/break blocks, open containers) during
            // the round-end celebration. resetFighter() puts them back into
            // SURVIVAL when the next round starts.
            Player aliveWinner = player(killer.getUuid());
            if (aliveWinner != null) aliveWinner.setGameMode(GameMode.ADVENTURE);

            // Restore arena AT the moment the new round is about to start
            // (after round-end-delay-ticks) so players see the round-end
            // message first, then the world snaps back to a clean state in
            // the same tick the new round spawns them in. Tracker restore is
            // 1-tick anyway — the delay is purely so the players read the
            // outcome before the blocks reset under their feet.
            long roundEndDelay = plugin.getConfig().getLong("match.round-end-delay-ticks", 60);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // Wipe loose drops / arrows / live projectiles in the arena
                // BEFORE the next round spawns the players, so leftover ender
                // pearls from round 1 can't land on the new round's fighters.
                cleanupArenaDrops(match.getArena());
                if (match.getArena().isVanilla()) {
                    plugin.getArenaRestoreManager().startRestore(match, () -> startRound(match));
                } else {
                    startRound(match);
                }
            }, roundEndDelay);
        }
    }

    // ── End match ────────────────────────────────────────────────────────────

    public void endMatch(Match match, boolean forfeit) {
        if (match.getState() == Match.State.FINISHED) return;

        BukkitTask ct = countdownTasks.remove(match.getMatchId());
        if (ct != null) ct.cancel();
        match.setState(Match.State.FINISHED);
        match.setForfeit(forfeit);
        match.getArena().setFree();

        plugin.getNameTagProvider().clearMatchNametags(match);
        plugin.getDuelScoreboardManager().hide(match);
        // Arena cleanup (block-restore + drop wipe + projectiles) is deferred
        // to AFTER the players are teleported back to the lobby — see the
        // runTaskLater further down. Otherwise the blocks were popping back
        // under their feet while they were still reading the victory title.

        MatchPlayer winner = match.getWinner();
        MatchPlayer loser  = match.getLoser();

        if (winner != null && loser != null && !forfeit) {
            plugin.getStatsManager().recordWin(winner.getUuid(),  match.getMode().getId());
            plugin.getStatsManager().recordLoss(loser.getUuid(),  match.getMode().getId());
        }

        if (plugin.getConfig().getBoolean("placeholders.update-on-match-end", true))
            plugin.getPlaceholderCache().refreshMode(match.getMode().getId());

        // Victory/defeat titles
        showTitle(winner != null ? player(winner.getUuid()) : null,
                plugin.getConfigManager().rawStr("match.victory"),
                plugin.getConfigManager().rawStr("match.victory-subtitle"));
        showTitle(loser  != null ? player(loser.getUuid())  : null,
                plugin.getConfigManager().rawStr("match.defeat"),
                plugin.getConfigManager().rawStr("match.defeat-subtitle"));

        // Final score broadcast — styled block messages
        String modeName = match.getMode().getDisplayName();
        if (forfeit) {
            if (winner != null) {
                // Forfeit block to winner
                sendBlockToPlayer(player(winner.getUuid()), "match-forfeit-block",
                        "{mode}", modeName);
                // Also broadcast simple message
                broadcastToMatch(match, plugin.getConfigManager().msg("match.forfeit-win",
                        "{player}", winner.getName()));
            }
        } else {
            if (winner != null && loser != null) {
                String ws = String.valueOf(winner.getWins());
                String ls = String.valueOf(loser.getWins());
                Player winnerPlayer = player(winner.getUuid());
                String winnerHp = winnerPlayer != null
                        ? String.format("%.2f", winnerPlayer.getHealth()) : "?";

                // Duel-finished block (per-player, with hover stats tooltip)
                for (UUID uuid : match.getAllUuids()) {
                    Player p = player(uuid);
                    if (p == null) continue;
                    com.scorepvp.util.StatsMessageBuilder.sendDuelFinished(plugin, p,
                            match.getMatchId(), uuid,
                            winner.getName(),
                            winnerHp,
                            winner.getWins(),
                            loser.getWins());
                }
            } else if (winner == null && match.getFighter1() != null && match.getFighter2() != null) {
                // Tie block to all
                sendBlockToMatch(match, "match-tie-block",
                        "{score1}", String.valueOf(match.getFighter1().getWins()),
                        "{score2}", String.valueOf(match.getFighter2().getWins()),
                        "{mode}", modeName);
            }
        }

        // Detach the match from BOTH lookup maps SYNCHRONOUSLY, right now —
        // before the deferred teleport runs. Previously byPlayer/byId removal
        // lived inside the 60-tick runTaskLater, so for 3 seconds after a match
        // finished the players were still mapped to a FINISHED match. A quit /
        // /leave / watchdog pass in that window re-entered handleDisconnect or
        // endMatch on the stale match, re-broadcasting the forfeit and stacking
        // another teleport task — the "auto-win spam that multiplies until
        // restart" bug. Detaching here closes that window entirely. Player
        // cleanup (inventory, teleport, arena restore) stays deferred so the
        // geometry doesn't pop under their feet during the victory title.
        java.util.List<UUID> participants = new java.util.ArrayList<>(match.getAllUuids());
        for (UUID uuid : participants) { byPlayer.remove(uuid); pendingSpawnTp.add(uuid); }
        byId.remove(match.getMatchId());

        Location spawn = plugin.getConfigManager().getTpSpawnLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID uuid : participants) {
                Player p = player(uuid);
                if (p == null) { pendingSpawnTp.remove(uuid); continue; }
                clearInventory(p);
                resetFighter(p);
                if (spawn != null) p.teleport(spawn);
                // Only NOW that the player is at spawn do we hand back the lobby
                // hotbar — clearing the pending guard first so the items land
                // on a spawn-side player, never mid-arena (Bug 5).
                pendingSpawnTp.remove(uuid);
                boolean inParty = plugin.getPartyManager().isInParty(uuid);
                if (inParty) plugin.getHotbarManager().updatePartySlot(p, true);
                else         plugin.getHotbarManager().restoreHotbar(p);
                p.updateInventory();
            }
            // Players are now on spawn — wipe drops / arrows / projectiles AND
            // restore arena blocks. Doing it AFTER the teleport means players
            // don't see geometry pop under their feet during the victory title.
            cleanupArenaDrops(match.getArena());
            if (match.getArena().isVanilla()) {
                plugin.getArenaRestoreManager().startRestore(match, null);
            }
            plugin.getRoundStatsManager().clearMatch(match.getMatchId());
        }, 60L);
    }

    // ── Admin force-draw ────────────────────────────────────────────────────────

    /**
     * Force-ends a match as a DRAW (admin /stopduel). No win/loss/kill stats are
     * recorded; both players (and any spectators) are sent back to spawn exactly
     * like a normal match end. Works for duels and FFA alike.
     */
    public void forceDraw(Match match) {
        if (match == null || match.getState() == Match.State.FINISHED) return;

        BukkitTask ct = countdownTasks.remove(match.getMatchId());
        if (ct != null) ct.cancel();
        match.setState(Match.State.FINISHED);
        match.getArena().setFree();

        plugin.getNameTagProvider().clearMatchNametags(match);
        plugin.getDuelScoreboardManager().hide(match);

        // Announce the draw to everyone in the match.
        broadcastToMatch(match, plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("match.admin-draw"));

        if (plugin.getConfig().getBoolean("placeholders.update-on-match-end", true))
            plugin.getPlaceholderCache().refreshMode(match.getMode().getId());

        // Detach synchronously (same rationale as endMatch).
        java.util.List<UUID> participants = new java.util.ArrayList<>(match.getAllUuids());
        for (UUID uuid : participants) { byPlayer.remove(uuid); pendingSpawnTp.add(uuid); }
        byId.remove(match.getMatchId());

        Location spawn = plugin.getConfigManager().getTpSpawnLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID uuid : participants) {
                Player p = player(uuid);
                if (p == null) { pendingSpawnTp.remove(uuid); continue; }
                clearInventory(p);
                resetFighter(p);
                if (spawn != null) p.teleport(spawn);
                pendingSpawnTp.remove(uuid);
                if (plugin.getPartyManager().isInParty(uuid))
                    plugin.getHotbarManager().updatePartySlot(p, true);
                else
                    plugin.getHotbarManager().restoreHotbar(p);
                p.updateInventory();
            }
            cleanupArenaDrops(match.getArena());
            if (match.getArena().isVanilla()) {
                plugin.getArenaRestoreManager().startRestore(match, null);
            }
            plugin.getRoundStatsManager().clearMatch(match.getMatchId());
        }, 60L);
    }

    // ── Reload / shutdown teardown ─────────────────────────────────────────────

    /**
     * Synchronously tears down EVERY active match — used on plugin reload and
     * onDisable. For each match we:
     *   1. restore the vanilla arena (block diff + arrows + loose drops),
     *   2. clear match nametags,
     *   3. teleport every participant (fighters + spectators) back to spawn and
     *      restore their lobby hotbar.
     *
     * Everything runs inline (no runTaskLater) because the Bukkit scheduler is
     * already dead during onDisable; deferring would silently drop the work and
     * leave arenas griefed / players trapped in spectator after a restart.
     */
    public void shutdownAllMatches() {
        java.util.List<Match> matches = new java.util.ArrayList<>(byId.values());
        Location spawn = plugin.getConfigManager().getTpSpawnLocation();

        for (Match match : matches) {
            // Stop any running countdown timer.
            BukkitTask ct = countdownTasks.remove(match.getMatchId());
            if (ct != null) ct.cancel();

            match.setState(Match.State.FINISHED);
            match.getArena().setFree();

            try { plugin.getNameTagProvider().clearMatchNametags(match); }
            catch (Throwable ignored) {}
            try { plugin.getDuelScoreboardManager().hide(match); }
            catch (Throwable ignored) {}

            // Restore the arena before players are moved — synchronous, 1-tick.
            try {
                cleanupArenaDrops(match.getArena());
                if (match.getArena().isVanilla()) {
                    plugin.getArenaRestoreManager().startRestore(match, null);
                }
            } catch (Throwable ignored) {}

            for (UUID uuid : new java.util.ArrayList<>(match.getAllUuids())) {
                byPlayer.remove(uuid);
                Player p = player(uuid);
                if (p == null) continue;
                try {
                    clearInventory(p);
                    resetFighter(p);
                    if (spawn != null) p.teleport(spawn);
                    if (plugin.getPartyManager().isInParty(uuid))
                        plugin.getHotbarManager().updatePartySlot(p, true);
                    else
                        plugin.getHotbarManager().restoreHotbar(p);
                    p.updateInventory();
                } catch (Throwable ignored) {}
            }
            byId.remove(match.getMatchId());
            try { plugin.getRoundStatsManager().clearMatch(match.getMatchId()); }
            catch (Throwable ignored) {}
        }
    }

    // ── Spectator ─────────────────────────────────────────────────────────────

    public boolean addSpectator(Player spec, Match match) {
        if (byPlayer.containsKey(spec.getUniqueId())) return false;
        MatchPlayer mp = new MatchPlayer(spec.getUniqueId(), spec.getName(), MatchPlayer.Role.SPECTATOR);
        match.addSpectator(mp);
        byPlayer.put(spec.getUniqueId(), match);

        // Strip the lobby hotbar/inventory FIRST so the spectator can't keep
        // (or use) lobby items while watching, then switch to SPECTATOR and
        // teleport into the arena.
        plugin.getHotbarManager().removeHotbar(spec);
        clearInventory(spec);
        spec.setGameMode(GameMode.SPECTATOR);
        safeTP(spec, match.getArena().getCentre());

        // Re-assert SPECTATOR a tick after the teleport: the previous code set
        // it before the TP, and something in the same/next tick (region kit
        // re-give, hotbar restore) was knocking them back into SURVIVAL — so
        // /spec dropped you into the live fight in survival until the round
        // ended. Re-applying after the TP settles makes it stick immediately.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!spec.isOnline()) return;
            if (!byPlayer.containsKey(spec.getUniqueId())) return; // left already
            if (spec.getGameMode() != GameMode.SPECTATOR) {
                spec.setGameMode(GameMode.SPECTATOR);
            }
        });

        spec.sendMessage(plugin.getConfigManager().rawMsg("prefix")
                + " " + plugin.getConfigManager().rawMsg("match.spectate-msg",
                "{player1}", match.getFighter1() != null ? match.getFighter1().getName() : "?",
                "{player2}", match.getFighter2() != null ? match.getFighter2().getName() : "?"));
        return true;
    }

    public void removeSpectator(Player spec) {
        Match match = byPlayer.get(spec.getUniqueId());
        if (match == null || match.isFighter(spec.getUniqueId())) return;
        match.removeSpectator(spec.getUniqueId());
        byPlayer.remove(spec.getUniqueId());

        // Strip the match scoreboard / TAB team off this player BEFORE we
        // reset their state — otherwise the duel-prefix ("[T1] | …") stayed
        // glued to their name in the lobby.
        try {
            plugin.getNameTagProvider().clearMatchNametagsForPlayer(match, spec);
        } catch (Throwable ignored) { /* fallback: provider may not implement it */ }
        plugin.getDuelScoreboardManager().hideFor(spec.getUniqueId());

        // Fully reset the player so nothing leaks from the match: hp, food,
        // potion effects, fire, invulnerability, walk/fly flags. Same set as
        // resetFighter so a leaving spectator can't carry a god-mode or
        // sub-speed state back into the lobby.
        resetFighter(spec);
        clearInventory(spec);

        Location spawn = plugin.getConfigManager().getTpSpawnLocation();
        if (spawn != null) spec.teleport(spawn);
        plugin.getHotbarManager().restoreHotbar(spec);
    }

    // ── Forfeit / Disconnect ──────────────────────────────────────────────────

    public void handleDisconnect(UUID uuid) {
        Match match = byPlayer.get(uuid);
        if (match == null) return;

        // A FINISHED match is already torn down (byPlayer is normally cleared
        // synchronously in endMatch, but guard anyway): never re-broadcast a
        // forfeit or re-end it. Just drop the stale mapping. This is the second
        // layer of protection against the auto-win spam spiral.
        if (match.getState() == Match.State.FINISHED) {
            byPlayer.remove(uuid);
            return;
        }

        if (match.isFfa()) {
            MatchPlayer mp = match.getFighter(uuid);
            if (mp != null && !mp.isDead()) {
                match.markFfaFighterDead(uuid);
                broadcastToMatch(match, plugin.getConfigManager().rawMsg("prefix")
                        + " " + plugin.getConfigManager().rawMsg("match.player-left-duel",
                        "{player}", mp.getName()));
                if (match.isFinished()) endFfaMatch(match);
            } else {
                // Spectator-on-quit cleanup — strip their team entry from the
                // active match scoreboard so other viewers don't see a ghost.
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) {
                    try { plugin.getNameTagProvider().clearMatchNametagsForPlayer(match, p); }
                    catch (Throwable ignored) {}
                }
                plugin.getDuelScoreboardManager().hideFor(uuid);
                match.removeSpectator(uuid);
                byPlayer.remove(uuid);
            }
            return;
        }

        MatchPlayer leaver = match.getFighter(uuid);
        if (leaver != null) {
            MatchPlayer opponent = match.getOpponent(uuid);

            broadcastToMatch(match, plugin.getConfigManager().rawMsg("prefix")
                    + " " + plugin.getConfigManager().rawMsg("match.player-left-duel",
                    "{player}", leaver.getName()));

            // First-to-N: forfeiting hands the opponent the win outright —
            // top them up to the wins target so the match is immediately finished.
            if (opponent != null) {
                int need = match.getTotalRounds() - opponent.getWins();
                for (int i = 0; i < need; i++) opponent.addWin();
            }
            if (match.getState() == Match.State.RESTORING && match.getArena().isVanilla()) {
                plugin.getArenaRestoreManager().manualRestore(match.getArena(), null);
            }
            endMatch(match, true);
        } else {
            Player p = Bukkit.getPlayer(uuid);
            if (p != null) {
                try { plugin.getNameTagProvider().clearMatchNametagsForPlayer(match, p); }
                catch (Throwable ignored) {}
            }
            plugin.getDuelScoreboardManager().hideFor(uuid);
            match.removeSpectator(uuid);
            byPlayer.remove(uuid);
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public Match   getMatch(UUID uuid)   { return byPlayer.get(uuid); }
    public Match   getMatchById(UUID matchId) { return byId.get(matchId); }
    public boolean isInMatch(UUID uuid)  { return byPlayer.containsKey(uuid); }
    public java.util.Collection<Match> getAllMatches() { return byId.values(); }

    /** True when the player is attached to a match but is NOT one of its fighters. */
    public boolean isSpectating(UUID uuid) {
        Match m = byPlayer.get(uuid);
        return m != null && !m.isFighter(uuid);
    }
    public boolean isFighter(UUID uuid) {
        Match m = byPlayer.get(uuid);
        return m != null && m.isFighter(uuid);
    }

    // ── Util ─────────────────────────────────────────────────────────────────

    private void giveKit(Player p, Match match) {
        if (p == null) return;
        plugin.getHotbarManager().removeHotbar(p);
        plugin.getKitManager().giveKitByType(p, match.getMode().getKitType());
        // Vanilla per-round reset of the ender-chest kit: drop the live PLAY box
        // so the next open rebuilds it from the saved kit contents.
        if ("vanilla".equals(match.getMode().getKitType()) && plugin.getEnderKitGUI() != null) {
            plugin.getEnderKitGUI().resetPlay(p.getUniqueId());
        }
        // Duel hats opt-in: stamp the fighter's cosmetic hat model onto their
        // kit helmet so it renders on the head (third-person / to others), not
        // just in the inventory. Only when the duel enabled hats AND the player
        // actually owns a selected hat — otherwise the plain kit helmet stays.
        if (match.isWithHats()) {
            plugin.getKitManager().stampDuelHatOnHelmet(p);
        }
    }

    /**
     * Sets the fighter's food saturation to the mode's configured start value
     * (Bug 4). resetFighter() always tops it to 20; this caps it afterwards so
     * e.g. sword fighters don't passively regen a big chunk of HP from a full
     * saturation bar. Food level stays at 20 (full) — only the heal-fuel changes.
     */
    private void applyStartSaturation(Player p, Match match) {
        if (p == null || match.getMode() == null) return;
        float sat = match.getMode().getStartSaturation();
        p.setFoodLevel(20);
        p.setSaturation(Math.max(0f, Math.min(20f, sat)));
    }

    private void resetFighter(Player p) {
        if (p == null) return;
        p.setGameMode(GameMode.SURVIVAL);
        // Inside a match every fighter must be hittable, regardless of op
        // status / external god-mode plugins (Essentials god, NPCs marked
        // invulnerable, etc). Otherwise admins were silently un-PvP-able.
        p.setInvulnerable(false);
        // After SPECTATOR → SURVIVAL transition, Bukkit sometimes leaves the
        // player with `allowFlight = true` / `flying = true` from the previous
        // tick. That manifested as "I can't move after the match" — they were
        // floating in unloaded space, no gravity, controls dead. Force-reset
        // both flags so the next tick they're a normal walking survival player.
        p.setFlying(false);
        p.setAllowFlight(false);
        p.setWalkSpeed(0.2f);
        p.setFlySpeed(0.1f);
        p.setHealth(p.getMaxHealth());
        p.setFoodLevel(20);
        p.setSaturation(20f);
        p.getActivePotionEffects().forEach(e -> p.removePotionEffect(e.getType()));
        p.setFireTicks(0);
    }

    private void clearInventory(Player p) {
        if (p == null) return;
        p.getInventory().clear();
        p.getInventory().setArmorContents(new org.bukkit.inventory.ItemStack[4]);
        p.getInventory().setItemInOffHand(null);
    }

    private Player player(UUID uuid) { return Bukkit.getPlayer(uuid); }
    private void safeTP(Player p, Location loc) { if (p != null && loc != null) p.teleport(loc); }

    private void showTitle(Player p, String title, String sub) {
        if (p == null) return;
        p.showTitle(Title.title(comp(title), comp(sub),
                Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofSeconds(1))));
    }

    private Component comp(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    private void broadcastTitle(Match match, Title title) {
        match.getAllUuids().forEach(uuid -> { Player p = player(uuid); if (p != null) p.showTitle(title); });
    }

    private void broadcastToMatch(Match match, String msg) {
        String colored = com.scorepvp.util.ColorUtil.color(msg);
        match.getAllUuids().forEach(uuid -> { Player p = player(uuid); if (p != null) p.sendMessage(colored); });
    }

    private void sendBlockToMatch(Match match, String langPath, String... placeholders) {
        java.util.List<String> lines = plugin.getConfigManager().msgLines(langPath, placeholders);
        match.getAllUuids().forEach(uuid -> {
            Player p = player(uuid);
            if (p != null) lines.forEach(p::sendMessage);
        });
    }

    private void sendBlockToPlayer(Player p, String langPath, String... placeholders) {
        if (p == null) return;
        plugin.getConfigManager().sendBlockMessage(p, langPath, placeholders);
    }

    /**
     * Removes all dropped items, arrows, and live projectiles within arena
     * bounds. Called between rounds AND at full match end.
     *
     * Why projectiles too: a player can throw an ender pearl upward at the end
     * of round 1; if it falls during round 2 they teleport mid-fight. Same for
     * stuck arrows / tridents / fireworks left behind. We wipe ANY Projectile
     * inside the box plus any AbstractArrow that already stuck into geometry
     * (those are no longer Projectile-instanceof on Paper once stuck).
     */
    public void cleanupArenaDrops(com.scorepvp.model.Arena arena) {
        Location c1 = arena.getCorner1();
        Location c2 = arena.getCorner2();
        if (c1 == null || c2 == null || c1.getWorld() == null) return;

        int minX = Math.min(c1.getBlockX(), c2.getBlockX());
        int maxX = Math.max(c1.getBlockX(), c2.getBlockX());
        int minY = Math.min(c1.getBlockY(), c2.getBlockY());
        int maxY = Math.max(c1.getBlockY(), c2.getBlockY());
        int minZ = Math.min(c1.getBlockZ(), c2.getBlockZ());
        int maxZ = Math.max(c1.getBlockZ(), c2.getBlockZ());

        c1.getWorld().getEntities().stream()
                .filter(e -> e instanceof org.bukkit.entity.Item
                        || e instanceof org.bukkit.entity.AbstractArrow
                        || e instanceof org.bukkit.entity.Projectile)
                .filter(e -> {
                    Location loc = e.getLocation();
                    int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
                    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
                })
                .forEach(org.bukkit.entity.Entity::remove);
    }
}
