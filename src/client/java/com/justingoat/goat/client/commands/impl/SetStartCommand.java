package com.justingoat.goat.client.commands.impl;

import com.justingoat.goat.client.commands.Command;
import com.justingoat.goat.client.commands.CommandFeedback;
import com.justingoat.goat.client.config.GoatConfigManager;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.movement.FarmingMacro;
import net.minecraft.client.MinecraftClient;

public class SetStartCommand extends Command {
    public SetStartCommand() {
        super("setstart", "Set farming macro start point", "");
    }

    @Override
    public void execute(String[] args) {
        GoatModule module = ModuleManager.findByName("FarmingMacro");
        if (module instanceof FarmingMacro fm) {
            fm.setStartPoint(MinecraftClient.getInstance());
            GoatConfigManager.save();
            CommandFeedback.success("Farming start point set.");
        } else {
            CommandFeedback.moduleNotFound("FarmingMacro");
        }
    }
}
