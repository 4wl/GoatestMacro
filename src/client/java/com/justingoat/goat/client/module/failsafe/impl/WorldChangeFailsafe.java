package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.module.movement.FarmingMacro;

public class WorldChangeFailsafe extends Failsafe {
    private String lastWorldName = null;

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public String getName() {
        return "World Change";
    }

    @Override
    public void onTick() {
        if (client.world == null) {
            lastWorldName = null;
            return;
        }

        if (!FailsafeManager.getInstance().isAnyMacroActive()) {
            lastWorldName = null;
            return;
        }

        String currentWorld = client.world.getRegistryKey().getValue().toString();

        if (isFarmingServerRecoveryActive()) {
            lastWorldName = currentWorld;
            return;
        }
        
        if (lastWorldName == null) {
            lastWorldName = currentWorld;
            return;
        }

        if (!currentWorld.equals(lastWorldName)) {
            FailsafeManager.getInstance().triggerEmergency(this);
        }

        lastWorldName = currentWorld;
    }

    @Override
    public void reset() {
        lastWorldName = null;
    }

    private boolean isFarmingServerRecoveryActive() {
        GoatModule module = ModuleManager.findByName("FarmingMacro");
        return module instanceof FarmingMacro farmingMacro && farmingMacro.isRecoveringFromServerMove();
    }
}
