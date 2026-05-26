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

    public SettingsManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    public void loadAsync(UUID uuid) {
        CompletableFuture.supplyAsync(() -> plugin.getStorage().getMain().loadSettings(uuid))
                .thenAccept(s -> cache.put(uuid, s));
    }

    public void saveAsync(UUID uuid) {
        PlayerSettings s = cache.get(uuid);
        if (s != null) CompletableFuture.runAsync(() -> plugin.getStorage().getMain().saveSettings(s));
    }

    public void unload(UUID uuid) {
        saveAsync(uuid);
        cache.remove(uuid);
    }

    public PlayerSettings get(UUID uuid) {
        return cache.computeIfAbsent(uuid, PlayerSettings::new);
    }

    /** Persist immediately (e.g., after GUI click). */
    public void persist(UUID uuid) {
        saveAsync(uuid);
    }
}
