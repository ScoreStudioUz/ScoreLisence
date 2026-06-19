package com.scorepvp.manager;

import com.scorepvp.ScorePvP;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

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

    /**
     * Records this player's current name into the name → uuid index so admins
     * can target them with {@code /tier set <name> …} after they log out.
     * Called on join — does NOT load tiers (that's {@link #loadAsync}'s job).
     */
    public void rememberNameAsync(UUID uuid, String name) {
        CompletableFuture.runAsync(() ->
                plugin.getStorage().getTiers().rememberName(uuid, name));
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

    /**
     * Highest tier this (cached/online) player holds across all modes, ranked by
     * the configured tier order. Returns {@code [tier, modeId]} or {@code null}
     * if the player has no tier (or isn't cached).
     */
    public String[] getHighestTier(UUID uuid) {
        Map<String, String> tiers = cache.get(uuid);
        if (tiers == null || tiers.isEmpty()) return null;
        var order = plugin.getConfigManager().getTierOrder();
        String best = null, bestMode = null;
        int bestIdx = -1;
        for (var e : tiers.entrySet()) {
            String tier = e.getValue();
            if (tier == null || tier.isBlank()) continue;
            int idx = order.indexOf(tier);
            if (idx > bestIdx) { bestIdx = idx; best = tier; bestMode = e.getKey(); }
        }
        return best == null ? null : new String[]{best, bestMode};
    }

    /**
     * Resolves a player's full tier map for read-only display (e.g. /profile of
     * another player). If the tiers are already cached (online player) the
     * callback fires immediately on the calling thread; otherwise the map is
     * loaded from the DB off-thread and the callback is delivered back on the
     * main thread. This is why an offline player's profile previously showed
     * "unranked" — getTier only reads the cache, which is empty for them.
     */
    public void loadTiersForDisplay(UUID uuid, java.util.function.Consumer<Map<String, String>> callback) {
        Map<String, String> cached = cache.get(uuid);
        if (cached != null) {
            callback.accept(cached);
            return;
        }
        CompletableFuture.supplyAsync(() -> plugin.getStorage().getTiers().loadTiers(uuid))
                .thenAccept(tiers -> Bukkit.getScheduler().runTask(plugin,
                        () -> callback.accept(tiers != null ? tiers : Map.of())));
    }

    /**
     * Returns the (uuid, name) of a player that admins may target with a tier
     * command, even when that player is currently offline. Resolution order:
     * <ol>
     *   <li>online player (case-insensitive)</li>
     *   <li>tiers DB name index (set on every prior join via {@link #rememberNameAsync})</li>
     *   <li>Bukkit's offline-player cache (last-played-before only — never
     *       queries Mojang, so admins can't accidentally tier random Mojang
     *       accounts the server has never seen)</li>
     * </ol>
     * Returns {@code null} if no match is found anywhere.
     */
    public Resolved resolveTarget(String name) {
        if (name == null || name.isBlank()) return null;

        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return new Resolved(online.getUniqueId(), online.getName());

        UUID indexed = plugin.getStorage().getTiers().findUuidByName(name);
        if (indexed != null) return new Resolved(indexed, name);

        @SuppressWarnings("deprecation")
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.hasPlayedBefore()) {
            return new Resolved(cached.getUniqueId(),
                    cached.getName() != null ? cached.getName() : name);
        }
        return null;
    }

    /** Pair returned by {@link #resolveTarget}. */
    public record Resolved(UUID uuid, String name) {}

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Sets a tier for the given player in the given mode. Works whether or
     * not the player is currently online — the new tier is persisted to the
     * tiers DB and (if they later join) picked up by {@link #loadAsync}.
     *
     * @return false if the tier string is not in the configured tier order.
     */
    public boolean setTier(UUID uuid, String name, String mode, String tier) {
        if (!plugin.getConfigManager().getTierOrder().contains(tier)) return false;
        cache.computeIfAbsent(uuid, k -> new ConcurrentHashMap<>()).put(mode, tier);
        CompletableFuture.runAsync(() ->
                plugin.getStorage().getTiers().saveTier(uuid, mode, tier, name));
        // If the target is in an active match, repaint the nametag so the new
        // tier appears next to their name immediately — otherwise the tier
        // only shows up on the next match (or after rejoin).
        refreshActiveNametag(uuid);
        return true;
    }

    /**
     * Removes a tier for the given player in the given mode. Works for
     * offline players too — the cache entry may be missing, but the DB
     * delete still fires.
     *
     * @return true if a tier was actually removed (cache or DB) — best-effort,
     *         since for offline players we don't synchronously read the DB
     *         before deleting; we just trust the admin.
     */
    public boolean removeTier(UUID uuid, String mode) {
        Map<String, String> tiers = cache.get(uuid);
        boolean hadInCache = tiers != null && tiers.containsKey(mode);
        if (hadInCache) tiers.remove(mode);
        CompletableFuture.runAsync(() ->
                plugin.getStorage().getTiers().removeTier(uuid, mode));
        refreshActiveNametag(uuid);
        // For online players we can tell the caller whether the tier actually
        // existed; for offline ones we return true (best-effort) so the admin
        // doesn't see a misleading "no such tier" error when there was one in
        // the DB but it wasn't cached.
        return hadInCache || Bukkit.getPlayer(uuid) == null;
    }

    /** Tells the active nametag provider to redraw nametags for the match this UUID is in. */
    private void refreshActiveNametag(UUID uuid) {
        com.scorepvp.model.Match match = plugin.getMatchManager().getMatch(uuid);
        if (match == null || match.getState() == com.scorepvp.model.Match.State.FINISHED) return;
        // onExternalReload re-applies prefixes for every active match — simplest
        // way to push the new tier without inventing a new provider method.
        plugin.getNameTagProvider().onExternalReload();
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

    /** Returns the colored tier prefix, e.g. "&e[LT3]" (gradient-aware), or "" if no tier. */
    public String getColoredTier(UUID uuid, String mode) {
        String tier = getTier(uuid, mode);
        if (tier == null || tier.isBlank()) return "";
        return plugin.getConfigManager().getTierDisplay(tier);
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
