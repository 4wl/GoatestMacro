package com.justingoat.goat.client.utils;

public final class ConditionTimer {
    private final MacroClock clock = new MacroClock();
    private boolean active;

    public void reset() {
        active = false;
        clock.resetReady();
    }

    public boolean active() {
        return active;
    }

    public boolean confirmed(long millis) {
        if (!active) {
            active = true;
            clock.mark();
            return false;
        }
        return clock.ready(millis);
    }
}
