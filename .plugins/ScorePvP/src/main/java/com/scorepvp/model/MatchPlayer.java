package com.scorepvp.model;

import java.util.UUID;

public class MatchPlayer {

    public enum Role { FIGHTER, SPECTATOR }

    private final UUID uuid;
    private final String name;
    private Role role;
    private int wins = 0;
    private boolean frozen = false;   // true during countdown
    private boolean dead  = false;    // true after dying in a round (spectating round end)

    public MatchPlayer(UUID uuid, String name, Role role) {
        this.uuid = uuid;
        this.name = name;
        this.role = role;
    }

    public UUID   getUuid()          { return uuid; }
    public String getName()          { return name; }
    public Role   getRole()          { return role; }
    public void   setRole(Role r)    { this.role = r; }

    public int    getWins()          { return wins; }
    public void   addWin()           { wins++; }

    public boolean isFrozen()        { return frozen; }
    public void   setFrozen(boolean b){ this.frozen = b; }

    public boolean isDead()          { return dead; }
    public void   setDead(boolean b) { this.dead = b; }

    public boolean isFighter()       { return role == Role.FIGHTER; }
    public boolean isSpectator()     { return role == Role.SPECTATOR; }
}
