package com.justingoat.goat.client.utils;

import java.util.regex.Pattern;

public class StringUtils {
    private static final Pattern COLOR_CODE_PATTERN = Pattern.compile("§[0-9a-fk-or]");

    public static String stripColor(String text) {
        if (text == null) return null;
        return COLOR_CODE_PATTERN.matcher(text).replaceAll("");
    }
}
