package com.justingoat.goat.client.module.pathfinder;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

public class PathSmoother {

    private static final int MAX_SKIP = 10;

    public static List<PathNode> smooth(List<PathNode> rawPath) {
        return smooth(rawPath, false);
    }

    public static List<PathNode> smooth(List<PathNode> rawPath, boolean allowWater) {
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

                if (hasLineOfWalk(client, from.getPos(), to.getPos(), allowWater)) {
                    farthest = probe;
                    break;
                }
            }

            smoothed.add(rawPath.get(farthest));
            current = farthest;
        }

        return addCornerNodes(client, smoothed, allowWater);
    }

    private static List<PathNode> addCornerNodes(MinecraftClient client, List<PathNode> path, boolean allowWater) {
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
                BlockPos mid = new BlockPos(mx, cp.getY(), mz);
                if (canInsertCornerNode(client, prev.getPos(), mid, cur.getPos(), allowWater)) {
                    result.add(new PathNode(mid, PathNode.MoveType.WALK));
                }
            }

            result.add(cur);

            if (angle > Math.PI * 0.3) {
                BlockPos cp = cur.getPos();
                BlockPos np = next.getPos();
                int mx = (cp.getX() + np.getX()) / 2;
                int mz = (cp.getZ() + np.getZ()) / 2;
                BlockPos mid = new BlockPos(mx, cp.getY(), mz);
                if (canInsertCornerNode(client, cur.getPos(), mid, next.getPos(), allowWater)) {
                    result.add(new PathNode(mid, PathNode.MoveType.WALK));
                }
            }
        }

        result.add(path.get(path.size() - 1));
        return result;
    }

    private static boolean canInsertCornerNode(MinecraftClient client, BlockPos from, BlockPos mid, BlockPos to, boolean allowWater) {
        if (mid.equals(from) || mid.equals(to)) return false;
        if (from.getY() != mid.getY() || to.getY() != mid.getY()) return false;
        if (!isChunkLoaded(client, mid) || !isChunkLoaded(client, mid.up()) || !isChunkLoaded(client, mid.up(2))) return false;
        if (!isStandableGround(client, mid)) return false;
        if (!isPassable(client, mid.up()) || !isPassable(client, mid.up(2))) return false;
        if (isLava(client, mid.up())) return false;
        if (!allowWater && isWater(client, mid.up())) return false;
        return hasLineOfWalk(client, from, mid, allowWater) && hasLineOfWalk(client, mid, to, allowWater);
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

    private static boolean hasLineOfWalk(MinecraftClient client, BlockPos from, BlockPos to, boolean allowWater) {
        Vec3d a = new Vec3d(from.getX() + 0.5, from.getY(), from.getZ() + 0.5);
        Vec3d b = new Vec3d(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return true;

        double ox = -dz / len * 0.3;
        double oz = dx / len * 0.3;
        return hasWalkRay(client, a, b, 0.0, 0.0, allowWater)
            && hasWalkRay(client, a, b, ox, oz, allowWater)
            && hasWalkRay(client, a, b, -ox, -oz, allowWater);
    }

    private static boolean hasWalkRay(MinecraftClient client, Vec3d from, Vec3d to,
                                      double offsetX, double offsetZ, boolean allowWater) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        for (double d = 0.25; d < dist; d += 0.25) {
            double t = d / dist;
            int x = (int) Math.floor(from.x + dx * t + offsetX);
            int z = (int) Math.floor(from.z + dz * t + offsetZ);
            int y = (int) Math.floor(from.y);

            BlockPos ground = new BlockPos(x, y, z);
            BlockPos feet = ground.up();
            BlockPos head = ground.up(2);

            if (!isChunkLoaded(client, ground) || !isChunkLoaded(client, feet) || !isChunkLoaded(client, head)) return false;
            if (!isStandableGround(client, ground)) return false;
            if (!isPassable(client, feet)) return false;
            if (!isPassable(client, head)) return false;
            if (isLava(client, feet)) return false;
            if (!allowWater && isWater(client, feet)) return false;
        }
        return true;
    }

    private static boolean isChunkLoaded(MinecraftClient c, BlockPos pos) {
        if (c.world == null) return false;
        WorldChunk chunk = c.world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null;
    }

    private static boolean isSolid(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(c.world, pos);
        return !shape.isEmpty();
    }

    private static boolean isStandableGround(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(c.world, pos);
        return !shape.isEmpty() && shape.getMax(Direction.Axis.Y) <= 1.0;
    }

    private static boolean isPassable(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        VoxelShape shape = state.getCollisionShape(c.world, pos);
        return shape.isEmpty();
    }

    private static boolean isWater(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        return state.getFluidState().isIn(FluidTags.WATER);
    }

    private static boolean isLava(MinecraftClient c, BlockPos pos) {
        BlockState state = c.world.getBlockState(pos);
        return state.getFluidState().isIn(FluidTags.LAVA);
    }
}
