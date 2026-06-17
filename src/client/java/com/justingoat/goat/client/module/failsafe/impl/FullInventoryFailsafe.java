package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.ConditionTimer;

public class FullInventoryFailsafe extends Failsafe {
    private final ConditionTimer fullTimer = new ConditionTimer();

    @Override
    public int getPriority() { return 3; }

    @Override
    public String getName() { return "Full Inventory"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckMacro()) {
            fullTimer.reset();
            return;
        }

        if (!FailsafeDetectionUtils.isInventoryFull()) {
            fullTimer.reset();
            return;
        }

        if (!fullTimer.confirmed(600L)) return;

        ChatUtils.sendWarningMessage("Failsafe: inventory is full");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public boolean shouldResumeMacros() {
        return false;
    }

    @Override
    public void reset() {
        fullTimer.reset();
    }
}
