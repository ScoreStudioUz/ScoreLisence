package com.scoretpa.model;

import java.util.UUID;

public class TpaRequest {

    private final UUID sender;
    private final UUID target;
    private final long createdAt;

    public TpaRequest(UUID sender, UUID target) {
        this.sender = sender;
        this.target = target;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID getSender() {
        return sender;
    }

    public UUID getTarget() {
        return target;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public boolean isExpired(int expireSeconds) {
        return System.currentTimeMillis() - createdAt > expireSeconds * 1000L;
    }
}
