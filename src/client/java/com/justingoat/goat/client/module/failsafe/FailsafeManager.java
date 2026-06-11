package com.justingoat.goat.client.module.failsafe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.justingoat.goat.client.module.failsafe.impl.RotationFailsafe;
import com.justingoat.goat.client.module.failsafe.impl.WorldChangeFailsafe;

public class FailsafeManager {
    private static final FailsafeManager INSTANCE = new FailsafeManager();
    private final List<Failsafe> failsafes = new ArrayList<>();
    private final List<Failsafe> emergencyQueue = new ArrayList<>();
    private boolean hasEmergency = false;
    private Failsafe activeFailsafe = null;

    private FailsafeManager() {
        failsafes.add(new RotationFailsafe());
        failsafes.add(new WorldChangeFailsafe());
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
        // Sort by priority (lower number = higher priority)
        emergencyQueue.sort(Comparator.comparingInt(Failsafe::getPriority));
        activeFailsafe = emergencyQueue.get(0);
        emergencyQueue.clear();

        System.out.println("[Goat] Failsafe triggered: " + activeFailsafe.getName());
    }

    public boolean hasEmergency() {
        return hasEmergency;
    }

    public void reset() {
        hasEmergency = false;
        activeFailsafe = null;
        emergencyQueue.clear();
        for (Failsafe failsafe : failsafes) {
            failsafe.reset();
        }
    }
}
