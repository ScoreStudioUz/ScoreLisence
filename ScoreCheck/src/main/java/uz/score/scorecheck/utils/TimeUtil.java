package uz.score.scorecheck.utils;

import uz.score.scorecheck.ScoreCheck;
import uz.score.scorecheck.managers.ConfigManager;

public final class TimeUtil {

    private TimeUtil() {}

    /**
     * Parse "5m", "30s", "1h", "1h30m10s" → seconds.
     * Returns -1 if invalid.
     */
    public static int parseTime(String input) {
        if (input == null || input.isBlank()) return -1;
        input = input.trim().toLowerCase();
        int total = 0;
        boolean found = false;
        StringBuilder num = new StringBuilder();

        for (char c : input.toCharArray()) {
            if (Character.isDigit(c)) {
                num.append(c);
            } else {
                if (num.isEmpty()) return -1;
                int value;
                try { value = Integer.parseInt(num.toString()); }
                catch (NumberFormatException e) { return -1; }
                num.setLength(0);
                switch (c) {
                    case 'h' -> { total += value * 3600; found = true; }
                    case 'm' -> { total += value * 60;   found = true; }
                    case 's' -> { total += value;         found = true; }
                    default  -> { return -1; }
                }
            }
        }
        if (!num.isEmpty()) {
            try { total += Integer.parseInt(num.toString()); found = true; }
            catch (NumberFormatException e) { return -1; }
        }
        return found ? total : -1;
    }

    /**
     * Format seconds → "1ч 30м 10с" using config labels.
     */
    public static String format(int totalSeconds) {
        ConfigManager cfg = ScoreCheck.getInstance().getConfigManager();
        if (totalSeconds <= 0) return "0" + ColorUtil.stripColors(cfg.getSecondsLabel());

        int hours   = totalSeconds / 3600;
        int minutes = (totalSeconds % 3600) / 60;
        int seconds = totalSeconds % 60;

        String hLabel = cfg.getHoursLabel();
        String mLabel = cfg.getMinutesLabel();
        String sLabel = cfg.getSecondsLabel();

        StringBuilder sb = new StringBuilder();
        if (hours   > 0) sb.append(hours).append(hLabel).append(" ");
        if (minutes > 0) sb.append(minutes).append(mLabel).append(" ");
        if (seconds > 0 || sb.isEmpty()) sb.append(seconds).append(sLabel);
        return sb.toString().trim();
    }

    /** Format without color codes (plain text). */
    public static String formatPlain(int totalSeconds) {
        return ColorUtil.stripColors(format(totalSeconds));
    }
}
