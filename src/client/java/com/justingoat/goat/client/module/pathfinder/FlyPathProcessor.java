package com.justingoat.goat.client.module.pathfinder;

import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.RotationUtils;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class FlyPathProcessor {

    private enum Phase {
        IDLE,
        FLY_TAP_1,
        FLY_GAP,
        FLY_TAP_2,
        FLY_WAIT,
        NAVIGATING
    }

    private enum VerticalHint { NONE, HIGHER, LOWER }

    private List<Vec3d> path;
    private int currentIndex = 0;
    private boolean active = false;
    private boolean complete = false;
    private boolean failed = false;
    private final RotationUtils rotation = new RotationUtils();

    private Phase phase = Phase.IDLE;
    private int phaseTicks = 0;
    private int flyRetries = 0;

    private static final double ARRIVE_DIST_SQ = 4.0;
    private static final float VERTICAL_DEADZONE = 0.8f;
    private static final int MAX_FLY_RETRIES = 5;
    private static final double LOOKAHEAD_DIST = 10.0;
    private static final float AIM_DEADZONE = 1.5f;
    private static final double LOS_STEP = 0.25;

    private int stuckTicks = 0;
    private Vec3d lastPos = null;

    // Entity following
    private Entity followEntity = null;
    private float entityYAddition = 2.75f;
    private boolean rotateToEntity = false;
    private int repathCooldown = 0;
    private static final int REPATH_INTERVAL = 30;
    private static final double ENTITY_DRIFT_THRESHOLD_SQ = 25.0;

    // Render data
    private static volatile List<Vec3d> renderPath = null;
    private static volatile int renderIndex = 0;

    public static List<Vec3d> getRenderPath() { return renderPath; }
    public static int getRenderIndex() { return renderIndex; }

    // ─────────────────── API ───────────────────

    public void setPath(List<Vec3d> path) {
        this.path = path;
        this.currentIndex = 0;
        this.active = true;
        this.complete = false;
        this.failed = false;
        this.stuckTicks = 0;
        this.lastPos = null;
        this.phase = Phase.IDLE;
        this.phaseTicks = 0;
        this.flyRetries = 0;
        this.rotation.clear();
        RotationInterpolator.setActive(rotation);
        renderPath = path;
    }

    public void setFollowTarget(Entity entity, float yAdd, boolean rotate) {
        this.followEntity = entity;
        this.entityYAddition = yAdd;
        this.rotateToEntity = rotate;
        this.repathCooldown = 0;
    }

    public void clearFollowTarget() {
        this.followEntity = null;
    }

    public Entity getFollowEntity() { return followEntity; }

    public boolean isDone() { return !active || complete || path == null; }

    public boolean didFail() { return failed; }

    public List<Vec3d> getPath() { return path; }

    public RotationUtils getRotation() { return rotation; }

    public void stop() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (rotation.isActive() && client.player != null) {
            client.player.setYaw(rotation.getCurrentYaw());
            client.player.setPitch(rotation.getCurrentPitch());
        }
        this.path = null;
        this.active = false;
        this.failed = false;
        this.phase = Phase.IDLE;
        this.followEntity = null;
        this.rotation.clear();
        RotationInterpolator.clearActive();
        InputUtils.releaseAll();
        renderPath = null;
    }

    // ─────────────────── Main tick ───────────────────

    public void tick(MinecraftClient client, float rotSpeed) {
        if (isDone() || client.player == null) return;

        ClientPlayerEntity player = client.player;

        if (!rotation.isActive()) {
            rotation.init(player.getYaw(), player.getPitch());
        }

        if (!player.getAbilities().flying) {
            if (!player.getAbilities().allowFlying) {
                fail();
                return;
            }
            tickFlyActivation(player);
            return;
        }

        if (phase != Phase.NAVIGATING) {
            phase = Phase.NAVIGATING;
            phaseTicks = 0;
            flyRetries = 0;
        }

        tickEntityFollow(client, player);
        tickNavigate(client, player, rotSpeed);
    }

    // ─────────────────── Flight activation ───────────────────

    private void tickFlyActivation(ClientPlayerEntity player) {
        switch (phase) {
            case IDLE, NAVIGATING -> {
                flyRetries++;
                if (flyRetries > MAX_FLY_RETRIES) {
                    finish();
                    return;
                }
                phase = Phase.FLY_TAP_1;
                phaseTicks = 0;
                InputUtils.setJump(true);
            }
            case FLY_TAP_1 -> {
                phase = Phase.FLY_GAP;
                phaseTicks = 0;
                InputUtils.setJump(false);
            }
            case FLY_GAP -> {
                phaseTicks++;
                InputUtils.setJump(false);
                if (phaseTicks >= 2) {
                    phase = Phase.FLY_TAP_2;
                    phaseTicks = 0;
                }
            }
            case FLY_TAP_2 -> {
                InputUtils.setJump(true);
                phase = Phase.FLY_WAIT;
                phaseTicks = 0;
            }
            case FLY_WAIT -> {
                phaseTicks++;
                InputUtils.setJump(false);
                if (phaseTicks >= 5) {
                    phase = Phase.IDLE;
                    phaseTicks = 0;
                }
            }
        }
    }

    // ─────────────────── Entity follow ───────────────────

    private void tickEntityFollow(MinecraftClient client, ClientPlayerEntity player) {
        if (followEntity == null) return;
        if (followEntity.isRemoved()) {
            followEntity = null;
            return;
        }

        repathCooldown--;
        if (repathCooldown > 0) return;

        Vec3d entityTarget = predictEntityPosition(followEntity, entityYAddition);
        Vec3d pathEnd = path != null && !path.isEmpty() ? path.get(path.size() - 1) : null;

        if (pathEnd != null) {
            double driftSq = pathEnd.squaredDistanceTo(entityTarget);
            if (driftSq < ENTITY_DRIFT_THRESHOLD_SQ) return;
        }

        repathCooldown = REPATH_INTERVAL;
        BlockPos start = player.getBlockPos();
        BlockPos end = BlockPos.ofFloored(entityTarget);

        CompletableFuture.supplyAsync(() -> computeFlyPath(client, start, end, 30000))
            .thenAccept(newPath -> client.execute(() -> {
                if (newPath != null && !newPath.isEmpty()) {
                    setPath(newPath);
                    if (followEntity != null) {
                        RotationInterpolator.setActive(rotation);
                    }
                }
            }));
    }

    // ─────────────────── Navigation ───────────────────

    private void tickNavigate(MinecraftClient client, ClientPlayerEntity player, float rotSpeed) {
        double px = player.getX(), py = player.getY(), pz = player.getZ();
        renderIndex = currentIndex;

        Vec3d finalTarget = path.get(path.size() - 1);
        double hDistToFinal = Math.sqrt(hDistSq(px, pz, finalTarget.x, finalTarget.z));
        double vDistToFinal = Math.abs(py - finalTarget.y);

        if (hDistToFinal < 1.5 && vDistToFinal < 2.0) {
            if (followEntity == null) {
                finish();
            } else {
                InputUtils.releaseAll();
            }
            return;
        }

        // Stuck detection
        if (lastPos != null) {
            double moved = lastPos.squaredDistanceTo(px, py, pz);
            stuckTicks = moved < 0.005 ? stuckTicks + 1 : 0;
        }
        lastPos = new Vec3d(px, py, pz);
        if (stuckTicks > 80) {
            fail();
            return;
        }

        // Advance past reached waypoints
        while (currentIndex < path.size() - 1) {
            Vec3d wp = path.get(currentIndex);
            if (distSq(px, py, pz, wp) < ARRIVE_DIST_SQ) {
                currentIndex++;
            } else {
                break;
            }
        }

        boolean colliding = player.horizontalCollision;
        VerticalHint vHint = shouldChangeHeight(client, player);

        // Distance-based lookahead: find a point ~LOOKAHEAD_DIST blocks ahead on the path
        Vec3d moveTarget = getDistanceLookahead(px, py, pz);

        // Rotation — if following entity and close, rotate toward entity
        float desiredYaw;
        float desiredPitch = 0.0f;
        if (rotateToEntity && followEntity != null) {
            Vec3d entityCenter = new Vec3d(followEntity.getX(), followEntity.getY() + followEntity.getHeight() * 0.5, followEntity.getZ());
            float[] look = RotationUtils.lookAt(px, player.getEyeY(), pz,
                    entityCenter.x, entityCenter.y, entityCenter.z);
            desiredYaw = look[0];
            desiredPitch = look[1];
        } else {
            desiredYaw = calcYaw(px, pz, moveTarget.x, moveTarget.z);
        }

        // Only update rotation target if angle change exceeds deadzone
        float currentYaw = rotation.getCurrentYaw();
        float yawDelta = Math.abs(MathHelper.wrapDegrees(desiredYaw - currentYaw));
        if (yawDelta > AIM_DEADZONE || !rotation.isActive()) {
            rotation.setTarget(desiredYaw, desiredPitch);
        }
        rotation.setSpeed(rotSpeed);
        rotation.tick();

        currentYaw = rotation.getCurrentYaw();

        // Movement yaw always faces path direction (not entity)
        float moveYaw = calcYaw(px, pz, moveTarget.x, moveTarget.z);
        float angleToPath = Math.abs(MathHelper.wrapDegrees(moveYaw - currentYaw));

        // Deceleration near final target
        if (hDistToFinal < 4.0 && followEntity == null) {
            InputUtils.setSprint(false);
            InputUtils.setForward(hDistToFinal > 1.0);
            double yErr = finalTarget.y - py;
            InputUtils.setJump(yErr > VERTICAL_DEADZONE);
            InputUtils.setSneak(yErr < -VERTICAL_DEADZONE);
            return;
        }

        // Predict deceleration distance from current velocity
        double speed = player.getVelocity().horizontalLength();
        double decelDist = speed * speed / (2.0 * 0.09);
        boolean shouldDecel = hDistToFinal < decelDist + 2.0 && followEntity == null;

        // Horizontal movement
        if (rotateToEntity && followEntity != null) {
            // When rotating to entity, use key mapping based on path direction relative to look
            applyDirectionalKeys(moveYaw, currentYaw, colliding, shouldDecel);
        } else if (angleToPath > 60.0f) {
            InputUtils.setForward(false);
            InputUtils.setSprint(false);
        } else {
            InputUtils.setForward(true);
            InputUtils.setSprint(!colliding && !shouldDecel);
            applyStrafeCorrection(moveYaw, currentYaw);
        }

        // Vertical movement with obstacle avoidance
        double yTarget = path.get(Math.min(currentIndex + 1, path.size() - 1)).y;
        double yErr = yTarget - py;

        if (colliding || vHint == VerticalHint.HIGHER) {
            InputUtils.setJump(true);
            InputUtils.setSneak(false);
        } else if (vHint == VerticalHint.LOWER) {
            InputUtils.setJump(false);
            InputUtils.setSneak(true);
        } else {
            InputUtils.setJump(yErr > VERTICAL_DEADZONE);
            InputUtils.setSneak(yErr < -VERTICAL_DEADZONE);
        }
    }

    // ─────────────────── Obstacle avoidance ───────────────────

    private VerticalHint shouldChangeHeight(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) return VerticalHint.NONE;

        float yaw = rotation.isActive() ? rotation.getCurrentYaw() : player.getYaw();
        float yawRad = (float) Math.toRadians(yaw);
        double dirX = -Math.sin(yawRad);
        double dirZ = Math.cos(yawRad);

        double px = player.getX(), py = player.getY(), pz = player.getZ();
        int checkDist = 3;

        boolean blockedCenter = false;
        boolean blockedUp = false;
        boolean blockedDown = false;

        for (int d = 1; d <= checkDist; d++) {
            BlockPos ahead = BlockPos.ofFloored(px + dirX * d, py + 0.5, pz + dirZ * d);
            BlockPos aheadUp = BlockPos.ofFloored(px + dirX * d, py + 2.5, pz + dirZ * d);
            BlockPos aheadDown = BlockPos.ofFloored(px + dirX * d, py - 0.5, pz + dirZ * d);

            if (!isPassable(client, ahead)) blockedCenter = true;
            if (!isPassable(client, aheadUp)) blockedUp = true;
            if (!isPassable(client, aheadDown)) blockedDown = true;
        }

        if (blockedCenter) {
            if (!blockedUp) return VerticalHint.HIGHER;
            if (!blockedDown) return VerticalHint.LOWER;
            return VerticalHint.HIGHER;
        }
        return VerticalHint.NONE;
    }

    private static boolean isPassable(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return true;
        BlockState state = client.world.getBlockState(pos);
        return state.getCollisionShape(client.world, pos).isEmpty();
    }

    // ─────────────────── Directional key mapping ───────────────────

    private void applyDirectionalKeys(float moveYaw, float lookYaw, boolean colliding, boolean decel) {
        float relYaw = MathHelper.wrapDegrees(moveYaw - lookYaw);

        // Map relative yaw to WASD keys
        // -45 to 45 = forward, 45 to 135 = left, 135 to -135 = back, -135 to -45 = right
        boolean forward = false, back = false, left = false, right = false;

        if (relYaw >= -67.5f && relYaw < 67.5f) {
            forward = true;
        }
        if (relYaw >= 22.5f && relYaw < 157.5f) {
            left = true;
        }
        if (relYaw >= 112.5f || relYaw < -112.5f) {
            back = true;
        }
        if (relYaw >= -157.5f && relYaw < -22.5f) {
            right = true;
        }

        InputUtils.setForward(forward);
        InputUtils.setBack(back);
        InputUtils.setLeft(left);
        InputUtils.setRight(right);
        InputUtils.setSprint(!colliding && !decel && forward);
    }

    // ─────────────────── Helpers ───────────────────

    private void finish() {
        finish(false);
    }

    private void fail() {
        finish(true);
    }

    private void finish(boolean failed) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (rotation.isActive() && client.player != null) {
            client.player.setYaw(rotation.getCurrentYaw());
            client.player.setPitch(rotation.getCurrentPitch());
        }
        this.path = null;
        this.active = false;
        this.complete = true;
        this.failed = failed;
        this.phase = Phase.IDLE;
        this.followEntity = null;
        this.rotation.clear();
        RotationInterpolator.clearActive();
        InputUtils.releaseAll();
        renderPath = null;
    }

    private Vec3d getDistanceLookahead(double px, double py, double pz) {
        double remaining = LOOKAHEAD_DIST;
        Vec3d prev = new Vec3d(px, py, pz);

        for (int i = currentIndex; i < path.size(); i++) {
            Vec3d node = path.get(i);
            double segLen = prev.distanceTo(node);

            if (segLen >= remaining) {
                double t = remaining / segLen;
                return new Vec3d(
                    prev.x + (node.x - prev.x) * t,
                    prev.y + (node.y - prev.y) * t,
                    prev.z + (node.z - prev.z) * t
                );
            }
            remaining -= segLen;
            prev = node;
        }
        return path.get(path.size() - 1);
    }

    private void applyStrafeCorrection(float aimYaw, float currentYaw) {
        float rel = MathHelper.wrapDegrees(aimYaw - currentYaw);
        if (Math.abs(rel) < 8.0f) {
            InputUtils.setLeft(false);
            InputUtils.setRight(false);
        } else if (rel < 0) {
            InputUtils.setLeft(true);
            InputUtils.setRight(false);
        } else {
            InputUtils.setLeft(false);
            InputUtils.setRight(true);
        }
    }

    private static float calcYaw(double fx, double fz, double tx, double tz) {
        double dx = tx - fx;
        double dz = tz - fz;
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
    }

    private static double distSq(double px, double py, double pz, Vec3d t) {
        double dx = px - t.x, dy = py - t.y, dz = pz - t.z;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double hDistSq(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }

    // ─────────────────── Entity position prediction ───────────────────

    private static Vec3d predictEntityPosition(Entity entity, float yAdd) {
        Vec3d vel = entity.getVelocity();
        Vec3d base = new Vec3d(entity.getX(), entity.getY(), entity.getZ());
        if (vel.horizontalLength() > 0.15) {
            base = base.add(vel.x * 1.3, 0, vel.z * 1.3);
        }
        return base.add(0, yAdd, 0);
    }

    // ─────────────────── 3D Fly A* ───────────────────

    private static final int[][] DIRECTIONS = {
        {1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0},
        {1,0,1},{1,0,-1},{-1,0,1},{-1,0,-1},
        {1,1,0},{-1,1,0},{0,1,1},{0,1,-1},
        {1,-1,0},{-1,-1,0},{0,-1,1},{0,-1,-1},
        {1,1,1},{1,1,-1},{-1,1,1},{-1,1,-1},
        {1,-1,1},{1,-1,-1},{-1,-1,1},{-1,-1,-1},
    };

    public static CompletableFuture<List<Vec3d>> computePathAsync(BlockPos start, BlockPos end, int maxNodes) {
        MinecraftClient client = MinecraftClient.getInstance();
        return CompletableFuture.supplyAsync(() -> computeFlyPath(client, start, end, maxNodes));
    }

    public static CompletableFuture<List<Vec3d>> computePathToEntityAsync(
            BlockPos start, Entity entity, float yAdd, int maxNodes) {
        MinecraftClient client = MinecraftClient.getInstance();
        Vec3d target = predictEntityPosition(entity, yAdd);
        BlockPos end = BlockPos.ofFloored(target);
        return CompletableFuture.supplyAsync(() -> computeFlyPath(client, start, end, maxNodes));
    }

    private static List<Vec3d> computeFlyPath(MinecraftClient client, BlockPos start, BlockPos end, int maxNodes) {
        if (client.world == null) return null;

        PriorityQueue<FlyNode> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        Long2DoubleOpenHashMap best = new Long2DoubleOpenHashMap();
        best.defaultReturnValue(Double.POSITIVE_INFINITY);

        FlyNode startNode = new FlyNode(start, 0, heuristic(start, end), null);
        open.add(startNode);
        best.put(start.asLong(), 0.0);

        int eval = 0;
        while (!open.isEmpty() && eval < maxNodes) {
            FlyNode cur = open.poll();
            eval++;

            double b = best.get(cur.pos.asLong());
            if (cur.gCost > b) continue;

            if (cur.pos.getX() == end.getX() && cur.pos.getZ() == end.getZ()
                && Math.abs(cur.pos.getY() - end.getY()) <= 1) {
                return reconstructPath(cur);
            }

            for (int[] d : DIRECTIONS) {
                BlockPos np = cur.pos.add(d[0], d[1], d[2]);
                if (!isFlyPassable(client, np)) continue;

                double cost = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                double newG = cur.gCost + cost;
                double existing = best.get(np.asLong());
                if (newG >= existing) continue;

                best.put(np.asLong(), newG);
                open.add(new FlyNode(np, newG, newG + heuristic(np, end), cur));
            }
        }
        return null;
    }

    private static boolean isFlyPassable(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        WorldChunk c = client.world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (c == null) return false;
        BlockState s1 = client.world.getBlockState(pos);
        if (!s1.getCollisionShape(client.world, pos).isEmpty()) return false;
        BlockPos up = pos.up();
        BlockState s2 = client.world.getBlockState(up);
        return s2.getCollisionShape(client.world, up).isEmpty();
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.sqrt(a.getSquaredDistance(b));
    }

    private static List<Vec3d> reconstructPath(FlyNode end) {
        List<Vec3d> result = new ArrayList<>();
        FlyNode cur = end;
        while (cur != null) {
            result.add(new Vec3d(cur.pos.getX() + 0.5, cur.pos.getY(), cur.pos.getZ() + 0.5));
            cur = cur.parent;
        }
        Collections.reverse(result);
        return smoothFlyPath(result);
    }

    private static List<Vec3d> smoothFlyPath(List<Vec3d> raw) {
        if (raw == null || raw.size() <= 2) return raw;
        List<Vec3d> smoothed = new ArrayList<>();
        smoothed.add(raw.get(0));
        int current = 0;
        while (current < raw.size() - 1) {
            int farthest = current + 1;
            for (int probe = raw.size() - 1; probe > current + 1; probe--) {
                if (hasPlayerSizedLOS(raw.get(current), raw.get(probe))) {
                    farthest = probe;
                    break;
                }
            }
            smoothed.add(raw.get(farthest));
            current = farthest;
        }
        return smoothed;
    }

    private static boolean hasPlayerSizedLOS(Vec3d from, Vec3d to) {
        float[] yOffsets = {0.1f, 0.9f, 1.1f, 1.9f};
        for (float yOff : yOffsets) {
            if (!hasLineOfSight(from.add(0, yOff, 0), to.add(0, yOff, 0))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasLineOfSight(Vec3d from, Vec3d to) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.1) return true;
        for (double d = LOS_STEP; d < dist; d += LOS_STEP) {
            double t = d / dist;
            BlockPos pos = BlockPos.ofFloored(from.x + dx * t, from.y + dy * t, from.z + dz * t);
            BlockState state = client.world.getBlockState(pos);
            if (!state.getCollisionShape(client.world, pos).isEmpty()) return false;
        }
        return true;
    }

    private static class FlyNode {
        final BlockPos pos;
        final double gCost;
        final double fCost;
        final FlyNode parent;

        FlyNode(BlockPos pos, double gCost, double fCost, FlyNode parent) {
            this.pos = pos;
            this.gCost = gCost;
            this.fCost = fCost;
            this.parent = parent;
        }
    }
}
