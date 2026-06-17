package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

public final class ContainerUtils {
    private ContainerUtils() {
    }

    public static ItemStack getStack(GenericContainerScreenHandler handler, int slot) {
        if (handler == null || slot < 0 || slot >= handler.slots.size()) return ItemStack.EMPTY;
        return handler.slots.get(slot).getStack();
    }

    public static boolean clickSlot(GenericContainerScreenHandler handler, int slot) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (handler == null || client.player == null || client.interactionManager == null) return false;
        if (slot < 0 || slot >= handler.slots.size()) return false;
        client.interactionManager.clickSlot(handler.syncId, slot, 0, SlotActionType.PICKUP, client.player);
        return true;
    }

    public static List<String> getLoreLines(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Collections.emptyList();

        List<String> result = new ArrayList<>();
        List<Text> tooltip = stack.getTooltip(
            net.minecraft.item.Item.TooltipContext.DEFAULT,
            null,
            net.minecraft.item.tooltip.TooltipType.BASIC
        );
        for (int i = 1; i < tooltip.size(); i++) {
            String line = ItemNameUtils.strip(tooltip.get(i).getString()).trim();
            if (!line.isBlank()) result.add(line);
        }
        return result;
    }

    public static String getLoreString(ItemStack stack) {
        return String.join(" ", getLoreLines(stack));
    }

    public static int firstContainerSlot(GenericContainerScreenHandler handler) {
        return 0;
    }

    public static int visibleContainerLimit(GenericContainerScreenHandler handler) {
        return handler == null ? 0 : Math.min(handler.slots.size(), 54);
    }

    public static Integer findSlot(GenericContainerScreenHandler handler, Predicate<ItemStack> predicate) {
        if (handler == null || predicate == null) return null;
        int limit = visibleContainerLimit(handler);
        for (int slot = firstContainerSlot(handler); slot < limit; slot++) {
            ItemStack stack = getStack(handler, slot);
            if (!stack.isEmpty() && predicate.test(stack)) return slot;
        }
        return null;
    }

    public static Integer findSlot(GenericContainerScreenHandler handler, BiPredicate<Integer, ItemStack> predicate) {
        if (handler == null || predicate == null) return null;
        int limit = visibleContainerLimit(handler);
        for (int slot = firstContainerSlot(handler); slot < limit; slot++) {
            ItemStack stack = getStack(handler, slot);
            if (!stack.isEmpty() && predicate.test(slot, stack)) return slot;
        }
        return null;
    }

    public static Integer findSlotByName(GenericContainerScreenHandler handler, String needle) {
        return findSlot(handler, stack -> ItemNameUtils.contains(stack, needle));
    }

    public static Integer findSlotByLore(GenericContainerScreenHandler handler, String needle) {
        return findSlot(handler, stack -> ItemNameUtils.contains(getLoreString(stack), needle));
    }

    public static Integer findSlotByNameOrLore(GenericContainerScreenHandler handler, String needle) {
        return findSlot(handler, stack ->
            ItemNameUtils.contains(stack, needle) || ItemNameUtils.contains(getLoreString(stack), needle)
        );
    }

    public static boolean clickFirstMatching(GenericContainerScreenHandler handler, Predicate<ItemStack> predicate) {
        Integer slot = findSlot(handler, predicate);
        return slot != null && clickSlot(handler, slot);
    }
}
