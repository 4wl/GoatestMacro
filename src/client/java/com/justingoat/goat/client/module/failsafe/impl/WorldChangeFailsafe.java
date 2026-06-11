package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;

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

        String currentWorld = client.world.getRegistryKey().getValue().toString();
        
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
}
