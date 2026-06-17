package com.justingoat.goat.client.commands;

import com.justingoat.goat.client.utils.ChatUtils;
import net.minecraft.util.math.BlockPos;

public final class CommandFeedback {
    private CommandFeedback() {
    }

    public static void usage(Command command) {
        ChatUtils.sendErrorMessage("Usage: " + command.getUsage());
    }

    public static void moduleNotFound(String moduleName) {
        ChatUtils.sendErrorMessage(moduleName + " module not found.");
    }

    public static void success(String message) {
        ChatUtils.sendSuccessMessage(message);
    }

    public static void error(String message) {
        ChatUtils.sendErrorMessage(message);
    }

    public static void warning(String message) {
        ChatUtils.sendWarningMessage(message);
    }

    public static void info(String message) {
        ChatUtils.sendInfoMessage(message);
    }

    public static void header(String title) {
        ChatUtils.sendHeader(title);
    }

    public static void pathing(String verb, BlockPos pos) {
        ChatUtils.sendSuccessMessage(verb + " " + pos.getX() + " " + pos.getY() + " " + pos.getZ());
    }
}
