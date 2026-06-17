package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;

public final class CommandUtils {
    private CommandUtils() {
    }

    public static boolean send(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        return send(client, command);
    }

    public static boolean send(MinecraftClient client, String command) {
        if (client == null || client.player == null || client.player.networkHandler == null) return false;
        if (command == null || command.isBlank()) return false;
        client.player.networkHandler.sendChatCommand(command.startsWith("/") ? command.substring(1) : command);
        return true;
    }

    public static boolean warpGarden(MinecraftClient client) {
        return send(client, "warp garden");
    }

    public static boolean openBazaar(MinecraftClient client, String itemName) {
        if (itemName == null || itemName.isBlank()) return false;
        return send(client, "bz " + itemName);
    }

    public static boolean teleportToPlotBarn(MinecraftClient client) {
        return send(client, "tptoplot barn");
    }

    public static boolean plotTeleport(MinecraftClient client, String plotCommandName) {
        if (plotCommandName == null || plotCommandName.isBlank()) return false;
        return send(client, "plottp " + plotCommandName);
    }

    public static boolean evacuate(MinecraftClient client) {
        return send(client, "evacuate");
    }
}
