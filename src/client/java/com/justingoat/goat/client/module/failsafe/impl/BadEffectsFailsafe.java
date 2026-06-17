package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.ConditionTimer;

public class BadEffectsFailsafe extends Failsafe {
    private final ConditionTimer seenTimer = new ConditionTimer();

    @Override
    public int getPriority() { return 1; }

    @Override
    public String getName() { return "Bad Effects"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckMacro()) {
            seenTimer.reset();
            return;
        }

        if (!FailsafeDetectionUtils.hasBadEffect()) {
            seenTimer.reset();
            return;
        }

        if (!seenTimer.confirmed(500L)) return;

        ChatUtils.sendWarningMessage("Failsafe: bad status effect detected");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public void reset() {
        seenTimer.reset();
    }
}
