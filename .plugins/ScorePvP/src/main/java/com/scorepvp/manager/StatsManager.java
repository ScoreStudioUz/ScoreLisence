package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PlayerStats;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-player, per-mode statistics and playtime.
 *
 * Design:
 *  - Stats are loaded asynchronously into an in-memory cache on first access.
 *  - All writes are fire-and-forget async (no main-thread DB calls during gameplay).
 *  - get() returns from cache if present; if not, starts an async load and returns
 *    a default object so callers never block.
 */
public class StatsManager {

    private final ScorePvP plugin;

    /** Cache key: uuid + ":" + mode */
    private final Map<String, PlayerStats> cache = new ConcurrentHashMap<>();

    /** Playtime: uuid → total minutes (loaded from DB) */
    private final Map<UUID, Long> playtime  = new ConcurrentHashMap<>();
    /** Session start timestamps: uuid → System.currentTimeMillis() */
    private final Map<UUID, Long> loginTime = new ConcurrentHashMap<>();

    public StatsManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private String key(UUID uuid, String mode) { return uuid + ":" + mode; }

    /**
     * Async load — pre-populates the cache.  Use for warmup before a match starts.
     */
    public CompletableFuture<PlayerStats> loadAsync(UUID uuid, String mode) {
        String k = key(uuid, mode);
        if (cache.containsKey(k)) return CompletableFuture.completedFuture(cache.get(k));
        return CompletableFuture.supplyAsync(() -> {
            PlayerStats s = plugin.getStorage().getMain().loadStats(uuid, mode);
            cache.put(k, s);
            return s;
        });
    }

    /**
     * Returns stats from cache (never blocks).  If not cached yet, starts an
     * async load and returns a fresh default object in the meantime.
     */
    public PlayerStats get(UUID uuid, String mode) {
        String k = key(uuid, mode);
        PlayerStats cached = cache.get(k);
        if (cached != null) return cached;

        // Return a default immediately; async load will update the cache for next call
        PlayerStats def = new PlayerStats(uuid, mode);
        cache.put(k, def);
        CompletableFuture.supplyAsync(() -> plugin.getStorage().getMain().loadStats(uuid, mode))
                .thenAccept(s -> cache.put(k, s));
        return def;
    }

    public void recordWin(UUID uuid, String mode) {
        PlayerStats s = get(uuid, mode);
        s.addWin();
        org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(uuid);
        if (online != null && s.getPlayerName().isEmpty()) s.setPlayerName(online.getName());
        saveAsync(s);
    }

    public void recordLoss(UUID uuid, String mode) {
        PlayerStats s = get(uuid, mode);
        s.addLoss();
        org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(uuid);
        if (online != null && s.getPlayerName().isEmpty()) s.setPlayerName(online.getName());
        saveAsync(s);
    }

    public void recordKill(UUID uuid, String mode) {
        PlayerStats s = get(uuid, mode);
        s.addKill();
        // Ensure player name is stored for leaderboard queries
        org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(uuid);
        if (online != null && s.getPlayerName().isEmpty()) s.setPlayerName(online.getName());
        saveAsync(s);
    }

    public void recordDeath(UUID uuid, String mode) {
        PlayerStats s = get(uuid, mode);
        s.addDeath();
        org.bukkit.entity.Player online = org.bukkit.Bukkit.getPlayer(uuid);
        if (online != null && s.getPlayerName().isEmpty()) s.setPlayerName(online.getName());
        saveAsync(s);
    }

    private void saveAsync(PlayerStats s) {
        CompletableFuture.runAsync(() -> plugin.getStorage().getMain().saveStats(s));
    }

    // ── Clear (admin commands) ─────────────────────────────────────────────────

    /** Clears all stats caches (used after /spvp clear all). */
    public void clearAllCache() {
        cache.clear();
    }

    /** Clears cached stats for a specific player (used after /spvp clear <player>). */
    public void clearPlayerCache(UUID uuid) {
        cache.entrySet().removeIf(e -> e.getKey().startsWith(uuid.toString()));
        playtime.remove(uuid);
    }

    // ── Playtime ─────────────────────────────────────────────────────────────

    public void onLogin(UUID uuid) {
        loginTime.put(uuid, System.currentTimeMillis());
        // Async load persisted playtime
        CompletableFuture.supplyAsync(() -> plugin.getStorage().getMain().loadPlaytime(uuid))
                .thenAccept(min -> playtime.put(uuid, min));
    }

    public void onLogout(UUID uuid) {
        flush(uuid);
        playtime.remove(uuid);
        loginTime.remove(uuid);
    }

    /**
     * Returns current total playtime in minutes, including active session.
     * If the cache has not yet been populated for this player (async load
     * still in flight, or player is offline), falls back to a synchronous
     * DB read — otherwise placeholders would show 0 right after join.
     */
    public long getPlaytimeMinutes(UUID uuid) {
        Long base = playtime.get(uuid);
        if (base == null) {
            // Sync fallback — cheap (one row by PK), only triggered when
            // the async load hasn't finished or the player is offline.
            try {
                base = plugin.getStorage().getMain().loadPlaytime(uuid);
            } catch (Exception e) {
                base = 0L;
            }
            playtime.put(uuid, base);
        }
        Long login = loginTime.get(uuid);
        if (login == null) return base;
        return base + (System.currentTimeMillis() - login) / 60_000L;
    }

    private void flush(UUID uuid) {
        long total = getPlaytimeMinutes(uuid);
        CompletableFuture.runAsync(() -> plugin.getStorage().getMain().savePlaytime(uuid, total));
    }

    /** Called by PlaytimeTask every 5 minutes to persist playtime for all online players. */
    public void flushAll() {
        loginTime.keySet().forEach(this::flush);
    }

    /**
     * Synchronously saves ALL cached stats and playtime to the database.
     * Used in onDisable() to guarantee data is persisted before shutdown.
     */
    public void flushAllSync() {
        // Save all playtime synchronously
        for (UUID uuid : loginTime.keySet()) {
            long total = getPlaytimeMinutes(uuid);
            try {
                plugin.getStorage().getMain().savePlaytime(uuid, total);
            } catch (Exception e) {
                plugin.getLogger().severe("[ScorePvP] Failed to save playtime for " + uuid + ": " + e.getMessage());
            }
        }
        // Save all cached stats synchronously
        for (PlayerStats s : cache.values()) {
            try {
                plugin.getStorage().getMain().saveStats(s);
            } catch (Exception e) {
                plugin.getLogger().severe("[ScorePvP] Failed to save stats for " + s.getUuid() + ": " + e.getMessage());
            }
        }
    }
}
