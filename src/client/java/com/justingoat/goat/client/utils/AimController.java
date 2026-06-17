package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class AimController {
    private final RotationUtils rotation;

    public AimController() {
        this(new RotationUtils());
    }

    public AimController(RotationUtils rotation) {
        this.rotation = rotation;
    }

    public RotationUtils rotation() {
        return rotation;
    }

    public void initIfNeeded(MinecraftClient client) {
        if (client == null || client.player == null || rotation.isActive()) return;
        rotation.init(client.player.getYaw(), client.player.getPitch());
    }

    public void aimAtEntity(MinecraftClient client, Entity target, float speed) {
        if (client == null || client.player == null || target == null) return;
        aimAt(client, target.getBoundingBox().getCenter(), speed);
    }

    public void aimAtBlockCenter(MinecraftClient client, BlockPos pos, float speed) {
        if (pos == null) return;
        aimAt(client, Vec3d.ofCenter(pos), speed);
    }

    public void aimAt(MinecraftClient client, Vec3d target, float speed) {
        if (client == null || client.player == null || target == null) return;
        initIfNeeded(client);
        Vec3d eye = client.player.getEyePos();
        float[] look = RotationUtils.lookAt(eye.x, eye.y, eye.z, target.x, target.y, target.z);
        rotation.setTarget(look[0], look[1]);
        rotation.setSpeed(speed);
        rotation.tick();
    }

    public void apply(MinecraftClient client) {
        if (client == null || client.player == null) return;
        client.player.setYaw(rotation.getCurrentYaw());
        client.player.setPitch(rotation.getCurrentPitch());
    }

    public void applyAndClear(MinecraftClient client) {
        if (rotation.isActive()) apply(client);
        clear();
    }

    public void aimAtAndApply(MinecraftClient client, Vec3d target, float speed) {
        aimAt(client, target, speed);
        apply(client);
    }

    public boolean isRoughlyFacing() {
        return rotation.isRoughlyFacing();
    }

    public void clear() {
        rotation.clear();
    }
}
