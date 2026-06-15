package com.justingoat.goat.client.module.movement;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.module.failsafe.impl.TeleportFailsafe;
import com.justingoat.goat.client.module.farming.PestCleaner;
import com.justingoat.goat.client.utils.BPSTracker;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.LagDetector;
import com.justingoat.goat.client.utils.RotationUtils;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;

public class FarmingMacro extends GoatModule implements MacroHudInfo {

    private static final Set<String> VERTICAL_CROPS = Set.of(
        "minecraft:nether_wart", "minecraft:potatoes", "minecraft:wheat", "minecraft:carrots"
    );

    private static final Map<String, String> CROP_TOOLS = Map.of(
        "minecraft:nether_wart", "Nether Wart Hoe",
        "minecraft:potatoes", "Potato Hoe",
        "minecraft:wheat", "Wheat Hoe",
        "minecraft:carrots", "Carrot Hoe"
    );

    private static final int ANTI_STUCK_TICKS = 45;
    private static final int ANTI_STUCK_NUDGE_TICKS = 10;
    private static final int ANTI_STUCK_MAX_NUDGES = 3;
    private static final double ANTI_STUCK_MIN_HORIZONTAL_MOVE_SQ = 0.0025;
    private static final double ANTI_STUCK_ESCAPE_CHECK_DISTANCE = 1.25;
    private static final double BPS_STUCK_THRESHOLD = 0.35;
    private static final long BPS_STUCK_DURATION_MS = 2200L;
    private static final int S_DIRECTION_SCAN_DISTANCE = 180;
    private static final int S_CROP_DIRECTION_SCAN_FORWARD = 5;
    private static final int S_MATURE_WHEAT_AGE = 7;

    // ── States ──
    private enum State {
        WAITING,
        // Vertical states
        V_SCAN_FOR_CROP, V_DECIDE_ROTATION, V_DECIDE_ITEM,
        V_DECIDE_MOVEMENT, V_IDLE_CHECKS,
        // S-Shape states: strafe A/D along row, W/S to switch lane
        S_NONE, S_LEFT, S_RIGHT, S_SWITCHING_LANE, S_DROPPING,
        // Shared
        PEST_CLEANING, REWARP
    }

    // ── Settings ──
    private final ModeValue farmType;
    private final NumberValue pitch;
    private final BooleanValue snapTo45;
    private final BooleanValue lagPause;
    private final BooleanValue bpsMonitor;
    private final NumberValue delayMin;
    private final NumberValue delayMax;
    private final BooleanValue debug;

    // ── Shared state ──
    private State state = State.WAITING;
    private final RotationUtils rotation = new RotationUtils();
    private float lockedYaw;
    private boolean warping = false;
    private Long warpDelay = null;
    private BlockPos startPoint = null;
    private BlockPos endPoint = null;
    private BlockPos rewarpTriggerPoint = null;
    private boolean rewarpTriggered = false;
    private int antiStuckTicks = 0;
    private int antiStuckNudgeTicks = 0;
    private int antiStuckAttempts = 0;
    private Vec3d antiStuckLastPos = null;
    private Float antiStuckEscapeYaw = null;

    // ── Vertical state ──
    private String farmAxis = null;
    private String movementKey = null;
    private boolean inAir = false;
    private boolean decidePrompted = false;

    // ── S-Shape state ──
    private enum ChangeLaneDir { FORWARD, BACKWARD }
    private ChangeLaneDir changeLaneDir = null;
    private int sLayerY = 0;
    private double sSwitchStartX, sSwitchStartZ;

    public FarmingMacro() {
        super("FarmingMacro", ModuleCategory.MACRO, false);
        farmType = addMode("Farm Type", "Vertical", "Vertical", "S-Shape");
        pitch = addNumber("Pitch", 3.0, -90.0, 90.0);
        snapTo45 = addBoolean("Snap 45°", false);
        lagPause = addBoolean("LagPause", true);
        bpsMonitor = addBoolean("BPSTracker", true);
        delayMin = addNumber("Delay Min (ms)", 50.0, 0.0, 500.0);
        delayMax = addNumber("Delay Max (ms)", 150.0, 0.0, 500.0);
        debug = addBoolean("Debug", false);
    }

    // ══════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════

