package com.justingoat.goat.client.utils;

public final class StateTimer<T> {
    private final MacroClock clock = new MacroClock();
    private T state;

    public StateTimer(T initialState) {
        state = initialState;
        clock.mark();
    }

    public T state() {
        return state;
    }

    public boolean is(T candidate) {
        return state == candidate || (state != null && state.equals(candidate));
    }

    public void set(T next) {
        if (is(next)) return;
        state = next;
        clock.mark();
    }

    public void force(T next) {
        state = next;
        clock.mark();
    }

    public long elapsed() {
        return clock.elapsed();
    }

    public boolean elapsedAtLeast(long millis) {
        return clock.timeout(millis);
    }
}
