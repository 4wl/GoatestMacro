package com.justingoat.goat.client.module.failsafe;

import net.minecraft.client.MinecraftClient;

public abstract class Failsafe {
    protected final MinecraftClient client = MinecraftClient.getInstance();

    public abstract int getPriority();
    public abstract String getName();

    public void onTick() {}
    public void reset() {}
}
