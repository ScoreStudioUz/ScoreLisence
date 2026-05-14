package com.scorepvp.model;

import java.util.UUID;

/** Per-player, per-mode statistics. */
public class PlayerStats {

    private final UUID uuid;
    private final String mode;

    private int wins   = 0;
    private int losses = 0;
    private int kills  = 0;
    private int deaths = 0;
    private String playerName = "";

    public PlayerStats(UUID uuid, String mode) {
        this.uuid = uuid;
        this.mode = mode;
    }

    public UUID   getUuid()    { return uuid; }
    public String getMode()    { return mode; }

    public int    getWins()       { return wins; }
    public int    getLosses()     { return losses; }
    public int    getKills()      { return kills; }
    public int    getDeaths()     { return deaths; }
    public String getPlayerName() { return playerName; }

    public void addWin()           { wins++; }
    public void addLoss()          { losses++; }
    public void addKill()          { kills++; }
    public void addDeath()         { deaths++; }
    public void setWins(int w)     { this.wins = w; }
    public void setLosses(int l)   { this.losses = l; }
    public void setKills(int k)    { this.kills = k; }
    public void setDeaths(int d)   { this.deaths = d; }
    public void setPlayerName(String n){ this.playerName = n == null ? "" : n; }

    /** Returns kills / deaths ratio. Deaths = 0 → returns kills as-is. */
    public double getKdRatio() {
        if (deaths == 0) return kills;
        return Math.round((double) kills / deaths * 100.0) / 100.0;
    }
}
