package com.scorepvp.model;

import java.util.UUID;

public class PlayerSettings {

    public enum Visibility { ANYONE, ONLY_FRIENDS }
    public enum ProfileVisibility { PUBLIC, PRIVATE }

    private final UUID uuid;

    private Visibility duelRequests    = Visibility.ANYONE;
    private Visibility partyInvites    = Visibility.ANYONE;
    private Visibility showOwnTier     = Visibility.ANYONE;
    private boolean    friendRequests  = true;
    private Visibility directMessages  = Visibility.ANYONE;
    private boolean    friendJoinNotifier = true;
    private ProfileVisibility profileVisibility = ProfileVisibility.PUBLIC;

    public PlayerSettings(UUID uuid) {
        this.uuid = uuid;
    }

    // Cycle toggles
    public void cycleDuelRequests()   { duelRequests  = next(duelRequests); }
    public void cyclePartyInvites()   { partyInvites  = next(partyInvites); }
    public void cycleShowOwnTier()    { showOwnTier   = next(showOwnTier); }
    public void toggleFriendRequests(){ friendRequests = !friendRequests; }
    public void cycleDirectMessages() { directMessages = next(directMessages); }
    public void toggleFriendJoin()    { friendJoinNotifier = !friendJoinNotifier; }
    public void cycleProfileVisibility() {
        profileVisibility = (profileVisibility == ProfileVisibility.PUBLIC)
                ? ProfileVisibility.PRIVATE : ProfileVisibility.PUBLIC;
    }

    private Visibility next(Visibility v) {
        return (v == Visibility.ANYONE) ? Visibility.ONLY_FRIENDS : Visibility.ANYONE;
    }

    // Getters
    public UUID getUuid()                            { return uuid; }
    public Visibility getDuelRequests()              { return duelRequests; }
    public Visibility getPartyInvites()              { return partyInvites; }
    public Visibility getShowOwnTier()               { return showOwnTier; }
    public boolean isFriendRequests()                { return friendRequests; }
    public Visibility getDirectMessages()            { return directMessages; }
    public boolean isFriendJoinNotifier()            { return friendJoinNotifier; }
    public ProfileVisibility getProfileVisibility()  { return profileVisibility; }

    // Setters (for DB load)
    public void setDuelRequests(Visibility v)        { this.duelRequests = v; }
    public void setPartyInvites(Visibility v)        { this.partyInvites = v; }
    public void setShowOwnTier(Visibility v)         { this.showOwnTier = v; }
    public void setFriendRequests(boolean b)         { this.friendRequests = b; }
    public void setDirectMessages(Visibility v)      { this.directMessages = v; }
    public void setFriendJoinNotifier(boolean b)     { this.friendJoinNotifier = b; }
    public void setProfileVisibility(ProfileVisibility v) { this.profileVisibility = v; }
}
