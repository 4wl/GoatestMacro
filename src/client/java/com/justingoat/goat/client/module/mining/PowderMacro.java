package com.justingoat.goat.client.module.mining;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.text.Text;

public class PowderMacro extends GoatModule {

    public enum PowderState { IDLE, MINING, CHEST, RETURNING }

    private final NumberValue height, width, speed, compression, maxPitch;

    private PowderState state = PowderState.IDLE;
    private float pivotYaw, pivotPitch;
    private long startTime;
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

    public PowderState getPowderState() { return state; }

    @Override
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = isEnabled();
        super.setEnabled(enabled);
        if (enabled && !wasEnabled) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) { super.setEnabled(false); return; }

            pivotYaw = client.player.getYaw();
            pivotPitch = client.player.getPitch();
            startTime = System.currentTimeMillis();
            hasSavedRotation = false;
            targetChest = null;
            state = PowderState.MINING;

            InputUtils.setAttack(true);
            InputUtils.setSneak(true);
            message("§a[Goat] PowderMacro enabled.");
        } else if (!enabled && wasEnabled) {
            state = PowderState.IDLE;
            InputUtils.setAttack(false);
            InputUtils.setSneak(false);
            targetChest = null;
            message("§c[Goat] PowderMacro disabled.");
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null) return;

        switch (state) {
            case MINING -> tickMining(client);
            case CHEST -> tickChest(client);
            case RETURNING -> tickReturning(client);
            default -> {}
        }
    }

    public void onChestSpawn() {
        if (!isEnabled() || state == PowderState.CHEST) return;
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        savedYaw = client.player.getYaw();
        savedPitch = client.player.getPitch();
        hasSavedRotation = true;
        targetChest = null;
        state = PowderState.CHEST;
    }

    public void onChestOpen() {
        if (!isEnabled()) return;
        targetChest = null;
        InputUtils.setSneak(true);
        InputUtils.setAttack(true);
        state = PowderState.RETURNING;
    }

    private void tickMining(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
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
            String blockId = Registries.BLOCK.getId(client.world.getBlockState(targetChest).getBlock()).toString();
            if (!blockId.contains("chest")) {
                targetChest = null;
            }
        }

        if (targetChest == null) {
            targetChest = findNearestChest(client);
        }

        if (targetChest != null) {
            float yaw = aimYaw(client.player, targetChest);
            float pitch = aimPitch(client.player, targetChest);
            applyRotation(client.player, yaw, pitch);
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
            state = PowderState.MINING;
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
        startTime = System.currentTimeMillis() - (long) ((currentAngle / speed.getValue()) * 1000);
    }

    private BlockPos findNearestChest(MinecraftClient client) {
        if (client.player == null || client.world == null) return null;
        int bx = MathHelper.floor(client.player.getX());
        int by = MathHelper.floor(client.player.getY());
        int bz = MathHelper.floor(client.player.getZ());

        BlockPos closest = null;
        double closestDist = Double.MAX_VALUE;

        for (int dx = -CHEST_SEARCH_RADIUS; dx <= CHEST_SEARCH_RADIUS; dx++) {
            for (int dy = -CHEST_SEARCH_RADIUS; dy <= CHEST_SEARCH_RADIUS; dy++) {
                for (int dz = -CHEST_SEARCH_RADIUS; dz <= CHEST_SEARCH_RADIUS; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos pos = new BlockPos(bx + dx, by + dy, bz + dz);
                    String id = Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
                    if (id.contains("chest")) {
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d < closestDist) {
                            closestDist = d;
                            closest = pos;
                        }
                    }
                }
            }
        }
        return closest;
    }

    private static float aimYaw(ClientPlayerEntity player, BlockPos target) {
        double dx = target.getX() + 0.5 - player.getX();
        double dz = target.getZ() + 0.5 - player.getZ();
        return (float) -(Math.toDegrees(Math.atan2(dx, dz)));
    }

    private static float aimPitch(ClientPlayerEntity player, BlockPos target) {
        double dx = target.getX() + 0.5 - player.getX();
        double dy = target.getY() + 0.5 - player.getEyePos().y;
        double dz = target.getZ() + 0.5 - player.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        return (float) -(Math.toDegrees(Math.atan2(dy, dist)));
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
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }
}
