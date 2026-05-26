package com.scorepvp.storage;

import java.util.Map;
import java.util.UUID;

/**
 * Tier-only storage. Lives in its own DB (SQLite or MySQL, switchable via
 * {@code storage.tiers.type} in config.yml) so admins can share tiers across
 * a network without paying the schema-size of stats / friends / settings.
 *
 * Implementations: {@link SqliteTierStorage}, {@link MysqlTierStorage}.
 */
public interface TierStorage {

    /** Returns {mode → tier} for the player. Empty map if no tiers set. */
    Map<String, String> loadTiers(UUID uuid);

    /** Inserts or replaces the tier for (uuid, mode). */
    void saveTier(UUID uuid, String mode, String tier);

    /** Removes the tier entry for (uuid, mode). No-op if absent. */
    void removeTier(UUID uuid, String mode);

    /** Closes underlying connection pool. Safe to call multiple times. */
    void close();
}
