package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public final class PathMath {
    private PathMath() {
    }

    public static double pathLength(List<Vec3d> points) {
        if (points == null || points.size() < 2) return 0.0;
        double total = 0.0;
        for (int i = 1; i < points.size(); i++) {
            total += points.get(i - 1).distanceTo(points.get(i));
        }
        return total;
    }

    public static double horizontalDistance(Vec3d a, Vec3d b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static double distance(Vec3d a, Vec3d b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        return a.distanceTo(b);
    }

    public static boolean reached(MinecraftClient client, Vec3d target, double distSq) {
        if (client == null || client.player == null || target == null) return false;
        return WorldUtils.playerPos(client).squaredDistanceTo(target) <= distSq;
    }

    public static Vec3d blockCenterFeet(BlockPos pos) {
        return new Vec3d(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
