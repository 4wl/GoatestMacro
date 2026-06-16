package com.justingoat.goat.client.module.failsafe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.justingoat.goat.client.events.EventManager;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.MacroScheduler;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.failsafe.impl.*;
import com.justingoat.goat.client.module.movement.PathfinderTest;
import com.justingoat.goat.client.module.pathfinder.AStarPathfinder;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

public class FailsafeManager {
    private static final FailsafeManager INSTANCE = new FailsafeManager();
    private static final long RETURN_TIMEOUT_MS = 35_000L;
    private static final double RETURN_REACH_DISTANCE_SQ = 0.64;
    private final List<Failsafe> failsafes = new ArrayList<>();
    private final List<Failsafe> emergencyQueue = new ArrayList<>();
    private final FailsafeReactionController reactionController = new FailsafeReactionController();
    private boolean hasEmergency = false;
    private Failsafe activeFailsafe = null;
    private long emergencyStartTime = 0;
    private final List<String> disabledMacroNames = new ArrayList<>();
    private BlockPos savedReturnBlock = null;
    private float savedReturnYaw = 0.0f;
    private float savedReturnPitch = 0.0f;
    private boolean returningToSavedPosition = false;
    private boolean returnPathStarted = false;
    private long returnStartTime = 0L;

    private FailsafeManager() {
        register(new BadEffectsFailsafe());
        register(new BedrockCageFailsafe());
        register(new DisconnectFailsafe());
        register(new EvacuateFailsafe());
        register(new GuestVisitFailsafe());
        register(new RotationFailsafe());
        register(new WorldChangeFailsafe());
        register(new CobwebFailsafe());
        register(new DirtFailsafe());
        register(new FullInventoryFailsafe());
        register(new ItemChangeFailsafe());
        register(new ChatMentionFailsafe());
        register(new PlayerGriefFailsafe());
        register(new TeleportFailsafe());
        register(new KnockbackFailsafe());
        register(new SlotChangeFailsafe());
        register(new BanwaveFailsafe());
        register(new JacobFailsafe());
        register(new LowerAvgBpsFailsafe());
    }

    private void register(Failsafe failsafe) {
        failsafes.add(failsafe);
        EventManager.INSTANCE.register(failsafe);
    }

    public static FailsafeManager getInstance() {
        return INSTANCE;
    }

    public List<Failsafe> getFailsafes() {
        return failsafes;
    }

    public void tick() {
        if (!hasEmergency) {
            for (Failsafe failsafe : failsafes) {
                if (failsafe.isEnabled()) {
                    failsafe.onTick();
                }
            }
            processEmergencyQueue();
        } else {
            reactionController.tick();
            if (!reactionController.isActive()) {
                if (shouldReturnBeforeResume()) {
                    if (!isAtSavedReturnPosition()) {
                        tickReturnToSavedPosition();
                        return;
                    }
                    MinecraftClient client = MinecraftClient.getInstance();
                    restorePreFailsafeRotation(client);
                }
                completeEmergencyAndResume();
            }
        }
    }

    public void triggerEmergency(Failsafe failsafe) {
        if (!emergencyQueue.contains(failsafe)) {
            emergencyQueue.add(failsafe);
        }
    }

    private void processEmergencyQueue() {
        if (emergencyQueue.isEmpty()) return;

        hasEmergency = true;
        emergencyStartTime = System.currentTimeMillis();
        emergencyQueue.sort(Comparator.comparingInt(Failsafe::getPriority));
        activeFailsafe = emergencyQueue.get(0);
        emergencyQueue.clear();

        ChatUtils.sendErrorMessage("§c§lFailsafe triggered: " + activeFailsafe.getName());

        InputUtils.releaseAll();
        captureReturnPosition();
        disableAllMacros();
        if (!activeFailsafe.shouldRunReaction()) {
            abortReturnAndKeepMacrosPaused(activeFailsafe.getName() + " triggered. Macro will stay paused.");
            return;
        }
        reactionController.start(activeFailsafe);
    }

    private void captureReturnPosition() {
        MinecraftClient client = MinecraftClient.getInstance();
        savedReturnBlock = null;
        returningToSavedPosition = false;
        returnPathStarted = false;
        returnStartTime = 0L;

        if (client.player == null || client.world == null) return;

        savedReturnBlock = AStarPathfinder.findNearestStandableGround(client.player.getBlockPos(), true);
        if (savedReturnBlock == null) {
            savedReturnBlock = client.player.getBlockPos().down();
        }
        savedReturnYaw = client.player.getYaw();
        savedReturnPitch = client.player.getPitch();
    }

    private void disableAllMacros() {
        disabledMacroNames.clear();
        for (GoatModule module : ModuleManager.getModules()) {
            if (!module.isEnabled()) continue;
            if (module.getCategory() == ModuleCategory.MACRO) {
                disabledMacroNames.add(module.getName());
                module.setEnabled(false);
            }
        }
    }

