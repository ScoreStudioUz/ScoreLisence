package ru.score.hwauction.util;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class MoneyUtil {

    private static final DecimalFormatSymbols SYMBOLS;
    private static final DecimalFormat FORMAT;
    private static String CURRENCY_SYMBOL = "α";

    static {
        SYMBOLS = new DecimalFormatSymbols(Locale.ROOT);
        SYMBOLS.setGroupingSeparator(' ');
        SYMBOLS.setDecimalSeparator('.');
        FORMAT = new DecimalFormat("#,##0.##", SYMBOLS);
    }

    private MoneyUtil() {}

    public static void setCurrencySymbol(String symbol) {
        CURRENCY_SYMBOL = symbol;
    }

    public static String format(double amount) {
        return FORMAT.format(amount) + CURRENCY_SYMBOL;
    }

    public static String formatRaw(double amount) {
        return FORMAT.format(amount);
    }

    /** Parse "1 000 000", "1000000", "1k", "1kk", "1m" safely */
    public static double parse(String s) {
        if (s == null || s.isBlank()) return -1;
        String cleaned = s.replace(" ", "").replace(",", ".").toLowerCase();
        double multiplier = 1;
        if (cleaned.endsWith("kk")) {
            multiplier = 1_000_000;
            cleaned = cleaned.substring(0, cleaned.length() - 2);
        } else if (cleaned.endsWith("m")) {
            multiplier = 1_000_000;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        } else if (cleaned.endsWith("k")) {
            multiplier = 1_000;
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        try {
            return Double.parseDouble(cleaned) * multiplier;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
