package com.scorepvp.model;

/** One row in a leaderboard query result. */
public class TopEntry {
    private final String playerName;
    private final double value;

    public TopEntry(String playerName, double value) {
        this.playerName = playerName;
        this.value      = value;
    }

    public String getPlayerName() { return playerName; }
    public double getValue()      { return value; }

    /** Returns value as an int string if it has no fractional part, else 2dp. */
    public String getValueDisplay() {
        if (value == Math.floor(value)) return String.valueOf((long) value);
        return String.format("%.2f", value);
    }
}
