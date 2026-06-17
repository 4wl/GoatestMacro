package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public final class AntiStuckController {
    private AntiStuckController() {
    }

    public static float chooseEscapeYaw(MinecraftClient client, Vec3d target, double checkDistance) {
        if (client == null || client.player == null) return 0.0f;
        float preferredYaw = target == null
            ? MathHelper.wrapDegrees(client.player.getYaw() + 180.0f)
            : WorldUtils.yawTo(WorldUtils.playerPos(client), target);
        return chooseEscapeYaw(client, preferredYaw, checkDistance);
    }

    public static float chooseEscapeYaw(MinecraftClient client, float preferredYaw, double checkDistance) {
        if (client == null || client.player == null) return preferredYaw;

        BlockPos intersecting = findIntersectingBlock(client);
        if (intersecting != null) {
            Direction side = findClosestClearSide(client, intersecting);
            if (side != null) {
                Vec3d escape = Vec3d.ofCenter(intersecting).add(
                    side.getOffsetX() * checkDistance,
                    0.0,
                    side.getOffsetZ() * checkDistance
                );
                return WorldUtils.yawTo(WorldUtils.playerPos(client), escape);
            }
        }

        float bestYaw = MathHelper.wrapDegrees(preferredYaw + 180.0f);
        float bestScore = Float.MAX_VALUE;
        for (int offset = 0; offset < 360; offset += 20) {
            float yaw = MathHelper.wrapDegrees(preferredYaw + offset);
            if (!isEscapeDirectionClear(client, yaw, checkDistance)) continue;

            float score = Math.abs(MathHelper.wrapDegrees(yaw - preferredYaw));
            if (score < bestScore) {
                bestScore = score;
                bestYaw = yaw;
            }
        }
        return bestYaw;
    }

    public static void applyMovement(MinecraftClient client, float desiredYaw) {
        if (client == null || client.player == null) return;
        float relYaw = MathHelper.wrapDegrees(desiredYaw - client.player.getYaw());

        InputUtils.setForward(relYaw >= -67.5f && relYaw < 67.5f);
        InputUtils.setBack(relYaw >= 112.5f || relYaw < -112.5f);
        InputUtils.setLeft(relYaw >= 22.5f && relYaw < 157.5f);
        InputUtils.setRight(relYaw >= -157.5f && relYaw < -22.5f);
    }

    public static BlockPos findIntersectingBlock(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return null;

        Box playerBox = client.player.getBoundingBox().expand(0.02, 0.0, 0.02);
        BlockPos playerBlock = client.player.getBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = playerBlock.add(dx, dy, dz);
                    if (WorldUtils.isPassable(client, pos)) continue;
                    if (!playerBox.intersects(new Box(pos))) continue;

                    double distSq = Vec3d.ofCenter(pos).squaredDistanceTo(WorldUtils.playerPos(client));
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    public static Direction findClosestClearSide(MinecraftClient client, BlockPos pos) {
        if (client == null || pos == null) return null;
        Direction best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos adjacent = pos.offset(direction);
            if (!WorldUtils.isPassable(client, adjacent) || !WorldUtils.isPassable(client, adjacent.up())) continue;

            Vec3d side = Vec3d.ofCenter(pos).add(direction.getOffsetX() * 0.5, 0.0, direction.getOffsetZ() * 0.5);
            double distSq = side.squaredDistanceTo(WorldUtils.playerPos(client));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = direction;
            }
        }
        return best;
    }

    public static boolean isEscapeDirectionClear(MinecraftClient client, float yaw, double checkDistance) {
        Vec3d pos = WorldUtils.playerPos(client);
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians) * checkDistance;
        double dz = Math.cos(radians) * checkDistance;

        BlockPos feet = BlockPos.ofFloored(pos.x + dx, pos.y + 0.1, pos.z + dz);
        BlockPos body = BlockPos.ofFloored(pos.x + dx, pos.y + 0.9, pos.z + dz);
        BlockPos head = BlockPos.ofFloored(pos.x + dx, pos.y + 1.8, pos.z + dz);
        return WorldUtils.isPassable(client, feet)
            && WorldUtils.isPassable(client, body)
            && WorldUtils.isPassable(client, head);
    }
}
