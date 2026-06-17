package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

import java.util.function.Predicate;

public final class InventoryUtils {
    public static final int HOTBAR_SIZE = 9;

    private InventoryUtils() {
    }

    public static int findHotbarSlot(MinecraftClient client, Predicate<ItemStack> predicate) {
        if (client == null || client.player == null) return -1;
        return findHotbarSlot(client.player, predicate);
    }

    public static int findHotbarSlot(ClientPlayerEntity player, Predicate<ItemStack> predicate) {
        if (player == null || predicate == null) return -1;
        for (int slot = 0; slot < HOTBAR_SIZE; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!stack.isEmpty() && predicate.test(stack)) return slot;
        }
        return -1;
    }

    public static int findHotbarSlotByName(MinecraftClient client, String namePart) {
        return findHotbarSlot(client, stack -> ItemNameUtils.contains(stack, namePart));
    }

    public static int findHotbarSlotByName(ClientPlayerEntity player, String namePart) {
        return findHotbarSlot(player, stack -> ItemNameUtils.contains(stack, namePart));
    }

    public static int findHotbarSlotByAnyName(MinecraftClient client, String... nameParts) {
        return findHotbarSlot(client, stack -> ItemNameUtils.containsAny(stack, nameParts));
    }

    public static int findHotbarSlotByAnyName(ClientPlayerEntity player, String... nameParts) {
        return findHotbarSlot(player, stack -> ItemNameUtils.containsAny(stack, nameParts));
    }

    public static boolean equipHotbarSlot(MinecraftClient client, int slot) {
        if (client == null || client.player == null || slot < 0 || slot >= HOTBAR_SIZE) return false;
        InputUtils.setHotbarSlot(slot);
        return true;
    }

    public static boolean equipHotbarSlot(ClientPlayerEntity player, int slot) {
        if (player == null || slot < 0 || slot >= HOTBAR_SIZE) return false;
        InputUtils.setHotbarSlot(slot);
        return true;
    }

    public static boolean equipFirstHotbarSlot(MinecraftClient client, Predicate<ItemStack> predicate) {
        int slot = findHotbarSlot(client, predicate);
        return equipHotbarSlot(client, slot);
    }

    public static boolean isMainInventoryFull(MinecraftClient client) {
        if (client == null || client.player == null) return false;
        for (int slot = HOTBAR_SIZE; slot < 36; slot++) {
            if (client.player.getInventory().getStack(slot).isEmpty()) return false;
        }
        return true;
    }
}
