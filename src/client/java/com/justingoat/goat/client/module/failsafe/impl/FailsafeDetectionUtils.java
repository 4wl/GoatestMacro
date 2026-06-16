package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.SkyBlockUtils;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

final class FailsafeDetectionUtils {
    private static final MinecraftClient CLIENT = MinecraftClient.getInstance();

    private FailsafeDetectionUtils() {}

    static boolean canCheckMacro() {
        return FailsafeManager.getInstance().isAnyMacroActive()
            && CLIENT.player != null
            && CLIENT.world != null;
    }

    static boolean canCheckGardenMacro() {
        return canCheckMacro() && SkyBlockUtils.isInGarden();
    }

    static boolean hasBadEffect() {
        if (CLIENT.player == null) return false;
        return CLIENT.player.hasStatusEffect(StatusEffects.POISON)
            || CLIENT.player.hasStatusEffect(StatusEffects.WITHER)
            || CLIENT.player.hasStatusEffect(StatusEffects.BLINDNESS)
            || CLIENT.player.hasStatusEffect(StatusEffects.NAUSEA)
            || CLIENT.player.hasStatusEffect(StatusEffects.MINING_FATIGUE)
            || CLIENT.player.hasStatusEffect(StatusEffects.HUNGER)
            || CLIENT.player.hasStatusEffect(StatusEffects.SLOWNESS)
            || CLIENT.player.hasStatusEffect(StatusEffects.WEAKNESS)
            || CLIENT.player.isOnFire();
    }

    static boolean isInventoryFull() {
        if (CLIENT.player == null) return false;
        for (int i = 9; i < 36; i++) {
            ItemStack stack = CLIENT.player.getInventory().getStack(i);
            if (stack.isEmpty()) return false;
        }
        return true;
    }

    static int countNearby(Block target, int horizontalRadius, int down, int up) {
        int count = 0;
        for (BlockPos pos : nearbyPositions(horizontalRadius, down, up)) {
            if (CLIENT.world.getBlockState(pos).isOf(target)) count++;
        }
        return count;
    }

    static List<BlockPos> nearbyPositions(int horizontalRadius, int down, int up) {
        List<BlockPos> positions = new ArrayList<>();
        if (CLIENT.player == null) return positions;
        BlockPos base = CLIENT.player.getBlockPos();
        for (int x = -horizontalRadius; x <= horizontalRadius; x++) {
            for (int y = -down; y <= up; y++) {
                for (int z = -horizontalRadius; z <= horizontalRadius; z++) {
                    positions.add(base.add(x, y, z));
                }
            }
        }
        return positions;
    }

    static boolean isSuspiciousPlacedBlock(BlockState state) {
        Block block = state.getBlock();
        if (state.isAir()) return false;
        if (isCrop(block)) return false;
        if (block == Blocks.WATER || block == Blocks.LADDER || block == Blocks.OAK_TRAPDOOR) return false;
        return state.isFullCube(CLIENT.world, BlockPos.ORIGIN);
    }

    static boolean isCrop(Block block) {
        String id = Registries.BLOCK.getId(block).toString();
        return id.equals("minecraft:wheat")
            || id.equals("minecraft:carrots")
            || id.equals("minecraft:potatoes")
            || id.equals("minecraft:nether_wart")
            || id.equals("minecraft:melon")
            || id.equals("minecraft:pumpkin")
            || id.equals("minecraft:sugar_cane")
            || id.equals("minecraft:cactus")
            || id.equals("minecraft:cocoa")
            || id.equals("minecraft:red_mushroom")
            || id.equals("minecraft:brown_mushroom");
    }

    static String normalize(String message) {
        return message == null ? "" : message.toLowerCase().trim();
    }
}
