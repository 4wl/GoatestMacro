package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;

public class FullInventoryFailsafe extends Failsafe {
    private long fullSince = 0L;

    @Override
    public int getPriority() { return 3; }

    @Override
    public String getName() { return "Full Inventory"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckMacro()) {
            fullSince = 0L;
            return;
        }

        if (!FailsafeDetectionUtils.isInventoryFull()) {
            fullSince = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (fullSince == 0L) fullSince = now;
        if (now - fullSince < 600L) return;

        ChatUtils.sendWarningMessage("Failsafe: inventory is full");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public boolean shouldResumeMacros() {
        return false;
    }

    @Override
    public void reset() {
        fullSince = 0L;
    }
}
