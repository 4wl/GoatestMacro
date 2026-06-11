package com.justingoat.goat.client.module.movement;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.pathfinder.AStarPathfinder;
import com.justingoat.goat.client.module.pathfinder.PathNode;
import com.justingoat.goat.client.module.pathfinder.PathProcessor;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.InputUtils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;

public class PathfinderTest extends GoatModule {
    private final PathProcessor pathProcessor = new PathProcessor();
    private volatile boolean computing = false;

    private final NumberValue maxNodes;
    private final NumberValue maxDrop;
    private final BooleanValue sprint;
    private final NumberValue rotationSpeed;
    private final BooleanValue autoRepath;
    private final BooleanValue renderPath;
    private final NumberValue waypointReach;
    private final NumberValue stuckThreshold;

    public PathProcessor getPathProcessor() {
        return pathProcessor;
    }

    public PathfinderTest() {
        super("Pathfinder", ModuleCategory.MOVEMENT, false);
        maxNodes = addNumber("MaxNodes", 100000, 10000, 500000);
        maxDrop = addNumber("MaxDrop", 3, 1, 3);
        sprint = addBoolean("Sprint", true);
        rotationSpeed = addNumber("RotSpeed", 0.5, 0.1, 1.0);
        autoRepath = addBoolean("AutoRepath", true);
        renderPath = addBoolean("RenderPath", true);
        waypointReach = addNumber("WPReach", 0.7, 0.3, 2.0);
        stuckThreshold = addNumber("StuckTicks", 30, 10, 60);
    }

    public int getMaxNodes() { return (int) maxNodes.getValue(); }
    public int getMaxDrop() { return (int) maxDrop.getValue(); }
    public boolean canSprint() { return sprint.getValue(); }
    public float getRotationSpeed() { return (float) rotationSpeed.getValue(); }
    public boolean canAutoRepath() { return autoRepath.getValue(); }
    public boolean shouldRenderPath() { return renderPath.getValue(); }
    public float getWaypointReach() { return (float) waypointReach.getValue(); }
    public int getStuckThreshold() { return (int) stuckThreshold.getValue(); }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            InputUtils.releaseAll();
        }
    }

    public void pathTarget(BlockPos target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        BlockPos start = client.player.getBlockPos().down();
        client.player.sendMessage(
            Text.literal("§7[Goat] Calculating path to " + target.toShortString() + "..."), false);
        this.setEnabled(true);
        computing = true;

        int nodes = getMaxNodes();
        int drop = getMaxDrop();

        CompletableFuture.supplyAsync(() ->
            AStarPathfinder.computePath(start, target, nodes, drop)
        ).thenAccept(path -> client.execute(() -> {
            computing = false;
            if (path != null) {
                client.player.sendMessage(
                    Text.literal("§a[Goat] Path found — " + path.size() + " nodes."), false);
                pathProcessor.setPath(path);
            } else {
                client.player.sendMessage(
                    Text.literal("§c[Goat] No path found."), false);
                this.setEnabled(false);
            }
        }));
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || computing) return;
        pathProcessor.tick(client, this);
        if (pathProcessor.isDone()) {
            this.setEnabled(false);
        }
    }
}
