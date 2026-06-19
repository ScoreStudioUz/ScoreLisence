package uz.score.scorecheck.managers;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.file.FileConfiguration;
import uz.score.scorecheck.ScoreCheck;
import uz.score.scorecheck.utils.ColorUtil;

import java.util.List;

public class ConfigManager {

    private final ScoreCheck plugin;
    private FileConfiguration config;

    public ConfigManager(ScoreCheck plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfig();
    }

    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    // ---- Locations ----
    public Location getTeleportLocation() {
        return readLocation("teleport-location");
    }

    public void setTeleportLocation(Location loc) {
        writeLocation("teleport-location", loc);
    }

    public boolean isReturnLocationEnabled() {
        return config.getBoolean("return-location.enabled", false);
    }

    public Location getReturnLocation() {
        if (!isReturnLocationEnabled()) return null;
        return readLocation("return-location");
    }

    private Location readLocation(String path) {
        String worldName = config.getString(path + ".world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) world = Bukkit.getWorlds().get(0);
        double x     = config.getDouble(path + ".x", 0.5);
        double y     = config.getDouble(path + ".y", 64.0);
        double z     = config.getDouble(path + ".z", 0.5);
        float  yaw   = (float) config.getDouble(path + ".yaw", 0.0);
        float  pitch = (float) config.getDouble(path + ".pitch", 0.0);
        return new Location(world, x, y, z, yaw, pitch);
    }

    private void writeLocation(String path, Location loc) {
        config.set(path + ".world", loc.getWorld().getName());
        config.set(path + ".x", loc.getX());
        config.set(path + ".y", loc.getY());
        config.set(path + ".z", loc.getZ());
        config.set(path + ".yaw",   (double) loc.getYaw());
        config.set(path + ".pitch", (double) loc.getPitch());
        plugin.saveConfig();
    }

    // ---- Timer ----
    public int  getWaitingTime()      { return config.getInt("waiting-time", 300); }
    public int  getReminderInterval() { return config.getInt("reminder-interval", 15); }
    public String getHoursLabel()     { return config.getString("time-format.hours",   "&fч"); }
    public String getMinutesLabel()   { return config.getString("time-format.minutes", "&fм"); }
    public String getSecondsLabel()   { return config.getString("time-format.seconds", "&fс"); }

    // ---- Ban ----
    public String getBanCommand() {
        return config.getString("ban-command", "ban {player} Не прошёл проверку. Модератор: {moderator}");
    }

    // ---- Features ----
    public boolean isVanishEnabled()    { return config.getBoolean("vanish-on-check", true); }
    public boolean isEffectsEnabled()   { return config.getBoolean("effects.enabled", true); }
    public int     getSlownessLevel()   { return config.getInt("effects.slowness-level", 10); }
    public boolean isBlindnessEnabled() { return config.getBoolean("effects.blindness-on-escape", true); }
    public int     getBlindnessDuration(){ return config.getInt("effects.blindness-duration", 60); }

    // ---- ActionBar ----
    public boolean isActionBarEnabled() { return config.getBoolean("actionbar.enabled", true); }
    public String  getActionBarPhase1() { return config.getString("actionbar.phase1", ""); }
    public String  getActionBarPhase2() { return config.getString("actionbar.phase2", ""); }

    // ---- BossBar ----
    public String      getBossbarTitle(int phase)     { return config.getString("bossbar.phase" + phase + ".title", ""); }
    public BarColor    getBossbarColor(int phase)     {
        try { return BarColor.valueOf(config.getString("bossbar.phase" + phase + ".color", "RED").toUpperCase()); }
        catch (Exception e) { return phase == 1 ? BarColor.RED : BarColor.YELLOW; }
    }
    public BarStyle    getBossbarStyle(int phase)     {
        try { return BarStyle.valueOf(config.getString("bossbar.phase" + phase + ".style", "SOLID").toUpperCase()); }
        catch (Exception e) { return BarStyle.SOLID; }
    }
    public List<String> getBossbarAnimation(int phase) { return config.getStringList("bossbar.phase" + phase + ".animation"); }
    public int          getBossbarAnimInterval(int phase){ return config.getInt("bossbar.phase" + phase + ".animation-interval", 25); }

    // ---- Messages ----
    public String getPrefix() {
        return config.getString("messages.prefix", "&8[&cScoreCheck&8] &r");
    }

    public String getMessage(String key) {
        String raw = config.getString("messages." + key, "");
        return raw.replace("{prefix}", getPrefix());
    }

    public String applyPlaceholders(String text, String player, String moderator,
                                     String timer, String message, String phaseInfo,
                                     String count, String phase, String newTime, String time) {
        if (text == null) return "";
        return text
                .replace("{player}",     player     != null ? player     : "")
                .replace("{moderator}",  moderator  != null ? moderator  : "")
                .replace("{timer}",      timer      != null ? timer      : "")
                .replace("{message}",    message    != null ? message    : "")
                .replace("{phase_info}", phaseInfo  != null ? phaseInfo  : "")
                .replace("{count}",      count      != null ? count      : "")
                .replace("{phase}",      phase      != null ? phase      : "")
                .replace("{new_time}",   newTime    != null ? newTime    : "")
                .replace("{time}",       time       != null ? time       : "")
                .replace("{prefix}",     getPrefix());
    }
}
