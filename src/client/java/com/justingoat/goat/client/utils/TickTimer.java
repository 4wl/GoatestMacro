package com.justingoat.goat.client.utils;

public final class TickTimer {
    private int ticks;

    public TickTimer() {
        this(0);
    }

    public TickTimer(int ticks) {
        this.ticks = Math.max(0, ticks);
    }

    public void set(int ticks) {
        this.ticks = Math.max(0, ticks);
    }

    public void reset() {
        ticks = 0;
    }

    public boolean active() {
        return ticks > 0;
    }

    public boolean tick() {
        if (ticks <= 0) return false;
        ticks--;
        return ticks > 0;
    }

    public int remaining() {
        return ticks;
    }
}
