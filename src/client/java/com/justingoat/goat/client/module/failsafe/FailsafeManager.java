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
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;
import net.fabricmc.loader.api.FabricLoader;

public class FailsafeManager {
    private static final FailsafeManager INSTANCE = new FailsafeManager();
    private final List<Failsafe> failsafes = new ArrayList<>();
    private final List<Failsafe> emergencyQueue = new ArrayList<>();
    private final FailsafeReactionController reactionController = new FailsafeReactionController();
    private boolean hasEmergency = false;
    private Failsafe activeFailsafe = null;
    private long emergencyStartTime = 0;
    private final List<String> disabledMacroNames = new ArrayList<>();

    private FailsafeManager() {
        register(new RotationFailsafe());
        register(new WorldChangeFailsafe());
        register(new ChatMentionFailsafe());
        register(new PlayerGriefFailsafe());
        register(new TeleportFailsafe());
        register(new VelocityFailsafe());
        register(new SlotChangeFailsafe());
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
        disableAllMacros();
        reactionController.start(activeFailsafe);
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
        hasEmergency = false;
        activeFailsafe = null;
        emergencyStartTime = 0;
        emergencyQueue.clear();
        disabledMacroNames.clear();
        reactionController.stop();
        for (Failsafe failsafe : failsafes) {
            failsafe.reset();
        }
    }
}
