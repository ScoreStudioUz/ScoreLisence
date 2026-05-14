package ru.score.hwauction.util;

import ru.score.hwauction.config.Messages;

import java.util.concurrent.TimeUnit;

public final class TimeUtil {

    private static String HOURS   = "ч.";
    private static String MINUTES = "мин.";
    private static String SECONDS = "сек.";
    private static String HOURS_SHORT   = "ч";
    private static String MINUTES_SHORT = "м";
    private static String SECONDS_SHORT = "с";
    private static String ZERO    = "0сек.";
    private static String EXPIRED = "истёк";

    private TimeUtil() {}

    public static void reload(Messages msg) {
        HOURS   = msg.raw().getString("time.hours", "ч.");
        MINUTES = msg.raw().getString("time.minutes", "мин.");
        SECONDS = msg.raw().getString("time.seconds", "сек.");
        HOURS_SHORT   = msg.raw().getString("time.hours-short", "ч");
        MINUTES_SHORT = msg.raw().getString("time.minutes-short", "м");
        SECONDS_SHORT = msg.raw().getString("time.seconds-short", "с");
        ZERO    = msg.raw().getString("time.zero", "0сек.");
        EXPIRED = msg.raw().getString("time.expired", "истёк");
    }

    public static String format(long millis) {
        if (millis <= 0) return ZERO;
        long h = TimeUnit.MILLISECONDS.toHours(millis);
        long m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        long s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) sb.append(h).append(HOURS).append(" ");
        if (m > 0) sb.append(m).append(MINUTES).append(" ");
        if (s > 0 || sb.length() == 0) sb.append(s).append(SECONDS);
        return sb.toString().trim();
    }

    public static String formatShort(long millis) {
        if (millis <= 0) return EXPIRED;
        long h = TimeUnit.MILLISECONDS.toHours(millis);
        long m = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        if (h > 0) return h + HOURS_SHORT + " " + m + MINUTES_SHORT;
        long s = TimeUnit.MILLISECONDS.toSeconds(millis) % 60;
        if (m > 0) return m + MINUTES_SHORT + " " + s + SECONDS_SHORT;
        return s + SECONDS_SHORT;
    }

    public static long hoursToMillis(int hours) {
        return TimeUnit.HOURS.toMillis(hours);
    }

    public static long minutesToMillis(int minutes) {
        return TimeUnit.MINUTES.toMillis(minutes);
    }
}
