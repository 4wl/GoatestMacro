package com.justingoat.goat.client.module.pathfinder;

import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

import java.util.List;

public class PathProcessor {

    private List<PathNode> path;
    private int currentIndex = 0;

    private final RotationUtils rotation = new RotationUtils();

    // Stuck detection (horizontal-only)
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private int stuckTicks = 0;
    private int nudgeCooldown = 0;
    private int activeTicks = 0;

    // ---------------------------------------------------------------- API

    public void setPath(List<PathNode> path) {
        this.path = path;
        this.currentIndex = 0;
        this.lastX = Double.NaN;
        this.lastZ = Double.NaN;
        this.stuckTicks = 0;
        this.nudgeCooldown = 0;
        this.activeTicks = 0;
        this.rotation.clear();
    }

    public List<PathNode> getPath() { return path; }
    public int getCurrentIndex() { return currentIndex; }

    public boolean isDone() {
        return path == null || currentIndex >= path.size();
    }

    // --------------------------------------------------------------- tick

    public void tick(MinecraftClient client) {
        if (isDone() || client.player == null) return;

        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();

        activeTicks++;

        // ── 0. Final-destination arrival (generous radius) ──────────
        if (activeTicks > 10) {
            PathNode lastNode = path.get(path.size() - 1);
            BlockPos lp = lastNode.getPos();
            double fdx = px - (lp.getX() + 0.5);
            double fdz = pz - (lp.getZ() + 0.5);
            double fdy = Math.abs(py - (lp.getY() + 1.0));
            if (fdx * fdx + fdz * fdz < 1.0 && fdy < 1.5) {
                finish();
                return;
            }
        }

        // ── 1. Stuck detection (horizontal only) ────────────────────
        if (handleStuck(client, px, pz)) return;

        // ── 2. Waypoint arrival with DYNAMIC radius ─────────────────
        //    Straight segments: tight 0.7-block radius.
        //    Turns: widen up to ~3 blocks so the player "cuts" the corner
        //    and never walks all the way to the wall.
        PathNode curNode = path.get(currentIndex);
        Vec3d curCenter = nodeCenter(curNode);
        double hDistSqCur = hDistSq(px, pz, curCenter.x, curCenter.z);
        double vDistCur = Math.abs(py - curCenter.y);

        double reach;
        if (curNode.getMoveType() != PathNode.MoveType.WALK) {
            reach = 1.0; // jumps/drops need proximity
        } else {
            double turnAngle = getTurnAngle(currentIndex);
            // 0° turn → reach 0.7,  90° turn → reach ~2.5,  180° → ~3.0
            reach = 0.7 + (turnAngle / Math.PI) * 2.3;
        }

        if (hDistSqCur < reach * reach && vDistCur < 1.5) {
            currentIndex++;
            if (isDone()) { finish(); return; }
        }

        // ── 3. Determine aim target ─────────────────────────────────
        PathNode aimNode = path.get(currentIndex);
        Vec3d aimCenter = nodeCenter(aimNode);

        float aimYaw = calcYaw(px, pz, aimCenter.x, aimCenter.z);

        // Anticipatory cornering: blend yaw toward the NEXT waypoint
        // when approaching the current one. Combined with the dynamic
        // arrival radius, this creates smooth arcs around corners.
        if (currentIndex + 1 < path.size()) {
            double distToCur = Math.sqrt(hDistSq(px, pz, aimCenter.x, aimCenter.z));
            if (distToCur < 3.5) {
                Vec3d nextCenter = nodeCenter(path.get(currentIndex + 1));
                float nextYaw = calcYaw(px, pz, nextCenter.x, nextCenter.z);
                float blend = (float) (1.0 - distToCur / 3.5);
                blend = blend * blend;
                aimYaw = aimYaw + MathHelper.wrapDegrees(nextYaw - aimYaw) * blend * 0.6f;
            }
        }

        // Natural walking pitch
        float navPitch;
        PathNode.MoveType moveType = aimNode.getMoveType();
        if (moveType == PathNode.MoveType.STEP_UP) {
            navPitch = -5.0f;
        } else if (moveType == PathNode.MoveType.DROP) {
            navPitch = 18.0f;
        } else {
            navPitch = 10.0f;
        }

        // ── 4. Smooth rotation ──────────────────────────────────────
        rotation.setTarget(aimYaw, navPitch);

        float[] rot = rotation.tick(client.player.getYaw(), client.player.getPitch());
        if (rot != null) {
            client.player.setYaw(rot[0]);
            client.player.setPitch(rot[1]);
        }

        // ── 5. Movement ─────────────────────────────────────────────
        float angleToTarget = Math.abs(MathHelper.wrapDegrees(aimYaw - client.player.getYaw()));

        if (angleToTarget > 70.0f) {
            InputUtils.setForward(false);
            InputUtils.setLeft(false);
            InputUtils.setRight(false);
            InputUtils.setJump(false);
            InputUtils.setSprint(false);
            return;
        }

        InputUtils.setForward(true);
        applyStrafeCorrection(client, aimYaw);

        // ── 6. Jump logic ───────────────────────────────────────────
        boolean shouldJump = false;
        if (moveType == PathNode.MoveType.STEP_UP) {
            shouldJump = true;
        } else if (moveType == PathNode.MoveType.JUMP_ACROSS) {
            shouldJump = true;
        } else if (isJumpableObstacle(client)) {
            shouldJump = true;
        }
        InputUtils.setJump(shouldJump);

        // ── 7. Sprint ───────────────────────────────────────────────
        boolean wantSprint = (moveType == PathNode.MoveType.WALK
            || moveType == PathNode.MoveType.JUMP_ACROSS)
            && stuckTicks < 5 && angleToTarget < 30.0f;
        InputUtils.setSprint(wantSprint);
    }

