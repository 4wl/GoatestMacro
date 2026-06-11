package com.justingoat.goat.client.utils;

import com.justingoat.goat.client.mixin.PlayerListHudAccessor;
import net.minecraft.client.MinecraftClient;

import java.util.Collections;
import java.util.List;

public class TabUtils {

    private static PlayerListHudAccessor getAccessorOrNull() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.inGameHud == null || client.inGameHud.getPlayerListHud() == null) {
            return null;
        }
        return (PlayerListHudAccessor) client.inGameHud.getPlayerListHud();
    }

    public static List<String> getTabLines() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return Collections.emptyList();

        PlayerListHudAccessor accessor = getAccessorOrNull();
        if (accessor == null) return Collections.emptyList();

        return accessor.invokeCollectPlayerEntries().stream()
            .filter(entry -> entry.getDisplayName() != null)
            .map(entry -> StringUtils.stripColor(entry.getDisplayName().getString()).trim())
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
