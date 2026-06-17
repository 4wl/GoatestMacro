package com.justingoat.goat.client.utils;

import java.util.Locale;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SkyBlockTextUtils {
    private static final Pattern FIRST_INTEGER = Pattern.compile("([\\d,]+)");
    private static final Pattern FIRST_NUMBER = Pattern.compile("([\\d,]+(?:\\.\\d+)?)");

    private SkyBlockTextUtils() {
    }

    public static String strip(String text) {
        return ItemNameUtils.strip(text).trim();
    }

    public static String stripColor(String text) {
        return text == null ? "" : ItemNameUtils.strip(text);
    }

    public static String normalize(String text) {
        return strip(text).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    public static boolean containsNormalized(String text, String needle) {
        return normalize(text).contains(normalize(needle));
    }

    public static boolean hasLetters(String text) {
        return strip(text).matches(".*[a-zA-Z].*");
    }

    public static OptionalInt firstInteger(String text) {
        Matcher matcher = FIRST_INTEGER.matcher(strip(text));
        if (!matcher.find()) return OptionalInt.empty();
        try {
            return OptionalInt.of(Integer.parseInt(matcher.group(1).replace(",", "")));
        } catch (NumberFormatException ignored) {
            return OptionalInt.empty();
        }
    }

    public static int parseAmount(String text, int fallback) {
        return Math.max(1, firstInteger(text).orElse(fallback));
    }

    public static double parseProgressOrDone(String text) {
        String stripped = strip(text);
        if ("DONE".equalsIgnoreCase(stripped)) return 1.0;
        Matcher matcher = FIRST_NUMBER.matcher(stripped);
        if (!matcher.find()) return 0.0;
        try {
            return Double.parseDouble(matcher.group(1).replace(",", "")) / 100.0;
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    public static double extractCoinCost(List<String> lines) {
        double best = 0.0;
        if (lines == null) return best;
        for (String line : lines) {
            String lower = strip(line).toLowerCase(Locale.ROOT);
            if (!lower.contains("coin") && !lower.contains("cost") && !lower.contains("price")) continue;
            Matcher matcher = FIRST_NUMBER.matcher(line);
            while (matcher.find()) {
                try {
                    best = Math.max(best, Double.parseDouble(matcher.group(1).replace(",", "")));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return best;
    }
}
