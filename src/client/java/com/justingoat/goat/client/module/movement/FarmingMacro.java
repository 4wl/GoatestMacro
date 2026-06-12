package com.justingoat.goat.client.module.movement;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationUtils;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

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

    // ── States ──
    private enum State {
        WAITING,
        // Vertical states
        V_SCAN_FOR_CROP, V_DECIDE_ROTATION, V_DECIDE_ITEM,
        V_DECIDE_MOVEMENT, V_IDLE_CHECKS,
        // S-Shape states: strafe A/D along row, W/S to switch lane
        S_NONE, S_LEFT, S_RIGHT, S_SWITCHING_LANE, S_DROPPING,
        // Shared
        REWARP
    }

    // ── Settings ──
    private final ModeValue farmType;
    private final NumberValue pitch;
    private final BooleanValue snapTo45;
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
            ChatUtils.sendSuccessMessage("FarmingMacro enabled (" + farmType.getValue() + ")");
        } else if (!enabled && wasEnabled) {
            state = State.WAITING;
            rotation.clear();
            InputUtils.releaseAll();
            ChatUtils.sendWarningMessage("FarmingMacro disabled");
        }
    }

    private void resetAllState(MinecraftClient client) {
        warping = false;
        warpDelay = null;
        rewarpTriggered = false;
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
            return;
        } else {
            InputUtils.setForward(false);
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
            inAir = true;
        }

        if (inAir && isOnGround) {
            inAir = false;
            state = State.V_DECIDE_MOVEMENT;
        }
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
        } else {
            InputUtils.setBack(true);
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
        // Check which side has walkable space (crops)
        if (sIsRightWalkable(client)) return State.S_RIGHT;
        if (sIsLeftWalkable(client)) return State.S_LEFT;

        // Scan outward for walls to pick side
        for (int i = 1; i < 180; i++) {
            BlockPos rightPos = sGetRelativeBlockPos(client, i, 0, 0);
            BlockPos leftPos = sGetRelativeBlockPos(client, -i, 0, 0);
            boolean rightBlocked = !sCanWalkThrough(client, rightPos);
            boolean leftBlocked = !sCanWalkThrough(client, leftPos);

            if (rightBlocked && !leftBlocked) return State.S_RIGHT;
            if (leftBlocked && !rightBlocked) return State.S_LEFT;
            if (rightBlocked && leftBlocked) break;
        }
        return State.S_NONE;
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

        if (System.currentTimeMillis() >= warpDelay) {
            client.player.networkHandler.sendChatCommand("warp garden");
            warpDelay = System.currentTimeMillis() + 5000;
        }

        if (isAtPoint(client, startPoint, 1.0)) {
            if (areChunksLoaded(client, startPoint)) {
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
            } else {
                debugMsg("Waiting for chunks...");
            }
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
        if (state == State.REWARP || state == State.WAITING) return;

        if (isAtPoint(client, rewarpTriggerPoint, 1.5)) {
            rewarpTriggered = true;
            debugMsg("Rewarp trigger reached at " + rewarpTriggerPoint.toShortString());
            ChatUtils.sendInfoMessage("Rewarp trigger reached, warping...");
            InputUtils.releaseAll();
            client.player.networkHandler.sendChatCommand("warp garden");
            state = State.REWARP;
        }
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
