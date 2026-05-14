package ru.score.hwauction.manager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Holds pending sell-auto confirmations per player.
 * Entry expires after TIMEOUT_MS to prevent stale confirmations.
 */
public final class PendingConfirmManager {

    /** How long a pending confirmation stays valid (ms) */
    public static final long TIMEOUT_MS = 30_000L;

    public record AutoSellPreview(
            double suggestedPrice,  // price per unit we suggest
            int    amount,          // item amount
            String itemName,        // display name for messages
            long   createdAt        // timestamp for expiry check
    ) {
        public boolean isExpired() {
            return System.currentTimeMillis() - createdAt > TIMEOUT_MS;
        }

        public double totalPrice() { return suggestedPrice * amount; }
    }

    private final Map<UUID, AutoSellPreview> pending = new ConcurrentHashMap<>();

    public void set(UUID uuid, AutoSellPreview preview) {
        pending.put(uuid, preview);
    }

    public AutoSellPreview get(UUID uuid) {
        AutoSellPreview p = pending.get(uuid);
        if (p == null) return null;
        if (p.isExpired()) { pending.remove(uuid); return null; }
        return p;
    }

    public void clear(UUID uuid) {
        pending.remove(uuid);
    }

    public boolean hasPending(UUID uuid) {
        return get(uuid) != null;
    }
}
