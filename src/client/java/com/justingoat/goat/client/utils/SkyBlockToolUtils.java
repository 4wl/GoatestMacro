package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

import java.util.Set;

public final class SkyBlockToolUtils {
    private static final String[] MINING_TOOL_NAME_PARTS = {
        "drill",
        "pickaxe",
        "pickonimbus",
        "gauntlet",
        "stonk"
    };

    private static final Set<String> VACUUM_NAME_PARTS = Set.of(
        "vacuum"
    );

    private SkyBlockToolUtils() {
    }

    public static boolean equipMiningToolFromHotbar(MinecraftClient client) {
        int slot = findMiningToolSlot(client);
        if (slot < 0) {
            ChatUtils.sendWarningMessage("No mining tool found in hotbar.");
            return false;
        }
        return InventoryUtils.equipHotbarSlot(client, slot);
    }

    public static int findMiningToolSlot(MinecraftClient client) {
        return ToolSelector.findBest(client, ToolSelector.Category.MINING);
    }

    public static int findMiningToolSlot(ClientPlayerEntity player) {
        return ToolSelector.findBest(player, ToolSelector.Category.MINING);
    }

    public static boolean isMiningTool(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        if (stack.isOf(Items.WOODEN_PICKAXE)
                || stack.isOf(Items.STONE_PICKAXE)
                || stack.isOf(Items.IRON_PICKAXE)
                || stack.isOf(Items.GOLDEN_PICKAXE)
                || stack.isOf(Items.DIAMOND_PICKAXE)
                || stack.isOf(Items.NETHERITE_PICKAXE)) {
            return true;
        }

        return ItemNameUtils.containsAny(stack, MINING_TOOL_NAME_PARTS);
    }

    public static int findAoteOrAotvSlot(ClientPlayerEntity player) {
        return ToolSelector.findBest(player, ToolSelector.Category.AOTE);
    }

    public static boolean isAoteOrAotv(ItemStack stack) {
        return ItemNameUtils.containsAny(stack, "Aspect of the Void", "Aspect of the End");
    }

    public static int findVacuumSlot(MinecraftClient client) {
        return ToolSelector.findBest(client, ToolSelector.Category.VACUUM);
    }

    public static boolean isVacuum(ItemStack stack) {
        return ItemNameUtils.containsAny(stack, VACUUM_NAME_PARTS.toArray(String[]::new));
    }

    public static boolean isFarmingHoe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (stack.isOf(Items.WOODEN_HOE)
                || stack.isOf(Items.STONE_HOE)
                || stack.isOf(Items.IRON_HOE)
                || stack.isOf(Items.GOLDEN_HOE)
                || stack.isOf(Items.DIAMOND_HOE)
                || stack.isOf(Items.NETHERITE_HOE)) {
            return true;
        }
        return ItemNameUtils.containsAny(stack, "Hoe", "Dicer", "Chopper");
    }

    public static boolean isScythe(ItemStack stack) {
        return ItemNameUtils.contains(stack, "Scythe");
    }

    public static boolean isAxe(ItemStack stack) {
        return ItemNameUtils.contains(stack, "Treecapitator")
            || (ItemNameUtils.contains(stack, "Axe") && !ItemNameUtils.contains(stack, "Pick"));
    }

    public static boolean isStonkOrPickaxe(ItemStack stack) {
        return ItemNameUtils.containsAny(stack, "Pickaxe", "Stonk");
    }
}
