package com.justingoat.goat.client.module.movement;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.pathfinder.*;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.InputUtils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

public class PathfinderTest extends GoatModule {
    private final PathProcessor pathProcessor = new PathProcessor();
    private final FlyPathProcessor flyProcessor = new FlyPathProcessor();
    private final EtherwarpPathfinder etherwarpPathfinder = new EtherwarpPathfinder();
    private volatile boolean computing = false;

    private final ModeValue mode;
    private final NumberValue maxNodes;
    private final NumberValue maxDrop;
    private final BooleanValue sprint;
    private final NumberValue rotationSpeed;
    private final BooleanValue autoRepath;
    private final BooleanValue renderPath;
    private final NumberValue waypointReach;
    private final NumberValue stuckThreshold;
    private final BooleanValue aoteEnabled;

    public PathProcessor getPathProcessor() { return pathProcessor; }
    public FlyPathProcessor getFlyProcessor() { return flyProcessor; }
    public EtherwarpPathfinder getEtherwarpPathfinder() { return etherwarpPathfinder; }

    public PathfinderTest() {
        super("Pathfinder", ModuleCategory.MACRO, false);
        mode = addMode("Mode", "Walk", "Walk", "Fly", "Etherwarp");
        maxNodes = addNumber("MaxNodes", 100000, 10000, 500000);
        maxDrop = addNumber("MaxDrop", 3, 1, 3);
        sprint = addBoolean("Sprint", true);
        rotationSpeed = addNumber("RotSpeed", 0.5, 0.1, 1.0);
        autoRepath = addBoolean("AutoRepath", true);
        renderPath = addBoolean("RenderPath", true);
        waypointReach = addNumber("WPReach", 0.7, 0.3, 2.0);
        stuckThreshold = addNumber("StuckTicks", 30, 10, 60);
        aoteEnabled = addBoolean("AOTE", false);
    }

    public String getMode() { return mode.getValue(); }
    public int getMaxNodes() { return (int) maxNodes.getValue(); }
    public int getMaxDrop() { return (int) maxDrop.getValue(); }
    public boolean canSprint() { return sprint.getValue(); }
    public float getRotationSpeed() { return (float) rotationSpeed.getValue(); }
    public boolean canAutoRepath() { return autoRepath.getValue(); }
    public boolean shouldRenderPath() { return renderPath.getValue(); }
    public float getWaypointReach() { return (float) waypointReach.getValue(); }
    public int getStuckThreshold() { return (int) stuckThreshold.getValue(); }
    public boolean isAoteEnabled() { return aoteEnabled.getValue(); }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            pathProcessor.stop();
            flyProcessor.stop();
            etherwarpPathfinder.cancel();
            computing = false;
        }
    }

    public void pathTarget(BlockPos target) {
        String m = getMode();
        switch (m) {
            case "Fly" -> pathTargetFly(target);
            case "Etherwarp" -> pathTargetEtherwarp(target);
            default -> pathTargetWalk(target);
        }
    }

    public void pathTargetWalk(BlockPos target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        BlockPos start = client.player.getBlockPos().down();
        client.player.sendMessage(
            Text.literal("§7[Goat] Calculating walk path to " + target.toShortString() + "..."), false);
        this.setEnabled(true);
        computing = true;

        int nodes = getMaxNodes();
        int drop = getMaxDrop();

        CompletableFuture.supplyAsync(() -> {
            List<PathNode> raw = AStarPathfinder.computePath(start, target, nodes, drop);
            return raw != null ? PathSmoother.smooth(raw) : null;
        }).thenAccept(path -> client.execute(() -> {
            computing = false;
            if (path != null) {
                client.player.sendMessage(
                    Text.literal("§a[Goat] Walk path found — " + path.size() + " nodes."), false);
                pathProcessor.setPath(path);
            } else {
                client.player.sendMessage(
                    Text.literal("§c[Goat] No walk path found."), false);
                this.setEnabled(false);
            }
        }));
    }

    public void pathTargetFly(BlockPos target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        BlockPos start = client.player.getBlockPos();
        client.player.sendMessage(
            Text.literal("§7[Goat] Calculating fly path to " + target.toShortString() + "..."), false);
        this.setEnabled(true);
        computing = true;

        int nodes = getMaxNodes();

        FlyPathProcessor.computePathAsync(start, target, nodes).thenAccept(path -> client.execute(() -> {
            computing = false;
            if (path != null) {
                client.player.sendMessage(
                    Text.literal("§a[Goat] Fly path found — " + path.size() + " waypoints."), false);
                flyProcessor.setPath(path);
            } else {
                client.player.sendMessage(
                    Text.literal("§c[Goat] No fly path found."), false);
                this.setEnabled(false);
            }
        }));
    }

    public void pathTargetEtherwarp(BlockPos target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        this.setEnabled(true);
        etherwarpPathfinder.findPath(target);
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || computing) return;

        String m = getMode();

        if ("Etherwarp".equals(m)) {
            etherwarpPathfinder.tick(client);
            EtherwarpPathfinder.State s = etherwarpPathfinder.getState();
            if (s == EtherwarpPathfinder.State.DONE || s == EtherwarpPathfinder.State.FAILED) {
                this.setEnabled(false);
            }
            return;
        }

        if ("Fly".equals(m)) {
            flyProcessor.tick(client, getRotationSpeed());
            if (flyProcessor.isDone()) {
                this.setEnabled(false);
            }
            return;
        }

        // Walk mode
        pathProcessor.tick(client, this);
        if (pathProcessor.isDone()) {
            this.setEnabled(false);
        }
    }
}