    // ──────────────────────────────────────────────────── strafe

    private void applyStrafeCorrection(MinecraftClient client, float aimYaw) {
        float rel = MathHelper.wrapDegrees(aimYaw - client.player.getYaw());

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

    // ───────────────────────────────────────────── obstacle check

    private boolean isJumpableObstacle(MinecraftClient client) {
        if (client.world == null) return false;
        BlockPos feet = client.player.getBlockPos();
        float yaw = client.player.getYaw();
        int dx = Math.round(-MathHelper.sin(yaw * 0.017453292f));
        int dz = Math.round(MathHelper.cos(yaw * 0.017453292f));

        BlockPos ahead = feet.add(dx, 0, dz);
        BlockPos aboveAhead = ahead.up();
        BlockPos topAhead = ahead.up(2);

        boolean feetBlocked = !client.world.getBlockState(ahead)
            .getCollisionShape(client.world, ahead).isEmpty();
        boolean bodyFree = client.world.getBlockState(aboveAhead)
            .getCollisionShape(client.world, aboveAhead).isEmpty();
        boolean headFree = client.world.getBlockState(topAhead)
            .getCollisionShape(client.world, topAhead).isEmpty();

        return feetBlocked && bodyFree && headFree;
    }

    // ───────────────────────────────────────── stuck detection

    private boolean handleStuck(MinecraftClient client, double px, double pz) {
        if (!Double.isNaN(lastX)) {
            double hdx = px - lastX;
            double hdz = pz - lastZ;
            if (hdx * hdx + hdz * hdz < 0.01) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
        }
        lastX = px;
        lastZ = pz;

        if (nudgeCooldown > 0) nudgeCooldown--;

        if (stuckTicks >= 15 && stuckTicks < 30) {
            if (nudgeCooldown == 0) {
                InputUtils.setJump(true);
                boolean left = Math.random() < 0.5;
                InputUtils.setLeft(left);
                InputUtils.setRight(!left);
                nudgeCooldown = 5;
            }
            return false;
        }

        if (stuckTicks == 30) {
            BlockPos goal = path.get(path.size() - 1).getPos();
            client.player.sendMessage(
                Text.literal("\u00a7e[Goat] Stuck \u2014 repathing..."), false);
            List<PathNode> newPath = AStarPathfinder.computePath(
                client.player.getBlockPos().down(), goal, 100000);
            if (newPath != null) {
                setPath(newPath);
            } else {
                stuckTicks = 55;
            }
            return true;
        }

        if (stuckTicks >= 60) {
            client.player.sendMessage(
                Text.literal("\u00a7c[Goat] Repath failed. Stopping."), false);
            finish();
            return true;
        }

        return false;
    }

    // ────────────────────────────────────────────────── helpers

    private void finish() {
        this.path = null;
        InputUtils.releaseAll();
    }

    /**
     * Calculate the turn angle at waypoint idx.
     * Compares the direction from (idx-1 → idx) vs (idx → idx+1).
     * Returns 0 for straight, PI for U-turn.
     */
    private double getTurnAngle(int idx) {
        if (idx <= 0 || idx + 1 >= path.size()) return 0;

        BlockPos prev = path.get(idx - 1).getPos();
        BlockPos cur  = path.get(idx).getPos();
        BlockPos next = path.get(idx + 1).getPos();

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

    private static float calcYaw(double fromX, double fromZ, double toX, double toZ) {
        double dx = toX - fromX;
        double dz = toZ - fromZ;
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
    }

    private static Vec3d nodeCenter(PathNode node) {
        BlockPos p = node.getPos();
        return new Vec3d(p.getX() + 0.5, p.getY() + 1.0, p.getZ() + 0.5);
    }

    private static double hDistSq(double x1, double z1, double x2, double z2) {
        double dx = x1 - x2;
        double dz = z1 - z2;
        return dx * dx + dz * dz;
    }
}
