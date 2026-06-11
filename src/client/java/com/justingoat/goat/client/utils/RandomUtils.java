package com.justingoat.goat.client.utils;

import java.util.List;
import java.util.Random;

public class RandomUtils {
    private static final Random RANDOM = new Random();

    public static int randomInt(int min, int max) {
        return RANDOM.nextInt((max - min) + 1) + min;
    }

    public static double randomDouble(double min, double max) {
        return min + (max - min) * RANDOM.nextDouble();
    }

    public static boolean randomBoolean() {
        return RANDOM.nextBoolean();
    }

    public static <T> T randomElement(T[] array) {
        if (array == null || array.length == 0) return null;
        return array[RANDOM.nextInt(array.length)];
    }

    public static <T> T randomElement(List<T> list) {
        if (list == null || list.isEmpty()) return null;
        return list.get(RANDOM.nextInt(list.size()));
    }
}
