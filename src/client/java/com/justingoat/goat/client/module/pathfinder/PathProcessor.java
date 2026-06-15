package com.justingoat.goat.client.module.pathfinder;

import com.justingoat.goat.client.module.movement.PathfinderTest;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.Blocks;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.text.Text;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PathProcessor {

    private List<PathNode> path;
    private int currentIndex = 0;

    private final RotationUtils rotation = new RotationUtils();
    private final PathAote aote = new PathAote();

    // Stuck detection (horizontal-only)
    private double lastX = Double.NaN;
    private double lastZ = Double.NaN;
    private int stuckTicks = 0;
    private int nudgeCooldown = 0;
    private int activeTicks = 0;
    private int interactCooldownTicks = 0;
    private int targetIndex = -1;
    private int targetTicks = 0;
    private int targetNoProgressTicks = 0;
    private int repathPauseTicks = 0;
    private boolean nodeRepathQueued = false;
    private int jumpLockIndex = -1;
    private boolean jumpLockWasAirborne = false;
    private double targetBestScore = Double.POSITIVE_INFINITY;

    // V5-inspired multi-stage recovery
    private static final int STUCK_JUMP_TICKS = 10;
    private static final int STUCK_BACKUP_TICKS = 22;
    private static final int STUCK_REPATH_TICKS = 44;
    private static final int NODE_NO_PROGRESS_REPATH_TICKS = 55;
    private static final int NODE_HARD_REPATH_TICKS = 160;
    private static final int REPATH_PAUSE_TICKS = 6;
    private int backupTicks = 0;

    // Async repath state
    private volatile boolean repathing = false;

    // ---------------------------------------------------------------- API

    public void setPath(List<PathNode> path) {
        this.path = path;
        this.currentIndex = 0;
        this.lastX = Double.NaN;
        this.lastZ = Double.NaN;
        this.stuckTicks = 0;
        this.nudgeCooldown = 0;
        this.activeTicks = 0;
        this.interactCooldownTicks = 0;
        this.targetIndex = -1;
        this.targetTicks = 0;
        this.targetNoProgressTicks = 0;
        this.repathPauseTicks = 0;
        this.nodeRepathQueued = false;
        this.jumpLockIndex = -1;
        this.jumpLockWasAirborne = false;
        this.targetBestScore = Double.POSITIVE_INFINITY;
        this.backupTicks = 0;
        this.repathing = false;
        this.rotation.clear();
        RotationInterpolator.setActive(rotation);
    }

    public List<PathNode> getPath() { return path; }
    public int getCurrentIndex() { return currentIndex; }

    public boolean isDone() {
        return path == null || currentIndex >= path.size();
    }

    public void stop() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (rotation.isActive() && client.player != null) {
            client.player.setYaw(rotation.getCurrentYaw());
            client.player.setPitch(rotation.getCurrentPitch());
        }
        this.path = null;
        this.repathing = false;
        this.repathPauseTicks = 0;
        this.nodeRepathQueued = false;
        this.jumpLockIndex = -1;
        this.jumpLockWasAirborne = false;
        this.rotation.clear();
        this.aote.stop();
        RotationInterpolator.clearActive();
        InputUtils.releaseAll();
    }

    // --------------------------------------------------------------- tick

    public void tick(MinecraftClient client, PathfinderTest settings) {
        if (isDone() || client.player == null) return;

        if (repathing) {
            InputUtils.releaseAll();
            return;
        }
        if (repathPauseTicks > 0) {
            InputUtils.releaseAll();
            repathPauseTicks--;
            if (repathPauseTicks == 0) {
                triggerRepath(client, settings);
            }
            return;
        }

        // Initialize rotation state from player on first tick
        if (!rotation.isActive()) {
            rotation.init(client.player.getYaw(), client.player.getPitch());
        }

        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();

        activeTicks++;
        if (interactCooldownTicks > 0) interactCooldownTicks--;
        if (jumpLockIndex >= 0 && !client.player.isOnGround()) {
            jumpLockWasAirborne = true;
        }

        // ── 0. Final-destination arrival (generous radius) ──────────
        if (activeTicks > 10 && currentIndex >= path.size() - 1) {
            PathNode lastNode = path.get(path.size() - 1);
            if (hasReachedNode(client, lastNode, px, py, pz)) {
                finish(client);
                return;
            }
        }

        // ── 1. Stuck detection (horizontal only) ────────────────────
        if (handleStuck(client, px, pz, settings)) return;

        // ── 2. Waypoint arrival ──────────────────────────────────
        while (!isDone() && hasReachedNode(client, path.get(currentIndex), px, py, pz)) {
            PathNode reachedNode = path.get(currentIndex);
            currentIndex++;
            resetTargetProgress();
            if (isDone()) { finish(client); return; }
            if (!canSkipReachedNode(reachedNode)) break;
        }
        while (!isDone() && canAdvancePastWalkNode(px, py, pz)) {
            currentIndex++;
            resetTargetProgress();
            if (isDone()) { finish(client); return; }
        }

        PathNode curNode = path.get(currentIndex);
        Vec3d curCenter = nodeCenter(curNode);
        double hDistSqCur = hDistSq(px, pz, curCenter.x, curCenter.z);
        double vDistCur = Math.abs(py - curCenter.y);
        if (handleNodeProgress(client, settings, curNode, px, py, pz)) return;

        // ── 3. Determine aim target ─────────────────────────────────
        PathNode aimNode = path.get(currentIndex);
        Vec3d aimCenter = nodeCenter(aimNode);
        PathNode.MoveType moveType = aimNode.getMoveType();

        if (handleInteractable(client, aimNode, px, py, pz)) {
            return;
        }

        // ── CLIMB handling ──────────────────────────────────────────
        if (moveType == PathNode.MoveType.CLIMB) {
            handleClimb(client, aimCenter, py);
            return;
        }
        if (moveType == PathNode.MoveType.SWIM) {
            handleSwim(client, aimCenter, py);
            return;
        }

        float aimYaw = calcYaw(px, pz, aimCenter.x, aimCenter.z);

        // Anticipatory cornering — only for WALK nodes, skip for jumps
        boolean isJumpMove = moveType == PathNode.MoveType.STEP_UP
            || moveType == PathNode.MoveType.JUMP_ACROSS
            || moveType == PathNode.MoveType.SPRINT_JUMP;
        if (shouldUseLookahead(moveType) && currentIndex + 1 < path.size()) {
            double distToCur = Math.sqrt(hDistSq(px, pz, aimCenter.x, aimCenter.z));
            if (distToCur < 4.0) {
                // Blend toward the average direction of up to 3 upcoming nodes
                float blendYaw = 0.0f;
                int blendCount = 0;
                int lookMax = Math.min(path.size(), currentIndex + 4);
                for (int i = currentIndex + 1; i < lookMax; i++) {
                    Vec3d nc = nodeCenter(path.get(i));
                    float ny = calcYaw(px, pz, nc.x, nc.z);
                    blendYaw += MathHelper.wrapDegrees(ny - aimYaw);
                    blendCount++;
                }
                if (blendCount > 0) {
                    float avgDelta = blendYaw / blendCount;
                    float blend = (float) (1.0 - distToCur / 4.0);
                    blend = blend * blend * 0.5f;
                    aimYaw = aimYaw + avgDelta * blend;
                }
            }
        }

        // Natural walking pitch
        float navPitch;
        if (moveType == PathNode.MoveType.STEP_UP) {
            navPitch = -5.0f;
        } else if (moveType == PathNode.MoveType.DROP) {
            navPitch = 18.0f;
        } else {
            navPitch = 10.0f;
        }

        // ── 4. Smooth rotation (tick the spring — no player write) ──
        rotation.setTarget(aimYaw, navPitch);
        rotation.setSpeed(settings.getRotationSpeed());
        rotation.tick();

        // ── 4b. AOTE teleport (skip normal movement if used) ──
        if (settings.isAoteEnabled()) {
            float aoteYaw = rotation.getCurrentYaw();
            if (aote.tick(client, path, currentIndex, aoteYaw)) {
                return;
            }
        }

        // ── 5. Movement (use rotation's internal yaw, not player's) ─
        float currentYaw = rotation.getCurrentYaw();
        float angleToTarget = Math.abs(MathHelper.wrapDegrees(aimYaw - currentYaw));

        if (angleToTarget > 70.0f) {
            InputUtils.setForward(false);
            InputUtils.setLeft(false);
            InputUtils.setRight(false);
            InputUtils.setJump(false);
            InputUtils.setSprint(false);
            return;
        }

        InputUtils.setForward(true);
        applyStrafeCorrection(aimYaw, currentYaw);

        // ── 6. Jump logic ───────────────────────────────────────────
        boolean shouldJump = false;
        boolean slowForJump = false;

        if (isJumpMove) {
            double distToJumpTarget = Math.sqrt(hDistSqCur);

            if (moveType == PathNode.MoveType.SPRINT_JUMP) {
                shouldJump = distToJumpTarget < 4.2 && client.player.isOnGround();
            } else if (distToJumpTarget > 2.5) {
                // Far from jump — walk normally, slow down as we approach
            } else if (distToJumpTarget > 1.2) {
                // Approaching jump zone — stop sprinting, walk toward target
                slowForJump = true;
            } else if (client.player.isOnGround()) {
                // Close enough and on ground — jump + sprint
                shouldJump = true;
            }
        }

        if (!shouldJump) {
            if (client.player.isOnGround() && isJumpableObstacle(client)) {
                shouldJump = true;
            } else if (isInFluid(client)) {
                shouldJump = true;
            }
        }
        if (aote.shouldSuppressJump()) shouldJump = false;
        if (shouldJump && isJumpTransition(moveType)) {
            if (jumpLockIndex != currentIndex) {
                jumpLockIndex = currentIndex;
                jumpLockWasAirborne = false;
            }
        }
        InputUtils.setJump(shouldJump);

        // ── 7. Sprint ───────────────────────────────────────────────
        boolean wantSprint = settings.canSprint()
            && moveType != PathNode.MoveType.CLIMB
            && (!slowForJump || moveType == PathNode.MoveType.SPRINT_JUMP)
            && stuckTicks < 5 && angleToTarget < 30.0f;
        InputUtils.setSprint(wantSprint);
    }

    // ──────────────────────────────────────────────────── climb

    private void handleClimb(MinecraftClient client, Vec3d target, double py) {
        InputUtils.setSprint(false);
        InputUtils.setLeft(false);
        InputUtils.setRight(false);

        double dy = target.y - py;

        if (dy > 0.5) {
            InputUtils.setForward(true);
            InputUtils.setJump(true);
        } else {
            InputUtils.setForward(true);
            InputUtils.setJump(false);
        }

        float aimYaw = calcYaw(client.player.getX(), client.player.getZ(), target.x, target.z);
        rotation.setTarget(aimYaw, dy > 0.5 ? -30.0f : (dy < -0.5 ? 30.0f : 0.0f));
        rotation.setSpeed(0.6f);
        rotation.tick();
    }

    private void handleSwim(MinecraftClient client, Vec3d target, double py) {
        InputUtils.setSprint(false);
        InputUtils.setBack(false);

        float aimYaw = calcYaw(client.player.getX(), client.player.getZ(), target.x, target.z);
        rotation.setTarget(aimYaw, 0.0f);
        rotation.setSpeed(0.45f);
        rotation.tick();

        float currentYaw = rotation.getCurrentYaw();
        if (Math.abs(MathHelper.wrapDegrees(aimYaw - currentYaw)) > 75.0f) {
            InputUtils.setForward(false);
            InputUtils.setLeft(false);
            InputUtils.setRight(false);
        } else {
            InputUtils.setForward(true);
            applyStrafeCorrection(aimYaw, currentYaw);
        }

        double dy = target.y - py;
        InputUtils.setJump(dy > 0.35);
        InputUtils.setSneak(dy < -0.45);
    }

    private boolean handleInteractable(MinecraftClient client, PathNode aimNode, double px, double py, double pz) {
        if (client.world == null || client.player == null || client.interactionManager == null) return false;
        if (interactCooldownTicks > 0) return false;

        BlockPos base = aimNode.getPos();
        BlockPos[] checks = {base, base.up(), base.up(2)};
        for (BlockPos pos : checks) {
            BlockState state = client.world.getBlockState(pos);
            if (!isClosedOpenable(state)) continue;
            if (new Vec3d(px, py + 1.0, pz).squaredDistanceTo(Vec3d.ofCenter(pos)) > 9.0) continue;

            BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hit);
            client.player.swingHand(Hand.MAIN_HAND);
            InputUtils.setForward(false);
            InputUtils.setSprint(false);
            interactCooldownTicks = 8;
            return true;
        }
        return false;
    }

    private boolean isClosedOpenable(BlockState state) {
        if (!(state.getBlock() instanceof DoorBlock
            || state.getBlock() instanceof TrapdoorBlock
            || state.getBlock() instanceof FenceGateBlock)) {
            return false;
        }
        return state.contains(Properties.OPEN) && !state.get(Properties.OPEN);
    }

    private boolean hasReachedNode(MinecraftClient client, PathNode node, double px, double py, double pz) {
        BlockPos ground = client.player.getBlockPos().down();
        boolean onTargetGround = ground.equals(node.getPos());
        if (requiresStrictLanding(node.getMoveType())) {
            if (jumpLockIndex == currentIndex && (!client.player.isOnGround() || !jumpLockWasAirborne)) {
                return false;
            }
            return client.player.isOnGround() && onTargetGround && Math.abs(py - (node.getPos().getY() + 1.0)) <= 0.35;
        }
        if (onTargetGround) return true;

        Vec3d center = nodeCenter(node);
        double reach = nodeReach(node.getMoveType());
        double hDistSq = hDistSq(px, pz, center.x, center.z);
        double vDist = Math.abs(py - center.y);
        return hDistSq <= reach * reach && vDist <= nodeVerticalReach(node.getMoveType());
    }

    private boolean canSkipReachedNode(PathNode node) {
        return node.getMoveType() == PathNode.MoveType.WALK;
    }

    private boolean canAdvancePastWalkNode(double px, double py, double pz) {
        if (currentIndex <= 0 || currentIndex + 1 >= path.size()) return false;

        PathNode prev = path.get(currentIndex - 1);
        PathNode cur = path.get(currentIndex);
        PathNode next = path.get(currentIndex + 1);
        if (cur.getMoveType() != PathNode.MoveType.WALK || next.getMoveType() != PathNode.MoveType.WALK) return false;
        if (prev.getPos().getY() != cur.getPos().getY() || next.getPos().getY() != cur.getPos().getY()) return false;
        if (Math.abs(py - (cur.getPos().getY() + 1.0)) > 0.9) return false;

        Vec3d curCenter = nodeCenter(cur);
        Vec3d nextCenter = nodeCenter(next);
        double segX = nextCenter.x - curCenter.x;
        double segZ = nextCenter.z - curCenter.z;
        double segLenSq = segX * segX + segZ * segZ;
        if (segLenSq < 0.0001) return false;

        double playerX = px - curCenter.x;
        double playerZ = pz - curCenter.z;
        double along = (playerX * segX + playerZ * segZ) / segLenSq;
        double cross = Math.abs(playerX * segZ - playerZ * segX) / Math.sqrt(segLenSq);
        return along > 0.12 && cross < 0.9;
    }

    private boolean requiresStrictLanding(PathNode.MoveType type) {
        return type == PathNode.MoveType.STEP_UP
            || type == PathNode.MoveType.JUMP_ACROSS
            || type == PathNode.MoveType.SPRINT_JUMP
            || type == PathNode.MoveType.DROP;
    }

    private boolean isJumpTransition(PathNode.MoveType type) {
        return type == PathNode.MoveType.STEP_UP
            || type == PathNode.MoveType.JUMP_ACROSS
            || type == PathNode.MoveType.SPRINT_JUMP;
    }

    private boolean shouldUseLookahead(PathNode.MoveType type) {
        if (type != PathNode.MoveType.WALK) return false;
        if (currentIndex + 1 >= path.size()) return false;

        PathNode current = path.get(currentIndex);
        PathNode next = path.get(currentIndex + 1);
        if (next.getPos().getY() != current.getPos().getY()) return false;
        return next.getMoveType() == PathNode.MoveType.WALK;
    }

    private double nodeReach(PathNode.MoveType type) {
        return switch (type) {
            case CLIMB, SWIM -> 0.62;
            case DROP -> 0.55;
            case STEP_UP, JUMP_ACROSS, SPRINT_JUMP -> 0.48;
            case WALK -> 0.42;
        };
    }

    private double nodeVerticalReach(PathNode.MoveType type) {
        return switch (type) {
            case CLIMB, SWIM, DROP -> 1.2;
            default -> 0.7;
        };
    }

    private boolean handleNodeProgress(MinecraftClient client, PathfinderTest settings,
                                       PathNode node, double px, double py, double pz) {
        double score = nodeDistanceScore(node, px, py, pz);
        if (targetIndex != currentIndex) {
            targetIndex = currentIndex;
            targetTicks = 0;
            targetNoProgressTicks = 0;
            targetBestScore = score;
            return false;
        }

        targetTicks++;
        if (score < targetBestScore - 0.04) {
            targetBestScore = score;
            targetNoProgressTicks = 0;
        } else {
            targetNoProgressTicks++;
        }

        if (!settings.canAutoRepath()) return false;
        int noProgressLimit = node.getMoveType() == PathNode.MoveType.WALK
            ? NODE_NO_PROGRESS_REPATH_TICKS * 2
            : NODE_NO_PROGRESS_REPATH_TICKS;
        if (requiresStrictLanding(node.getMoveType())) {
            BlockPos ground = client.player.getBlockPos().down();
            boolean landedOnWrongBlock = jumpLockIndex == currentIndex
                && jumpLockWasAirborne
                && client.player.isOnGround()
                && !ground.equals(node.getPos());
            boolean fellBelowTarget = jumpLockWasAirborne && py < node.getPos().getY() + 0.35;
            boolean failedToClimb = node.getPos().getY() > ground.getY()
                && targetTicks >= NODE_NO_PROGRESS_REPATH_TICKS
                && targetNoProgressTicks >= NODE_NO_PROGRESS_REPATH_TICKS / 2;
            if (landedOnWrongBlock || fellBelowTarget || failedToClimb) {
                scheduleNodeRepath(client, "禮e[Goat] Missed jump node. Pausing before repath...");
                return true;
            }
        }

        boolean closeButNotReached = score < 2.25 && targetNoProgressTicks >= noProgressLimit;
        boolean hardTimeout = targetTicks >= NODE_HARD_REPATH_TICKS && targetNoProgressTicks >= noProgressLimit;
        if (!closeButNotReached && !hardTimeout) return false;

        scheduleNodeRepath(client, "禮e[Goat] Cannot step on node. Pausing before repath...");
        return true;
    }

    private void scheduleNodeRepath(MinecraftClient client, String message) {
        if (nodeRepathQueued || repathPauseTicks > 0 || repathing) return;
        nodeRepathQueued = true;
        freezeRotation(client);
        client.player.sendMessage(Text.literal(message), false);
        InputUtils.releaseAll();
        repathPauseTicks = REPATH_PAUSE_TICKS;
        targetTicks = 0;
        targetNoProgressTicks = 0;
        jumpLockIndex = -1;
        jumpLockWasAirborne = false;
    }

    private void freezeRotation(MinecraftClient client) {
        if (client.player != null && rotation.isActive()) {
            client.player.setYaw(rotation.getCurrentYaw());
            client.player.setPitch(rotation.getCurrentPitch());
        }
        rotation.clear();
        RotationInterpolator.clearActive();
    }

    private double nodeDistanceScore(PathNode node, double px, double py, double pz) {
        Vec3d center = nodeCenter(node);
        double hDistSq = hDistSq(px, pz, center.x, center.z);
        double vDist = Math.abs(py - center.y);
        return hDistSq + vDist * vDist;
    }

    private void resetTargetProgress() {
        targetIndex = -1;
        targetTicks = 0;
        targetNoProgressTicks = 0;
        jumpLockIndex = -1;
        jumpLockWasAirborne = false;
        targetBestScore = Double.POSITIVE_INFINITY;
    }

    // ──────────────────────────────────────────────────── strafe

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

    // ───────────────────────────────────────────── obstacle check

    private boolean isJumpableObstacle(MinecraftClient client) {
        if (client.world == null) return false;
        BlockPos feet = client.player.getBlockPos();
        float yaw = rotation.getCurrentYaw();
        int dx = Math.round(-MathHelper.sin(yaw * 0.017453292f));
        int dz = Math.round(MathHelper.cos(yaw * 0.017453292f));

        BlockPos ahead = feet.add(dx, 0, dz);
        BlockPos aboveAhead = ahead.up();
        BlockPos topAhead = ahead.up(2);

        VoxelShape aheadShape = client.world.getBlockState(ahead)
            .getCollisionShape(client.world, ahead);
        boolean feetBlocked = !aheadShape.isEmpty();
        boolean obstacleIsJumpableHeight = feetBlocked && aheadShape.getMax(Direction.Axis.Y) <= 1.0;
        boolean bodyFree = client.world.getBlockState(aboveAhead)
            .getCollisionShape(client.world, aboveAhead).isEmpty();
        boolean headFree = client.world.getBlockState(topAhead)
            .getCollisionShape(client.world, topAhead).isEmpty();

        return feetBlocked && obstacleIsJumpableHeight && bodyFree && headFree;
    }

    // ───────────────────────────────────────── fluid jump (V5)

    private boolean isInFluid(MinecraftClient client) {
        if (client.world == null || client.player == null) return false;
        BlockPos feet = client.player.getBlockPos();
        BlockState feetState = client.world.getBlockState(feet);
        return feetState.getBlock() instanceof FluidBlock
            || feetState.isOf(Blocks.WATER)
            || feetState.isOf(Blocks.LAVA);
    }

    private double getTerrainSpeedFactor(MinecraftClient client) {
        if (client.world == null || client.player == null) return 1.0;
        BlockState ground = client.world.getBlockState(client.player.getBlockPos().down());
        if (ground.isOf(Blocks.SOUL_SAND) || ground.isOf(Blocks.SOUL_SOIL)) return 0.4;
        if (ground.isOf(Blocks.ICE) || ground.isOf(Blocks.PACKED_ICE)
            || ground.isOf(Blocks.BLUE_ICE) || ground.isOf(Blocks.FROSTED_ICE)) {
            return 1.4;
        }
        return 1.0;
    }

    // ───────────────────────────── V5-inspired multi-stage recovery

    private boolean handleStuck(MinecraftClient client, double px, double pz, PathfinderTest settings) {
        // Handle active backup movement
        if (backupTicks > 0) {
            backupTicks--;
            InputUtils.setForward(false);
            InputUtils.setBack(true);
            InputUtils.setJump(false);
            InputUtils.setSprint(false);
            if (backupTicks == 0) {
                InputUtils.setBack(false);
                triggerRepath(client, settings);
            }
            return true;
        }

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

        // Stage 1: Try jumping (V5: STUCK_TICKS_JUMP = 10)
        if (stuckTicks >= STUCK_JUMP_TICKS && stuckTicks < STUCK_BACKUP_TICKS) {
            if (nudgeCooldown == 0) {
                InputUtils.setJump(true);
                boolean left = Math.random() < 0.5;
                InputUtils.setLeft(left);
                InputUtils.setRight(!left);
                nudgeCooldown = 5;
            }
            return false;
        }

        // Stage 2: Back up then repath (V5: STUCK_TICKS_BACKUP_RECALC = 44)
        if (stuckTicks == STUCK_BACKUP_TICKS && settings.canAutoRepath()) {
            client.player.sendMessage(
                Text.literal("§e[Goat] Stuck — backing up..."), false);
            backupTicks = 10;
            stuckTicks = 0;
            return true;
        }

        // Stage 3: Direct repath without backup
        if (stuckTicks == STUCK_REPATH_TICKS && settings.canAutoRepath()) {
            triggerRepath(client, settings);
            return true;
        }

        // Give up after extended stuck
        if (stuckTicks >= STUCK_REPATH_TICKS + 40) {
            client.player.sendMessage(
                Text.literal("§c[Goat] Cannot recover. Stopping."), false);
            finish(client);
            return true;
        }

        return false;
    }

    private void triggerRepath(MinecraftClient client, PathfinderTest settings) {
        if (path == null || path.isEmpty()) return;
        freezeRotation(client);
        BlockPos goal = path.get(path.size() - 1).getPos();
        client.player.sendMessage(
            Text.literal("§e[Goat] Repathing..."), false);

        repathing = true;
        repathPauseTicks = 0;
        nodeRepathQueued = false;
        resetTargetProgress();
        InputUtils.releaseAll();

        int maxNodes = settings.getMaxNodes();
        int maxDrop = settings.getMaxDrop();
        BlockPos start = client.player.getBlockPos().down();

        CompletableFuture.supplyAsync(() -> {
            List<PathNode> raw = AStarPathfinder.computePath(start, goal, maxNodes, maxDrop);
            return raw != null ? PathSmoother.smooth(raw) : null;
        }).thenAccept(newPath -> client.execute(() -> {
            repathing = false;
            if (newPath != null) {
                setPath(newPath);
                client.player.sendMessage(
                    Text.literal("§a[Goat] Repath done — " + newPath.size() + " nodes."), false);
            } else {
                nodeRepathQueued = false;
                stuckTicks = STUCK_REPATH_TICKS;
                client.player.sendMessage(
                    Text.literal("§c[Goat] Repath failed."), false);
            }
        }));
    }

    // ────────────────────────────────────────────────── helpers

    private void finish(MinecraftClient client) {
        // Commit final rotation to player before releasing
        if (rotation.isActive() && client.player != null) {
            client.player.setYaw(rotation.getCurrentYaw());
            client.player.setPitch(rotation.getCurrentPitch());
        }
        this.path = null;
        this.nodeRepathQueued = false;
        this.rotation.clear();
        RotationInterpolator.clearActive();
        InputUtils.releaseAll();
    }

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
