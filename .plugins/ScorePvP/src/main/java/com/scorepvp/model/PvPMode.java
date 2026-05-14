package com.scorepvp.model;

import org.bukkit.Material;

import java.util.List;

public class PvPMode {

    private final String id;
    private final String displayName;
    private final Material icon;
    private final boolean iconEnchanted;
    private final boolean enabled;
    private final String kitType;
    private final List<String> allowedArenas;
    private final int defaultRounds;
    private final int countdownSeconds;
    private final String minClientVersion;  // "" = no restriction; "1.21.0" = 1.21+

    public PvPMode(String id, String displayName, Material icon, boolean iconEnchanted,
                   boolean enabled, String kitType,
                   List<String> allowedArenas, int defaultRounds,
                   int countdownSeconds, String minClientVersion) {
        this.id               = id;
        this.displayName      = displayName;
        this.icon             = icon;
        this.iconEnchanted    = iconEnchanted;
        this.enabled          = enabled;
        this.kitType          = kitType;
        this.allowedArenas    = allowedArenas;
        this.defaultRounds    = defaultRounds;
        this.countdownSeconds = countdownSeconds;
        this.minClientVersion = minClientVersion == null ? "" : minClientVersion;
    }

    public String  getId()                     { return id; }
    public String  getDisplayName()            { return displayName; }
    public Material getIcon()                  { return icon; }
    public boolean isIconEnchanted()           { return iconEnchanted; }
    public boolean isEnabled()                 { return enabled; }
    public String  getKitType()                { return kitType; }
    public List<String>  getAllowedArenas()    { return allowedArenas; }
    public int     getDefaultRounds()          { return defaultRounds; }
    public int     getCountdownSeconds()       { return countdownSeconds; }
    public String  getMinClientVersion()       { return minClientVersion; }
    public boolean hasVersionRestriction()     { return !minClientVersion.isEmpty(); }
}
