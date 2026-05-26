package com.scorepvp.model;

import org.bukkit.Location;

/**
 * Represents a PvP arena.
 *
 * Fully configured = corners + pos1 + pos2 + centre + name + type set.
 *
 * <h2>Why no `hasSnapshot` anymore</h2>
 * Earlier versions tracked a "saved block snapshot" flag because VANILLA
 * arenas needed a pre-recorded baseline to restore after each round. That
 * baseline lived in a {@code arena_snapshots} SQLite table and bloated the
 * DB to 500MB. We now restore via {@link com.scorepvp.manager.ArenaBlockTracker}
 * — it diffs blocks live and replays the inverse at round end, so no
 * baseline is needed.
 */
public class Arena {

    public enum Status   { FREE, BUSY }
    public enum ArenaType { DEFAULT, VANILLA }

    private final int number;
    private String    name   = "";           // e.g. "sword1"
    private ArenaType type   = ArenaType.DEFAULT;

    // Region corners
    private Location corner1;
    private Location corner2;

    // Spawn positions
    private Location pos1;
    private Location pos2;
    private Location centre;

    // Raw serialized strings preserved from disk (used when world is not loaded yet
    // or when world is already unloaded during shutdown)
    private String rawCorner1;
    private String rawCorner2;
    private String rawPos1;
    private String rawPos2;
    private String rawCentre;

    private Status   status    = Status.FREE;

    public Arena(int number) {
        this.number = number;
    }

    // ── Readiness ─────────────────────────────────────────────────────────────

    /**
     * Fully configured: has corners, all three positions, a name, and a type.
     * VANILLA and DEFAULT now share the same readiness rule — snapshots
     * are no longer required (live tracker handles restoration).
     */
    public boolean isFullyConfigured() {
        return corner1 != null && corner2 != null
            && pos1 != null && pos2 != null && centre != null
            && !name.isEmpty();
    }

    /** Legacy alias kept for compatibility. */
    public boolean isDuelReady() { return isFullyConfigured(); }
    public boolean isFfaReady()  { return centre != null; }

    public boolean isFree() { return status == Status.FREE; }
    public boolean isBusy() { return status == Status.BUSY; }
    public void setFree()   { this.status = Status.FREE; }
    public void setBusy()   { this.status = Status.BUSY; }

    // ── Getters / Setters ─────────────────────────────────────────────────────

    public int       getNumber()               { return number; }
    public String    getName()                 { return name; }
    public ArenaType getType()                 { return type; }
    public Location  getCorner1()              { return corner1; }
    public Location  getCorner2()              { return corner2; }
    public Location  getPos1()                 { return pos1; }
    public Location  getPos2()                 { return pos2; }
    public Location  getCentre()               { return centre; }
    public Status    getStatus()               { return status; }

    public void setName(String n)              { this.name = n == null ? "" : n.toLowerCase(); }
    public void setType(ArenaType t)           { this.type = t; }
    public void setCorner1(Location l)         { this.corner1 = l; if (l != null) this.rawCorner1 = serializeLoc(l); }
    public void setCorner2(Location l)         { this.corner2 = l; if (l != null) this.rawCorner2 = serializeLoc(l); }
    public void setPos1(Location l)            { this.pos1 = l; if (l != null) this.rawPos1 = serializeLoc(l); }
    public void setPos2(Location l)            { this.pos2 = l; if (l != null) this.rawPos2 = serializeLoc(l); }
    public void setCentre(Location l)          { this.centre = l; if (l != null) this.rawCentre = serializeLoc(l); }

    /** Serializes location to string immediately while the World reference is still valid. */
    private static String serializeLoc(Location l) {
        if (l.getWorld() == null) return null;
        return l.getWorld().getName() + "," + l.getX() + "," + l.getY() + "," + l.getZ()
                + "," + l.getYaw() + "," + l.getPitch();
    }

    // Raw string getters/setters — used to preserve persisted data when world is not loaded
    public String getRawCorner1()              { return rawCorner1; }
    public String getRawCorner2()              { return rawCorner2; }
    public String getRawPos1()                 { return rawPos1; }
    public String getRawPos2()                 { return rawPos2; }
    public String getRawCentre()               { return rawCentre; }
    public void setRawCorner1(String s)        { this.rawCorner1 = s; }
    public void setRawCorner2(String s)        { this.rawCorner2 = s; }
    public void setRawPos1(String s)           { this.rawPos1 = s; }
    public void setRawPos2(String s)           { this.rawPos2 = s; }
    public void setRawCentre(String s)         { this.rawCentre = s; }

    public boolean isVanilla() { return type == ArenaType.VANILLA; }
}
