package com.justingoat.goat.client.module.movement;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.pathfinder.AStarPathfinder;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.events.EventListener;
import com.justingoat.goat.client.events.EventManager;
import com.justingoat.goat.client.events.impl.packet.ChatMessageEvent;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.module.failsafe.impl.TeleportFailsafe;
import com.justingoat.goat.client.module.farming.PestCleaner;
import com.justingoat.goat.client.utils.AntiStuckController;
import com.justingoat.goat.client.utils.BPSTracker;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.CommandUtils;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.InventoryUtils;
import com.justingoat.goat.client.utils.LagDetector;
import com.justingoat.goat.client.utils.RotationUtils;
import com.justingoat.goat.client.utils.SkyBlockToolUtils;
import com.justingoat.goat.client.utils.ToolSelector;
import com.justingoat.goat.client.utils.SkyBlockUtils;
import com.justingoat.goat.client.utils.WorldUtils;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
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
    private static final long SERVER_RECOVERY_GRACE_MS = 90_000L;
    private static final long SERVER_RECOVERY_LOCATION_GRACE_MS = 5_000L;
    private static final long SERVER_RECOVERY_SKYBLOCK_RETRY_MS = 10_000L;
    private static final long SERVER_RECOVERY_GARDEN_RETRY_MS = 5_000L;
    private static final long SERVER_RECOVERY_RETURN_TIMEOUT_MS = 35_000L;
    private static final double SERVER_RECOVERY_RETURN_REACH_DISTANCE_SQ = 0.64;
    private static final int IMMATURE_CROP_TICK_LIMIT = 50;
    private static final int IMMATURE_CROP_BLOCK_LIMIT = 8;
    private static final int NO_CROP_TICK_LIMIT = 240;
    private static final int NO_CROP_SCAN_RADIUS = 2;

    // ── States ──
    private enum State {
        WAITING,
        // Vertical states
        V_SCAN_FOR_CROP, V_DECIDE_ROTATION, V_DECIDE_ITEM,
        V_DECIDE_MOVEMENT, V_IDLE_CHECKS,
        // S-Shape states: strafe A/D along row, W/S to switch lane
        S_NONE, S_LEFT, S_RIGHT, S_SWITCHING_LANE, S_DROPPING,
        // Shared
        PEST_CLEANING, REWARP, SERVER_RECOVERY
    }

    // ── Settings ──
    private final ModeValue farmType;
    private final NumberValue pitch;
    private final BooleanValue snapTo45;
    private final BooleanValue lagPause;
    private final BooleanValue bpsMonitor;
    private final BooleanValue serverRecovery;
    private final BooleanValue cropGuard;
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
    private boolean recoveringFromServerMove = false;
    private long expectedServerMoveUntil = 0L;
    private long nextServerRecoveryCommandAt = 0L;
    private String serverRecoveryReason = "";
    private BlockPos lastFarmingResumeBlock = null;
    private float lastFarmingResumeYaw = 0.0f;
    private float lastFarmingResumePitch = 0.0f;
    private BlockPos serverRecoveryReturnBlock = null;
    private float serverRecoveryReturnYaw = 0.0f;
    private float serverRecoveryReturnPitch = 0.0f;
    private boolean serverRecoveryReturnPathStarted = false;
    private long serverRecoveryReturnStartTime = 0L;
    private boolean returnToSavedPositionAfterRewarp = false;
    private int immatureCropTicks = 0;
    private int immatureCropBlocks = 0;
    private int noCropTicks = 0;
    private BlockPos lastCropGuardBlock = null;

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
        serverRecovery = addBoolean("ServerRecovery", true);
        cropGuard = addBoolean("CropGuard", true);
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
            if (!EventManager.INSTANCE.isRegistered(this)) {
                EventManager.INSTANCE.register(this);
            }
            resetAllState(client);
            equipBestHoe(client);
            ChatUtils.sendSuccessMessage("FarmingMacro enabled (" + farmType.getValue() + ")");
        } else if (!enabled && wasEnabled) {
            state = State.WAITING;
            rotation.clear();
            stopServerRecovery();
            resetCropGuard();
            resetAntiStuck();
            releaseInputs();
            EventManager.INSTANCE.unregister(this);
            ChatUtils.sendWarningMessage("FarmingMacro disabled");
        }
    }

    private void resetAllState(MinecraftClient client) {
        warping = false;
        warpDelay = null;
        rewarpTriggered = false;
        stopServerRecovery();
        clearSavedReturnPosition();
        lastFarmingResumeBlock = null;
        resetCropGuard();
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

        InventoryUtils.equipHotbarSlot(client, slot);
        debugMsg("Equipped hoe in slot " + (slot + 1));
    }

    private int findBestHoeSlot(MinecraftClient client) {
        return ToolSelector.findBest(client, ToolSelector.Category.FARMING_HOE);
    }

    // ══════════════════════════════════════════════════════════════
    // Main tick dispatch
    // ══════════════════════════════════════════════════════════════

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) return;

        if (recoveringFromServerMove) {
            tickServerRecovery(client);
            return;
        }

        if (FailsafeManager.getInstance().hasEmergency()) {
            setEnabled(false);
            return;
        }

        if (client.currentScreen != null) {
            InputUtils.releaseAll();
            return;
        }

        recordFarmingResumePosition(client);

        if (shouldStartServerRecoveryFromLocation()) {
            startServerRecovery("Left Garden");
            tickServerRecovery(client);
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
            case SERVER_RECOVERY -> tickServerRecovery(client);
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
            int slot = InventoryUtils.findHotbarSlotByName(client, tool);
            if (slot != -1) {
                InventoryUtils.equipHotbarSlot(client, slot);
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
        if (tickCropGuard(client)) return;

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
        if (tickCropGuard(client)) return;

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
            } else {
                startSShapeCornerRecovery(client, "s-shape left corner", lockedYaw + 90.0f);
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
        if (tickCropGuard(client)) return;

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
            } else {
                startSShapeCornerRecovery(client, "s-shape right corner", lockedYaw - 90.0f);
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

    @EventListener
    private void onChatMessage(ChatMessageEvent event) {
        if (!serverRecovery.getValue() || !isEnabled() || event.isOverlay()) return;
        if (event.getMessage().contains(":")) return;

        String message = event.getMessage().toLowerCase(Locale.ROOT);
        if (!isServerMoveMessage(message)) return;

        expectedServerMoveUntil = System.currentTimeMillis() + SERVER_RECOVERY_GRACE_MS;
        startServerRecovery("Server reboot/update");
    }

    private boolean isServerMoveMessage(String message) {
        return message.contains("scheduled reboot")
            || message.contains("scheduled server reboot")
            || (message.contains("server") && message.contains("reboot"))
            || (message.contains("server") && message.contains("restart"))
            || message.contains("game update")
            || message.contains("game updating")
            || message.contains("being updated")
            || message.contains("instance shutdown")
            || message.contains("server is closing");
    }

    private boolean shouldStartServerRecoveryFromLocation() {
        if (!serverRecovery.getValue() || startPoint == null) return false;
        if (getEnabledDurationMillis() < SERVER_RECOVERY_LOCATION_GRACE_MS) return false;
        if (state == State.WAITING || state == State.REWARP || state == State.PEST_CLEANING || state == State.SERVER_RECOVERY) {
            return false;
        }
        if (SkyBlockUtils.isInGarden()) return false;

        String island = SkyBlockUtils.getCurrentIsland();
        if (SkyBlockUtils.isOnSkyBlock() && "Unknown".equalsIgnoreCase(island)) return false;

        return true;
    }

    private void startServerRecovery(String reason) {
        if (!serverRecovery.getValue()) return;
        if (startPoint == null) {
            ChatUtils.sendErrorMessage("Server recovery needs a start point. Use /goatsetstart");
            setEnabled(false);
            return;
        }

        if (!recoveringFromServerMove) {
            captureServerRecoveryReturnPosition(MinecraftClient.getInstance());
            ChatUtils.sendWarningMessage("Server recovery started: " + reason);
        }
        recoveringFromServerMove = true;
        serverRecoveryReason = reason;
        nextServerRecoveryCommandAt = 0L;
        state = State.SERVER_RECOVERY;
        InputUtils.releaseAll();
        rotation.clear();
        resetAntiStuck();
        GoatModule pestModule = ModuleManager.findByName("PestCleaner");
        if (pestModule != null && pestModule.isEnabled()) {
            pestModule.setEnabled(false);
        }
    }

    private void tickServerRecovery(MinecraftClient client) {
        InputUtils.releaseAll();
        resetAntiStuck();

        if (client.currentScreen != null) return;
        if (startPoint == null) {
            ChatUtils.sendErrorMessage("Server recovery needs a start point. Use /goatsetstart");
            setEnabled(false);
            return;
        }

        long now = System.currentTimeMillis();
        if (!SkyBlockUtils.isOnSkyBlock()) {
            sendServerRecoveryCommand(client, "skyblock", SERVER_RECOVERY_SKYBLOCK_RETRY_MS, now);
            return;
        }

        if (!SkyBlockUtils.isInGarden()) {
            sendServerRecoveryCommand(client, "warp garden", SERVER_RECOVERY_GARDEN_RETRY_MS, now);
            return;
        }

        if (serverRecoveryReturnBlock == null
            && (!isHorizontallyAtPoint(client, startPoint, 1.0) || !areChunksLoaded(client, startPoint))) {
            sendServerRecoveryCommand(client, "warp garden", SERVER_RECOVERY_GARDEN_RETRY_MS, now);
            return;
        }

        if (!client.player.isOnGround()) {
            InputUtils.setSneak(true);
            return;
        }

        if (shouldReturnToServerRecoveryPosition(client)) {
            tickReturnToServerRecoveryPosition(client);
            return;
        }

        restoreServerRecoveryRotation(client);
        ChatUtils.sendSuccessMessage("Server recovery complete, resuming FarmingMacro");
        stopServerRecovery();
        resumeAfterRewarpWhenGrounded(client);
    }

    private void sendServerRecoveryCommand(MinecraftClient client, String command, long retryMs, long now) {
        if (client.player == null || client.player.networkHandler == null) return;
        if (now < nextServerRecoveryCommandAt) return;

        markWarpCommand();
        CommandUtils.send(client, command);
        nextServerRecoveryCommandAt = now + retryMs;
        debugMsg("Server recovery command: /" + command + " (" + serverRecoveryReason + ")");
    }

    private void captureServerRecoveryReturnPosition(MinecraftClient client) {
        serverRecoveryReturnPathStarted = false;
        serverRecoveryReturnStartTime = 0L;

        if (lastFarmingResumeBlock != null) {
            serverRecoveryReturnBlock = lastFarmingResumeBlock.toImmutable();
            serverRecoveryReturnYaw = lastFarmingResumeYaw;
            serverRecoveryReturnPitch = lastFarmingResumePitch;
            return;
        }

        serverRecoveryReturnBlock = null;
        if (client.player == null || client.world == null || !SkyBlockUtils.isInGarden()) return;

        serverRecoveryReturnBlock = AStarPathfinder.findNearestStandableGround(client.player.getBlockPos(), true);
        if (serverRecoveryReturnBlock == null) {
            serverRecoveryReturnBlock = client.player.getBlockPos().down();
        }
        serverRecoveryReturnYaw = client.player.getYaw();
        serverRecoveryReturnPitch = client.player.getPitch();
    }

    private boolean shouldReturnToServerRecoveryPosition(MinecraftClient client) {
        return serverRecoveryReturnBlock != null && !isAtBlockPosition(client, serverRecoveryReturnBlock);
    }

    private void tickReturnToServerRecoveryPosition(MinecraftClient client) {
        GoatModule module = ModuleManager.findByName("Pathfinder");
        if (!(module instanceof PathfinderTest pathfinder)) {
            ChatUtils.sendErrorMessage("Server recovery cannot return: Pathfinder module not found.");
            setEnabled(false);
            return;
        }

        if (!serverRecoveryReturnPathStarted) {
            serverRecoveryReturnPathStarted = true;
            serverRecoveryReturnStartTime = System.currentTimeMillis();
            ChatUtils.sendInfoMessage("Returning to saved farming position...");
            pathfinder.pathTargetWalkAllowWater(serverRecoveryReturnBlock);
            return;
        }

        if (isAtBlockPosition(client, serverRecoveryReturnBlock)) {
            pathfinder.setEnabled(false);
            restoreServerRecoveryRotation(client);
            return;
        }

        if (System.currentTimeMillis() - serverRecoveryReturnStartTime > SERVER_RECOVERY_RETURN_TIMEOUT_MS) {
            pathfinder.setEnabled(false);
            ChatUtils.sendErrorMessage("Server recovery return timed out. FarmingMacro stopped.");
            setEnabled(false);
            return;
        }

        if (!pathfinder.isEnabled()) {
            ChatUtils.sendErrorMessage("Server recovery could not return to saved position. FarmingMacro stopped.");
            setEnabled(false);
        }
    }

    private boolean isAtBlockPosition(MinecraftClient client, BlockPos block) {
        if (client.player == null || block == null) return false;

        double dx = client.player.getX() - (block.getX() + 0.5);
        double dz = client.player.getZ() - (block.getZ() + 0.5);
        double dy = Math.abs(client.player.getY() - (block.getY() + 1.0));
        return dx * dx + dz * dz <= SERVER_RECOVERY_RETURN_REACH_DISTANCE_SQ && dy <= 1.25;
    }

    private void restoreServerRecoveryRotation(MinecraftClient client) {
        if (client.player == null || serverRecoveryReturnBlock == null) return;
        client.player.setYaw(serverRecoveryReturnYaw);
        client.player.setPitch(serverRecoveryReturnPitch);
    }

    private void stopServerRecovery() {
        recoveringFromServerMove = false;
        expectedServerMoveUntil = 0L;
        nextServerRecoveryCommandAt = 0L;
        serverRecoveryReason = "";
        clearSavedReturnPosition();
    }

    private void clearSavedReturnPosition() {
        serverRecoveryReturnBlock = null;
        serverRecoveryReturnPathStarted = false;
        serverRecoveryReturnStartTime = 0L;
        returnToSavedPositionAfterRewarp = false;
    }

    public boolean isRecoveringFromServerMove() {
        return recoveringFromServerMove || System.currentTimeMillis() < expectedServerMoveUntil;
    }

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

        if (returnToSavedPositionAfterRewarp && serverRecoveryReturnBlock != null && SkyBlockUtils.isInGarden()) {
            if (!client.player.isOnGround()) {
                InputUtils.releaseAll();
                InputUtils.setSneak(true);
                InputUtils.setJump(false);
                return;
            }

            if (shouldReturnToServerRecoveryPosition(client)) {
                tickReturnToServerRecoveryPosition(client);
                return;
            }

            restoreServerRecoveryRotation(client);
            clearSavedReturnPosition();
            resumeAfterRewarpWhenGrounded(client);
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
            CommandUtils.warpGarden(client);
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
        resetCropGuard();
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
                CommandUtils.warpGarden(client);
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

    private void recordFarmingResumePosition(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        if (!SkyBlockUtils.isInGarden()) return;
        if (!shouldRecordFarmingResumePosition()) return;

        lastFarmingResumeBlock = AStarPathfinder.findNearestStandableGround(client.player.getBlockPos(), true);
        if (lastFarmingResumeBlock == null) {
            lastFarmingResumeBlock = client.player.getBlockPos().down();
        }
        lastFarmingResumeYaw = client.player.getYaw();
        lastFarmingResumePitch = client.player.getPitch();
    }

    private boolean shouldRecordFarmingResumePosition() {
        return switch (state) {
            case V_DECIDE_MOVEMENT, V_IDLE_CHECKS, S_LEFT, S_RIGHT, S_SWITCHING_LANE, S_DROPPING -> true;
            default -> false;
        };
    }

    private boolean tickCropGuard(MinecraftClient client) {
        if (!cropGuard.getValue()) return false;

        CropGuardResult result = getCropGuardTarget(client);
        if (result == CropGuardResult.MATURE) {
            resetCropGuard();
            return false;
        }

        if (result == CropGuardResult.IMMATURE) {
            immatureCropTicks++;
            noCropTicks = 0;
            if (immatureCropTicks >= IMMATURE_CROP_TICK_LIMIT || immatureCropBlocks >= IMMATURE_CROP_BLOCK_LIMIT) {
                stopForCropGuard("Too many immature crops detected. FarmingMacro stopped to avoid starting from an already-farmed row.");
                return true;
            }
            return false;
        }

        if (hasNearbyCropForGuard(client)) {
            noCropTicks = 0;
            immatureCropTicks = 0;
            immatureCropBlocks = 0;
            lastCropGuardBlock = null;
            return false;
        }

        noCropTicks++;
        immatureCropTicks = 0;
        immatureCropBlocks = 0;
        lastCropGuardBlock = null;
        if (noCropTicks >= NO_CROP_TICK_LIMIT) {
            stopForCropGuard("No crops detected while farming. FarmingMacro stopped to avoid continuing from an already-farmed row.");
            return true;
        }
        return false;
    }

    private boolean hasNearbyCropForGuard(MinecraftClient client) {
        if (client.player == null || client.world == null) return false;

        BlockPos base = client.player.getBlockPos();
        for (int x = -NO_CROP_SCAN_RADIUS; x <= NO_CROP_SCAN_RADIUS; x++) {
            for (int y = 0; y <= 2; y++) {
                for (int z = -NO_CROP_SCAN_RADIUS; z <= NO_CROP_SCAN_RADIUS; z++) {
                    if (isCrop(getRegistryId(client, base.add(x, y, z)))) return true;
                }
            }
        }
        return false;
    }

    private CropGuardResult getCropGuardTarget(MinecraftClient client) {
        HitResult hit = client.crosshairTarget;
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            return CropGuardResult.NO_CROP;
        }

        BlockPos pos = blockHit.getBlockPos();
        String id = getRegistryId(client, pos);
        if (!isCrop(id)) return CropGuardResult.NO_CROP;

        int age = getCropAge(client, pos);
        if (isMatureCrop(id, age)) return CropGuardResult.MATURE;

        if (!pos.equals(lastCropGuardBlock)) {
            immatureCropBlocks++;
            lastCropGuardBlock = pos.toImmutable();
        }
        return CropGuardResult.IMMATURE;
    }

    private boolean isMatureCrop(String registryId, int age) {
        if ("minecraft:nether_wart".equals(registryId)) return age >= 3;
        if ("minecraft:wheat".equals(registryId)
            || "minecraft:carrots".equals(registryId)
            || "minecraft:potatoes".equals(registryId)) {
            return age >= 7;
        }
        return false;
    }

    private void stopForCropGuard(String message) {
        ChatUtils.sendErrorMessage(message);
        InputUtils.releaseAll();
        setEnabled(false);
    }

    private void resetCropGuard() {
        immatureCropTicks = 0;
        immatureCropBlocks = 0;
        noCropTicks = 0;
        lastCropGuardBlock = null;
    }

    private enum CropGuardResult {
        MATURE,
        IMMATURE,
        NO_CROP
    }

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
            AntiStuckController.applyMovement(client, escapeYaw);
            InputUtils.setSprint(false);
            InputUtils.setJump(true);
            InputUtils.setSneak(false);
            InputUtils.setAttack(true);

            if (antiStuckNudgeTicks == 0) {
                InputUtils.releaseAll();
                antiStuckTicks = 0;
                antiStuckLastPos = null;
                if (!isVertical()) {
                    changeLaneDir = null;
                    state = State.S_NONE;
                }
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
            antiStuckEscapeYaw = AntiStuckController.chooseEscapeYaw(client, preferredYaw, ANTI_STUCK_ESCAPE_CHECK_DISTANCE);
            antiStuckNudgeTicks = ANTI_STUCK_NUDGE_TICKS;
            debugMsg("Anti-stuck nudge: " + reason);
            return true;
        }

        debugMsg("Anti-stuck reset: " + reason);
        antiStuckAttempts = 0;
        resetMovementStateAfterStuck(client);
        return true;
    }

    private void startSShapeCornerRecovery(MinecraftClient client, String reason, float preferredYaw) {
        InputUtils.releaseAll();
        antiStuckTicks = 0;
        antiStuckLastPos = null;
        antiStuckAttempts = Math.min(antiStuckAttempts + 1, ANTI_STUCK_MAX_NUDGES);
        antiStuckEscapeYaw = AntiStuckController.chooseEscapeYaw(client, preferredYaw, ANTI_STUCK_ESCAPE_CHECK_DISTANCE);
        antiStuckNudgeTicks = ANTI_STUCK_NUDGE_TICKS;
        debugMsg("Anti-stuck corner nudge: " + reason);
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

    private boolean isPassable(MinecraftClient client, BlockPos pos) {
        return WorldUtils.isPassable(client, pos);
    }

    private Vec3d playerPos(MinecraftClient client) {
        return WorldUtils.playerPos(client);
    }

    private float snapYaw(float yaw) {
        float step = snapTo45.getValue() ? 45.0f : 90.0f;
        return Math.round(yaw / step) * step;
    }

    private float angleToBlock(MinecraftClient client, BlockPos pos) {
        return WorldUtils.yawToBlockCenter(client, pos);
    }

    private float angleToPoint(MinecraftClient client, double x, double z) {
        return WorldUtils.yawToPoint(client, x, z);
    }

    private boolean isAtPoint(MinecraftClient client, BlockPos point, double minDist) {
        return WorldUtils.isAtPoint(client, point, minDist);
    }

    private boolean isHorizontallyAtPoint(MinecraftClient client, BlockPos point, double minDist) {
        return WorldUtils.isHorizontallyAtPoint(client, point, minDist);
    }

    private boolean areChunksLoaded(MinecraftClient client, BlockPos pos) {
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        return client.world.getChunkManager().isChunkLoaded(chunkX, chunkZ);
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
                captureServerRecoveryReturnPosition(client);
                returnToSavedPositionAfterRewarp = serverRecoveryReturnBlock != null;
                debugMsg("Rewarp trigger reached, running PestCleaner first...");
                ChatUtils.sendInfoMessage("Rewarp trigger reached, cleaning pests first...");
                pestModule.setEnabled(true);
                state = State.PEST_CLEANING;
            } else {
                markWarpCommand();
                ChatUtils.sendInfoMessage("Rewarp trigger reached, warping...");
                CommandUtils.warpGarden(client);
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
            CommandUtils.warpGarden(client);
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
