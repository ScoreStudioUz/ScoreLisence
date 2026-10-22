package com.scorepvp.manager;

import com.scorepvp.ScorePvP;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Kumush" (silver) currency used to buy cosmetic badges.
 *
 * <p>Balances live in the player_silver table. Online players are cached so
 * reads (menus, lore) are instant and on the main thread; writes go through to
 * the DB asynchronously. Offline players (e.g. {@code /kumush give <nick>}) are
 * read/written directly against the DB off-thread — they have no cache entry,
 * and if they happen to be online the cache is refreshed too.
 */
public class SilverManager {

    private final ScorePvP plugin;
    private final Map<UUID, Integer> cache = new ConcurrentHashMap<>();
    /** UUIDs whose balance has actually been read from the DB (vs. never loaded). */
    private final java.util.Set<UUID> loaded = ConcurrentHashMap.newKeySet();

    public SilverManager(ScorePvP plugin) { this.plugin = plugin; }

    // ── Online cache lifecycle ───────────────────────────────────────────────

    public void loadAsync(UUID uuid) {
        CompletableFuture.supplyAsync(() -> plugin.getStorage().getMain().getSilver(uuid))
                .thenAccept(v -> { cache.put(uuid, v); loaded.add(uuid); })
                .exceptionally(ex -> {
                    // Without this the failure was swallowed and the cache stayed
                    // empty → get() returned 0 → "not enough silver" even though
                    // the player had a balance. Log it and leave `loaded` false so
                    // trySpend re-triggers the load instead of debiting from 0.
                    plugin.getLogger().warning("[ScorePvP] Silver load failed for "
                            + uuid + ": " + ex.getMessage());
                    return null;
                });
    }

    public void unload(UUID uuid) { cache.remove(uuid); loaded.remove(uuid); }

    /** True once this player's balance has been read from the DB this session. */
    public boolean isLoaded(UUID uuid) { return loaded.contains(uuid); }

    // ── Reads ────────────────────────────────────────────────────────────────

    /**
     * Authoritative balance. If the cache is warm it's instant; if it's cold
     * (async load stalled / failed / not finished) this does a single blocking
     * DB read and populates the cache, so the caller NEVER sees a phantom 0.
     *
     * <p>The blocking read only happens on the cold path and hits a local SQLite
     * file (sub-millisecond), so it's safe from the main thread — this is exactly
     * what fixes the "look shows 13k but purchase says no silver" desync: reads
     * and spends now share one source of truth.
     */
    public int get(UUID uuid) {
        Integer v = cache.get(uuid);
        if (v != null) return v;
        int db = plugin.getStorage().getMain().getSilver(uuid);
        cache.put(uuid, db);
        loaded.add(uuid);
        return db;
    }

    /** Direct DB read — use for offline targets. Blocking; call off the main thread. */
    public int getFromDb(UUID uuid) {
        return plugin.getStorage().getMain().getSilver(uuid);
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /** Sets an online player's balance (cache + async DB). */
    public void set(UUID uuid, int amount) {
        int amt = Math.max(0, amount);
        cache.put(uuid, amt);
        loaded.add(uuid);
        CompletableFuture.runAsync(() -> plugin.getStorage().getMain().setSilver(uuid, amt));
    }

    /** Adds (or subtracts, if negative) to an online player's balance. */
    public void add(UUID uuid, int delta) {
        set(uuid, get(uuid) + delta);
    }

    /**
     * Tries to spend {@code cost}. Returns true and debits if the player can
     * afford it; false otherwise. Reads through {@link #get} so it always sees
     * the true DB balance even on a cold cache — no more false "not enough silver".
     */
    public boolean trySpend(UUID uuid, int cost) {
        if (cost <= 0) return true;
        int bal = get(uuid);
        if (bal < cost) return false;
        set(uuid, bal - cost);
        return true;
    }

    /**
     * Offline-safe mutation by UUID. Reads the current balance (cache for online,
     * DB otherwise), applies {@code op}, and writes back to the DB. For an ONLINE
     * player the cache is always refreshed to the new value (previously this was
     * gated on the cache already being warm — the root of the "/kumush give
     * didn't update my in-game balance" desync). Runs off-thread; pass a
     * main-thread callback if you need to message the actor afterwards.
     *
     * @param op given the current balance, returns the new balance
     */
    public void mutateOffline(UUID uuid, java.util.function.IntUnaryOperator op,
                              java.util.function.IntConsumer done) {
        CompletableFuture.supplyAsync(() -> {
            int current = cache.containsKey(uuid) ? cache.get(uuid)
                    : plugin.getStorage().getMain().getSilver(uuid);
            int next = Math.max(0, op.applyAsInt(current));
            plugin.getStorage().getMain().setSilver(uuid, next);
            return next;
        }).thenAccept(next -> {
            // Always refresh the cache for an online player so the new balance is
            // visible to menus / purchases immediately.
            if (plugin.getServer().getPlayer(uuid) != null) {
                cache.put(uuid, next);
                loaded.add(uuid);
            }
            if (done != null) plugin.getServer().getScheduler().runTask(plugin, () -> done.accept(next));
        });
    }

    /** Offline-safe read by UUID, delivering the balance on the main thread. */
    public void getOffline(UUID uuid, java.util.function.IntConsumer done) {
        if (cache.containsKey(uuid)) { done.accept(cache.get(uuid)); return; }
        CompletableFuture.supplyAsync(() -> plugin.getStorage().getMain().getSilver(uuid))
                .thenAccept(v -> plugin.getServer().getScheduler().runTask(plugin, () -> done.accept(v)));
    }
}
