package com.scorepvp.database;

import com.scorepvp.model.*;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface IDatabase {

    void connect() throws Exception;
    void disconnect();
    void createTables();

    // ── Player Settings ──────────────────────────────────────────────────────
    PlayerSettings loadSettings(UUID uuid);
    void saveSettings(PlayerSettings settings);

    // ── Tiers ────────────────────────────────────────────────────────────────
    Map<String, String> loadTiers(UUID uuid);
    void saveTier(UUID uuid, String mode, String tier);
    void removeTier(UUID uuid, String mode);

    // ── Kits ─────────────────────────────────────────────────────────────────
    ItemStack[] loadPersonalKit(UUID uuid);
    void savePersonalKit(UUID uuid, ItemStack[] contents);

    // ── Arenas ───────────────────────────────────────────────────────────────
    List<Arena> loadAllArenas();
    void saveArena(Arena arena);
    void deleteArena(int number);

    // ── Arena block snapshots (vanilla restore) ───────────────────────────────
    List<ArenaBlock> loadArenaSnapshot(int arenaNumber);
    void saveArenaSnapshot(int arenaNumber, List<ArenaBlock> blocks);
    void deleteArenaSnapshot(int arenaNumber);

    // ── Player Stats ─────────────────────────────────────────────────────────
    PlayerStats loadStats(UUID uuid, String mode);
    void saveStats(PlayerStats stats);

    // ── Top leaderboard (global queries) ─────────────────────────────────────
    /** Returns up to `limit` entries ordered by wins DESC. */
    List<TopEntry> loadTopWins(String mode, int limit);
    List<TopEntry> loadTopElo(String mode, int limit);
    List<TopEntry> loadTopKd(String mode, int limit);
    List<TopEntry> loadTopKills(String mode, int limit);

    // ── Clear Stats (admin commands) ──────────────────────────────────────────
    /** Deletes all player_stats and player_tiers rows. Does NOT touch arenas, kits, or settings. */
    void clearAllStats();
    /** Deletes player_stats, player_tiers, and player_playtime rows for a specific player. */
    void clearPlayerStats(UUID uuid);

    // ── Playtime ─────────────────────────────────────────────────────────────
    long loadPlaytime(UUID uuid);
    void savePlaytime(UUID uuid, long minutes);

    // ── Friends ──────────────────────────────────────────────────────────────
    List<Friendship> loadFriends(UUID uuid);
    void saveFriendship(Friendship friendship);
    void deleteFriendship(UUID a, UUID b);
}
