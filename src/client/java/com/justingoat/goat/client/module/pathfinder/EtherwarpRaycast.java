package com.justingoat.goat.client.module.pathfinder;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

public class EtherwarpRaycast {

    public static final double MAX_RANGE = 61.0;
    public static final double EYE_HEIGHT = 1.62;

    public static BlockPos findLanding(ClientWorld world, double eyeX, double eyeY, double eyeZ,
                                       float yaw, float pitch) {
        Vec3d dir = lookDirection(yaw, pitch);

        BlockPos lastChecked = null;
        for (double d = 0.5; d <= MAX_RANGE; d += 0.5) {
            int bx = MathHelper.floor(eyeX + dir.x * d);
            int by = MathHelper.floor(eyeY + dir.y * d);
            int bz = MathHelper.floor(eyeZ + dir.z * d);

            BlockPos pos = new BlockPos(bx, by, bz);
            if (pos.equals(lastChecked)) continue;
            lastChecked = pos;

            if (!isChunkLoaded(world, pos)) return null;

            BlockState state = world.getBlockState(pos);
            if (!state.getCollisionShape(world, pos).isEmpty()) {
                if (isValidLanding(world, pos)) return pos;
                return null;
            }
        }
        return null;
    }

    public static float[] findPreciseAngle(ClientWorld world, double eyeX, double eyeY, double eyeZ,
                                            BlockPos target) {
        double dx = target.getX() + 0.5 - eyeX;
        double dy = target.getY() + 0.5 - eyeY;
        double dz = target.getZ() + 0.5 - eyeZ;
        double horiz = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) -Math.toDegrees(Math.atan2(dx, dz));
        float pitch = (float) Math.toDegrees(Math.atan2(-dy, horiz));

        BlockPos hit = findLanding(world, eyeX, eyeY, eyeZ, yaw, pitch);
        if (target.equals(hit)) return new float[]{yaw, pitch};

        for (float dYaw = -3; dYaw <= 3; dYaw += 1) {
            for (float dPitch = -3; dPitch <= 3; dPitch += 1) {
                if (dYaw == 0 && dPitch == 0) continue;
                hit = findLanding(world, eyeX, eyeY, eyeZ, yaw + dYaw, pitch + dPitch);
                if (target.equals(hit)) return new float[]{yaw + dYaw, pitch + dPitch};
            }
        }
        return null;
    }

    public static boolean isValidLanding(ClientWorld world, BlockPos groundBlock) {
        if (!isChunkLoaded(world, groundBlock)) return false;
        BlockState ground = world.getBlockState(groundBlock);
        if (ground.getCollisionShape(world, groundBlock).isEmpty()) return false;

        BlockPos feet = groundBlock.up();
        BlockPos head = groundBlock.up(2);
        if (!isChunkLoaded(world, feet) || !isChunkLoaded(world, head)) return false;
        return world.getBlockState(feet).getCollisionShape(world, feet).isEmpty()
            && world.getBlockState(head).getCollisionShape(world, head).isEmpty();
    }

    static Vec3d lookDirection(float yaw, float pitch) {
        float f = pitch * 0.017453292F;
        float g = -yaw * 0.017453292F;
        return new Vec3d(
            MathHelper.sin(g) * MathHelper.cos(f),
            -MathHelper.sin(f),
            MathHelper.cos(g) * MathHelper.cos(f)
        );
    }

    static double eyeX(BlockPos ground) { return ground.getX() + 0.5; }
    static double eyeY(BlockPos ground) { return ground.getY() + 1.0 + EYE_HEIGHT; }
    static double eyeZ(BlockPos ground) { return ground.getZ() + 0.5; }

    private static boolean isChunkLoaded(ClientWorld world, BlockPos pos) {
        WorldChunk chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        return chunk != null;
    }
}
