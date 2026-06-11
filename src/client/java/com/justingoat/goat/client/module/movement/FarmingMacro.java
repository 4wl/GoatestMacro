package com.justingoat.goat.client.module.movement;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.Random;

public class FarmingMacro extends GoatModule {
    private final BooleanValue holdAttack;
    private final NumberValue delayMin;
    private final NumberValue delayMax;
    private final BooleanValue snapTo45;

    private enum MacroState {
        LANE_WALKING,
        SHIFTING,
        DROPPING,
        WAITING
    }

    private MacroState currentState = MacroState.LANE_WALKING;
    private MacroState stateAfterWait = MacroState.LANE_WALKING;

    private float lockedYaw;
    private boolean walkingForward = true;

    private double shiftStartX, shiftStartZ;
    private boolean shiftGoRight;

    private long waitUntil = 0;
    private final Random random = new Random();

    public FarmingMacro() {
        super("FarmingMacro", ModuleCategory.MOVEMENT, false);
        holdAttack = addBoolean("Hold Attack", true);
        delayMin = addNumber("Delay Min (ms)", 50.0, 0.0, 500.0);
        delayMax = addNumber("Delay Max (ms)", 150.0, 0.0, 500.0);
        snapTo45 = addBoolean("Snap 45°", false);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (enabled) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                lockedYaw = snapYaw(client.player.getYaw());
                client.player.setYaw(lockedYaw);
            }
            currentState = MacroState.LANE_WALKING;
            walkingForward = true;
            waitUntil = 0;
            if (holdAttack.getValue()) {
                InputUtils.setAttack(true);
            }
        } else {
            InputUtils.releaseAll();
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) return;

        if (FailsafeManager.getInstance().hasEmergency()) {
            this.setEnabled(false);
            return;
        }

        if (client.currentScreen != null) {
            InputUtils.releaseAll();
            return;
        }

        client.player.setYaw(lockedYaw);

        if (holdAttack.getValue()) {
            InputUtils.setAttack(true);
        }

        switch (currentState) {
            case WAITING:
                tickWaiting();
                break;
            case LANE_WALKING:
                tickLaneWalking(client);
                break;
            case SHIFTING:
                tickShifting(client);
                break;
            case DROPPING:
                tickDropping(client);
                break;
        }
    }

    private void tickWaiting() {
        InputUtils.setForward(false);
        InputUtils.setBack(false);
        InputUtils.setLeft(false);
        InputUtils.setRight(false);
        InputUtils.setJump(false);
        if (System.currentTimeMillis() >= waitUntil) {
            currentState = stateAfterWait;
        }
    }

    private void tickLaneWalking(MinecraftClient client) {
        InputUtils.setLeft(false);
        InputUtils.setRight(false);
        InputUtils.setJump(false);

        if (walkingForward) {
            InputUtils.setForward(true);
            InputUtils.setBack(false);
        } else {
            InputUtils.setForward(false);
            InputUtils.setBack(true);
        }

        double px = client.player.getX();
        double pz = client.player.getZ();
        int[] fwd = getForwardDir();
        int fdx = fwd[0], fdz = fwd[1];
        if (!walkingForward) { fdx = -fdx; fdz = -fdz; }

        BlockPos feet = client.player.getBlockPos();

        if (isWallAhead(client, feet, fdx, fdz)) {
            InputUtils.releaseAll();
            if (holdAttack.getValue()) InputUtils.setAttack(true);
            shiftGoRight = pickShiftDirection(client, feet);
            shiftStartX = px;
            shiftStartZ = pz;
            transitionTo(MacroState.SHIFTING);
            return;
        }

        BlockPos belowAhead = feet.add(fdx, -1, fdz);
        BlockPos belowAhead2 = feet.add(fdx * 2, -1, fdz * 2);
        if (!isBlockSolid(client, belowAhead) && !isBlockSolid(client, belowAhead2)) {
            InputUtils.setForward(false);
            InputUtils.setBack(false);
            currentState = MacroState.DROPPING;
        }
    }

    private void tickShifting(MinecraftClient client) {
        InputUtils.setForward(false);
        InputUtils.setBack(false);
        InputUtils.setJump(false);

        if (shiftGoRight) {
            InputUtils.setRight(true);
            InputUtils.setLeft(false);
        } else {
            InputUtils.setLeft(true);
            InputUtils.setRight(false);
        }

        double px = client.player.getX();
        double pz = client.player.getZ();
        double dx = px - shiftStartX;
        double dz = pz - shiftStartZ;
        double shifted = Math.sqrt(dx * dx + dz * dz);

        if (shifted >= 1.0) {
            InputUtils.releaseAll();
            if (holdAttack.getValue()) InputUtils.setAttack(true);
            walkingForward = !walkingForward;
            transitionTo(MacroState.LANE_WALKING);
        }
    }

    private void tickDropping(MinecraftClient client) {
        InputUtils.setForward(false);
        InputUtils.setBack(false);
        InputUtils.setLeft(false);
        InputUtils.setRight(false);
        InputUtils.setJump(false);

        if (client.player.isOnGround()) {
            transitionTo(MacroState.LANE_WALKING);
        }
    }

    private void transitionTo(MacroState next) {
        long min = (long) delayMin.getValue();
        long max = (long) delayMax.getValue();
        long delay = min + (long) (random.nextFloat() * Math.max(1, max - min));
        this.waitUntil = System.currentTimeMillis() + delay;
        this.stateAfterWait = next;
        this.currentState = MacroState.WAITING;
    }

    private int[] getForwardDir() {
        float yawRad = lockedYaw * 0.017453292f;
        int fdx = Math.round(-MathHelper.sin(yawRad));
        int fdz = Math.round(MathHelper.cos(yawRad));
        return new int[]{fdx, fdz};
    }

    private int[] getRightDir() {
        float yawRad = lockedYaw * 0.017453292f;
        int rx = Math.round(-MathHelper.cos(yawRad));
        int rz = Math.round(-MathHelper.sin(yawRad));
        return new int[]{rx, rz};
    }

    private boolean isWallAhead(MinecraftClient client, BlockPos feet, int fdx, int fdz) {
        BlockPos aheadFeet = feet.add(fdx, 0, fdz);
        BlockPos aheadBody = aheadFeet.up();
        return isBlockSolid(client, aheadFeet) || isBlockSolid(client, aheadBody);
    }

    private boolean pickShiftDirection(MinecraftClient client, BlockPos feet) {
        int[] right = getRightDir();
        int rx = right[0], rz = right[1];

        BlockPos rightFeet = feet.add(rx, 0, rz);
        BlockPos rightBody = rightFeet.up();
        boolean rightBlocked = isBlockSolid(client, rightFeet) || isBlockSolid(client, rightBody);

        BlockPos leftFeet = feet.add(-rx, 0, -rz);
        BlockPos leftBody = leftFeet.up();
        boolean leftBlocked = isBlockSolid(client, leftFeet) || isBlockSolid(client, leftBody);

        if (rightBlocked && !leftBlocked) return false;
        if (leftBlocked && !rightBlocked) return true;
        return true;
    }

    private float snapYaw(float yaw) {
        float step = snapTo45.getValue() ? 45.0f : 90.0f;
        return Math.round(yaw / step) * step;
    }

    private static boolean isBlockSolid(MinecraftClient client, BlockPos pos) {
        if (client.world == null) return false;
        BlockState state = client.world.getBlockState(pos);
        return !state.getCollisionShape(client.world, pos).isEmpty();
    }
}
