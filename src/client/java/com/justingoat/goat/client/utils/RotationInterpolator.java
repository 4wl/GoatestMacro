package com.justingoat.goat.client.utils;

import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;

/**
 * Applies per-render-frame interpolation of RotationUtils snapshots.
 * Yaw/pitch update at the monitor's refresh rate instead of 20 TPS.
 */
public class RotationInterpolator {

    private static RotationUtils activeRotation = null;

    public static void register() {
        WorldRenderEvents.BEFORE_ENTITIES.register(RotationInterpolator::onRender);
    }

    public static void setActive(RotationUtils rotation) {
        activeRotation = rotation;
    }

    public static void clearActive() {
        activeRotation = null;
    }

    private static void onRender(WorldRenderContext context) {
        if (activeRotation == null || !activeRotation.isActive()) return;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        float partialTick = client.getRenderTickCounter().getTickProgress(false);

        float[] interpolated = activeRotation.interpolate(partialTick);
        if (interpolated != null) {
            client.player.setYaw(interpolated[0]);
            client.player.setPitch(interpolated[1]);
        }
    }
}
