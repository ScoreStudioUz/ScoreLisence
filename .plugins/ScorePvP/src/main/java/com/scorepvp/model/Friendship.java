package com.scorepvp.model;

import java.util.UUID;

/** Represents a friend request or an accepted friendship. */
public class Friendship {

    public enum State { PENDING, ACCEPTED }

    private final UUID   sender;
    private final UUID   receiver;
    private       State  state;
    private final long   createdAt;

    public Friendship(UUID sender, UUID receiver) {
        this.sender    = sender;
        this.receiver  = receiver;
        this.state     = State.PENDING;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID  getSender()    { return sender; }
    public UUID  getReceiver()  { return receiver; }
    public State getState()     { return state; }
    public void  accept()       { this.state = State.ACCEPTED; }
    public boolean isPending()  { return state == State.PENDING; }
    public boolean isAccepted() { return state == State.ACCEPTED; }

    public boolean isExpired(int seconds) {
        return state == State.PENDING
            && System.currentTimeMillis() - createdAt > seconds * 1000L;
    }

    /** Returns true if either UUID matches. */
    public boolean involves(UUID uuid) {
        return sender.equals(uuid) || receiver.equals(uuid);
    }

    /** Returns the other UUID in the pair. */
    public UUID otherThan(UUID uuid) {
        return sender.equals(uuid) ? receiver : sender;
    }
}
