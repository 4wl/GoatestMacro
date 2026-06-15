package com.justingoat.goat.client.module.farming;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.LagDetector;
import com.justingoat.goat.client.utils.PlotUtils;
import com.justingoat.goat.client.utils.RotationUtils;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class PlotCleaningHelper extends GoatModule implements MacroHudInfo {
    private enum ToolType { SCYTHE, AXE, PICKAXE }

    private final NumberValue scanRadius;
    private final BooleanValue autoTool;
    private final BooleanValue lagPause;
    private final BooleanValue debug;
    private final RotationUtils rotation = new RotationUtils();

    private BlockPos target = null;
    private BlockPos activeBreak = null;
    private ToolType targetTool = null;
    private int scanDelay = 0;
    private int brokenCooldownTicks = 0;

    public PlotCleaningHelper() {
        super("PlotCleaningHelper", ModuleCategory.MACRO, false);
        scanRadius = addNumber("ScanRadius", 5.0, 2.0, 8.0);
        autoTool = addBoolean("AutoTool", true);
        lagPause = addBoolean("LagPause", true);
        debug = addBoolean("Debug", false);
    }

    @Override
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = isEnabled();
        super.setEnabled(enabled);
        if (!enabled && wasEnabled) {
            stop();
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null || client.interactionManager == null) return;
        if (client.currentScreen != null || (lagPause.getValue() && LagDetector.isLagging())) {
            InputUtils.releaseAll();
            return;
        }

        if (brokenCooldownTicks > 0) brokenCooldownTicks--;
        if (target == null || !canMine(client, target)) {
            target = null;
            activeBreak = null;
            targetTool = null;
            scanDelay--;
            if (scanDelay > 0) {
                InputUtils.setAttack(false);
                return;
            }
            scanDelay = 8;
            target = findTarget(client);
            if (target == null) {
                InputUtils.setAttack(false);
                return;
            }
            targetTool = classify(client, target);
            debugMsg("Target " + target.toShortString() + " with " + targetTool);
        }

        if (autoTool.getValue() && targetTool != null) {
            int slot = findToolSlot(client, targetTool);
            if (slot == -1) {
                target = null;
                return;
            }
            client.player.getInventory().setSelectedSlot(slot);
        }

        Vec3d center = Vec3d.ofCenter(target);
        float[] look = RotationUtils.lookAt(
            client.player.getX(), client.player.getEyeY(), client.player.getZ(),
            center.x, center.y, center.z
        );
        rotation.setTarget(look[0], look[1]);
        rotation.setSpeed(0.75f);
        rotation.tick();
        client.player.setYaw(rotation.getCurrentYaw());
        client.player.setPitch(rotation.getCurrentPitch());

        if (!rotation.isRoughlyFacing() || client.player.getEyePos().squaredDistanceTo(center) > 5.2 * 5.2) {
            InputUtils.setAttack(false);
            return;
        }

        Vec3d eye = client.player.getEyePos();
        Direction side = Direction.getFacing(
            center.x - eye.x,
            center.y - eye.y,
            center.z - eye.z
        ).getOpposite();

        if (!target.equals(activeBreak)) {
            client.interactionManager.attackBlock(target, side);
            activeBreak = target.toImmutable();
        } else {
            client.interactionManager.updateBlockBreakingProgress(target, side);
        }
        client.player.swingHand(Hand.MAIN_HAND);
        InputUtils.setAttack(true);
    }

    private void stop() {
        target = null;
        activeBreak = null;
        targetTool = null;
        rotation.clear();
        InputUtils.releaseAll();
    }

    private BlockPos findTarget(MinecraftClient client) {
        int radius = (int) scanRadius.getValue();
        BlockPos player = client.player.getBlockPos();
        Integer plotId = PlotUtils.getPlotIdAt(player);
        List<BlockPos> candidates = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = player.add(dx, dy, dz).toImmutable();
                    if (plotId != null && !PlotUtils.isInsidePlot(plotId, Vec3d.ofCenter(pos))) continue;
                    if (!canMine(client, pos)) continue;
                    candidates.add(pos);
                }
            }
        }

        return candidates.stream()
            .min(Comparator.comparingDouble(pos -> client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos))))
            .orElse(null);
    }

    private boolean canMine(MinecraftClient client, BlockPos pos) {
        if (brokenCooldownTicks > 0 && pos.equals(activeBreak)) return false;
        ToolType type = classify(client, pos);
        if (type == null) return false;
        if (!client.player.isOnGround() && type != ToolType.SCYTHE) return false;
        if (!autoTool.getValue()) return currentHeldToolMatches(client, type);
        return findToolSlot(client, type) != -1;
    }

    private ToolType classify(MinecraftClient client, BlockPos pos) {
        Block block = client.world.getBlockState(pos).getBlock();
        Identifier id = Registries.BLOCK.getId(block);
        String path = id.getPath();

        if (path.contains("grass") || path.contains("fern") || path.contains("flower")
            || path.contains("leaves") || path.contains("sapling") || path.contains("mushroom")) {
            return ToolType.SCYTHE;
        }
        if (path.contains("log") || path.contains("wood") || path.contains("stem") || path.contains("hyphae")) {
            return ToolType.AXE;
        }
        if ((path.contains("stone") || path.contains("cobble") || path.contains("slab") || path.contains("stairs"))
            && !path.contains("bedrock")) {
            return ToolType.PICKAXE;
        }
        return null;
    }

    private boolean currentHeldToolMatches(MinecraftClient client, ToolType type) {
        ItemStack stack = client.player.getMainHandStack();
        return !stack.isEmpty() && itemNameMatches(stack.getName().getString(), type);
    }

    private int findToolSlot(MinecraftClient client, ToolType type) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = client.player.getInventory().getStack(slot);
            if (!stack.isEmpty() && itemNameMatches(stack.getName().getString(), type)) return slot;
        }
        return -1;
    }

    private boolean itemNameMatches(String name, ToolType type) {
        return switch (type) {
            case SCYTHE -> name.contains("Scythe");
            case AXE -> name.contains("Treecapitator") || (name.contains("Axe") && !name.contains("Pick"));
            case PICKAXE -> name.contains("Pickaxe") || name.contains("Stonk");
        };
    }

    private void debugMsg(String msg) {
        if (debug.getValue()) {
            com.justingoat.goat.client.utils.ChatUtils.sendDebugMessage("[PlotClean] " + msg);
        }
    }

    @Override
    public String getHudName() {
        return "PlotClean";
    }

    @Override
    public String getHudState() {
        return target == null ? "SCANNING" : "BREAKING " + targetTool;
    }
}
