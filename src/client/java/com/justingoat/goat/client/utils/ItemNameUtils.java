package com.justingoat.goat.client.utils;

import net.minecraft.item.ItemStack;
import net.minecraft.util.Formatting;

import java.util.Locale;

public final class ItemNameUtils {
    private ItemNameUtils() {
    }

    public static String getName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "";
        return stack.getName().getString();
    }

    public static String getStrippedName(ItemStack stack) {
        return strip(getName(stack));
    }

    public static String getLowerName(ItemStack stack) {
        return getStrippedName(stack).toLowerCase(Locale.ROOT);
    }

    public static String strip(String text) {
        if (text == null) return "";
        String stripped = Formatting.strip(text);
        if (stripped == null) stripped = StringUtils.stripColor(text);
        return stripped == null ? "" : stripped;
    }

    public static boolean contains(ItemStack stack, String needle) {
        return contains(getStrippedName(stack), needle);
    }

    public static boolean contains(String haystack, String needle) {
        if (haystack == null || needle == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    public static boolean containsAny(ItemStack stack, String... needles) {
        return containsAny(getStrippedName(stack), needles);
    }

    public static boolean containsAny(String haystack, String... needles) {
        if (haystack == null || needles == null) return false;
        for (String needle : needles) {
            if (contains(haystack, needle)) return true;
        }
        return false;
    }
}
