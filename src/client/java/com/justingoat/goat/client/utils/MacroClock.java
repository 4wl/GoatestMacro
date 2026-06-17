package com.justingoat.goat.client.utils;

public final class MacroClock {
    private long markedAt;

    public MacroClock() {
        mark();
    }

    public long now() {
        return System.currentTimeMillis();
    }

    public void mark() {
        markedAt = now();
    }

    public void delay(long delayMs) {
        markedAt = now() + Math.max(0L, delayMs);
    }

    public void markAt(long timestampMillis) {
        markedAt = timestampMillis;
    }

    public void markOffsetFromNow(long offsetMillis) {
        markedAt = now() + offsetMillis;
    }

    public void resetReady() {
        markedAt = 0L;
    }

    public long elapsed() {
        return now() - markedAt;
    }

    public boolean ready(long delayMs) {
        return elapsed() >= delayMs;
    }

    public boolean timeout(long timeoutMs) {
        return elapsed() >= timeoutMs;
    }
}
