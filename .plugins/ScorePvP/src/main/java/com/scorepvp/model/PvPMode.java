package com.scorepvp.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;

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
    /** Per-mode tier → color spec overrides (tier-colors in modes.yml). Empty = use global. */
    private final Map<String, String> tierColors;
    /** Saturation each fighter starts a round with (0..20). Controls passive heal-back. */
    private final float startSaturation;

    public PvPMode(String id, String displayName, Material icon, boolean iconEnchanted,
                   boolean enabled, String kitType,
                   List<String> allowedArenas, int defaultRounds,
                   int countdownSeconds, String minClientVersion,
                   Map<String, String> tierColors, float startSaturation) {
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
        this.tierColors       = tierColors == null ? Map.of() : tierColors;
        this.startSaturation  = startSaturation;
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

    /** Per-mode color spec for a tier, or null if this mode has no override. */
    public String  getTierColor(String tier)   { return tierColors.get(tier); }
    public Map<String, String> getTierColors() { return tierColors; }
    public float   getStartSaturation()        { return startSaturation; }
}
