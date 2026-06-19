package uz.score.scorecheck.utils;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import uz.score.scorecheck.ScoreCheck;

public final class SoundUtil {

    private SoundUtil() {}

    public static void play(Player player, String key) {
        var cfg = ScoreCheck.getInstance().getConfig();
        if (!cfg.getBoolean("sounds.enabled", true)) return;

        String path = "sounds." + key;
        String soundName = cfg.getString(path + ".sound");
        if (soundName == null) return;

        float volume = (float) cfg.getDouble(path + ".volume", 1.0);
        float pitch  = (float) cfg.getDouble(path + ".pitch", 1.0);

        try {
            Sound sound = Sound.valueOf(soundName.toUpperCase());
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            // Invalid sound name in config — silently skip
        }
    }
}
