package com.justingoat.goat.client.module.mining;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.pathfinder.EtherwarpPathfinder;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.text.Text;

import java.util.*;

public class OreMacro extends GoatModule {

    public enum OreState { WAITING, DECIDING, WALKING, ETHERWARPING, MINING }

    private final BooleanValue coal, quartz, iron, gold, diamond, redstone, lapis, emerald;

    private OreState state = OreState.WAITING;
    private List<int[]> route = new ArrayList<>();
    private List<int[]> mineablePoints = new ArrayList<>();
    private List<Integer> navIndices = new ArrayList<>();
    private int currentIndex = -1;

    private final MiningBot miningBot;
    private final RotationUtils rotationHelper = new RotationUtils();
    private final EtherwarpPathfinder etherwarp = new EtherwarpPathfinder();

    public OreMacro() {
        super("OreMacro", ModuleCategory.WORLD, false);
        coal = addBoolean("Coal", true);
        quartz = addBoolean("Quartz", true);
        iron = addBoolean("Iron", true);
        gold = addBoolean("Gold", true);
        diamond = addBoolean("Diamond", true);
        redstone = addBoolean("Redstone", true);
        lapis = addBoolean("Lapis", true);
        emerald = addBoolean("Emerald", true);

        miningBot = new MiningBot();
        miningBot.setRotationHelper(rotationHelper);
    }

    public void setRoute(List<int[]> route) {
        this.route = route != null ? route : new ArrayList<>();
        updateRouteMeta();
    }

    public void addRoutePoint(int x, int y, int z, boolean mineable) {
        route.add(new int[]{x, y, z, mineable ? 1 : 0});
        updateRouteMeta();
    }

    public MiningBot getBot() { return miningBot; }
    public OreState getOreState() { return state; }

    @Override
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = isEnabled();
        super.setEnabled(enabled);
        if (enabled && !wasEnabled) {
            state = OreState.DECIDING;
            currentIndex = -1;
            miningBot.setCost(buildOreCosts());
            miningBot.setMovement(false);
            message("§a[Goat] OreMacro enabled.");
        } else if (!enabled && wasEnabled) {
            state = OreState.WAITING;
            miningBot.stop();
            etherwarp.cancel();
            InputUtils.releaseAll();
            message("§c[Goat] OreMacro disabled.");
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) return;
        miningBot.setCost(buildOreCosts());

        switch (state) {
            case DECIDING -> tickDeciding(client);
            case WALKING -> tickWalking(client);
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

        if (currentIndex < 0) {
            currentIndex = findClosestNavIndex(client.player);
            if (currentIndex < 0) {
                message("§c[Goat] No navigation points in route.");
                setEnabled(false);
                return;
            }
        }

        int[] point = route.get(currentIndex);
        boolean isMineable = point.length > 3 && point[3] == 1;
        if (isMineable) {
            advanceToNextNav();
            return;
        }

        state = OreState.ETHERWARPING;
        etherwarp.findPath(new BlockPos(point[0], point[1], point[2]));
    }

    private void tickWalking(MinecraftClient client) {
        if (currentIndex < 0 || currentIndex >= route.size()) {
            state = OreState.DECIDING;
            return;
        }

        int[] point = route.get(currentIndex);
        ClientPlayerEntity player = client.player;
        double dx = point[0] + 0.5 - player.getX();
        double dy = point[1] + 1.0 - player.getY();
        double dz = point[2] + 0.5 - player.getZ();
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        if (dist <= 0.75) {
            InputUtils.releaseAll();
            advanceToNextNav();
            state = OreState.MINING;
            return;
        }

        float yaw = (float) -(Math.toDegrees(Math.atan2(dx, dz)));
        float yawDelta = MathHelper.wrapDegrees(yaw - player.getYaw());

        InputUtils.setForward(true);
        InputUtils.setSprint(true);
        InputUtils.setSneak(dist <= 1.5 && Math.abs(dy) == Math.sqrt(dx * dx + dz * dz));
    }

    private void tickEtherwarping(MinecraftClient client) {
        etherwarp.tick(client);

        if (etherwarp.getState() == EtherwarpPathfinder.State.DONE
                || etherwarp.getState() == EtherwarpPathfinder.State.FAILED) {
            if (etherwarp.getState() == EtherwarpPathfinder.State.FAILED) {
                state = OreState.WALKING;
                return;
            }
            advanceToNextNav();
            state = OreState.MINING;
        }
    }

    private void tickMining(MinecraftClient client) {
        InputUtils.setForward(false);
        InputUtils.setBack(false);
        InputUtils.setLeft(false);
        InputUtils.setRight(false);

        miningBot.setFovPenalty(false);

        if (!miningBot.isEnabled()) {
            miningBot.scanForBlock(client.world, client.player);
            if (miningBot.getFoundLocations().isEmpty()) {
                miningBot.stop();
                state = OreState.DECIDING;
                return;
            }
            miningBot.setEnabled(true);
        }

        miningBot.tick(client);
        rotationHelper.tick();

        if (miningBot.getFoundLocations().isEmpty()) {
            miningBot.stop();
            state = OreState.DECIDING;
        }
    }

    private void advanceToNextNav() {
        if (navIndices.isEmpty()) return;
        int nextIdx = -1;
        for (int idx : navIndices) {
            if (idx > currentIndex) { nextIdx = idx; break; }
        }
        if (nextIdx < 0) nextIdx = navIndices.get(0);
        currentIndex = nextIdx;
    }

    private int findClosestNavIndex(ClientPlayerEntity player) {
        if (navIndices.isEmpty()) return -1;
        double closest = Double.MAX_VALUE;
        int best = navIndices.get(0);
        for (int idx : navIndices) {
            int[] pt = route.get(idx);
            double dx = pt[0] - player.getX();
            double dy = pt[1] - player.getY();
            double dz = pt[2] - player.getZ();
            double d = dx * dx + dy * dy + dz * dz;
            if (d < closest) { closest = d; best = idx; }
        }
        return best;
    }

    private void updateRouteMeta() {
        navIndices.clear();
        mineablePoints.clear();
        for (int i = 0; i < route.size(); i++) {
            int[] pt = route.get(i);
            if (pt.length > 3 && pt[3] == 1) {
                mineablePoints.add(pt);
            } else {
                navIndices.add(i);
            }
        }
    }

    private Map<String, Integer> buildOreCosts() {
        Map<String, Integer> costs = new HashMap<>();
        if (coal.getValue()) costs.put("minecraft:coal_block", 1);
        if (quartz.getValue()) costs.put("minecraft:quartz_block", 1);
        if (iron.getValue()) costs.put("minecraft:iron_block", 1);
        if (gold.getValue()) costs.put("minecraft:gold_block", 1);
        if (diamond.getValue()) costs.put("minecraft:diamond_block", 1);
        if (redstone.getValue()) costs.put("minecraft:redstone_block", 1);
        if (lapis.getValue()) costs.put("minecraft:lapis_block", 1);
        if (emerald.getValue()) costs.put("minecraft:emerald_block", 1);
        return costs;
    }

    private void message(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }
}
