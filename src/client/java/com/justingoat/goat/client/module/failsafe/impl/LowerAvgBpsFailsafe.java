package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.BPSTracker;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.LagDetector;

public class LowerAvgBpsFailsafe extends Failsafe {
    private static final double MIN_BPS = 0.25;
    private static final long LOW_BPS_DURATION_MS = 5_000L;
    private static final long COOLDOWN_MS = 60_000L;
    private long lowSince = 0L;
    private long lastTriggeredAt = 0L;

    @Override
    public int getPriority() { return 9; }

    @Override
    public String getName() { return "Lower Average BPS"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckMacro() || LagDetector.isLagging()) {
            lowSince = 0L;
            return;
        }

        double bps = BPSTracker.getBps();
        if (bps > MIN_BPS) {
            lowSince = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (lowSince == 0L) lowSince = now;
        if (now - lowSince < LOW_BPS_DURATION_MS) return;
        if (now - lastTriggeredAt < COOLDOWN_MS) {
            lowSince = 0L;
            return;
        }

        lastTriggeredAt = now;
        ChatUtils.sendWarningMessage(String.format("Failsafe: low average BPS detected (%.2f)", bps));
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public void reset() {
        lowSince = 0L;
    }
}
