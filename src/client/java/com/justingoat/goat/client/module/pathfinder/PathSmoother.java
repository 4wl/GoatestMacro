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

    private static final int MAX_SKIP = 10;

    public static List<PathNode> smooth(List<PathNode> rawPath) {
        if (rawPath == null || rawPath.size() <= 2) return rawPath;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return rawPath;

        List<PathNode> smoothed = new ArrayList<>();
        smoothed.add(rawPath.get(0));

        int current = 0;
        while (current < rawPath.size() - 1) {
            int farthest = current + 1;

            int probeLimit = Math.min(rawPath.size() - 1, current + MAX_SKIP);
            for (int probe = probeLimit; probe > current + 1; probe--) {
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

        return addCornerNodes(smoothed);
    }

    private static List<PathNode> addCornerNodes(List<PathNode> path) {
        if (path.size() <= 2) return path;

        List<PathNode> result = new ArrayList<>();
        result.add(path.get(0));

        for (int i = 1; i < path.size() - 1; i++) {
            PathNode prev = path.get(i - 1);
            PathNode cur = path.get(i);
            PathNode next = path.get(i + 1);

            if (cur.getMoveType() != PathNode.MoveType.WALK) {
                result.add(cur);
                continue;
            }

            double angle = turnAngle(prev.getPos(), cur.getPos(), next.getPos());
            if (angle > Math.PI * 0.3) {
                BlockPos cp = cur.getPos();
                BlockPos pp = prev.getPos();
                int mx = (cp.getX() + pp.getX()) / 2;
                int mz = (cp.getZ() + pp.getZ()) / 2;
                if (mx != cp.getX() || mz != cp.getZ()) {
                    BlockPos mid = new BlockPos(mx, cp.getY(), mz);
                    result.add(new PathNode(mid, PathNode.MoveType.WALK));
                }
            }

            result.add(cur);

            if (angle > Math.PI * 0.3) {
                BlockPos cp = cur.getPos();
                BlockPos np = next.getPos();
                int mx = (cp.getX() + np.getX()) / 2;
                int mz = (cp.getZ() + np.getZ()) / 2;
                if (mx != cp.getX() || mz != cp.getZ()) {
                    BlockPos mid = new BlockPos(mx, cp.getY(), mz);
                    result.add(new PathNode(mid, PathNode.MoveType.WALK));
                }
            }
        }

        result.add(path.get(path.size() - 1));
        return result;
    }

    private static double turnAngle(BlockPos prev, BlockPos cur, BlockPos next) {
        double d1x = cur.getX() - prev.getX();
        double d1z = cur.getZ() - prev.getZ();
        double d2x = next.getX() - cur.getX();
        double d2z = next.getZ() - cur.getZ();
        double len1 = Math.sqrt(d1x * d1x + d1z * d1z);
        double len2 = Math.sqrt(d2x * d2x + d2z * d2z);
        if (len1 < 0.01 || len2 < 0.01) return 0;
        double dot = d1x * d2x + d1z * d2z;
        double cosAngle = Math.max(-1.0, Math.min(1.0, dot / (len1 * len2)));
        return Math.acos(cosAngle);
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
