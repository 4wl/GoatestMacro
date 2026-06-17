package com.justingoat.goat.client.module.pathfinder;

import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.PathMath;
import com.justingoat.goat.client.utils.SkyBlockUtils;
import com.justingoat.goat.client.utils.SkyBlockToolUtils;
import com.justingoat.goat.client.utils.TickTimer;
import com.justingoat.goat.client.utils.WorldUtils;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.SnowBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class PathAote {

    private static final int AOTE_RANGE = 14;
    private static final int AOTE_MIN_GAIN = 12;
    private static final float STRAIGHTNESS_THRESHOLD_DEG = 25.0f;
    private static final float MAX_AIM_YAW_ERROR = 12.0f;
    private static final float FINAL_POINT_NO_AOTE_RADIUS = 18.0f;
    private static final int MINIMUM_MANA = 100;
    private static final int MINIMUM_PATH_BLOCKS = 40;
    private static final int COOLDOWN_TICKS = 12;
    private static final int JUMP_SUPPRESS_TICKS = 5;

    private final TickTimer cooldown = new TickTimer();
    private final TickTimer jumpSuppress = new TickTimer();
    private int originalSlot = -1;
    private boolean swapped = false;
    private boolean useKeyDown = false;

    public boolean tick(MinecraftClient client, List<PathNode> path, int currentIndex, float currentYaw) {
        if (client.player == null || client.world == null || path == null || path.isEmpty()) return false;

        ClientPlayerEntity player = client.player;

        jumpSuppress.tick();

        if (useKeyDown) {
            InputUtils.setUse(false);
            useKeyDown = false;
        }

        if (cooldown.active()) {
            cooldown.tick();
            return false;
        }

        Vec3d lastCenter = nodeCenter(path.get(path.size() - 1));
        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        if (playerPos.distanceTo(lastCenter) <= FINAL_POINT_NO_AOTE_RADIUS) {
            restoreSlot(player);
            return false;
        }

        if (getTotalPathLength(path) < MINIMUM_PATH_BLOCKS) return false;

        if (Math.abs(player.getVelocity().y) > 0.25 && !player.isTouchingWater() && !player.isInLava()) {
            return false;
        }

        int slot = SkyBlockToolUtils.findAoteOrAotvSlot(player);
        if (slot < 0) return false;

        double advanceDist = getAdvanceDistance(path, currentIndex, playerPos);
        if (advanceDist < AOTE_MIN_GAIN) return false;

        if (!isPathStraightEnough(path, currentIndex)) return false;
        if (!isAimingTowardPath(player, path, currentIndex, currentYaw)) return false;
        if (estimateTeleportDistance(client, player, currentYaw) < AOTE_MIN_GAIN) return false;

        int mana = SkyBlockUtils.getMana();
        if (mana >= 0 && mana < MINIMUM_MANA) return false;

        ensureHeld(player, slot);
        InputUtils.setUse(true);
        useKeyDown = true;
        cooldown.set(COOLDOWN_TICKS);
        jumpSuppress.set(JUMP_SUPPRESS_TICKS);
        return true;
    }

    public boolean shouldSuppressJump() {
        return jumpSuppress.active();
    }

    public void stop() {
        cooldown.reset();
        jumpSuppress.reset();
        if (useKeyDown) {
            InputUtils.setUse(false);
            useKeyDown = false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) restoreSlot(client.player);
    }

    private void ensureHeld(ClientPlayerEntity player, int slot) {
        if (originalSlot < 0) {
            originalSlot = player.getInventory().getSelectedSlot();
        }
        if (player.getInventory().getSelectedSlot() != slot) {
            InputUtils.setHotbarSlot(slot);
            swapped = true;
        }
    }

    private void restoreSlot(ClientPlayerEntity player) {
        if (originalSlot >= 0 && originalSlot <= 8 && swapped) {
            InputUtils.setHotbarSlot(originalSlot);
        }
        originalSlot = -1;
        swapped = false;
    }

    private double getAdvanceDistance(List<PathNode> path, int currentIndex, Vec3d playerPos) {
        if (currentIndex >= path.size() - 1) return 0;
        double traveled = 0;
        Vec3d prev = playerPos;
        for (int i = currentIndex; i < path.size(); i++) {
            Vec3d next = nodeCenter(path.get(i));
            traveled += prev.distanceTo(next);
            if (traveled >= AOTE_RANGE) return AOTE_RANGE;
            prev = next;
        }
        return traveled;
    }

    private boolean isPathStraightEnough(List<PathNode> path, int currentIndex) {
        int endIdx = Math.min(path.size() - 1, currentIndex + AOTE_RANGE / 2);
        if (endIdx <= currentIndex) return true;

        Vec3d start = nodeCenter(path.get(currentIndex));
        Vec3d end = nodeCenter(path.get(endIdx));
        double baseX = end.x - start.x;
        double baseZ = end.z - start.z;
        double baseLen = Math.sqrt(baseX * baseX + baseZ * baseZ);
        if (baseLen < 0.001) return false;
        double bx = baseX / baseLen, bz = baseZ / baseLen;

        Vec3d prev = start;
        for (int i = currentIndex + 1; i <= endIdx; i++) {
            Vec3d cur = nodeCenter(path.get(i));
            double dx = cur.x - prev.x;
            double dz = cur.z - prev.z;
            double len = Math.sqrt(dx * dx + dz * dz);
            prev = cur;
            if (len < 0.001) continue;
            double dot = Math.max(-1, Math.min(1, (dx / len) * bx + (dz / len) * bz));
            if (Math.toDegrees(Math.acos(dot)) > STRAIGHTNESS_THRESHOLD_DEG) return false;
        }
        return true;
    }

    private boolean isAimingTowardPath(ClientPlayerEntity player, List<PathNode> path, int currentIndex, float currentYaw) {
        int idx = Math.min(path.size() - 1, currentIndex + AOTE_RANGE / 2);
        Vec3d target = nodeCenter(path.get(idx));
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        if (dx * dx + dz * dz < 0.001) return true;
        float targetYaw = WorldUtils.yawTo(
                new Vec3d(player.getX(), player.getY(), player.getZ()),
                new Vec3d(target.x, player.getY(), target.z)
        );
        return Math.abs(MathHelper.wrapDegrees(targetYaw - currentYaw)) <= MAX_AIM_YAW_ERROR;
    }

    private int estimateTeleportDistance(MinecraftClient client, ClientPlayerEntity player, float yaw) {
        Vec3d eye = player.getEyePos();
        Vec3d dir = lookDirection(yaw, player.getPitch());

        for (int d = 1; d <= AOTE_RANGE; d++) {
            BlockPos pos = BlockPos.ofFloored(eye.x + dir.x * d, eye.y + dir.y * d, eye.z + dir.z * d);
            if (!canTeleportThrough(client, pos) || !canTeleportThrough(client, pos.up())) {
                return Math.max(0, d - 1);
            }
        }
        return AOTE_RANGE;
    }

    private boolean canTeleportThrough(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir()) return true;
        if (state.getCollisionShape(client.world, pos).isEmpty()) return true;
        if (state.isOf(Blocks.SNOW)) {
            return state.get(SnowBlock.LAYERS) <= 3;
        }
        return false;
    }

    private static Vec3d lookDirection(float yaw, float pitch) {
        float f = pitch * 0.017453292F;
        float g = -yaw * 0.017453292F;
        return new Vec3d(
            MathHelper.sin(g) * MathHelper.cos(f),
            -MathHelper.sin(f),
            MathHelper.cos(g) * MathHelper.cos(f)
        );
    }

    private static double getTotalPathLength(List<PathNode> path) {
        if (path.size() < 2) return 0;
        double total = 0;
        for (int i = 1; i < path.size(); i++) {
            total += PathMath.distance(nodeCenter(path.get(i - 1)), nodeCenter(path.get(i)));
        }
        return total;
    }

    private static Vec3d nodeCenter(PathNode node) {
        BlockPos p = node.getPos();
        return new Vec3d(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5);
    }
}
