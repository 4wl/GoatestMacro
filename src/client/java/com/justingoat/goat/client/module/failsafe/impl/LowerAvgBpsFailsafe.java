package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.BPSTracker;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.ConditionTimer;
import com.justingoat.goat.client.utils.LagDetector;
import com.justingoat.goat.client.utils.MacroClock;

public class LowerAvgBpsFailsafe extends Failsafe {
    private static final double MIN_BPS = 0.25;
    private static final long LOW_BPS_DURATION_MS = 5_000L;
    private static final long COOLDOWN_MS = 60_000L;
    private final ConditionTimer lowBpsTimer = new ConditionTimer();
    private final MacroClock cooldownClock = new MacroClock();
    private boolean cooldownActive = false;

    @Override
    public int getPriority() { return 9; }

    @Override
    public String getName() { return "Lower Average BPS"; }

    @Override
    public void onTick() {
        if (!FailsafeManager.getInstance().isMovementMacroActive() || !FailsafeDetectionUtils.canCheckMacro() || LagDetector.isLagging()) {
            lowBpsTimer.reset();
            return;
        }

        double bps = BPSTracker.getBps();
        if (bps > MIN_BPS) {
            lowBpsTimer.reset();
            return;
        }

        if (!lowBpsTimer.confirmed(LOW_BPS_DURATION_MS)) return;
        if (cooldownActive && !cooldownClock.ready(COOLDOWN_MS)) {
            lowBpsTimer.reset();
            return;
        }

        cooldownClock.mark();
        cooldownActive = true;
        ChatUtils.sendWarningMessage(String.format("Failsafe: low average BPS detected (%.2f)", bps));
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public void reset() {
        lowBpsTimer.reset();
    }
}
