package com.justingoat.goat.client.module.failsafe;

import net.minecraft.client.MinecraftClient;

public abstract class Failsafe {
    protected final MinecraftClient client = MinecraftClient.getInstance();
    private boolean enabled = true;

    public abstract int getPriority();
    public abstract String getName();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public void onTick() {}
    public void reset() {}
}
