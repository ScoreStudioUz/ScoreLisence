package com.scorepvp.storage;

import java.util.Map;
import java.util.UUID;

/**
 * Tier-only storage. Lives in its own DB (SQLite or MySQL, switchable via
 * {@code storage.tiers.type} in config.yml) so admins can share tiers across
 * a network without paying the schema-size of stats / friends / settings.
 *
 * <p>Each row also carries the player's last-known name so {@code /tier set}
 * can target a player who has logged in at least once even when they are
 * currently offline — no need to ping Mojang or wait for them to rejoin.
 *
 * <p>Implementations: {@link SqliteTierStorage}, {@link MysqlTierStorage}.
 */
public interface TierStorage {

    /** Returns {mode → tier} for the player. Empty map if no tiers set. */
    Map<String, String> loadTiers(UUID uuid);

    /**
     * Inserts or replaces the tier for (uuid, mode), and stores
     * {@code playerName} as the player's last-known name. {@code playerName}
     * may be {@code null}; the existing name is then kept.
     */
    void saveTier(UUID uuid, String mode, String tier, String playerName);

    /** Removes the tier entry for (uuid, mode). No-op if absent. */
    void removeTier(UUID uuid, String mode);

    /**
     * Resolves a last-known player name (case-insensitive) to a UUID, so
     * admins can run {@code /tier set <name>} against an offline player
     * that has tiers stored. Returns {@code null} if no row matches.
     */
    UUID findUuidByName(String playerName);

    /**
     * Upserts the player's last-known name without touching any tier row
     * — used on join so the index stays warm even for players who never
     * had a tier yet but might receive one later while they are offline.
     */
    void rememberName(UUID uuid, String playerName);

    /** Closes underlying connection pool. Safe to call multiple times. */
    void close();
}
