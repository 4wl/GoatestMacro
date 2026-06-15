package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;

public final class LagDetector {
    private static final long LAG_TICK_MS = 350L;
    private static final long LAG_HOLD_MS = 1200L;
    private static long lastTickMillis = 0L;
    private static long lagUntilMillis = 0L;
    private static long maxTickMillis = 0L;

    private LagDetector() {}

    public static void update(MinecraftClient client) {
        long now = System.currentTimeMillis();
        if (client == null || client.player == null || client.world == null) {
            reset();
            lastTickMillis = now;
            return;
        }

        if (lastTickMillis != 0L) {
            long delta = now - lastTickMillis;
            maxTickMillis = Math.max(maxTickMillis, delta);
            if (delta >= LAG_TICK_MS) {
                lagUntilMillis = now + LAG_HOLD_MS;
            }
        }
        lastTickMillis = now;
    }

    public static boolean isLagging() {
        return System.currentTimeMillis() < lagUntilMillis;
    }

    public static long getMaxTickMillis() {
        return maxTickMillis;
    }

    public static void reset() {
        lagUntilMillis = 0L;
        maxTickMillis = 0L;
    }
}
