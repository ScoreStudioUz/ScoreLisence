package com.scorepvp.manager;

import com.scorepvp.model.PlayerData;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerDataManager {

    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();

    public PlayerData get(UUID uuid) {
        return cache.computeIfAbsent(uuid, PlayerData::new);
    }

    public void remove(UUID uuid) {
        cache.remove(uuid);
    }

    public boolean isInVanillaRegion(UUID uuid) {
        PlayerData d = cache.get(uuid);
        return d != null && d.isInVanillaRegion();
    }

    public void setInVanillaRegion(UUID uuid, boolean value) {
        get(uuid).setInVanillaRegion(value);
    }

    public boolean isKitEditorMode(UUID uuid) {
        PlayerData d = cache.get(uuid);
        return d != null && d.isKitEditorMode();
    }

    public void setKitEditorMode(UUID uuid, boolean value) {
        get(uuid).setKitEditorMode(value);
    }

    public boolean isKitResetPending(UUID uuid) {
        PlayerData d = cache.get(uuid);
        return d != null && d.isKitResetPending();
    }

    public void setKitResetPending(UUID uuid, boolean value) {
        get(uuid).setKitResetPending(value);
    }
}
