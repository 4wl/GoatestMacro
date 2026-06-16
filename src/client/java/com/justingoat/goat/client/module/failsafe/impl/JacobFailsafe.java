package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.ScoreboardUtils;

public class JacobFailsafe extends Failsafe {
    private long contestSince = 0L;

    @Override
    public int getPriority() { return 7; }

    @Override
    public String getName() { return "Jacob Contest"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckMacro()) {
            contestSince = 0L;
            return;
        }

        boolean inContest = ScoreboardUtils.getScoreboardLines().stream()
            .map(FailsafeDetectionUtils::normalize)
            .anyMatch(line -> line.contains("jacob") || line.contains("contest"));

        if (!inContest) {
            contestSince = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (contestSince == 0L) contestSince = now;
        if (now - contestSince < 2_000L) return;

        ChatUtils.sendWarningMessage("Failsafe: Jacob's Contest detected. Macro will stay paused.");
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
        contestSince = 0L;
    }
}
