package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

public class ChatUtils {
    private static final String PREFIX = "§8[§6Goat§8]§r ";

    public static void sendMessage(String message) {
        sendMessage(Text.literal(message));
    }

    public static void sendMessage(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            MutableText prefixedMessage = Text.literal(PREFIX).append(message);
            client.player.sendMessage(prefixedMessage, false);
        }
    }

    public static void sendSuccessMessage(String message) {
        sendMessage("§a" + message);
    }

    public static void sendErrorMessage(String message) {
        sendMessage("§c" + message);
    }

    public static void sendWarningMessage(String message) {
        sendMessage("§e" + message);
    }

    public static void sendInfoMessage(String message) {
        sendMessage("§b" + message);
    }

    public static void sendDebugMessage(String message) {
        sendMessage("§f[DEBUG]§7 " + message);
    }

    public static void sendRawMessage(String message) {
        sendRawMessage(Text.literal(message));
    }

    public static void sendRawMessage(Text message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(message, false);
        }
    }

    public static void sendSeparator() {
        sendRawMessage("§8§m                                                    ");
    }

    public static void sendHeader(String title) {
        sendSeparator();
        sendMessage("§6§l" + title);
        sendSeparator();
    }
    public static void moduleSuccess(String module, String message) {
        sendSuccessMessage("[" + module + "] " + message);
    }

    public static void moduleWarning(String module, String message) {
        sendWarningMessage("[" + module + "] " + message);
    }

    public static void moduleError(String module, String message) {
        sendErrorMessage("[" + module + "] " + message);
    }

    public static void moduleInfo(String module, String message) {
        sendInfoMessage("[" + module + "] " + message);
    }
}
