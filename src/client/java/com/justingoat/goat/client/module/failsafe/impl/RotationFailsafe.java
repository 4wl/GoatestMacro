package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;

public class RotationFailsafe extends Failsafe {
    private float lastYaw = 0;
    private float lastPitch = 0;
    private boolean initialized = false;

    @Override
    public int getPriority() {
        return 1;
    }

    @Override
    public String getName() {
        return "Rotation Check";
    }

    @Override
    public void onTick() {
        if (client.player == null) {
            initialized = false;
            return;
        }

        if (!FailsafeManager.getInstance().isAnyMacroActive()) {
            initialized = false;
            return;
        }

        if (isMacroControllingRotation()) {
            lastYaw = client.player.getYaw();
            lastPitch = client.player.getPitch();
            initialized = true;
            return;
        }

        if (!initialized) {
            lastYaw = client.player.getYaw();
            lastPitch = client.player.getPitch();
            initialized = true;
            return;
        }

        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        float yawDiff = Math.abs(currentYaw - lastYaw);
        float pitchDiff = Math.abs(currentPitch - lastPitch);

        // If pitch or yaw changed by an unnatural amount in a single tick without our input
        if (yawDiff > 45.0f || pitchDiff > 45.0f) {
            FailsafeManager.getInstance().triggerEmergency(this);
        }

        lastYaw = currentYaw;
        lastPitch = currentPitch;
    }

    @Override
    public void reset() {
        initialized = false;
    }

    private boolean isMacroControllingRotation() {
        GoatModule foraging = ModuleManager.findByName("ForagingMacro");
        if (foraging != null && foraging.isEnabled()) return true;

        GoatModule combat = ModuleManager.findByName("CombatMacro");
        if (combat != null && combat.isEnabled()) return true;

        GoatModule pathfinder = ModuleManager.findByName("Pathfinder");
        return pathfinder != null && pathfinder.isEnabled();
    }
}
