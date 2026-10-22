package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import com.scorepvp.model.PlayerSettings;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class SettingsManager {

    private final ScorePvP plugin;
    private final Map<UUID, PlayerSettings> cache = new ConcurrentHashMap<>();
    /** UUIDs whose row was actually read from the DB (vs. a fresh default). */
    private final java.util.Set<UUID> loaded = ConcurrentHashMap.newKeySet();

    public SettingsManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    public void loadAsync(UUID uuid) {
        CompletableFuture.supplyAsync(() -> plugin.getStorage().getMain().loadSettings(uuid))
                .thenAccept(s -> {
                    // Warm-up only: NEVER clobber a cache entry that a main-thread
                    // access already created + possibly mutated (e.g. the player
                    // equipped a cosmetic before this async load landed). An
                    // unconditional put() here was the root of "selected cosmetics
                    // reset" — the load overwrote the just-equipped selection, then
                    // quit() persisted the stale row. get() below does a blocking
                    // read-through anyway, so a missed warm-up is harmless.
                    if (cache.putIfAbsent(uuid, s) == null) loaded.add(uuid);
                })
                .exceptionally(ex -> {
                    plugin.getLogger().warning("[ScorePvP] Settings load failed for "
                            + uuid + ": " + ex.getMessage());
                    return null;
                });
    }

    public void saveAsync(UUID uuid) {
        PlayerSettings s = cache.get(uuid);
        if (s != null) CompletableFuture.runAsync(() -> plugin.getStorage().getMain().saveSettings(s));
    }

    public void unload(UUID uuid) {
        saveAsync(uuid);
        cache.remove(uuid);
        loaded.remove(uuid);
    }

    /**
     * The player's settings. Authoritative: if the cache is cold (async warm-up
     * hasn't landed / failed) this does ONE blocking DB read and caches it, so a
     * caller never gets a fresh-default row that would later overwrite the real
     * saved cosmetics. Settings live in a local SQLite file (sub-millisecond), so
     * the cold-path read is safe from the main thread.
     */
    public PlayerSettings get(UUID uuid) {
        PlayerSettings cached = cache.get(uuid);
        if (cached != null) return cached;
        PlayerSettings loadedRow = plugin.getStorage().getMain().loadSettings(uuid);
        PlayerSettings prev = cache.putIfAbsent(uuid, loadedRow);
        loaded.add(uuid);
        return prev != null ? prev : loadedRow;
    }

    /** Persist immediately (e.g., after GUI click). */
    public void persist(UUID uuid) {
        saveAsync(uuid);
    }
}
