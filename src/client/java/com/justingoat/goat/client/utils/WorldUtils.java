package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public final class WorldUtils {
    private WorldUtils() {
    }

    public static Vec3d playerPos(MinecraftClient client) {
        if (client == null || client.player == null) return Vec3d.ZERO;
        return new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
    }

    public static boolean isPassable(MinecraftClient client, BlockPos pos) {
        if (client == null || client.world == null || pos == null) return true;
        return client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty();
    }

    public static float yawTo(Vec3d from, Vec3d to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
    }

    public static float yawToPoint(MinecraftClient client, double x, double z) {
        if (client == null || client.player == null) return 0.0f;
        return yawTo(playerPos(client), new Vec3d(x, client.player.getY(), z));
    }

    public static float yawToBlockCenter(MinecraftClient client, BlockPos pos) {
        return yawToPoint(client, pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    public static boolean isAtPoint(MinecraftClient client, BlockPos point, double minDist) {
        if (client == null || client.player == null || point == null) return false;
        return playerPos(client).distanceTo(new Vec3d(point.getX() + 0.5, point.getY(), point.getZ() + 0.5)) < minDist;
    }

    public static boolean isHorizontallyAtPoint(MinecraftClient client, BlockPos point, double minDist) {
        if (client == null || client.player == null || point == null) return false;
        double dx = client.player.getX() - (point.getX() + 0.5);
        double dz = client.player.getZ() - (point.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz) < minDist;
    }

    public static double distance(ClientPlayerEntity player, int[] pos) {
        if (player == null || pos == null || pos.length < 3) return Double.MAX_VALUE;
        double dx = player.getX() - pos[0];
        double dy = player.getY() - pos[1];
        double dz = player.getZ() - pos[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
