package com.scoretpa.manager;

import com.scoretpa.ScoreTpa;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class CooldownManager {

    private final ScoreTpa plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public CooldownManager(ScoreTpa plugin) {
        this.plugin = plugin;
    }

    public boolean isOnCooldown(UUID player) {
        Long last = cooldowns.get(player);
        if (last == null) return false;
        return System.currentTimeMillis() - last < plugin.getConfigManager().getCooldown() * 1000L;
    }

    public int getRemainingCooldown(UUID player) {
        Long last = cooldowns.get(player);
        if (last == null) return 0;
        long elapsed = System.currentTimeMillis() - last;
        long total = plugin.getConfigManager().getCooldown() * 1000L;
        if (elapsed >= total) return 0;
        return (int) Math.ceil((total - elapsed) / 1000.0);
    }

    public void setCooldown(UUID player) {
        cooldowns.put(player, System.currentTimeMillis());
    }
}
