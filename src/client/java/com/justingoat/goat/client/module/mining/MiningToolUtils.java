package com.justingoat.goat.client.module.mining;

import com.justingoat.goat.client.utils.SkyBlockToolUtils;
import com.justingoat.goat.client.utils.ToolSelector;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;

public final class MiningToolUtils {
    private MiningToolUtils() {
    }

    public static boolean equipMiningToolFromHotbar(MinecraftClient client) {
        return SkyBlockToolUtils.equipMiningToolFromHotbar(client);
    }

    public static int findMiningToolSlot(ClientPlayerEntity player) {
        return ToolSelector.findBest(player, ToolSelector.Category.MINING);
    }

    public static boolean isMiningTool(ItemStack stack) {
        return SkyBlockToolUtils.isMiningTool(stack);
    }
}
