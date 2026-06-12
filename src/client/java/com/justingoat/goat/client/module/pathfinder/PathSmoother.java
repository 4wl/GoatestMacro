package com.justingoat.goat.client.module.pathfinder;

import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;

import java.util.ArrayList;
import java.util.List;

public class PathSmoother {

    public static List<PathNode> smooth(List<PathNode> rawPath) {
        if (rawPath == null || rawPath.size() <= 2) return rawPath;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return rawPath;

        List<PathNode> smoothed = new ArrayList<>();
        smoothed.add(rawPath.get(0));

        int current = 0;
        while (current < rawPath.size() - 1) {
            int farthest = current + 1;

            for (int probe = rawPath.size() - 1; probe > current + 1; probe--) {
                PathNode from = rawPath.get(current);
                PathNode to = rawPath.get(probe);

                if (to.getMoveType() != PathNode.MoveType.WALK) continue;
                if (from.getPos().getY() != to.getPos().getY()) continue;

                if (hasLineOfWalk(client, from.getPos(), to.getPos())) {
                    farthest = probe;
                    break;
                }
            }

            smoothed.add(rawPath.get(farthest));
            current = farthest;
        }

        return smoothed;
    }

    private static boolean hasLineOfWalk(MinecraftClient client, BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps == 0) return true;

        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            int x = from.getX() + (int) Math.round(dx * t);
            int z = from.getZ() + (int) Math.round(dz * t);
            int y = from.getY();

            BlockPos ground = new BlockPos(x, y, z);
            BlockPos feet = ground.up();
            BlockPos head = ground.up(2);

            if (!isSolid(client, ground)) return false;
            if (!isPassable(client, feet)) return false;
            if (!isPassable(client, head)) return false;
            if (isLiquid(client, feet)) return false;
        }

        return true;
    }

    private static boolean isSolid(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(c.world, pos);
        return !shape.isEmpty();
    }

    private static boolean isPassable(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(c.world, pos);
        return shape.isEmpty();
    }

    private static boolean isLiquid(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        return state.getBlock() instanceof FluidBlock
            || state.isOf(Blocks.WATER)
            || state.isOf(Blocks.LAVA);
    }
}
