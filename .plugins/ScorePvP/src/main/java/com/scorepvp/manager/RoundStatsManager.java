package com.scorepvp.manager;

import com.scorepvp.ScorePvP;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory per-match, per-player round statistics tracker.
 *
 * Keyed by matchId + playerUuid. Reset automatically when a match starts/ends.
 * NOT persisted — discarded once the match is removed.
 *
 * Tracks per-player:
 *   hits, misses, total damage dealt, current combo, best combo, average reach.
 * Also keeps per-round snapshots so we can show "average across all rounds"
 * in the final duel-end summary.
 */
public class RoundStatsManager {

    private final ScorePvP plugin;
    /** matchId -> uuid -> live stats for the current round */
    private final Map<UUID, Map<UUID, PlayerRoundStats>> active = new ConcurrentHashMap<>();
    /** matchId -> uuid -> history of finished rounds */
    private final Map<UUID, Map<UUID, java.util.List<PlayerRoundStats>>> history = new ConcurrentHashMap<>();
    /** uuid -> ms timestamp of last damage (used to fade actionbar) */
    private final Map<UUID, Long> lastHitAt = new ConcurrentHashMap<>();
    /** uuid -> last hit info (damage, distance) for actionbar */
    private final Map<UUID, LastHit> lastHit = new ConcurrentHashMap<>();

    public RoundStatsManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    public PlayerRoundStats get(UUID matchId, UUID playerId) {
        return active
                .computeIfAbsent(matchId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(playerId, k -> new PlayerRoundStats());
    }

    public PlayerRoundStats getOrNull(UUID matchId, UUID playerId) {
        Map<UUID, PlayerRoundStats> m = active.get(matchId);
        return m == null ? null : m.get(playerId);
    }

    /** Returns the accuracy for a player based on their CURRENT round stats. */
    public double currentAccuracy(UUID matchId, UUID playerId) {
        PlayerRoundStats s = getOrNull(matchId, playerId);
        if (s == null) return 0d;
        return s.accuracy();
    }

    /** Returns averaged accuracy across all finished rounds + current round. */
    public double averageAccuracy(UUID matchId, UUID playerId) {
        java.util.List<PlayerRoundStats> rounds = collectAll(matchId, playerId);
        if (rounds.isEmpty()) return 0d;
        double sum = 0d;
        int count = 0;
        for (PlayerRoundStats s : rounds) {
            int total = s.hits + s.misses;
            if (total == 0) continue;
            sum += s.accuracy();
            count++;
        }
        return count == 0 ? 0d : sum / count;
    }

    /** Returns merged total stats for the final duel summary (sum across rounds). */
    public PlayerRoundStats totals(UUID matchId, UUID playerId) {
        java.util.List<PlayerRoundStats> rounds = collectAll(matchId, playerId);
        PlayerRoundStats agg = new PlayerRoundStats();
        double reachSum = 0d;
        int reachSamples = 0;
        for (PlayerRoundStats s : rounds) {
            agg.hits   += s.hits;
            agg.misses += s.misses;
            agg.totalDamage += s.totalDamage;
            agg.bestCombo = Math.max(agg.bestCombo, s.bestCombo);
            if (s.reachSamples > 0) {
                reachSum += s.reachSum;
                reachSamples += s.reachSamples;
            }
        }
        agg.reachSum = reachSum;
        agg.reachSamples = reachSamples;
        return agg;
    }

    private java.util.List<PlayerRoundStats> collectAll(UUID matchId, UUID playerId) {
        java.util.List<PlayerRoundStats> rounds = new java.util.ArrayList<>();
        Map<UUID, java.util.List<PlayerRoundStats>> h = history.get(matchId);
        if (h != null && h.get(playerId) != null) rounds.addAll(h.get(playerId));
        PlayerRoundStats cur = getOrNull(matchId, playerId);
        if (cur != null && (cur.hits + cur.misses + cur.totalDamage > 0)) rounds.add(cur);
        return rounds;
    }

    public void registerHit(UUID matchId, UUID attackerId, UUID victimId, double damage, double distance) {
        PlayerRoundStats s = get(matchId, attackerId);
        s.hits++;
        s.totalDamage += damage;
        s.currentCombo++;
        if (s.currentCombo > s.bestCombo) s.bestCombo = s.currentCombo;
        s.reachSum += distance;
        s.reachSamples++;
        lastHitAt.put(attackerId, System.currentTimeMillis());
        lastHit.put(attackerId, new LastHit(damage, distance));
    }

    public void registerMiss(UUID matchId, UUID attackerId) {
        PlayerRoundStats s = get(matchId, attackerId);
        s.misses++;
        s.currentCombo = 0;
    }

    public LastHit lastHit(UUID playerId) {
        return lastHit.get(playerId);
    }

    public long lastHitAt(UUID playerId) {
        Long v = lastHitAt.get(playerId);
        return v == null ? 0L : v;
    }

    /** Snapshot the current round into history and clear the live slot. */
    public void rolloverRound(UUID matchId) {
        Map<UUID, PlayerRoundStats> live = active.remove(matchId);
        if (live == null) return;
        Map<UUID, java.util.List<PlayerRoundStats>> hist =
                history.computeIfAbsent(matchId, k -> new ConcurrentHashMap<>());
        for (Map.Entry<UUID, PlayerRoundStats> e : live.entrySet()) {
            hist.computeIfAbsent(e.getKey(), k -> new java.util.ArrayList<>()).add(e.getValue());
        }
    }

    /** Wipe everything for a finished match. */
    public void clearMatch(UUID matchId) {
        active.remove(matchId);
        history.remove(matchId);
    }

    public void clearPlayer(UUID playerId) {
        lastHit.remove(playerId);
        lastHitAt.remove(playerId);
    }

    // ── Inner types ──────────────────────────────────────────────────────────

    public static final class PlayerRoundStats {
        public int hits;
        public int misses;
        public double totalDamage;
        public int currentCombo;
        public int bestCombo;
        public double reachSum;
        public int reachSamples;

        public double accuracy() {
            int total = hits + misses;
            if (total == 0) return 0d;
            return (hits * 100d) / total;
        }

        public double avgReach() {
            if (reachSamples == 0) return 0d;
            return reachSum / reachSamples;
        }
    }

    public static final class LastHit {
        public final double damage;
        public final double distance;

        public LastHit(double damage, double distance) {
            this.damage = damage;
            this.distance = distance;
        }
    }
}
