package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

public final class ToolSelector {
    public enum Category {
        MINING,
        FARMING_HOE,
        VACUUM,
        AOTE,
        AXE,
        SCYTHE,
        STONK_OR_PICKAXE,
        ROYAL_PIGEON
    }

    public record Candidate(int slot, ItemStack stack, int score) {}

    private ToolSelector() {
    }

    public static int findBest(MinecraftClient client, Category category) {
        if (client == null || client.player == null) return -1;
        return findBest(client.player, category);
    }

    public static int findBest(ClientPlayerEntity player, Category category) {
        if (player == null || category == null) return -1;
        Candidate best = null;
        for (int slot = 0; slot < InventoryUtils.HOTBAR_SIZE; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isEmpty() || !matches(category, stack)) continue;
            Candidate candidate = new Candidate(slot, stack, score(category, stack));
            if (best == null || candidate.score() > best.score()) best = candidate;
        }
        return best == null ? -1 : best.slot();
    }

    public static boolean equipBest(MinecraftClient client, Category category) {
        return InventoryUtils.equipHotbarSlot(client, findBest(client, category));
    }

    public static int findBest(MinecraftClient client, List<Predicate<ItemStack>> priorityPredicates) {
        if (client == null || client.player == null || priorityPredicates == null) return -1;
        for (Predicate<ItemStack> predicate : priorityPredicates) {
            int slot = InventoryUtils.findHotbarSlot(client, predicate);
            if (slot >= 0) return slot;
        }
        return -1;
    }

    public static boolean matches(Category category, ItemStack stack) {
        return switch (category) {
            case MINING -> SkyBlockToolUtils.isMiningTool(stack);
            case FARMING_HOE -> SkyBlockToolUtils.isFarmingHoe(stack);
            case VACUUM -> SkyBlockToolUtils.isVacuum(stack);
            case AOTE -> SkyBlockToolUtils.isAoteOrAotv(stack);
            case AXE -> SkyBlockToolUtils.isAxe(stack);
            case SCYTHE -> SkyBlockToolUtils.isScythe(stack);
            case STONK_OR_PICKAXE -> SkyBlockToolUtils.isStonkOrPickaxe(stack);
            case ROYAL_PIGEON -> ItemNameUtils.contains(stack, "Royal Pigeon");
        };
    }

    private static int score(Category category, ItemStack stack) {
        String name = ItemNameUtils.getStrippedName(stack).toLowerCase();
        int score = stack.getCount();
        if (category == Category.MINING) {
            if (name.contains("drill")) score += 100;
            if (name.contains("gauntlet")) score += 90;
            if (name.contains("pickonimbus")) score += 80;
            if (name.contains("stonk")) score += 70;
        } else if (category == Category.VACUUM) {
            if (name.contains("hooverius")) score += 100;
            if (name.contains("infinivacuum")) score += 90;
            if (name.contains("hyper")) score += 80;
            if (name.contains("turbo")) score += 70;
        } else if (category == Category.FARMING_HOE) {
            if (name.contains("dicer")) score += 90;
            if (name.contains("hoe")) score += 80;
            if (name.contains("chopper")) score += 70;
        }
        return score;
    }
}
