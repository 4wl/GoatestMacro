package com.justingoat.goat.client.module.pathfinder;

import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class FlyPathProcessor {

    private List<Vec3d> path;
    private int currentIndex = 0;
    private boolean active = false;
    private boolean complete = false;
    private final RotationUtils rotation = new RotationUtils();

    private static final double ARRIVE_DIST_SQ = 2.25;
    private static final double FINAL_ARRIVE_DIST_SQ = 1.0;
    private static final double DECEL_DIST_SQ = 9.0;
    private static final int MOVE_LOOKAHEAD = 6;
    private static final float LATERAL_DEADZONE = 0.55f;
    private static final float VERTICAL_DEADZONE = 0.55f;

    private int stuckTicks = 0;
    private double lastX = Double.NaN, lastZ = Double.NaN;

    // Render data
    private static volatile List<Vec3d> renderPath = null;
    private static volatile int renderIndex = 0;

    public static List<Vec3d> getRenderPath() { return renderPath; }
    public static int getRenderIndex() { return renderIndex; }

    public void setPath(List<Vec3d> path) {
        this.path = path;
        this.currentIndex = 0;
        this.active = true;
        this.complete = false;
        this.stuckTicks = 0;
        this.lastX = Double.NaN;
        this.lastZ = Double.NaN;
        this.rotation.clear();
        RotationInterpolator.setActive(rotation);
        renderPath = path;
    }

    public boolean isDone() { return !active || complete || path == null; }

    public List<Vec3d> getPath() { return path; }

    public void stop() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (rotation.isActive() && client.player != null) {
            client.player.setYaw(rotation.getCurrentYaw());
            client.player.setPitch(rotation.getCurrentPitch());
        }
        this.path = null;
        this.active = false;
        this.rotation.clear();
        RotationInterpolator.clearActive();
        InputUtils.releaseAll();
        renderPath = null;
    }

    public void tick(MinecraftClient client, float rotSpeed) {
        if (isDone() || client.player == null) return;

        ClientPlayerEntity player = client.player;

        if (!rotation.isActive()) {
            rotation.init(player.getYaw(), player.getPitch());
        }

        // Ensure flying
        if (player.getAbilities().allowFlying && !player.getAbilities().flying) {
            InputUtils.setJump(true);
            return;
        }

        double px = player.getX(), py = player.getY(), pz = player.getZ();
        renderIndex = currentIndex;

        // Final arrival
        Vec3d finalTarget = path.get(path.size() - 1);
        double finalDistSq = distSq(px, py, pz, finalTarget);
        if (finalDistSq < FINAL_ARRIVE_DIST_SQ) {
            finish();
            return;
        }

        // Stuck detection
        if (!Double.isNaN(lastX)) {
            double moved = (px - lastX) * (px - lastX) + (pz - lastZ) * (pz - lastZ);
            stuckTicks = moved < 0.005 ? stuckTicks + 1 : 0;
        }
        lastX = px;
        lastZ = pz;
        if (stuckTicks > 60) {
            finish();
            return;
        }

        // Advance current index
        while (currentIndex < path.size() - 1) {
            Vec3d wp = path.get(currentIndex);
            if (distSq(px, py, pz, wp) < ARRIVE_DIST_SQ) {
                currentIndex++;
            } else {
                break;
            }
        }

        // Movement target with lookahead
        Vec3d vel = player.getVelocity();
        double speed = Math.sqrt(vel.x * vel.x + vel.z * vel.z);
        int lookahead = (int) (MOVE_LOOKAHEAD + speed * 8);
        int moveIdx = Math.min(path.size() - 1, currentIndex + lookahead);
        Vec3d moveTarget = path.get(moveIdx);

        // Rotation
        float aimYaw = calcYaw(px, pz, moveTarget.x, moveTarget.z);
        float aimPitch = 0.0f;
        rotation.setTarget(aimYaw, aimPitch);
        rotation.setSpeed(rotSpeed);
        rotation.tick();

        float currentYaw = rotation.getCurrentYaw();
        float angleToTarget = Math.abs(MathHelper.wrapDegrees(aimYaw - currentYaw));

        // Deceleration near final target
        if (finalDistSq < DECEL_DIST_SQ) {
            InputUtils.setForward(false);
            InputUtils.setBack(false);
            InputUtils.setLeft(false);
            InputUtils.setRight(false);
            InputUtils.setSprint(false);
            // Vertical only
            double yDiff = finalTarget.y - py;
            InputUtils.setJump(yDiff > VERTICAL_DEADZONE);
            InputUtils.setSneak(yDiff < -VERTICAL_DEADZONE);
            return;
        }

        // Horizontal movement
        if (angleToTarget > 70.0f) {
            InputUtils.setForward(false);
            InputUtils.setLeft(false);
            InputUtils.setRight(false);
            InputUtils.setSprint(false);
        } else {
            InputUtils.setForward(true);
            InputUtils.setSprint(true);
            applyStrafeCorrection(aimYaw, currentYaw);
        }

        // Vertical movement
        int vertIdx = Math.min(path.size() - 1, currentIndex + Math.max(1, (int) (1 + speed * 2)));
        double yTarget = path.get(vertIdx).y;
        double yErr = yTarget - py;
        InputUtils.setJump(yErr > VERTICAL_DEADZONE);
        InputUtils.setSneak(yErr < -VERTICAL_DEADZONE);
    }

    private void finish() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (rotation.isActive() && client.player != null) {
            client.player.setYaw(rotation.getCurrentYaw());
            client.player.setPitch(rotation.getCurrentPitch());
        }
        this.path = null;
        this.active = false;
        this.complete = true;
        this.rotation.clear();
        RotationInterpolator.clearActive();
        InputUtils.releaseAll();
        renderPath = null;
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

    // ─────────────────── 3D Fly A* ───────────────────

    private static final int[][] DIRECTIONS = {
        {1,0,0},{-1,0,0},{0,0,1},{0,0,-1},{0,1,0},{0,-1,0},
        {1,0,1},{1,0,-1},{-1,0,1},{-1,0,-1},
        {1,1,0},{-1,1,0},{0,1,1},{0,1,-1},
        {1,-1,0},{-1,-1,0},{0,-1,1},{0,-1,-1},
    };

    public static CompletableFuture<List<Vec3d>> computePathAsync(BlockPos start, BlockPos end, int maxNodes) {
        MinecraftClient client = MinecraftClient.getInstance();
        return CompletableFuture.supplyAsync(() -> computeFlyPath(client, start, end, maxNodes));
    }

    private static List<Vec3d> computeFlyPath(MinecraftClient client, BlockPos start, BlockPos end, int maxNodes) {
        if (client.world == null) return null;

        PriorityQueue<FlyNode> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.fCost));
        Map<Long, Double> best = new HashMap<>();

        FlyNode startNode = new FlyNode(start, 0, heuristic(start, end), null);
        open.add(startNode);
        best.put(start.asLong(), 0.0);

        int eval = 0;
        while (!open.isEmpty() && eval < maxNodes) {
            FlyNode cur = open.poll();
            eval++;

            Double b = best.get(cur.pos.asLong());
            if (b != null && cur.gCost > b) continue;

            if (cur.pos.getX() == end.getX() && cur.pos.getZ() == end.getZ()
                && Math.abs(cur.pos.getY() - end.getY()) <= 1) {
                return reconstructPath(cur);
            }

            for (int[] d : DIRECTIONS) {
                BlockPos np = cur.pos.add(d[0], d[1], d[2]);
                if (!isFlyPassable(client, np)) continue;

                double cost = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
                double newG = cur.gCost + cost;
                Double existing = best.get(np.asLong());
                if (existing != null && newG >= existing) continue;

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
            result.add(new Vec3d(cur.pos.getX() + 0.5, cur.pos.getY() + 1.0, cur.pos.getZ() + 0.5));
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
                if (hasLineOfSight(raw.get(current), raw.get(probe))) {
                    farthest = probe;
                    break;
                }
            }
            smoothed.add(raw.get(farthest));
            current = farthest;
        }
        return smoothed;
    }

    private static boolean hasLineOfSight(Vec3d from, Vec3d to) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return false;
        double dx = to.x - from.x, dy = to.y - from.y, dz = to.z - from.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist < 0.1) return true;
        double step = 0.5;
        for (double d = step; d < dist; d += step) {
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
