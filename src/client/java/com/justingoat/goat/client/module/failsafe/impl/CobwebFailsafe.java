package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.ConditionTimer;
import net.minecraft.block.Blocks;

public class CobwebFailsafe extends Failsafe {
    private final ConditionTimer seenTimer = new ConditionTimer();

    @Override
    public int getPriority() { return 3; }

    @Override
    public String getName() { return "Cobweb"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckGardenMacro()) {
            seenTimer.reset();
            return;
        }

        int cobwebs = FailsafeDetectionUtils.countNearby(Blocks.COBWEB, 1, 1, 2);
        if (cobwebs == 0) {
            seenTimer.reset();
            return;
        }

        if (!seenTimer.confirmed(300L)) return;

        ChatUtils.sendWarningMessage("Failsafe: cobweb detected near player");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public void reset() {
        seenTimer.reset();
    }
}
