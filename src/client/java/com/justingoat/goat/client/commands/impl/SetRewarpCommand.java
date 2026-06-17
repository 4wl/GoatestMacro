package com.justingoat.goat.client.commands.impl;

import com.justingoat.goat.client.commands.Argument;
import com.justingoat.goat.client.commands.Command;
import com.justingoat.goat.client.commands.CommandFeedback;
import com.justingoat.goat.client.config.GoatConfigManager;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.movement.FarmingMacro;
import net.minecraft.client.MinecraftClient;

import java.util.List;

public class SetRewarpCommand extends Command {
    public SetRewarpCommand() {
        super("setrewarp", "Set or clear the re-warp trigger point", "[clear]");
    }

    @Override
    public void execute(String[] args) {
        GoatModule module = ModuleManager.findByName("FarmingMacro");
        if (!(module instanceof FarmingMacro fm)) {
            CommandFeedback.moduleNotFound("FarmingMacro");
            return;
        }

        if (args.length > 0 && args[0].equals("clear")) {
            fm.setRewarpTrigger(null);
            GoatConfigManager.save();
            CommandFeedback.success("Re-warp trigger cleared.");
        } else {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                fm.setRewarpTrigger(client.player.getBlockPos());
                GoatConfigManager.save();
                CommandFeedback.success("Re-warp trigger set.");
            }
        }
    }

    @Override
    public List<Argument> getArguments() {
        return List.of(new Argument("clear"));
    }
}
