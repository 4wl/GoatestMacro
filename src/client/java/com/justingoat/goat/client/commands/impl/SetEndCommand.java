package com.justingoat.goat.client.commands.impl;

import com.justingoat.goat.client.commands.Command;
import com.justingoat.goat.client.config.GoatConfigManager;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.movement.FarmingMacro;
import com.justingoat.goat.client.utils.ChatUtils;
import net.minecraft.client.MinecraftClient;

public class SetEndCommand extends Command {
    public SetEndCommand() {
        super("setend", "Set farming macro end point", "");
    }

    @Override
    public void execute(String[] args) {
        GoatModule module = ModuleManager.findByName("FarmingMacro");
        if (module instanceof FarmingMacro fm) {
            fm.setEndPoint(MinecraftClient.getInstance());
            GoatConfigManager.save();
            ChatUtils.sendSuccessMessage("Farming end point set.");
        } else {
            ChatUtils.sendErrorMessage("FarmingMacro module not found.");
        }
    }
}
