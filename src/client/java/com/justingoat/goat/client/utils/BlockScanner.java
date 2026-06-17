package com.justingoat.goat.client.utils;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

public final class BlockScanner {
    private BlockScanner() {
    }

    public static List<BlockPos> scanCube(BlockPos center, int horizontalRadius, int down, int up) {
        List<BlockPos> positions = new ArrayList<>();
        if (center == null) return positions;
        for (int dx = -horizontalRadius; dx <= horizontalRadius; dx++) {
            for (int dy = -down; dy <= up; dy++) {
                for (int dz = -horizontalRadius; dz <= horizontalRadius; dz++) {
                    positions.add(center.add(dx, dy, dz).toImmutable());
                }
            }
        }
        return positions;
    }

    public static Optional<BlockPos> findClosest(MinecraftClient client, BlockPos center, int horizontalRadius, int down, int up,
                                                 Predicate<BlockPos> predicate) {
        if (client == null || client.player == null || predicate == null) return Optional.empty();
        Vec3d eye = client.player.getEyePos();
        return scanCube(center, horizontalRadius, down, up).stream()
            .filter(predicate)
            .min(Comparator.comparingDouble(pos -> eye.squaredDistanceTo(Vec3d.ofCenter(pos))));
    }

    public static List<BlockPos> scanSphere(BlockPos center, int radius) {
        List<BlockPos> positions = new ArrayList<>();
        if (center == null) return positions;
        int radiusSq = radius * radius;
        for (BlockPos pos : scanCube(center, radius, radius, radius)) {
            if (pos.getSquaredDistance(center) <= radiusSq) positions.add(pos);
        }
        return positions;
    }

    public static Optional<BlockPos> findClosestBlock(MinecraftClient client, BlockPos center, int horizontalRadius, int down,
                                                      int up, Predicate<BlockState> predicate) {
        if (client == null || client.world == null || predicate == null) return Optional.empty();
        return findClosest(client, center, horizontalRadius, down, up, pos -> predicate.test(client.world.getBlockState(pos)));
    }

    public static int count(MinecraftClient client, BlockPos center, int horizontalRadius, int down, int up,
                            Predicate<BlockState> predicate) {
        if (client == null || client.world == null || predicate == null) return 0;
        int count = 0;
        for (BlockPos pos : scanCube(center, horizontalRadius, down, up)) {
            if (predicate.test(client.world.getBlockState(pos))) count++;
        }
        return count;
    }

    public static boolean isSolid(ClientWorld world, BlockPos pos) {
        if (world == null || pos == null) return false;
        BlockState state = world.getBlockState(pos);
        return !state.isAir() && !state.getCollisionShape(world, pos).isEmpty();
    }

    public static boolean isPassableBody(MinecraftClient client, BlockPos feet) {
        return WorldUtils.isPassable(client, feet) && WorldUtils.isPassable(client, feet.up());
    }

    public static String registryId(MinecraftClient client, BlockPos pos) {
        if (client == null || client.world == null || pos == null) return "";
        return registryId(client.world.getBlockState(pos));
    }

    public static String registryId(BlockState state) {
        if (state == null) return "";
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        return id.toString();
    }
}
