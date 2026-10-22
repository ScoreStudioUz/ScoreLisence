package com.scorepvp.storage;

import com.scorepvp.model.Friendship;
import com.scorepvp.model.PlayerSettings;
import com.scorepvp.model.PlayerStats;
import com.scorepvp.model.TopEntry;

import java.util.List;
import java.util.UUID;

/**
 * Single-purpose SQLite storage for everything that isn't a tier:
 *
 *   • player_stats     — per-player, per-mode wins/losses/kills/deaths
 *   • player_playtime  — total minutes played
 *   • player_settings  — privacy / visibility flags
 *   • friendships      — accepted + pending friendships
 *
 * Implementations: {@link SqliteMainStorage}.
 *
 * <h2>Design</h2>
 * Implementations MUST be thread-safe — every method may be called from a
 * Hikari-backed worker pool. They MUST NOT block the main thread.
 *
 * <h2>What is NOT here</h2>
 * Arenas live in YAML files ({@code data-arena/<n>.yml}), personal kits in
 * {@code data-player/<uuid>.yml}, and cosmetics in {@code cosmetics/}. These
 * are file-based because each entry is large and rarely queried.
 */
public interface MainStorage {

    // ── Stats ────────────────────────────────────────────────────────────────

    PlayerStats loadStats(UUID uuid, String mode);

    void saveStats(PlayerStats stats);

    void clearAllStats();

    void clearPlayerStats(UUID uuid);

    // ── Leaderboards (cached by PlaceholderCache) ────────────────────────────

    List<TopEntry> loadTopWins(String mode, int limit);

    List<TopEntry> loadTopElo(String mode, int limit);

    List<TopEntry> loadTopKd(String mode, int limit);

    List<TopEntry> loadTopKills(String mode, int limit);

    // ── Playtime ─────────────────────────────────────────────────────────────

    long loadPlaytime(UUID uuid);

    void savePlaytime(UUID uuid, long minutes);

    // ── Settings ─────────────────────────────────────────────────────────────

    PlayerSettings loadSettings(UUID uuid);

    void saveSettings(PlayerSettings settings);

    // ── Friends ──────────────────────────────────────────────────────────────

    List<Friendship> loadFriends(UUID uuid);

    void saveFriendship(Friendship friendship);

    void deleteFriendship(UUID a, UUID b);

    // ── Silver currency ────────────────────────────────────────────────────────

    int getSilver(UUID uuid);

    void setSilver(UUID uuid, int amount);

    // ── Badges (owned nishonlar) ─────────────────────────────────────────────────

    java.util.Set<String> loadBadges(UUID uuid);

    void addBadge(UUID uuid, String badgeId);

    // ── Hats (owned cosmetic hats) ───────────────────────────────────────────────

    java.util.Set<String> loadHats(UUID uuid);

    void addHat(UUID uuid, String hatId);

    /** Closes underlying connection pool. Safe to call multiple times. */
    void close();
}
