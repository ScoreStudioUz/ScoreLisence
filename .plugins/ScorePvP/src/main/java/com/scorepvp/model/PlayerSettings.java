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
    /** Mode-id whose tier the player chose to display in lobby/TAB, or null = auto (highest). */
    private String     tierTagMode = null;
    /** Chosen name-color id (from namecolors/*.yml), or null = default white nick. */
    private String     nameColor = null;
    /** Chosen name-tag/emoji id (from nametags.yml), or null = no emoji. */
    private String     nameTag = null;
    /** Elytra + wind-charge lobby cosmetic toggle. */
    private boolean    elytraCosmetic = false;
    /** Chosen cosmetic hat id (from hats.yml), or null = no hat. */
    private String     hat = null;
    /** Chosen badge color id (from namecolors/*.yml), or null = the badge's own color. */
    private String     badgeColor = null;

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
    public String getTierTagMode()                   { return tierTagMode; }
    public String getNameColor()                     { return nameColor; }
    public String getNameTag()                       { return nameTag; }
    public boolean isElytraCosmetic()                { return elytraCosmetic; }
    public String getHat()                           { return hat; }
    public String getBadgeColor()                    { return badgeColor; }

    // Setters (for DB load)
    public void setDuelRequests(Visibility v)        { this.duelRequests = v; }
    public void setPartyInvites(Visibility v)        { this.partyInvites = v; }
    public void setShowOwnTier(Visibility v)         { this.showOwnTier = v; }
    public void setFriendRequests(boolean b)         { this.friendRequests = b; }
    public void setDirectMessages(Visibility v)      { this.directMessages = v; }
    public void setFriendJoinNotifier(boolean b)     { this.friendJoinNotifier = b; }
    public void setProfileVisibility(ProfileVisibility v) { this.profileVisibility = v; }
    public void setTierTagMode(String mode)          { this.tierTagMode = (mode == null || mode.isBlank()) ? null : mode; }
    public void setNameColor(String id)              { this.nameColor = (id == null || id.isBlank()) ? null : id; }
    public void setNameTag(String id)                { this.nameTag = (id == null || id.isBlank()) ? null : id; }
    public void setElytraCosmetic(boolean b)         { this.elytraCosmetic = b; }
    public void setHat(String id)                    { this.hat = (id == null || id.isBlank()) ? null : id; }
    public void setBadgeColor(String id)             { this.badgeColor = (id == null || id.isBlank()) ? null : id; }
}
