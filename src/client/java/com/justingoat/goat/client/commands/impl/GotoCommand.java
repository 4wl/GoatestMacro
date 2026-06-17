package com.justingoat.goat.client.commands.impl;

import com.justingoat.goat.client.commands.Argument;
import com.justingoat.goat.client.commands.Command;
import com.justingoat.goat.client.commands.CommandFeedback;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.movement.PathfinderTest;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class GotoCommand extends Command {
    public GotoCommand() {
        super("goto", "Creates a path to the specified coordinates", "<x> <y> <z>");
    }

    @Override
    public void execute(String[] args) {
        if (args.length != 3) {
            CommandFeedback.usage(this);
            return;
        }

        int x = Integer.parseInt(args[0]);
        int y = Integer.parseInt(args[1]);
        int z = Integer.parseInt(args[2]);
        BlockPos target = new BlockPos(x, y, z);

        GoatModule module = ModuleManager.findByName("Pathfinder");
        if (module instanceof PathfinderTest pt) {
            pt.pathTargetWalk(target);
            CommandFeedback.pathing("Pathing to", target);
        } else {
            CommandFeedback.moduleNotFound("Pathfinder");
        }
    }

    @Override
    public List<Argument> getArguments() {
        Argument x = new Argument("x", Argument.ArgumentType.INTEGER);
        Argument y = x.addChild("y", Argument.ArgumentType.INTEGER);
        y.addChild("z", Argument.ArgumentType.INTEGER);
        return List.of(x);
    }
}
