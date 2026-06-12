package com.justingoat.goat.client.module.mining;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.movement.PathfinderTest;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.InputUtils;
import com.justingoat.goat.client.utils.RotationUtils;
import com.justingoat.goat.client.utils.TabUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.text.Text;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CommissionMacro extends GoatModule {

    public enum CState {
        IDLE, CHOOSING, TRAVELING, MINING, SLAYER, SELLING, CLAIMING, WAITING_GUI
    }

    private final NumberValue avoidanceRadius;
    private final NumberValue weaponSlot;

    private CState currentState = CState.IDLE;
    private int pauseTicks = 0;

    private List<Commission> commissions = new ArrayList<>();
    private Commission currentCommission = null;
    private String lastCompletedName = null;
    private int completedCount = 0;

    private final MiningBot miningBot;
    private final RotationUtils rotationHelper = new RotationUtils();

    private static final Pattern COMMISSION_PATTERN =
            Pattern.compile("^\\s*(.+?):\\s*(\\d+\\.?\\d*%|DONE)\\s*$");

    public CommissionMacro() {
        super("CommissionMacro", ModuleCategory.MACRO, false);
        avoidanceRadius = addNumber("AvoidRadius", 10, 0, 30);
        weaponSlot = addNumber("WeaponSlot", 1, 1, 8);

        miningBot = new MiningBot();
        miningBot.setRotationHelper(rotationHelper);
    }

    public CState getCommissionState() { return currentState; }
    public int getCompletedCount() { return completedCount; }

    @Override
    public void setEnabled(boolean enabled) {
        boolean wasEnabled = isEnabled();
        super.setEnabled(enabled);
        if (enabled && !wasEnabled) {
            resetState();
            currentState = CState.CHOOSING;
            completedCount = 0;
            message("§a[Goat] CommissionMacro enabled.");
        } else if (!enabled && wasEnabled) {
            resetState();
            message("§c[Goat] CommissionMacro disabled.");
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        if (!isEnabled() || client.player == null || client.world == null) return;
        miningBot.setCost(MiningBot.MITHRIL_COSTS);

        if (pauseTicks > 0) { pauseTicks--; return; }

        switch (currentState) {
            case IDLE -> currentState = CState.CHOOSING;
            case CHOOSING -> handleChoosing(client);
            case TRAVELING -> handleTraveling(client);
            case MINING -> handleMining(client);
            case CLAIMING -> handleClaiming(client);
            case WAITING_GUI -> handleWaitingGui(client);
            default -> {}
        }
    }

    private void handleChoosing(MinecraftClient client) {
        readCommissionsFromTab(client);

        Commission completed = findCompleted();
        if (completed != null) {
            currentCommission = completed;
            onCommissionComplete();
            return;
        }

        List<Commission> active = commissions.stream()
                .filter(c -> c.progress < 1.0)
                .toList();

        if (active.isEmpty()) {
            message("§c[Goat] No commissions found in tab list.");
            setEnabled(false);
            return;
        }

        List<CommissionData.CommissionEntry> supported = new ArrayList<>();
        for (Commission c : active) {
            CommissionData.CommissionEntry entry = CommissionData.findByName(c.name);
            if (entry != null) supported.add(entry);
        }

        if (supported.isEmpty()) {
            message("§e[Goat] No supported commissions available.");
            setEnabled(false);
            return;
        }

        supported.sort(Comparator.comparingInt(CommissionData.CommissionEntry::cost));
        CommissionData.CommissionEntry chosen = supported.get(0);

        Commission tab = active.stream()
                .filter(c -> chosen.names().contains(c.name))
                .findFirst().orElse(null);
        if (tab == null) return;

        currentCommission = tab;
        message("§e[Goat] Starting commission: " + tab.name);

        int[][] waypoints = chosen.waypoints();
        if (chosen.useAllMiningWaypoints()) {
            List<int[]> allWp = new ArrayList<>();
            for (CommissionData.CommissionEntry e : CommissionData.COMMISSIONS) {
                if (e.type() == CommissionData.CommissionType.MINING && !e.useAllMiningWaypoints()) {
                    allWp.addAll(Arrays.asList(e.waypoints()));
                }
            }
            waypoints = allWp.toArray(new int[0][]);
        }

        if (waypoints.length == 0) {
            message("§c[Goat] No waypoints for commission.");
            currentState = CState.IDLE;
            return;
        }

        int[] closest = findClosestWaypoint(client.player, waypoints);
        BlockPos target = new BlockPos(closest[0], closest[1], closest[2]);

        startWalkPath(target);
    }

    private void startWalkPath(BlockPos target) {
        GoatModule pathMod = ModuleManager.findByName("Pathfinder");
        if (pathMod instanceof PathfinderTest pt) {
            pt.pathTargetWalk(target);
            currentState = CState.TRAVELING;
        } else {
            message("§c[Goat] Pathfinder module not found.");
            setEnabled(false);
        }
    }

    private void handleTraveling(MinecraftClient client) {
        GoatModule pathMod = ModuleManager.findByName("Pathfinder");
        if (pathMod instanceof PathfinderTest pt) {
            if (!pt.isEnabled()) {
                CommissionData.CommissionEntry entry = currentCommission != null
                        ? CommissionData.findByName(currentCommission.name) : null;
                if (entry != null && entry.type() == CommissionData.CommissionType.MINING) {
                    startMining();
                } else if (entry != null && entry.type() == CommissionData.CommissionType.SLAYER) {
                    message("§e[Goat] Arrived at slayer area. Slayer not yet implemented.");
                    currentState = CState.IDLE;
                } else {
                    currentState = CState.IDLE;
                }
            }
        }
    }

    private void handleMining(MinecraftClient client) {
        if (!miningBot.isEnabled()) {
            boolean isTitanium = currentCommission != null && currentCommission.name.contains("Titanium");
            miningBot.setPrioritizeTitanium(isTitanium);
            miningBot.setPrioritizeGrayMithril(true);
            miningBot.setEnabled(true);
        }

        miningBot.tick(client);
        rotationHelper.tick();

        readCommissionsFromTab(client);
        Commission completed = findCompleted();
        if (completed != null) {
            currentCommission = completed;
            onCommissionComplete();
        }
    }

    private void handleClaiming(MinecraftClient client) {
        int pigeonSlot = findItemSlot(client.player, "Royal Pigeon");
        if (pigeonSlot >= 0) {
            if (client.player.getInventory().getSelectedSlot() != pigeonSlot) {
                InputUtils.setHotbarSlot(pigeonSlot);
                pauseTicks = 3;
                return;
            }
            InputUtils.setUse(true);
            MinecraftClient.getInstance().execute(() -> InputUtils.setUse(false));
            pauseTicks = 10;
            currentState = CState.WAITING_GUI;
            return;
        }

        int[][] emissaries = CommissionData.EMISSARY_LOCATIONS;
        int[] closest = findClosestWaypoint(client.player, emissaries);
        double dist = distance(client.player, closest);

        if (dist < 4) {
            InputUtils.setUse(true);
            MinecraftClient.getInstance().execute(() -> InputUtils.setUse(false));
            pauseTicks = 10;
            currentState = CState.WAITING_GUI;
        } else {
            BlockPos target = new BlockPos(closest[0], closest[1], closest[2]);
            GoatModule pathMod = ModuleManager.findByName("Pathfinder");
            if (pathMod instanceof PathfinderTest pt) {
                pt.pathTarget(target);
                currentState = CState.TRAVELING;
            }
        }
    }

    private void handleWaitingGui(MinecraftClient client) {
        if (client.currentScreen == null) {
            currentState = CState.CHOOSING;
        }
    }

    private void onCommissionComplete() {
        miningBot.stop();
        completedCount++;
        lastCompletedName = currentCommission != null ? currentCommission.name : null;
        message("§a[Goat] Commission complete: " + lastCompletedName);
        currentState = CState.CLAIMING;
    }

    private void startMining() {
        currentState = CState.MINING;
    }

    private void resetState() {
        currentState = CState.IDLE;
        commissions.clear();
        currentCommission = null;
        lastCompletedName = null;
        pauseTicks = 0;
        miningBot.stop();
        InputUtils.releaseAll();
    }

    private void readCommissionsFromTab(MinecraftClient client) {
        if (client.getNetworkHandler() == null) return;
        List<Commission> newComms = new ArrayList<>();

        var entries = client.getNetworkHandler().getPlayerList();
        for (var entry : entries) {
            if (entry.getDisplayName() == null) continue;
            String line = entry.getDisplayName().getString();
            Matcher m = COMMISSION_PATTERN.matcher(line);
            if (m.find()) {
                String name = m.group(1).trim();
                String progStr = m.group(2);
                double progress;
                if ("DONE".equals(progStr)) {
                    progress = 1.0;
                } else {
                    progress = Double.parseDouble(progStr.replace("%", "")) / 100.0;
                }
                if (CommissionData.isKnownCommission(name)) {
                    newComms.add(new Commission(name, progress));
                }
            }
        }

        if (!newComms.isEmpty()) {
            commissions = newComms;
        }
    }

    private Commission findCompleted() {
        for (Commission c : commissions) {
            if (c.progress >= 1.0 && CommissionData.isKnownCommission(c.name)) {
                return c;
            }
        }
        return null;
    }

    private static int findItemSlot(ClientPlayerEntity player, String name) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            if (stack.getName().getString().contains(name)) return i;
        }
        return -1;
    }

    private static int[] findClosestWaypoint(ClientPlayerEntity player, int[][] waypoints) {
        int[] closest = waypoints[0];
        double closestDist = Double.MAX_VALUE;
        for (int[] wp : waypoints) {
            double d = distance(player, wp);
            if (d < closestDist) { closestDist = d; closest = wp; }
        }
        return closest;
    }

    private static double distance(ClientPlayerEntity player, int[] pos) {
        double dx = player.getX() - pos[0];
        double dy = player.getY() - pos[1];
        double dz = player.getZ() - pos[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void message(String msg) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) client.player.sendMessage(Text.literal(msg), false);
    }

    private record Commission(String name, double progress) {}
}
