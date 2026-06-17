package com.justingoat.goat.client.module.farming;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroHudInfo;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.AimController;
import com.justingoat.goat.client.utils.BlockScanner;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.InventoryUtils;
import com.justingoat.goat.client.utils.LagDetector;
import com.justingoat.goat.client.utils.PlotUtils;
import com.justingoat.goat.client.utils.SkyBlockToolUtils;
import com.justingoat.goat.client.utils.TickTimer;
import com.justingoat.goat.client.utils.ToolSelector;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class PlotCleaningHelper extends GoatModule implements MacroHudInfo {
    private enum ToolType { SCYTHE, AXE, PICKAXE }

    private final NumberValue scanRadius;
    private final BooleanValue autoTool;
    private final BooleanValue lagPause;
    private final BooleanValue debug;
    private final AimController aim = new AimController();

    private BlockPos target = null;
    private BlockPos activeBreak = null;
    private ToolType targetTool = null;
    private int scanDelay = 0;
    private final TickTimer brokenCooldown = new TickTimer();

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

        brokenCooldown.tick();
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
            InventoryUtils.equipHotbarSlot(client, slot);
        }

        Vec3d center = Vec3d.ofCenter(target);
        aim.aimAtAndApply(client, center, 0.75f);

        if (!aim.isRoughlyFacing() || client.player.getEyePos().squaredDistanceTo(center) > 5.2 * 5.2) {
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
        aim.clear();
        InputUtils.releaseAll();
    }

    private BlockPos findTarget(MinecraftClient client) {
        int radius = (int) scanRadius.getValue();
        BlockPos player = client.player.getBlockPos();
        Integer plotId = PlotUtils.getPlotIdAt(player);
        return BlockScanner.findClosest(client, player, radius, 3, 3, pos -> {
            if (plotId != null && !PlotUtils.isInsidePlot(plotId, Vec3d.ofCenter(pos))) return false;
            return canMine(client, pos);
        }).orElse(null);
    }

    private boolean canMine(MinecraftClient client, BlockPos pos) {
        if (brokenCooldown.active() && pos.equals(activeBreak)) return false;
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
        return !stack.isEmpty() && itemNameMatches(stack, type);
    }

    private int findToolSlot(MinecraftClient client, ToolType type) {
        return switch (type) {
            case SCYTHE -> ToolSelector.findBest(client, ToolSelector.Category.SCYTHE);
            case AXE -> ToolSelector.findBest(client, ToolSelector.Category.AXE);
            case PICKAXE -> ToolSelector.findBest(client, ToolSelector.Category.STONK_OR_PICKAXE);
        };
    }

    private boolean itemNameMatches(ItemStack stack, ToolType type) {
        return switch (type) {
            case SCYTHE -> SkyBlockToolUtils.isScythe(stack);
            case AXE -> SkyBlockToolUtils.isAxe(stack);
            case PICKAXE -> SkyBlockToolUtils.isStonkOrPickaxe(stack);
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