    @Override
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = isEnabled();
        super.setEnabled(enabled);
        if (enabled && !wasEnabled) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null) {
                super.setEnabled(false);
                return;
            }
            resetAllState(client);
            equipBestHoe(client);
            ChatUtils.sendSuccessMessage("FarmingMacro enabled (" + farmType.getValue() + ")");
        } else if (!enabled && wasEnabled) {
            state = State.WAITING;
            rotation.clear();
            resetAntiStuck();
            InputUtils.releaseAll();
            ChatUtils.sendWarningMessage("FarmingMacro disabled");
        }
    }

    private void resetAllState(MinecraftClient client) {
        warping = false;
        warpDelay = null;
        rewarpTriggered = false;
        resetAntiStuck();
        rotation.init(client.player.getYaw(), client.player.getPitch());

        // Vertical
        farmAxis = null;
        movementKey = null;
        inAir = false;
        decidePrompted = false;

        // S-Shape
        changeLaneDir = null;
        sLayerY = client.player.getBlockPos().getY();

        if (isVertical()) {
            state = State.V_SCAN_FOR_CROP;
        } else {
            lockedYaw = snapYaw(client.player.getYaw());
            client.player.setYaw(lockedYaw);
            rotation.setTarget(lockedYaw, (float) pitch.getValue());
            state = State.S_NONE;
        }
    }

    private boolean isVertical() {
        return "Vertical".equals(farmType.getValue());
    }

    private void equipBestHoe(MinecraftClient client) {
        int slot = findBestHoeSlot(client);
        if (slot == -1) return;

        client.player.getInventory().setSelectedSlot(slot);
        debugMsg("Equipped hoe in slot " + (slot + 1));
    }

    private int findBestHoeSlot(MinecraftClient client) {
        Item[] priority = {
            Items.DIAMOND_HOE,
            Items.GOLDEN_HOE,
            Items.IRON_HOE
        };

        for (Item target : priority) {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack stack = client.player.getInventory().getStack(slot);
                if (!stack.isEmpty() && stack.isOf(target)) {
                    return slot;
                }
            }
        }
        return -1;
    }

    // ══════════════════════════════════════════════════════════════
    // Main tick dispatch
    // ══════════════════════════════════════════════════════════════

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) return;

        if (FailsafeManager.getInstance().hasEmergency()) {
            setEnabled(false);
            return;
        }

        if (client.currentScreen != null) {
            InputUtils.releaseAll();
            return;
        }

        if (lagPause.getValue() && LagDetector.isLagging() && shouldMonitorMovementState()) {
            InputUtils.releaseAll();
            resetAntiStuck();
            debugMsg("Lag detected, pausing movement");
            return;
        }

        if (state == State.PEST_CLEANING) {
            tickPestCleaning(client);
            return;
        }

        rotation.tick();
        if (rotation.isActive()) {
            float[] interp = rotation.interpolate(1.0f);
            if (interp != null) {
                client.player.setYaw(interp[0]);
                client.player.setPitch(interp[1]);
            }
        }

        checkRewarpTrigger(client);

        switch (state) {
            case WAITING -> {}
            // Vertical
            case V_SCAN_FOR_CROP -> tickVScanForCrop(client);
            case V_DECIDE_ROTATION -> tickVDecideRotation(client);
            case V_DECIDE_ITEM -> tickVDecideItem(client);
            case V_DECIDE_MOVEMENT -> tickVDecideMovement(client);
            case V_IDLE_CHECKS -> tickVIdleChecks(client);
            // S-Shape
            case S_NONE -> tickSNone(client);
            case S_LEFT -> tickSLeft(client);
            case S_RIGHT -> tickSRight(client);
            case S_SWITCHING_LANE -> tickSSwitchingLane(client);
            case S_DROPPING -> tickSDropping(client);
            // Shared
            case PEST_CLEANING -> tickPestCleaning(client);
            case REWARP -> tickRewarp(client);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── VERTICAL FARM ──
    // ══════════════════════════════════════════════════════════════

    private void tickVScanForCrop(MinecraftClient client) {
        List<BlockPos> targets = findTargetBlocks(client);

        if (!targets.isEmpty() && !warping) {
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (BlockPos pos : targets) {
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            int spanX = maxX - minX;
            int spanZ = maxZ - minZ;
            farmAxis = spanX > spanZ ? "X" : spanZ > spanX ? "Z" : "X";

            if (client.player.getAbilities().flying) {
                InputUtils.setSneak(true);
                return;
            }
            InputUtils.setSneak(false);
            state = State.V_DECIDE_ROTATION;
            debugMsg("Crops found, axis=" + farmAxis);
            return;
        }

        handleNocropsWarp(client);
    }

    private void tickVDecideRotation(MinecraftClient client) {
        float targetYaw;
        BlockInfo blockAhead = getBlockInFront(client, 1, 1);

        if (blockAhead != null && isCrop(blockAhead.registryId)) {
            targetYaw = angleToBlock(client, blockAhead.pos);
        } else {
            List<BlockPos> targets = findTargetBlocks(client);
            if (targets.isEmpty()) {
                state = State.V_SCAN_FOR_CROP;
                return;
            }
            double avgX = targets.stream().mapToInt(BlockPos::getX).average().orElse(0) + 0.5;
            double avgZ = targets.stream().mapToInt(BlockPos::getZ).average().orElse(0) + 0.5;
            targetYaw = angleToPoint(client, avgX, avgZ);
        }

        targetYaw = MathHelper.wrapDegrees(targetYaw);
        float[] allowedYaws = "X".equals(farmAxis)
            ? new float[]{0, -180}
            : new float[]{90, -90};

        float snappedYaw = targetYaw;
        float minDiff = 361;
        for (float allowed : allowedYaws) {
            float diff = Math.abs(MathHelper.wrapDegrees(targetYaw - allowed));
            if (diff < minDiff) {
                minDiff = diff;
                snappedYaw = allowed;
            }
        }

        lockedYaw = snappedYaw;
        rotation.setTarget(lockedYaw, (float) pitch.getValue());
        debugMsg("Rotating to yaw=" + lockedYaw);
        state = State.V_DECIDE_ITEM;
    }

    private void tickVDecideItem(MinecraftClient client) {
        if (!rotation.isRoughlyFacing()) return;

        BlockInfo block = getBlockInFront(client, 1, 1);
        if (block == null) {
            state = State.V_SCAN_FOR_CROP;
            return;
        }

        String tool = CROP_TOOLS.get(block.registryId);
        if (tool != null) {
            int slot = findItemInHotbar(client, tool);
            if (slot != -1) {
                client.player.getInventory().setSelectedSlot(slot);
                debugMsg("Equipped " + tool);
            }
        }
        state = State.V_DECIDE_MOVEMENT;
    }

    private void tickVDecideMovement(MinecraftClient client) {
        InputUtils.setAttack(true);

        BlockInfo blockData = getBlockInFront(client, 1, 0);
        if (blockData == null) {
            state = State.V_DECIDE_ROTATION;
            return;
        }

        double dx = blockData.centerX - client.player.getX();
        double dz = blockData.centerZ - client.player.getZ();
        double distFlat = Math.sqrt(dx * dx + dz * dz);

        if (distFlat > 1.0) {
            InputUtils.setForward(true);
            tickAntiStuck(client, "vertical approach", lockedYaw);
            return;
        } else {
            InputUtils.setForward(false);
            resetAntiStuck();
        }

        decideDirection(client);
        if (movementKey != null) {
            state = State.V_IDLE_CHECKS;
            debugMsg("Moving " + movementKey);
        }
    }

    private void tickVIdleChecks(MinecraftClient client) {
        if (endPoint != null && isAtPoint(client, endPoint, 1.0)) {
            ChatUtils.sendSuccessMessage("Reached end! Rewarping.");
            InputUtils.releaseAll();
            state = State.REWARP;
            return;
        }

        InputUtils.setAttack(true);

        if ("a".equals(movementKey)) {
            InputUtils.setLeft(true);
            InputUtils.setRight(false);
            InputUtils.setBack(false);
        } else if ("d".equals(movementKey)) {
            InputUtils.setRight(true);
            InputUtils.setLeft(false);
            InputUtils.setBack(false);
        }

        boolean isOnGround = client.player.isOnGround();
        if (!isOnGround) {
            InputUtils.setLeft(false);
            InputUtils.setRight(false);
            InputUtils.setForward(false);
            InputUtils.setBack(false);
            resetAntiStuck();
            inAir = true;
        }

        if (inAir && isOnGround) {
            inAir = false;
            resetAntiStuck();
            state = State.V_DECIDE_MOVEMENT;
            return;
        }

        tickAntiStuck(client, "vertical row", "a".equals(movementKey) ? lockedYaw + 90.0f : lockedYaw - 90.0f);
    }

    private void decideDirection(MinecraftClient client) {
        SidesDistance sides = getSidesDistance(client);

        if (sides.maxDistRight > sides.maxDistLeft) {
            debugMsg("Wall RIGHT, moving LEFT");
            movementKey = "a";
        } else if (sides.maxDistLeft > sides.maxDistRight) {
            debugMsg("Wall LEFT, moving RIGHT");
            movementKey = "d";
        } else {
            CropCorners corners = checkForCropAge(client, 1);
            if (corners.leftAge > corners.rightAge) {
                movementKey = "a";
            } else if (corners.rightAge > corners.leftAge) {
                movementKey = "d";
            } else {
                if (!decidePrompted) {
                    ChatUtils.sendWarningMessage("Can't decide direction, press A or D!");
                    decidePrompted = true;
                }
                if (client.options.leftKey.isPressed()) {
                    movementKey = "a";
                    decidePrompted = false;
                } else if (client.options.rightKey.isPressed()) {
                    movementKey = "d";
                    decidePrompted = false;
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── S-SHAPE FARM ──
    // Following FarmHelper pattern:
    //   LEFT/RIGHT = strafe along row (A/D + attack)
    //   SWITCHING_LANE = move to next row (W or S)
    //   Yaw faces the "lane" direction (forward/back between rows)
    // ══════════════════════════════════════════════════════════════

    private void tickSNone(MinecraftClient client) {
        if (!rotation.isRoughlyFacing()) return;
        InputUtils.releaseAll();

        State dir = sCalculateDirection(client);
        if (dir == State.S_NONE) {
            debugMsg("S-Shape: can't determine direction");
            return;
        }
        state = dir;
        debugMsg("S-Shape: starting " + dir.name());
    }

    private void tickSLeft(MinecraftClient client) {
        // End point check
        if (endPoint != null && isAtPoint(client, endPoint, 1.0)) {
            ChatUtils.sendSuccessMessage("Reached end! Rewarping.");
            InputUtils.releaseAll();
            state = State.REWARP;
            return;
        }

        // Dropping check
        if (sCheckDropping(client)) return;

        // Hold A + attack
        InputUtils.releaseAll();
        InputUtils.setLeft(true);
        InputUtils.setAttack(true);

        if (tickAntiStuck(client, "s-shape left", lockedYaw + 90.0f)) {
            return;
        }

        // updateState: check if we should switch lane
        if (!sIsLeftWalkable(client)) {
            if (sIsFrontWalkable(client) || sIsBackWalkable(client)) {
                changeLaneDir = null;
                InputUtils.releaseAll();
                state = State.S_SWITCHING_LANE;
                debugMsg("S-Shape: LEFT row end → SWITCHING_LANE");
            } else if (sIsRightWalkable(client)) {
                state = State.S_RIGHT;
                debugMsg("S-Shape: LEFT blocked, switching to RIGHT");
            }
        }
    }

    private void tickSRight(MinecraftClient client) {
        if (endPoint != null && isAtPoint(client, endPoint, 1.0)) {
            ChatUtils.sendSuccessMessage("Reached end! Rewarping.");
            InputUtils.releaseAll();
            state = State.REWARP;
            return;
        }

        if (sCheckDropping(client)) return;

        // Hold D + attack
        InputUtils.releaseAll();
        InputUtils.setRight(true);
        InputUtils.setAttack(true);

        if (tickAntiStuck(client, "s-shape right", lockedYaw - 90.0f)) {
            return;
        }

        if (!sIsRightWalkable(client)) {
            if (sIsFrontWalkable(client) || sIsBackWalkable(client)) {
                changeLaneDir = null;
                InputUtils.releaseAll();
                state = State.S_SWITCHING_LANE;
                debugMsg("S-Shape: RIGHT row end → SWITCHING_LANE");
            } else if (sIsLeftWalkable(client)) {
                state = State.S_LEFT;
                debugMsg("S-Shape: RIGHT blocked, switching to LEFT");
            }
        }
    }

    private void tickSSwitchingLane(MinecraftClient client) {
        if (sCheckDropping(client)) return;

        // Decide forward or backward on first tick
        if (changeLaneDir == null) {
            if (sIsFrontWalkable(client)) {
                changeLaneDir = ChangeLaneDir.FORWARD;
            } else if (sIsBackWalkable(client)) {
                changeLaneDir = ChangeLaneDir.BACKWARD;
            } else {
                state = State.S_NONE;
                return;
            }
            sSwitchStartX = client.player.getX();
            sSwitchStartZ = client.player.getZ();
            debugMsg("S-Shape: switching lane " + changeLaneDir);
        }

        // Hold W or S
        InputUtils.releaseAll();
        if (changeLaneDir == ChangeLaneDir.FORWARD) {
            InputUtils.setForward(true);
            InputUtils.setSprint(true);
            if (tickAntiStuck(client, "s-shape lane switch", lockedYaw)) {
                return;
            }
        } else {
            InputUtils.setBack(true);
            if (tickAntiStuck(client, "s-shape lane switch", lockedYaw + 180.0f)) {
                return;
            }
        }

        // Only check for new row after player has moved >= 1 block
        double dx = client.player.getX() - sSwitchStartX;
        double dz = client.player.getZ() - sSwitchStartZ;
        double moved = Math.sqrt(dx * dx + dz * dz);
        if (moved < 0.9) return;

        // Check if we arrived at new row (left or right is now walkable)
        if (sIsLeftWalkable(client)) {
            InputUtils.releaseAll();
            changeLaneDir = null;
            state = State.S_LEFT;
            debugMsg("S-Shape: entered new row → LEFT");
        } else if (sIsRightWalkable(client)) {
            InputUtils.releaseAll();
            changeLaneDir = null;
            state = State.S_RIGHT;
            debugMsg("S-Shape: entered new row → RIGHT");
        }
    }

    private void tickSDropping(MinecraftClient client) {
        InputUtils.releaseAll();
        if (client.player.isOnGround()
            && Math.abs(sLayerY - client.player.getBlockPos().getY()) > 1) {
            changeLaneDir = null;
            sLayerY = client.player.getBlockPos().getY();
            state = State.S_NONE;
            debugMsg("S-Shape: landed after drop");
        } else if (client.player.isOnGround()) {
            sLayerY = client.player.getBlockPos().getY();
            state = State.S_NONE;
        }
    }

    private boolean sCheckDropping(MinecraftClient client) {
        if (!client.player.isOnGround()
            && Math.abs(sLayerY - client.player.getY()) > 0.75) {
            InputUtils.releaseAll();
            state = State.S_DROPPING;
            debugMsg("S-Shape: dropping detected");
            return true;
        }
        return false;
    }

    // ── S-Shape: walkability checks (relative to lockedYaw) ──

    private BlockPos sGetRelativeBlockPos(MinecraftClient client, int right, int y, int forward) {
        float yawRad = (float) Math.toRadians(lockedYaw);
        double unitX = -Math.sin(yawRad);
        double unitZ = Math.cos(yawRad);

        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();
        if (py % 1 > 0.7) py = Math.ceil(py);

        return new BlockPos(
            (int) Math.floor(px + unitX * forward + unitZ * (-1) * right),
            (int) py + y,
            (int) Math.floor(pz + unitZ * forward + unitX * right)
        );
    }

    private boolean sCanWalkThrough(MinecraftClient client, BlockPos pos) {
        BlockState bottom = client.world.getBlockState(pos);
        BlockState top = client.world.getBlockState(pos.up());
        boolean bottomOk = bottom.getCollisionShape(client.world, pos).isEmpty()
            || bottom.getBlock().getDefaultState().isAir();
        boolean topOk = top.getCollisionShape(client.world, pos.up()).isEmpty()
            || top.getBlock().getDefaultState().isAir();
        return bottomOk && topOk;
    }

    private boolean sIsLeftWalkable(MinecraftClient client) {
        return sCanWalkThrough(client, sGetRelativeBlockPos(client, -1, 0, 0));
    }

    private boolean sIsRightWalkable(MinecraftClient client) {
        return sCanWalkThrough(client, sGetRelativeBlockPos(client, 1, 0, 0));
    }

    private boolean sIsFrontWalkable(MinecraftClient client) {
        return sCanWalkThrough(client, sGetRelativeBlockPos(client, 0, 0, 1));
    }

    private boolean sIsBackWalkable(MinecraftClient client) {
        return sCanWalkThrough(client, sGetRelativeBlockPos(client, 0, 0, -1));
    }

    private State sCalculateDirection(MinecraftClient client) {
        boolean rightWalkable = sIsRightWalkable(client);
        boolean leftWalkable = sIsLeftWalkable(client);

        if (rightWalkable && !leftWalkable) return State.S_RIGHT;
        if (leftWalkable && !rightWalkable) return State.S_LEFT;
        if (!rightWalkable && !leftWalkable) return State.S_NONE;

        SShapeCropScore rightScore = sScoreCropSide(client, 1);
        SShapeCropScore leftScore = sScoreCropSide(client, -1);
        int cropCompare = leftScore.compareTo(rightScore);
        if (cropCompare > 0) {
            debugMsg("S-Shape: mature crops left=" + leftScore + ", right=" + rightScore + ", starting LEFT");
            return State.S_LEFT;
        }
        if (cropCompare < 0) {
            debugMsg("S-Shape: mature crops left=" + leftScore + ", right=" + rightScore + ", starting RIGHT");
            return State.S_RIGHT;
        }

        int rightWallDistance = sDistanceToBlockedSide(client, 1);
        int leftWallDistance = sDistanceToBlockedSide(client, -1);

        if (rightWallDistance < leftWallDistance) {
            debugMsg("S-Shape: right wall closer, starting LEFT");
            return State.S_LEFT;
        }
        if (leftWallDistance < rightWallDistance) {
            debugMsg("S-Shape: left wall closer, starting RIGHT");
            return State.S_RIGHT;
        }
        return State.S_NONE;
    }

    private record SShapeCropScore(int matureCount, int totalAge, int cropCount) implements Comparable<SShapeCropScore> {
        @Override
        public int compareTo(SShapeCropScore other) {
            int matureCompare = Integer.compare(matureCount, other.matureCount);
            if (matureCompare != 0) return matureCompare;

            int ageCompare = Integer.compare(totalAge, other.totalAge);
            if (ageCompare != 0) return ageCompare;

            return Integer.compare(cropCount, other.cropCount);
        }
    }

    private SShapeCropScore sScoreCropSide(MinecraftClient client, int rightDirection) {
        int matureCount = 0;
        int totalAge = 0;
        int cropCount = 0;

        for (int forward = 0; forward <= S_CROP_DIRECTION_SCAN_FORWARD; forward++) {
            BlockPos pos = sGetRelativeBlockPos(client, rightDirection, 0, forward);
            int age = getSShapeWheatAge(client, pos);
            if (age < 0) continue;

            cropCount++;
            totalAge += age;
            if (age >= S_MATURE_WHEAT_AGE) {
                matureCount++;
            }
        }

        return new SShapeCropScore(matureCount, totalAge, cropCount);
    }

    private int getSShapeWheatAge(MinecraftClient client, BlockPos pos) {
        int age = getWheatAge(client, pos);
        if (age >= 0) return age;

        age = getWheatAge(client, pos.up());
        if (age >= 0) return age;

        return getWheatAge(client, pos.down());
    }

    private int getWheatAge(MinecraftClient client, BlockPos pos) {
        String id = getRegistryId(client, pos);
        if (!"minecraft:wheat".equals(id)) return -1;
        return getCropAge(client, pos);
    }

    private int sDistanceToBlockedSide(MinecraftClient client, int rightDirection) {
        for (int i = 1; i <= S_DIRECTION_SCAN_DISTANCE; i++) {
            BlockPos pos = sGetRelativeBlockPos(client, i * rightDirection, 0, 0);
            if (!sCanWalkThrough(client, pos)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    // ══════════════════════════════════════════════════════════════
    // ── SHARED: REWARP ──
    // ══════════════════════════════════════════════════════════════

    private void tickRewarp(MinecraftClient client) {
        if (startPoint == null) {
            ChatUtils.sendErrorMessage("No start point set! Use /goatsetstart");
            setEnabled(false);
            return;
        }

        if (warpDelay == null) {
            long delay = 500 + (long) (Math.random() * 250);
            warpDelay = System.currentTimeMillis() + delay;
            debugMsg("Warping in " + delay + "ms");
            return;
        }

        if (isHorizontallyAtPoint(client, startPoint, 1.0)) {
            if (areChunksLoaded(client, startPoint)) {
                resumeAfterRewarpWhenGrounded(client);
            } else {
                debugMsg("Waiting for chunks...");
            }
            return;
        }

        if (System.currentTimeMillis() >= warpDelay) {
            markWarpCommand();
            client.player.networkHandler.sendChatCommand("warp garden");
            warpDelay = System.currentTimeMillis() + 5000;
        }
    }

    private void resumeAfterRewarpWhenGrounded(MinecraftClient client) {
        if (!client.player.isOnGround()) {
            InputUtils.releaseAll();
            InputUtils.setSneak(true);
            InputUtils.setJump(false);
            return;
        }

        InputUtils.setSneak(false);
        warpDelay = null;
        if (isVertical()) {
            movementKey = null;
            inAir = false;
            state = State.V_SCAN_FOR_CROP;
        } else {
            changeLaneDir = null;
            sLayerY = client.player.getBlockPos().getY();
            state = State.S_NONE;
        }
    }

    private void handleNocropsWarp(MinecraftClient client) {
        if (startPoint == null) {
            ChatUtils.sendErrorMessage("Set a start point first! Use /goatsetstart");
            setEnabled(false);
            return;
        }

        if (!warping) {
            if (isAtPoint(client, startPoint, 1.0) && areChunksLoaded(client, startPoint)) {
                ChatUtils.sendErrorMessage("At start point but no crops found!");
                setEnabled(false);
            } else {
                ChatUtils.sendWarningMessage("Not near crop! Warping...");
                markWarpCommand();
                client.player.networkHandler.sendChatCommand("warp garden");
                warping = true;
            }
            return;
        }

        if (isAtPoint(client, startPoint, 1.0)) {
            warping = false;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // ── Block Scanning (Vertical) ──
    // ══════════════════════════════════════════════════════════════

    private List<BlockPos> findTargetBlocks(MinecraftClient client) {
        List<BlockPos> results = new ArrayList<>();
        int px = MathHelper.floor(client.player.getX());
        int py = Math.round((float) client.player.getY());
        int pz = MathHelper.floor(client.player.getZ());

        for (int yo = 0; yo <= 2; yo++) {
            for (int xo = -1; xo <= 1; xo++) {
                for (int zo = -1; zo <= 1; zo++) {
                    BlockPos pos = new BlockPos(px + xo, py + yo, pz + zo);
                    String id = getRegistryId(client, pos);
                    if (isCrop(id)) {
                        results.add(pos);
                    }
                }
            }
        }
        return results;
    }

    private record ScanResult(BlockPos pos, String registryId, int offset) {}

    private List<ScanResult> scanSides(MinecraftClient client) {
        int px = MathHelper.floor(client.player.getX());
        int py = Math.round((float) client.player.getY());
        int pz = MathHelper.floor(client.player.getZ());

        float yaw = ((lockedYaw % 360) + 360) % 360;
        int dx = 0, dz = 0;
        if (yaw >= 315 || yaw < 45) dz = 1;
        else if (yaw >= 45 && yaw < 135) dx = -1;
        else if (yaw >= 135 && yaw < 225) dz = -1;
        else if (yaw >= 225 && yaw < 315) dx = 1;

        int perpDx = -dz;
        int perpDz = dx;

        List<ScanResult> results = new ArrayList<>();
        for (int offset = -5; offset <= 5; offset++) {
            if (offset == 0) continue;
            int sx = px + perpDx * offset;
            int sz = pz + perpDz * offset;
            BlockPos pos = new BlockPos(sx, py, sz);
            String id = getRegistryId(client, pos);
            results.add(new ScanResult(pos, id, offset));
        }
        return results;
    }

    private record SidesDistance(int maxDistLeft, int maxDistRight) {}

    private SidesDistance getSidesDistance(MinecraftClient client) {
        List<ScanResult> sides = scanSides(client);
        int maxDistLeft = 0;
        int maxDistRight = 0;

        for (ScanResult sr : sides) {
            int dist = Math.abs(sr.offset);
            if (sr.registryId != null && !sr.registryId.contains("air") && !sr.registryId.contains("water")) {
                if (sr.offset < 0 && dist > maxDistLeft) maxDistLeft = dist;
                else if (sr.offset > 0 && dist > maxDistRight) maxDistRight = dist;
            }
        }
        return new SidesDistance(maxDistLeft, maxDistRight);
    }

    private record CropCorners(int leftAge, int rightAge) {}

    private CropCorners checkForCropAge(MinecraftClient client, int yOffset) {
        float yaw = ((lockedYaw % 360) + 360) % 360;
        int fx = 0, fz = 0;
        int sx = 0, sz = 0;

        if (yaw >= 315 || yaw < 45) { fz = 1; sx = -1; }
        else if (yaw >= 45 && yaw < 135) { fx = -1; sz = -1; }
        else if (yaw >= 135 && yaw < 225) { fz = -1; sx = 1; }
        else if (yaw >= 225 && yaw < 315) { fx = 1; sz = 1; }

        int px = MathHelper.floor(client.player.getX());
        int py = Math.round((float) client.player.getY());
        int pz = MathHelper.floor(client.player.getZ());

        int rightAge = getCropAge(client, new BlockPos(px + fx + sx, py + yOffset, pz + fz + sz));
        int leftAge = getCropAge(client, new BlockPos(px + fx - sx, py + yOffset, pz + fz - sz));

        return new CropCorners(leftAge, rightAge);
    }

    private int getCropAge(MinecraftClient client, BlockPos pos) {
        BlockState blockState = client.world.getBlockState(pos);
        for (Property<?> prop : blockState.getProperties()) {
            if (prop.getName().equals("age") && prop instanceof IntProperty intProp) {
                return blockState.get(intProp);
            }
        }
        return -1;
    }

    // ══════════════════════════════════════════════════════════════
    // ── Block Helpers (shared) ──
    // ══════════════════════════════════════════════════════════════

    private record BlockInfo(BlockPos pos, String registryId, double centerX, double centerZ) {}

    private BlockInfo getBlockInFront(MinecraftClient client, int offsetDist, int yOffset) {
        float yaw = ((lockedYaw % 360) + 360) % 360;
        int dx = 0, dz = 0;
        if (yaw >= 315 || yaw < 45) dz = 1;
        else if (yaw >= 45 && yaw < 135) dx = -1;
        else if (yaw >= 135 && yaw < 225) dz = -1;
        else if (yaw >= 225 && yaw < 315) dx = 1;

        int tx = MathHelper.floor(client.player.getX()) + dx * offsetDist;
        int ty = Math.round((float) client.player.getY()) + yOffset;
        int tz = MathHelper.floor(client.player.getZ()) + dz * offsetDist;
        BlockPos pos = new BlockPos(tx, ty, tz);
        String id = getRegistryId(client, pos);
        if (id == null) return null;
        return new BlockInfo(pos, id, tx + 0.5, tz + 0.5);
    }

    private String getRegistryId(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return null;
        Block block = client.world.getBlockState(pos).getBlock();
        Identifier id = Registries.BLOCK.getId(block);
        return id.toString();
    }

    private boolean isCrop(String registryId) {
        return registryId != null && VERTICAL_CROPS.contains(registryId);
    }

    // ── Shared utility ──

    private boolean shouldMonitorMovementState() {
        return switch (state) {
            case V_DECIDE_MOVEMENT, V_IDLE_CHECKS, S_LEFT, S_RIGHT, S_SWITCHING_LANE -> true;
            default -> false;
        };
    }

    private boolean tickAntiStuck(MinecraftClient client, String reason, float preferredYaw) {
        if (client.player == null || !client.player.isOnGround()) {
            resetAntiStuck();
            return false;
        }

        if (antiStuckNudgeTicks > 0) {
            antiStuckNudgeTicks--;
            float escapeYaw = antiStuckEscapeYaw != null ? antiStuckEscapeYaw : preferredYaw + 180.0f;
            applyAntiStuckMovement(client, escapeYaw);
            InputUtils.setSprint(false);
            InputUtils.setJump(true);
            InputUtils.setSneak(false);
            InputUtils.setAttack(true);

            if (antiStuckNudgeTicks == 0) {
                InputUtils.releaseAll();
                antiStuckTicks = 0;
                antiStuckLastPos = null;
            }
            return true;
        }

        Vec3d pos = playerPos(client);
        if (antiStuckLastPos != null) {
            double dx = pos.x - antiStuckLastPos.x;
            double dz = pos.z - antiStuckLastPos.z;
            double movedSq = dx * dx + dz * dz;
            if (movedSq < ANTI_STUCK_MIN_HORIZONTAL_MOVE_SQ) {
                antiStuckTicks++;
            } else {
                antiStuckTicks = 0;
                antiStuckAttempts = 0;
            }
        }
        antiStuckLastPos = pos;

        boolean lowBpsStuck = bpsMonitor.getValue()
            && shouldMonitorMovementState()
            && BPSTracker.isLowMovementFor(BPS_STUCK_THRESHOLD, BPS_STUCK_DURATION_MS);

        if (antiStuckTicks < ANTI_STUCK_TICKS && !lowBpsStuck) {
            return false;
        }

        InputUtils.releaseAll();
        antiStuckTicks = 0;
        antiStuckLastPos = null;

        if (antiStuckAttempts < ANTI_STUCK_MAX_NUDGES) {
            antiStuckAttempts++;
            antiStuckEscapeYaw = chooseAntiStuckEscapeYaw(client, preferredYaw);
            antiStuckNudgeTicks = ANTI_STUCK_NUDGE_TICKS;
            debugMsg("Anti-stuck nudge: " + reason);
            return true;
        }

        debugMsg("Anti-stuck reset: " + reason);
        antiStuckAttempts = 0;
        resetMovementStateAfterStuck(client);
        return true;
    }

    private void resetAntiStuck() {
        antiStuckTicks = 0;
        antiStuckNudgeTicks = 0;
        antiStuckAttempts = 0;
        antiStuckLastPos = null;
        antiStuckEscapeYaw = null;
    }

    private void resetMovementStateAfterStuck(MinecraftClient client) {
        InputUtils.releaseAll();
        if (isVertical()) {
            movementKey = null;
            inAir = false;
            state = State.V_DECIDE_MOVEMENT;
        } else {
            changeLaneDir = null;
            sSwitchStartX = client.player.getX();
            sSwitchStartZ = client.player.getZ();
            state = State.S_NONE;
        }
    }

    private float chooseAntiStuckEscapeYaw(MinecraftClient client, float preferredYaw) {
        BlockPos intersecting = findIntersectingBlock(client);
        if (intersecting != null) {
            Direction side = findClosestClearSide(client, intersecting);
            if (side != null) {
                Vec3d escape = Vec3d.ofCenter(intersecting).add(
                    side.getOffsetX() * ANTI_STUCK_ESCAPE_CHECK_DISTANCE,
                    0.0,
                    side.getOffsetZ() * ANTI_STUCK_ESCAPE_CHECK_DISTANCE
                );
                return angleToPoint(client, escape.x, escape.z);
            }
        }

        float bestYaw = MathHelper.wrapDegrees(preferredYaw + 180.0f);
        float bestScore = Float.MAX_VALUE;
        for (int offset = 0; offset < 360; offset += 20) {
            float yaw = MathHelper.wrapDegrees(preferredYaw + offset);
            if (!isEscapeDirectionClear(client, yaw)) continue;

            float score = Math.abs(MathHelper.wrapDegrees(yaw - preferredYaw));
            if (score < bestScore) {
                bestScore = score;
                bestYaw = yaw;
            }
        }
        return bestYaw;
    }

    private void applyAntiStuckMovement(MinecraftClient client, float desiredYaw) {
        float relYaw = MathHelper.wrapDegrees(desiredYaw - client.player.getYaw());

        boolean forward = relYaw >= -67.5f && relYaw < 67.5f;
        boolean left = relYaw >= 22.5f && relYaw < 157.5f;
        boolean back = relYaw >= 112.5f || relYaw < -112.5f;
        boolean right = relYaw >= -157.5f && relYaw < -22.5f;

        InputUtils.setForward(forward);
        InputUtils.setBack(back);
        InputUtils.setLeft(left);
        InputUtils.setRight(right);
    }

    private BlockPos findIntersectingBlock(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;

        Box playerBox = client.player.getBoundingBox().expand(0.02, 0.0, 0.02);
        BlockPos playerBlock = client.player.getBlockPos();
        BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = 0; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = playerBlock.add(dx, dy, dz);
                    if (isPassable(client, pos)) continue;

                    Box blockBox = new Box(pos);
                    if (!playerBox.intersects(blockBox)) continue;

                    double distSq = Vec3d.ofCenter(pos).squaredDistanceTo(playerPos(client));
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private Direction findClosestClearSide(MinecraftClient client, BlockPos pos) {
        Direction best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos adjacent = pos.offset(direction);
            if (!isPassable(client, adjacent) || !isPassable(client, adjacent.up())) continue;

            Vec3d side = Vec3d.ofCenter(pos).add(direction.getOffsetX() * 0.5, 0.0, direction.getOffsetZ() * 0.5);
            double distSq = side.squaredDistanceTo(playerPos(client));
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = direction;
            }
        }
        return best;
    }

    private boolean isEscapeDirectionClear(MinecraftClient client, float yaw) {
        Vec3d pos = playerPos(client);
        double radians = Math.toRadians(yaw);
        double dx = -Math.sin(radians) * ANTI_STUCK_ESCAPE_CHECK_DISTANCE;
        double dz = Math.cos(radians) * ANTI_STUCK_ESCAPE_CHECK_DISTANCE;

        BlockPos feet = BlockPos.ofFloored(pos.x + dx, pos.y + 0.1, pos.z + dz);
        BlockPos body = BlockPos.ofFloored(pos.x + dx, pos.y + 0.9, pos.z + dz);
        BlockPos head = BlockPos.ofFloored(pos.x + dx, pos.y + 1.8, pos.z + dz);
        return isPassable(client, feet) && isPassable(client, body) && isPassable(client, head);
    }

    private boolean isPassable(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return true;
        return client.world.getBlockState(pos).getCollisionShape(client.world, pos).isEmpty();
    }

    private Vec3d playerPos(MinecraftClient client) {
        return new Vec3d(client.player.getX(), client.player.getY(), client.player.getZ());
    }

    private float snapYaw(float yaw) {
        float step = snapTo45.getValue() ? 45.0f : 90.0f;
        return Math.round(yaw / step) * step;
    }

    private float angleToBlock(MinecraftClient client, BlockPos pos) {
        return angleToPoint(client, pos.getX() + 0.5, pos.getZ() + 0.5);
    }

    private float angleToPoint(MinecraftClient client, double x, double z) {
        double dx = x - client.player.getX();
        double dz = z - client.player.getZ();
        return (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0f;
    }

    private boolean isAtPoint(MinecraftClient client, BlockPos point, double minDist) {
        double dx = client.player.getX() - (point.getX() + 0.5);
        double dy = client.player.getY() - point.getY();
        double dz = client.player.getZ() - (point.getZ() + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz) < minDist;
    }

    private boolean isHorizontallyAtPoint(MinecraftClient client, BlockPos point, double minDist) {
        double dx = client.player.getX() - (point.getX() + 0.5);
        double dz = client.player.getZ() - (point.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz) < minDist;
    }

    private boolean areChunksLoaded(MinecraftClient client, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        return client.world.getChunkManager().isChunkLoaded(chunkX, chunkZ);
    }

    private int findItemInHotbar(MinecraftClient client, String name) {
        for (int i = 0; i < 9; i++) {
            var stack = client.player.getInventory().getStack(i);
            if (!stack.isEmpty() && stack.getName().getString().contains(name)) {
                return i;
            }
        }
        return -1;
    }

    private void debugMsg(String msg) {
        if (debug.getValue()) {
            ChatUtils.sendDebugMessage(msg);
        }
    }

    // ── Rewarp trigger: auto /warp garden when reaching a set coordinate ──

    private void checkRewarpTrigger(MinecraftClient client) {
        if (rewarpTriggerPoint == null || rewarpTriggered) return;
        if (state == State.REWARP || state == State.WAITING || state == State.PEST_CLEANING) return;

        if (isAtPoint(client, rewarpTriggerPoint, 1.5)) {
            rewarpTriggered = true;
            InputUtils.releaseAll();
            rotation.clear();

            GoatModule pestModule = ModuleManager.findByName("PestCleaner");
            if (pestModule instanceof PestCleaner) {
                debugMsg("Rewarp trigger reached, running PestCleaner first...");
                ChatUtils.sendInfoMessage("Rewarp trigger reached, cleaning pests first...");
                pestModule.setEnabled(true);
                state = State.PEST_CLEANING;
            } else {
                markWarpCommand();
                ChatUtils.sendInfoMessage("Rewarp trigger reached, warping...");
                client.player.networkHandler.sendChatCommand("warp garden");
                state = State.REWARP;
            }
        }
    }

    private void tickPestCleaning(MinecraftClient client) {
        GoatModule pestModule = ModuleManager.findByName("PestCleaner");
        if (pestModule == null || !pestModule.isEnabled()) {
            if (pestModule instanceof PestCleaner pestCleaner && pestCleaner.consumeAutoRewarpSent()) {
                debugMsg("PestCleaner finished and auto-rewarped...");
                ChatUtils.sendInfoMessage("Pest cleaning done, waiting for rewarp...");
                state = State.REWARP;
                return;
            }

            debugMsg("PestCleaner finished, rewarping...");
            ChatUtils.sendInfoMessage("Pest cleaning done, warping...");
            markWarpCommand();
            client.player.networkHandler.sendChatCommand("warp garden");
            state = State.REWARP;
        }
    }

    private void markWarpCommand() {
        TeleportFailsafe tf = FailsafeManager.getInstance().getFailsafe(TeleportFailsafe.class);
        if (tf != null) tf.markCommand();
    }

    // ══════════════════════════════════════════════════════════════
    // ── Public API ──
    // ══════════════════════════════════════════════════════════════

    public void setStartPoint(MinecraftClient client) {
        if (client.player == null) return;
        startPoint = client.player.getBlockPos();
        ChatUtils.sendSuccessMessage("Start point set: " + startPoint.toShortString());
    }

    public void setEndPoint(MinecraftClient client) {
        if (client.player == null) return;
        endPoint = client.player.getBlockPos();
        ChatUtils.sendSuccessMessage("End point set: " + endPoint.toShortString());
    }

    public void setRewarpTrigger(BlockPos pos) {
        rewarpTriggerPoint = pos;
        if (pos != null) {
            ChatUtils.sendSuccessMessage("Rewarp trigger set: " + pos.toShortString());
        } else {
            ChatUtils.sendWarningMessage("Rewarp trigger cleared.");
        }
    }

    public BlockPos getStartPoint() { return startPoint; }
    public BlockPos getEndPoint() { return endPoint; }
    public BlockPos getRewarpTriggerPoint() { return rewarpTriggerPoint; }

    public void loadSavedPoints(BlockPos startPoint, BlockPos endPoint, BlockPos rewarpTriggerPoint) {
        this.startPoint = startPoint;
        this.endPoint = endPoint;
        this.rewarpTriggerPoint = rewarpTriggerPoint;
    }

    // ── HUD ──

    @Override
    public String getHudName() {
        return "Farming";
    }

    @Override
    public String getHudState() {
        return state.name();
    }
}
