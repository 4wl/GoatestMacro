package com.justingoat.goat.client.module.movement;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.pathfinder.AStarPathfinder;
import com.justingoat.goat.client.module.pathfinder.PathNode;
import com.justingoat.goat.client.module.pathfinder.PathProcessor;
import com.justingoat.goat.client.utils.InputUtils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;

public class PathfinderTest extends GoatModule {
    private final PathProcessor pathProcessor = new PathProcessor();
    private volatile boolean computing = false;

    public PathProcessor getPathProcessor() {
        return pathProcessor;
    }

    public PathfinderTest() {
        super("Pathfinder", ModuleCategory.MOVEMENT, false);
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (!enabled) {
            InputUtils.releaseAll();
        }
    }

    /**
     * Kick off async A* computation. Result dispatches back to the render
     * thread via client.execute().
     */
    public void pathTarget(BlockPos target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        BlockPos start = client.player.getBlockPos().down();
        client.player.sendMessage(
            Text.literal("§7[Goat] Calculating path to " + target.toShortString() + "..."), false);
        this.setEnabled(true);
        computing = true;

        CompletableFuture.supplyAsync(() ->
            AStarPathfinder.computePath(start, target, 100000)
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
        pathProcessor.tick(client);
        if (pathProcessor.isDone()) {
            this.setEnabled(false);
        }
    }
}
