package com.justingoat.goat.client.module.failsafe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.justingoat.goat.client.events.EventManager;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.failsafe.impl.*;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.InputUtils;

public class FailsafeManager {
    private static final FailsafeManager INSTANCE = new FailsafeManager();
    private final List<Failsafe> failsafes = new ArrayList<>();
    private final List<Failsafe> emergencyQueue = new ArrayList<>();
    private boolean hasEmergency = false;
    private Failsafe activeFailsafe = null;
    private long emergencyStartTime = 0;

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

    public void tick() {
        if (!hasEmergency) {
            for (Failsafe failsafe : failsafes) {
                failsafe.onTick();
            }
            processEmergencyQueue();
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
    }

    private void disableAllMacros() {
        for (GoatModule module : ModuleManager.getModules()) {
            if (!module.isEnabled()) continue;
            ModuleCategory cat = module.getCategory();
            if (cat == ModuleCategory.COMBAT || cat == ModuleCategory.MOVEMENT) {
                module.setEnabled(false);
            }
        }
    }

    public boolean isAnyMacroActive() {
        for (GoatModule module : ModuleManager.getModules()) {
            if (!module.isEnabled()) continue;
            ModuleCategory cat = module.getCategory();
            if (cat == ModuleCategory.COMBAT || cat == ModuleCategory.MOVEMENT) {
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

    public void reset() {
        hasEmergency = false;
        activeFailsafe = null;
        emergencyStartTime = 0;
        emergencyQueue.clear();
        for (Failsafe failsafe : failsafes) {
            failsafe.reset();
        }
    }
}
