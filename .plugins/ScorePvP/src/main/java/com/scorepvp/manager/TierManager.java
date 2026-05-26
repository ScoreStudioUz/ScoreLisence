package com.scorepvp.manager;

import com.scorepvp.ScorePvP;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class TierManager {

    private final ScorePvP plugin;

    /** uuid → (mode → tier). Loaded from DB on join, kept in memory, saved immediately on change. */
    private final Map<UUID, Map<String, String>> cache = new ConcurrentHashMap<>();

    public TierManager(ScorePvP plugin) {
        this.plugin = plugin;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    public void loadAsync(UUID uuid) {
        CompletableFuture.supplyAsync(() -> plugin.getStorage().getTiers().loadTiers(uuid))
                .thenAccept(tiers -> cache.put(uuid, new ConcurrentHashMap<>(tiers)));
    }

    /** Tiers are saved immediately on change — no pending writes to flush on unload. */
    public void unload(UUID uuid) {
        cache.remove(uuid);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    public String getTier(UUID uuid, String mode) {
        Map<String, String> tiers = cache.get(uuid);
        return tiers != null ? tiers.get(mode) : null;
    }

    public Map<String, String> getAllTiers(UUID uuid) {
        return Collections.unmodifiableMap(cache.getOrDefault(uuid, Map.of()));
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Sets a tier for the given player in the given mode.
     * @return false if the tier string is not in the configured tier order.
     */
    public boolean setTier(UUID uuid, String mode, String tier) {
        if (!plugin.getConfigManager().getTierOrder().contains(tier)) return false;
        cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(mode, tier);
        CompletableFuture.runAsync(() -> plugin.getStorage().getTiers().saveTier(uuid, mode, tier));
        return true;
    }

    /**
     * Removes a tier for the given player in the given mode.
     * @return false if the player had no tier in that mode.
     */
    public boolean removeTier(UUID uuid, String mode) {
        Map<String, String> tiers = cache.get(uuid);
        if (tiers == null || !tiers.containsKey(mode)) return false;
        tiers.remove(mode);
        CompletableFuture.runAsync(() -> plugin.getStorage().getTiers().removeTier(uuid, mode));
        return true;
    }

    // ── Clear (admin commands) ─────────────────────────────────────────────────

    /** Clears all tier caches (used after /spvp clear all). */
    public void clearAllCache() {
        cache.clear();
    }

    /** Clears cached tiers for a specific player (used after /spvp clear <player>). */
    public void clearPlayerCache(UUID uuid) {
        cache.remove(uuid);
    }

    // ── Display ───────────────────────────────────────────────────────────────

    /** Returns the colored tier prefix, e.g. "&e[LT3]", or empty string if no tier. */
    public String getColoredTier(UUID uuid, String mode) {
        String tier = getTier(uuid, mode);
        if (tier == null || tier.isBlank()) return "";
        String color = plugin.getConfigManager().getTierColor(tier);
        return color + "[" + tier + "]";
    }

    /**
     * Returns the display name with tier prefix:
     *   "[LT3] MrBroccoli"  (in Sword mode)
     *   "MrBroccoli"        (no tier set for this mode)
     */
    public String getDisplayNameWithTier(UUID uuid, String playerName, String mode) {
        String prefix = getColoredTier(uuid, mode);
        if (prefix.isEmpty()) return playerName;
        return prefix + " &r" + playerName;
    }

    // ── Validation ────────────────────────────────────────────────────────────

    public boolean isValidTier(String tier) {
        return plugin.getConfigManager().getTierOrder().contains(tier);
    }

    public boolean isValidMode(String mode) {
        return plugin.getConfigManager().getMode(mode) != null;
    }
}