    public boolean isAnyMacroActive() {
        for (GoatModule module : ModuleManager.getModules()) {
            if (!module.isEnabled()) continue;
            if (module.getCategory() == ModuleCategory.MACRO) {
                if (module instanceof MacroScheduler scheduler) {
                    if (scheduler.isRunningTarget()) return true;
                    continue;
                }
                return true;
            }
        }
        return false;
    }

    public boolean hasEmergency() {
        return hasEmergency;
    }

    public Failsafe getActiveFailsafe() {
        return activeFailsafe;
    }

    public boolean isReactionActive() {
        return reactionController.isActive();
    }

    private boolean shouldReturnBeforeResume() {
        return savedReturnBlock != null && disabledMacroNames.contains("FarmingMacro");
    }

    private void tickReturnToSavedPosition() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) {
            abortReturnAndKeepMacrosPaused("Unable to return after failsafe: player/world unavailable.");
            return;
        }

        GoatModule module = ModuleManager.findByName("Pathfinder");
        if (!(module instanceof PathfinderTest pathfinder)) {
            abortReturnAndKeepMacrosPaused("Unable to return after failsafe: Pathfinder module not found.");
            return;
        }

        if (!returnPathStarted) {
            returningToSavedPosition = true;
            returnPathStarted = true;
            returnStartTime = System.currentTimeMillis();
            ChatUtils.sendInfoMessage("Returning to pre-failsafe position before resuming macro...");
            pathfinder.pathTargetWalkAllowWater(savedReturnBlock);
            return;
        }

        if (isAtSavedReturnPosition()) {
            pathfinder.setEnabled(false);
            restorePreFailsafeRotation(client);
            completeEmergencyAndResume();
            return;
        }

        if (System.currentTimeMillis() - returnStartTime > RETURN_TIMEOUT_MS) {
            pathfinder.setEnabled(false);
            abortReturnAndKeepMacrosPaused("Return to pre-failsafe position timed out. Macro will stay paused.");
            return;
        }

        if (!pathfinder.isEnabled()) {
            abortReturnAndKeepMacrosPaused("Pathfinder could not return to pre-failsafe position. Macro will stay paused.");
        }
    }

    private boolean isAtSavedReturnPosition() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || savedReturnBlock == null) return false;

        double dx = client.player.getX() - (savedReturnBlock.getX() + 0.5);
        double dz = client.player.getZ() - (savedReturnBlock.getZ() + 0.5);
        double dy = Math.abs(client.player.getY() - (savedReturnBlock.getY() + 1.0));
        return dx * dx + dz * dz <= RETURN_REACH_DISTANCE_SQ && dy <= 1.25;
    }

    private void restorePreFailsafeRotation(MinecraftClient client) {
        if (client.player == null) return;
        client.player.setYaw(savedReturnYaw);
        client.player.setPitch(savedReturnPitch);
    }

    private void completeEmergencyAndResume() {
        if (activeFailsafe != null && !activeFailsafe.shouldResumeMacros()) {
            abortReturnAndKeepMacrosPaused(activeFailsafe.getName() + " completed. Macro will stay paused.");
            return;
        }

        List<String> toResume = new ArrayList<>(disabledMacroNames);
        reset();
        for (String name : toResume) {
            GoatModule module = ModuleManager.findByName(name);
            if (module != null) {
                ChatUtils.sendSuccessMessage("Auto-resuming " + name);
                module.setEnabled(true);
            }
        }
    }

    private void abortReturnAndKeepMacrosPaused(String message) {
        ChatUtils.sendErrorMessage(message);
        reset();
    }

    public boolean triggerDevEmergency(String failsafeName) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return false;

        for (Failsafe failsafe : failsafes) {
            if (failsafe.getName().equalsIgnoreCase(failsafeName)
                || failsafe.getClass().getSimpleName().equalsIgnoreCase(failsafeName)) {
                triggerEmergency(failsafe);
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public <T extends Failsafe> T getFailsafe(Class<T> type) {
        for (Failsafe failsafe : failsafes) {
            if (type.isInstance(failsafe)) return (T) failsafe;
        }
        return null;
    }

    public void reset() {
        boolean wasReturning = returningToSavedPosition;
        hasEmergency = false;
        activeFailsafe = null;
        emergencyStartTime = 0;
        emergencyQueue.clear();
        disabledMacroNames.clear();
        savedReturnBlock = null;
        returningToSavedPosition = false;
        returnPathStarted = false;
        returnStartTime = 0L;
        reactionController.stop();
        GoatModule pathfinder = ModuleManager.findByName("Pathfinder");
        if (wasReturning && pathfinder != null) {
            pathfinder.setEnabled(false);
        }
        for (Failsafe failsafe : failsafes) {
            failsafe.reset();
        }
    }
}
