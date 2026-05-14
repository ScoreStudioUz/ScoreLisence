package ru.score.hwauction.util;

public final class MathUtil {

    private MathUtil() {}

    public static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public static boolean isPositive(double v) { return v > 0; }

    public static boolean inRange(double v, double min, double max) {
        return v >= min && v <= max;
    }
}
