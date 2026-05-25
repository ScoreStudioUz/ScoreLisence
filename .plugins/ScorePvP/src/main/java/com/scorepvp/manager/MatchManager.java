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

    public MatchManager(ScorePvP plugin) { this.plugin = plugin; }

    // ── Factory ───────────────────────────────────────────────────────────────

    public Match createMatch(Arena arena, PvPMode mode, int rounds, Player p1, Player p2) {
        // Validate arena is ready
        if (!arena.isFullyConfigured() || !arena.isFree()) return null;
        arena.setBusy();
        MatchPlayer mp1 = new MatchPlayer(p1.getUniqueId(), p1.getName(), MatchPlayer.Role.FIGHTER);
        MatchPlayer mp2 = new MatchPlayer(p2.getUniqueId(), p2.getName(), MatchPlayer.Role.FIGHTER);
        Match match = new Match(arena, mode, rounds, mp1, mp2);
        byPlayer.put(p1.getUniqueId(), match);
        byPlayer.put(p2.getUniqueId(), match);
        byId.put(match.getMatchId(), match);
        startRound(match);
        return match;
    }

    public Match createFfaMatch(Arena arena, PvPMode mode, java.util.List<Player> players) {
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

        // Clean up dropped items within arena bounds
        cleanupArenaDrops(match.getArena());
        plugin.getNameTagProvider().clearMatchNametags(match);

        // Roll back any blocks broken/placed during the final round of a
        // vanilla match. Tracker.restore() applies the diff in one tick
        // and removes the entry, so no follow-up cleanup is needed.
        if (match.getArena() != null && match.getArena().isVanilla()) {
            com.scorepvp.manager.ArenaBlockTracker tracker = plugin.getArenaBlockTracker();
            if (tracker != null) tracker.restore(match);
        }

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

        Location spawn = plugin.getConfigManager().getTpSpawnLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID uuid : match.getAllUuids()) {
                byPlayer.remove(uuid);
                Player p = player(uuid);
                if (p == null) continue;
                clearInventory(p);
                resetFighter(p);
                if (spawn != null) p.teleport(spawn);
                boolean inParty = plugin.getPartyManager().isInParty(uuid);
                if (inParty) plugin.getHotbarManager().updatePartySlot(p, true);
                else         plugin.getHotbarManager().restoreHotbar(p);
                p.updateInventory();
            }
            byId.remove(match.getMatchId());
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
        safeTP(p1, match.getArena().getPos1());
        safeTP(p2, match.getArena().getPos2());

        match.getSpectators().values().forEach(sp -> {
            Player spec = player(sp.getUuid());
            if (spec != null) { spec.setGameMode(GameMode.SPECTATOR); safeTP(spec, match.getArena().getCentre()); }
        });

        // Setup nametags + health IMMEDIATELY (before countdown ends)
        plugin.getNameTagProvider().setupMatchNametags(match);

        int countdown = plugin.getConfig().getInt("match.countdown", 5);
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

        showTitle(player(dead.getUuid()),
                plugin.getConfigManager().rawStr("match.round-loss"),
                "&7" + match.getScoreFor(dead.getUuid()));
        showTitle(player(killer.getUuid()),
                plugin.getConfigManager().rawStr("match.round-win"),
                "&7" + match.getScoreFor(killer.getUuid()));

        // Send round-finished block message — per-player rendering so we can
        // gate the accuracy/stats lines on permissions and embed a hoverable
        // [посмотреть] component with each viewer's own stats tooltip.
        String killerHp = killerPlayer != null ? String.format("%.2f", killerPlayer.getHealth()) : "?";
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

        // Snapshot the round into history so totals() can average all rounds.
        plugin.getRoundStatsManager().rolloverRound(match.getMatchId());

        // Play ALL rounds — only end when currentRound > totalRounds
        match.nextRound();
        if (match.isFinished()) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> endMatch(match, false),
                    plugin.getConfig().getLong("match.finish-delay-ticks", 60));
        } else {
            Player deadPlayer = player(dead.getUuid());
            if (deadPlayer != null) deadPlayer.setGameMode(GameMode.SPECTATOR);

            if (match.getArena().isVanilla()) {
                plugin.getArenaRestoreManager().startRestore(match, () -> startRound(match));
            } else {
                Bukkit.getScheduler().runTaskLater(plugin, () -> startRound(match),
                        plugin.getConfig().getLong("match.round-end-delay-ticks", 60));
            }
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

        // Clean up dropped items within arena bounds
        cleanupArenaDrops(match.getArena());
        plugin.getNameTagProvider().clearMatchNametags(match);

        // Roll back any blocks broken/placed during the final round of a
        // vanilla match. Tracker.restore() applies the diff in one tick
        // and removes the entry, so no follow-up cleanup is needed.
        if (match.getArena() != null && match.getArena().isVanilla()) {
            com.scorepvp.manager.ArenaBlockTracker tracker = plugin.getArenaBlockTracker();
            if (tracker != null) tracker.restore(match);
        }

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

        Location spawn = plugin.getConfigManager().getTpSpawnLocation();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            for (UUID uuid : match.getAllUuids()) {
                byPlayer.remove(uuid);
                Player p = player(uuid);
                if (p == null) continue;
                clearInventory(p);
                resetFighter(p);
                if (spawn != null) p.teleport(spawn);
                boolean inParty = plugin.getPartyManager().isInParty(uuid);
                if (inParty) plugin.getHotbarManager().updatePartySlot(p, true);
                else         plugin.getHotbarManager().restoreHotbar(p);
                p.updateInventory();
            }
            byId.remove(match.getMatchId());
            plugin.getRoundStatsManager().clearMatch(match.getMatchId());
        }, 60L);
    }

    // ── Spectator ─────────────────────────────────────────────────────────────

    public boolean addSpectator(Player spec, Match match) {
        if (byPlayer.containsKey(spec.getUniqueId())) return false;
        MatchPlayer mp = new MatchPlayer(spec.getUniqueId(), spec.getName(), MatchPlayer.Role.SPECTATOR);
        match.addSpectator(mp);
        byPlayer.put(spec.getUniqueId(), match);
        spec.setGameMode(GameMode.SPECTATOR);
        safeTP(spec, match.getArena().getCentre());
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
        spec.setGameMode(GameMode.SURVIVAL);
        Location spawn = plugin.getConfigManager().getTpSpawnLocation();
        if (spawn != null) spec.teleport(spawn);
        plugin.getHotbarManager().restoreHotbar(spec);
    }

    // ── Forfeit / Disconnect ──────────────────────────────────────────────────

    public void handleDisconnect(UUID uuid) {
        Match match = byPlayer.get(uuid);
        if (match == null) return;

        if (match.isFfa()) {
            MatchPlayer mp = match.getFighter(uuid);
            if (mp != null && !mp.isDead()) {
                match.markFfaFighterDead(uuid);
                broadcastToMatch(match, plugin.getConfigManager().rawMsg("prefix")
                        + " " + plugin.getConfigManager().rawMsg("match.player-left-duel",
                        "{player}", mp.getName()));
                if (match.isFinished()) endFfaMatch(match);
            } else {
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

            // Force opponent to win all remaining rounds for stat purposes
            if (opponent != null) {
                int remaining = match.getTotalRounds() - match.getCurrentRound() + 1;
                for (int i = 0; i < remaining; i++) opponent.addWin();
            }
            if (match.getState() == Match.State.RESTORING && match.getArena().isVanilla()) {
                plugin.getArenaRestoreManager().manualRestore(match.getArena(), null);
            }
            endMatch(match, true);
        } else {
            match.removeSpectator(uuid);
            byPlayer.remove(uuid);
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public Match   getMatch(UUID uuid)   { return byPlayer.get(uuid); }
    public Match   getMatchById(UUID matchId) { return byId.get(matchId); }
    public boolean isInMatch(UUID uuid)  { return byPlayer.containsKey(uuid); }
    public java.util.Collection<Match> getAllMatches() { return byId.values(); }
    public boolean isFighter(UUID uuid) {
        Match m = byPlayer.get(uuid);
        return m != null && m.isFighter(uuid);
    }

    // ── Util ─────────────────────────────────────────────────────────────────

    private void giveKit(Player p, Match match) {
        if (p == null) return;
        plugin.getHotbarManager().removeHotbar(p);
        plugin.getKitManager().giveKitByType(p, match.getMode().getKitType());
    }

    private void resetFighter(Player p) {
        if (p == null) return;
        p.setGameMode(GameMode.SURVIVAL);
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

    /** Removes all dropped items within arena bounds after a match ends. */
    private void cleanupArenaDrops(com.scorepvp.model.Arena arena) {
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
                .filter(e -> e instanceof org.bukkit.entity.Item)
                .filter(e -> {
                    Location loc = e.getLocation();
                    int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
                    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
                })
                .forEach(org.bukkit.entity.Entity::remove);
    }
}
