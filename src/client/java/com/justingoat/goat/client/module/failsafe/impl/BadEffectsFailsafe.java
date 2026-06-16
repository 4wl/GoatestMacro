package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;

public class BadEffectsFailsafe extends Failsafe {
    private long firstSeenAt = 0L;

    @Override
    public int getPriority() { return 1; }

    @Override
    public String getName() { return "Bad Effects"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckMacro()) {
            firstSeenAt = 0L;
            return;
        }

        if (!FailsafeDetectionUtils.hasBadEffect()) {
            firstSeenAt = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (firstSeenAt == 0L) firstSeenAt = now;
        if (now - firstSeenAt < 500L) return;

        ChatUtils.sendWarningMessage("Failsafe: bad status effect detected");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public void reset() {
        firstSeenAt = 0L;
    }
}
