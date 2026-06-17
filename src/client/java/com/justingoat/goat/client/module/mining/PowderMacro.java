package com.justingoat.goat.client.module.mining;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.BlockScanner;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.MacroClock;
import com.justingoat.goat.client.utils.RotationUtils;
import com.justingoat.goat.client.utils.StateTimer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class PowderMacro extends GoatModule {

    public enum PowderState { IDLE, MINING, CHEST, RETURNING }

    private final NumberValue height, width, speed, compression, maxPitch;

    private final StateTimer<PowderState> stateTimer = new StateTimer<>(PowderState.IDLE);
    private float pivotYaw, pivotPitch;
    private final MacroClock loopClock = new MacroClock();
    private float savedYaw, savedPitch;
    private boolean hasSavedRotation;
    private BlockPos targetChest;

    private static final float RETURN_THRESHOLD = 2.0f;
    private static final float RETURN_SPEED = 0.15f;
    private static final int CHEST_SEARCH_RADIUS = 3;

    private static final float SENSITIVITY = 0.25f;
    private static final float GCD;
    static {
        float f = SENSITIVITY * 0.6f + 0.2f;
        GCD = f * f * f * 8.0f;
    }

    public PowderMacro() {
        super("PowderMacro", ModuleCategory.MACRO, false);
        height = addNumber("Height", 8, 1, 30);
        width = addNumber("Width", 12, 1, 30);
        speed = addNumber("Speed", 4, 1, 20);
        compression = addNumber("TopCompression", 0.4, 0.1, 1.0);
        maxPitch = addNumber("MaxPitch", 75, 45, 90);
    }

    public PowderState getPowderState() { return stateTimer.state(); }

    @Override
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = isEnabled();
        super.setEnabled(enabled);
        if (enabled && !wasEnabled) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { super.setEnabled(false); return; }

            pivotYaw = client.player.getYaw();
            pivotPitch = client.player.getPitch();
            loopClock.mark();
            hasSavedRotation = false;
            targetChest = null;
            setState(PowderState.MINING);

            InputUtils.setAttack(true);
            InputUtils.setSneak(true);
            message("§a[Goat] PowderMacro enabled.");
        } else if (!enabled && wasEnabled) {
            setState(PowderState.IDLE);
            InputUtils.setAttack(false);
            InputUtils.setSneak(false);
            targetChest = null;
            message("§c[Goat] PowderMacro disabled.");
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null) return;

        switch (stateTimer.state()) {
            case MINING -> tickMining(client);
            case CHEST -> tickChest(client);
            case RETURNING -> tickReturning(client);
            default -> {}
        }
    }

    public void onChestSpawn() {
        if (!isEnabled() || stateTimer.is(PowderState.CHEST)) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        savedYaw = client.player.getYaw();
        savedPitch = client.player.getPitch();
        hasSavedRotation = true;
        targetChest = null;
        setState(PowderState.CHEST);
    }

    public void onChestOpen() {
        if (!isEnabled()) return;
        targetChest = null;
        InputUtils.setSneak(true);
        InputUtils.setAttack(true);
        setState(PowderState.RETURNING);
    }

    private void tickMining(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        double elapsed = loopClock.elapsed() / 1000.0;
        double angle = elapsed * speed.getValue();

        double dPitch = Math.sin(angle) * height.getValue();
        if (dPitch < 0) dPitch *= compression.getValue();

        float targetYaw = (float) (pivotYaw + Math.cos(angle) * width.getValue());
        float targetPitchVal = (float) Math.min(pivotPitch + dPitch, maxPitch.getValue());

        applyRotation(player, targetYaw, targetPitchVal);
    }

    private void tickChest(MinecraftClient client) {
        InputUtils.setAttack(false);
        InputUtils.setSneak(false);

        if (targetChest != null) {
            if (!BlockScanner.registryId(client, targetChest).contains("chest")) {
                targetChest = null;
            }
        }

        if (targetChest == null) {
            targetChest = findNearestChest(client);
        }

        if (targetChest != null) {
            float[] look = RotationUtils.lookAt(
                client.player.getEyePos().x,
                client.player.getEyePos().y,
                client.player.getEyePos().z,
                targetChest.getX() + 0.5,
                targetChest.getY() + 0.5,
                targetChest.getZ() + 0.5
            );
            applyRotation(client.player, look[0], look[1]);
        }
    }

    private void tickReturning(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        float targetY = hasSavedRotation ? savedYaw : pivotYaw;
        float targetP = hasSavedRotation ? savedPitch : pivotPitch;

        float diffYaw = MathHelper.wrapDegrees(targetY - player.getYaw());
        float diffPitch = targetP - player.getPitch();
        double dist = Math.sqrt(diffYaw * diffYaw + diffPitch * diffPitch);

        if (dist < RETURN_THRESHOLD) {
            syncLoopAngle(player);
            setState(PowderState.MINING);
        } else {
            applyRotation(player, player.getYaw() + diffYaw * RETURN_SPEED,
                    player.getPitch() + diffPitch * RETURN_SPEED);
        }
    }

    private void syncLoopAngle(ClientPlayerEntity player) {
        double dYaw = player.getYaw() - pivotYaw;
        double dPitch = player.getPitch() - pivotPitch;
        if (dPitch < 0 && compression.getValue() != 0) {
            dPitch /= compression.getValue();
        }
        double currentAngle = Math.atan2(dPitch / height.getValue(), dYaw / width.getValue());
        loopClock.markOffsetFromNow(-(long) ((currentAngle / speed.getValue()) * 1000));
    }

    private BlockPos findNearestChest(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;
        return BlockScanner.findClosest(client, client.player.getBlockPos(), CHEST_SEARCH_RADIUS, CHEST_SEARCH_RADIUS,
            CHEST_SEARCH_RADIUS, pos -> !pos.equals(client.player.getBlockPos()) && BlockScanner.registryId(client, pos).contains("chest")
        ).orElse(null);
    }

    private static void applyRotation(ClientPlayerEntity player, float targetYaw, float targetPitch) {
        float yawDelta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float pitchDelta = targetPitch - player.getPitch();
        yawDelta = Math.round(yawDelta / GCD) * GCD;
        pitchDelta = Math.round(pitchDelta / GCD) * GCD;
        player.setYaw(player.getYaw() + yawDelta);
        player.setPitch(MathHelper.clamp(player.getPitch() + pitchDelta, -90, 90));
    }

    private void message(String msg) {
        ChatUtils.sendRawMessage(msg);
    }

    private void setState(PowderState next) {
        stateTimer.set(next);
    }
}
