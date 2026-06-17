package com.justingoat.goat.client.commands.impl;

import com.justingoat.goat.client.commands.Argument;
import com.justingoat.goat.client.commands.Command;
import com.justingoat.goat.client.commands.CommandFeedback;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.movement.PathfinderTest;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class WaypointCommand extends Command {
    public WaypointCommand() {
        super("waypoint", "Manages Pathfinder waypoint markers", "<add|remove|clear|list|run>");
    }

    @Override
    public void execute(String[] args) {
        PathfinderTest pathfinder = getPathfinder();
        if (pathfinder == null) {
            CommandFeedback.moduleNotFound("Pathfinder");
            return;
        }

        if (args.length == 0) {
            CommandFeedback.usage(this);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "add" -> addWaypoint(pathfinder, args);
            case "remove" -> removeWaypoint(pathfinder, args);
            case "clear" -> {
                pathfinder.clearWaypoints();
                CommandFeedback.success("Cleared Pathfinder waypoints.");
            }
            case "list" -> listWaypoints(pathfinder);
            case "run" -> runRoute(pathfinder, args);
            default -> CommandFeedback.error("Unknown waypoint action: " + args[0]);
        }
    }

    @Override
    public List<Argument> getArguments() {
        Argument add = new Argument("add");
        Argument addX = add.addChild("x", Argument.ArgumentType.INTEGER);
        Argument addY = addX.addChild("y", Argument.ArgumentType.INTEGER);
        addY.addChild("z", Argument.ArgumentType.INTEGER);

        Argument remove = new Argument("remove");
        remove.addChild("index", Argument.ArgumentType.INTEGER);

        Argument clear = new Argument("clear");
        Argument list = new Argument("list");

        Argument run = new Argument("run");
        Argument runX = run.addChild("x", Argument.ArgumentType.INTEGER);
        Argument runY = runX.addChild("y", Argument.ArgumentType.INTEGER);
        runY.addChild("z", Argument.ArgumentType.INTEGER);

        return List.of(add, remove, clear, list, run);
    }

    private void addWaypoint(PathfinderTest pathfinder, String[] args) {
        BlockPos pos;
        if (args.length == 1) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null) {
                CommandFeedback.error("Player not available.");
                return;
            }
            pos = client.player.getBlockPos().down();
        } else if (args.length == 4) {
            pos = parsePos(args, 1);
        } else {
            CommandFeedback.error("Usage: /goat waypoint add [x y z]");
            return;
        }

        pathfinder.addWaypoint(pos);
        CommandFeedback.success("Added waypoint #" + pathfinder.getWaypoints().size() + " at " + pos.toShortString());
    }

    private void removeWaypoint(PathfinderTest pathfinder, String[] args) {
        if (args.length != 2) {
            CommandFeedback.error("Usage: /goat waypoint remove <index>");
            return;
        }

        int index = Integer.parseInt(args[1]) - 1;
        if (pathfinder.removeWaypoint(index)) {
            CommandFeedback.success("Removed waypoint #" + (index + 1) + ".");
        } else {
            CommandFeedback.error("Waypoint index out of range.");
        }
    }

    private void listWaypoints(PathfinderTest pathfinder) {
        List<BlockPos> waypoints = pathfinder.getWaypoints();
        if (waypoints.isEmpty()) {
            CommandFeedback.info("No Pathfinder waypoints set.");
            return;
        }

        CommandFeedback.header("Pathfinder Waypoints");
        for (int i = 0; i < waypoints.size(); i++) {
            CommandFeedback.info("#" + (i + 1) + " " + waypoints.get(i).toShortString());
        }
    }

    private void runRoute(PathfinderTest pathfinder, String[] args) {
        if (args.length != 4) {
            CommandFeedback.error("Usage: /goat waypoint run <x> <y> <z>");
            return;
        }

        BlockPos target = parsePos(args, 1);
        pathfinder.pathTargetWalk(target);
        CommandFeedback.success("Pathing via " + pathfinder.getWaypoints().size()
            + " waypoints to " + target.toShortString());
    }

    private BlockPos parsePos(String[] args, int offset) {
        int x = Integer.parseInt(args[offset]);
        int y = Integer.parseInt(args[offset + 1]);
        int z = Integer.parseInt(args[offset + 2]);
        return new BlockPos(x, y, z);
    }

    private PathfinderTest getPathfinder() {
        GoatModule module = ModuleManager.findByName("Pathfinder");
        return module instanceof PathfinderTest pathfinder ? pathfinder : null;
    }
}
