package uz.score.scorecheck.managers;

import org.bukkit.Location;

import java.util.UUID;

public class CheckSession {

    public enum Phase {
        WAITING,   // Phase 1 — countdown timer
        CHECKING   // Phase 2 — infinite, moderator-controlled
    }

    private final UUID   playerUUID;
    private final UUID   moderatorUUID;
    private final String playerName;
    private final String moderatorName;
    private final Location returnLocation; // where to send player on pass

    private Phase phase;
    private int   remainingSeconds;
    private final int initialSeconds;

    public CheckSession(UUID playerUUID, UUID moderatorUUID,
                        String playerName, String moderatorName,
                        int waitingSeconds, Location returnLocation) {
        this.playerUUID      = playerUUID;
        this.moderatorUUID   = moderatorUUID;
        this.playerName      = playerName;
        this.moderatorName   = moderatorName;
        this.remainingSeconds = waitingSeconds;
        this.initialSeconds   = waitingSeconds;
        this.phase           = Phase.WAITING;
        this.returnLocation  = returnLocation;
    }

    // ---- Getters ----
    public UUID     getPlayerUUID()     { return playerUUID; }
    public UUID     getModeratorUUID()  { return moderatorUUID; }
    public String   getPlayerName()     { return playerName; }
    public String   getModeratorName()  { return moderatorName; }
    public Phase    getPhase()          { return phase; }
    public int      getRemainingSeconds(){ return remainingSeconds; }
    public int      getInitialSeconds() { return initialSeconds; }
    public Location getReturnLocation() { return returnLocation; }

    // ---- Setters ----
    public void setPhase(Phase phase)   { this.phase = phase; }

    public void setRemainingSeconds(int s) { this.remainingSeconds = Math.max(0, s); }
    public void addSeconds(int s)          { this.remainingSeconds = Math.max(0, remainingSeconds + s); }
    public void removeSeconds(int s)       { this.remainingSeconds = Math.max(0, remainingSeconds - s); }

    public void tickSecond() {
        if (phase == Phase.WAITING && remainingSeconds > 0) remainingSeconds--;
    }

    public boolean isTimedOut()       { return phase == Phase.WAITING && remainingSeconds <= 0; }
    public boolean isPhase(Phase p)   { return this.phase == p; }

    /** Progress 0.0–1.0 for boss bar (decreasing as time runs out). */
    public double getProgress() {
        if (initialSeconds <= 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, (double) remainingSeconds / initialSeconds));
    }
}
