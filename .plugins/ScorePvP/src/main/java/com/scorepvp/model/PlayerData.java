package com.scorepvp.model;

import java.util.UUID;

/**
 * Tracks runtime (non-persistent) state for each online player.
 */
public class PlayerData {

    private final UUID uuid;

    private boolean inVanillaRegion = false;
    private boolean hotbarGiven     = false;

    /**
     * Section 3: Kit editor mode.
     * When true, lobby hotbar items are hidden so /spvp kit save doesn't
     * accidentally capture them.
     */
    private boolean kitEditorMode = false;

    /**
     * Section 2: Kit reset pending.
     * When true, the personal kit is cleared from memory/DB but hasn't been
     * applied yet. On next vanilla region entry the default kit will be given.
     */
    private boolean kitResetPending = false;

    public PlayerData(UUID uuid) { this.uuid = uuid; }

    public UUID getUuid()                       { return uuid; }

    public boolean isInVanillaRegion()          { return inVanillaRegion; }
    public void setInVanillaRegion(boolean b)   { this.inVanillaRegion = b; }

    public boolean isHotbarGiven()              { return hotbarGiven; }
    public void setHotbarGiven(boolean b)       { this.hotbarGiven = b; }

    public boolean isKitEditorMode()            { return kitEditorMode; }
    public void setKitEditorMode(boolean b)     { this.kitEditorMode = b; }

    public boolean isKitResetPending()          { return kitResetPending; }
    public void setKitResetPending(boolean b)   { this.kitResetPending = b; }
}
