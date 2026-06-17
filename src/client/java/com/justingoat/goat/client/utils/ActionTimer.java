package com.justingoat.goat.client.utils;

public final class ActionTimer {
    private long lastActionAt = 0L;

    public boolean ready(long delayMs) {
        return System.currentTimeMillis() - lastActionAt >= delayMs;
    }

    public void markNow() {
        lastActionAt = System.currentTimeMillis();
    }

    public void delayFromNow(long extraMs) {
        lastActionAt = System.currentTimeMillis() + Math.max(0L, extraMs);
    }

    public void resetReady() {
        lastActionAt = 0L;
    }

    public long getLastActionAt() {
        return lastActionAt;
    }
}
