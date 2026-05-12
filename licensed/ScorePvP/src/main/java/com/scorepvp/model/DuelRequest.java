package com.scorepvp.model;

import java.util.UUID;

/** A pending duel challenge from one player to another. */
public class DuelRequest {

    private final UUID sender;
    private final UUID target;
    private final String modeName;
    private final int rounds;
    private final long createdAt;

    public DuelRequest(UUID sender, UUID target, String modeName, int rounds) {
        this.sender    = sender;
        this.target    = target;
        this.modeName  = modeName;
        this.rounds    = rounds;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID   getSender()    { return sender; }
    public UUID   getTarget()    { return target; }
    public String getModeName()  { return modeName; }
    public int    getRounds()    { return rounds; }

    /** Returns true if this request is older than the given seconds. */
    public boolean isExpired(int seconds) {
        return System.currentTimeMillis() - createdAt > seconds * 1000L;
    }
}
