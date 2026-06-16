package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;

public class DisconnectFailsafe extends Failsafe {
    private boolean hadMacroWithWorld = false;

    @Override
    public int getPriority() { return 1; }

    @Override
    public String getName() { return "Disconnect"; }

    @Override
    public void onTick() {
        boolean macroActive = FailsafeManager.getInstance().isAnyMacroActive();
        if (macroActive && client.player != null && client.world != null) {
            hadMacroWithWorld = true;
            return;
        }

        if (!hadMacroWithWorld || !macroActive) return;
        if (client.player != null && client.world != null) return;

        ChatUtils.sendWarningMessage("Failsafe: disconnected or world unavailable");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public boolean shouldRunReaction() {
        return false;
    }

    @Override
    public boolean shouldResumeMacros() {
        return false;
    }

    @Override
    public void reset() {
        hadMacroWithWorld = false;
    }
}
