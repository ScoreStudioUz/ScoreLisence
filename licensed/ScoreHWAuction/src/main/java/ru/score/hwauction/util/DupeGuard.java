package ru.score.hwauction.util;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prevents race-condition dupes by holding an exclusive lock per auction item id
 * during the critical window of a buy/bid operation.
 *
 * Usage:
 *   if (!DupeGuard.lock(id)) return BuyResult.ALREADY_SOLD;
 *   try { ... do transaction ... }
 *   finally { DupeGuard.unlock(id); }
 */
public final class DupeGuard {

    private static final Set<Integer> LOCKED = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private DupeGuard() {}

    /**
     * Try to acquire lock for item id.
     * @return true if lock was acquired, false if another thread already holds it
     */
    public static boolean lock(int itemId) {
        return LOCKED.add(itemId);
    }

    /** Release lock for item id. Always call in a finally block. */
    public static void unlock(int itemId) {
        LOCKED.remove(itemId);
    }

    /** Check if item is currently locked (for diagnostic only). */
    public static boolean isLocked(int itemId) {
        return LOCKED.contains(itemId);
    }

    public static int lockedCount() {
        return LOCKED.size();
    }
}
