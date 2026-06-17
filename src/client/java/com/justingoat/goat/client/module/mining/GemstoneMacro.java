package com.justingoat.goat.client.module.mining;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.pathfinder.EtherwarpPathfinder;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.MacroControls;
import com.justingoat.goat.client.utils.PathMath;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class GemstoneMacro extends GoatModule {

    public enum GemState { WAITING, DECIDING, ETHERWARPING, MINING }

    private final BooleanValue ruby, sapphire, amethyst, topaz, jade, jasper, amber;

    private GemState state = GemState.WAITING;
    private List<int[]> route = new ArrayList<>();
    private int currentPointIndex = -1;
    private boolean scanned = false;

    private final MiningBot miningBot;
    private final RotationUtils rotationHelper = new RotationUtils();
    private final EtherwarpPathfinder etherwarp = new EtherwarpPathfinder();

    public GemstoneMacro() {
        super("GemstoneMacro", ModuleCategory.MACRO, false);
        ruby = addBoolean("Ruby", true);
        sapphire = addBoolean("Sapphire", true);
        amethyst = addBoolean("Amethyst", true);
        topaz = addBoolean("Topaz", true);
        jade = addBoolean("Jade", true);
        jasper = addBoolean("Jasper", true);
        amber = addBoolean("Amber", true);

        miningBot = new MiningBot();
        miningBot.setRotationHelper(rotationHelper);
    }

    public void setRoute(List<int[]> route) {
        this.route = route != null ? route : new ArrayList<>();
    }

    public void addRoutePoint(int x, int y, int z) {
        route.add(new int[]{x, y, z});
    }

    public GemState getGemState() { return state; }

    @Override
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = isEnabled();
        super.setEnabled(enabled);
        if (enabled && !wasEnabled) {
            state = GemState.DECIDING;
            currentPointIndex = -1;
            scanned = false;
            MiningToolUtils.equipMiningToolFromHotbar(MinecraftClient.getInstance());
            message("§a[Goat] GemstoneMacro enabled.");
        } else if (!enabled && wasEnabled) {
            state = GemState.WAITING;
            miningBot.stop();
            etherwarp.cancel();
            MacroControls.stopAll();
            scanned = false;
            message("§c[Goat] GemstoneMacro disabled.");
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) return;
        miningBot.setCost(buildGemstoneCosts());

        switch (state) {
            case DECIDING -> tickDeciding(client);
            case ETHERWARPING -> tickEtherwarping(client);
            case MINING -> tickMining(client);
            default -> {}
        }
    }

    private void tickDeciding(MinecraftClient client) {
        if (route.size() <= 1) {
            message("§c[Goat] Route needs at least 2 points.");
            setEnabled(false);
            return;
        }

        if (currentPointIndex < 0) {
            currentPointIndex = findClosestPoint(client.player);
        }

        state = GemState.ETHERWARPING;
        int[] pt = route.get(currentPointIndex);
        etherwarp.findPath(new BlockPos(pt[0], pt[1], pt[2]));
    }

    private void tickEtherwarping(MinecraftClient client) {
        etherwarp.tick(client);

        EtherwarpPathfinder.State ewState = etherwarp.getState();
        if (ewState == EtherwarpPathfinder.State.DONE || ewState == EtherwarpPathfinder.State.FAILED) {
            if (ewState == EtherwarpPathfinder.State.DONE) {
                currentPointIndex = (currentPointIndex + 1) % route.size();
            }
            state = GemState.MINING;
            scanned = false;
        }
    }

    private void tickMining(MinecraftClient client) {
        miningBot.setFovPenalty(false);

        if (!scanned) {
            miningBot.scanForBlock(client.world, client.player);
            scanned = true;
            return;
        }

        if (miningBot.getFoundLocations().isEmpty()) {
            miningBot.stop();
            scanned = false;
            state = GemState.DECIDING;
            return;
        }

        if (!miningBot.isEnabled()) miningBot.setEnabled(true);

        miningBot.tick(client);
        rotationHelper.tick();

        if (miningBot.getFoundLocations().isEmpty()) {
            miningBot.stop();
            scanned = false;
            state = GemState.DECIDING;
        }
    }

    private int findClosestPoint(ClientPlayerEntity player) {
        double closest = Double.MAX_VALUE;
        int best = 0;
        for (int i = 0; i < route.size(); i++) {
            int[] pt = route.get(i);
            double d = PathMath.blockCenterFeet(new BlockPos(pt[0], pt[1], pt[2]))
                .squaredDistanceTo(player.getX(), player.getY(), player.getZ());
            if (d < closest) { closest = d; best = i; }
        }
        return best;
    }

    private Map<String, Integer> buildGemstoneCosts() {
        Map<String, Integer> costs = new HashMap<>();
        if (ruby.getValue()) {
            costs.put("minecraft:red_stained_glass", 1);
            costs.put("minecraft:red_stained_glass_pane", 1);
        }
        if (sapphire.getValue()) {
            costs.put("minecraft:light_blue_stained_glass", 1);
            costs.put("minecraft:light_blue_stained_glass_pane", 1);
        }
        if (amethyst.getValue()) {
            costs.put("minecraft:purple_stained_glass", 1);
            costs.put("minecraft:purple_stained_glass_pane", 1);
        }
        if (topaz.getValue()) {
            costs.put("minecraft:yellow_stained_glass", 1);
            costs.put("minecraft:yellow_stained_glass_pane", 1);
        }
        if (jade.getValue()) {
            costs.put("minecraft:lime_stained_glass", 1);
            costs.put("minecraft:lime_stained_glass_pane", 1);
        }
        if (jasper.getValue()) {
            costs.put("minecraft:magenta_stained_glass", 1);
            costs.put("minecraft:magenta_stained_glass_pane", 1);
        }
        if (amber.getValue()) {
            costs.put("minecraft:orange_stained_glass", 1);
            costs.put("minecraft:orange_stained_glass_pane", 1);
        }
        return costs;
    }

    private void message(String msg) {
        ChatUtils.sendRawMessage(msg);
    }
}
