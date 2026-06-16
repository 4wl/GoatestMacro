package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import net.minecraft.block.Blocks;

public class CobwebFailsafe extends Failsafe {
    private long firstSeenAt = 0L;

    @Override
    public int getPriority() { return 3; }

    @Override
    public String getName() { return "Cobweb"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckGardenMacro()) {
            firstSeenAt = 0L;
            return;
        }

        int cobwebs = FailsafeDetectionUtils.countNearby(Blocks.COBWEB, 1, 1, 2);
        if (cobwebs == 0) {
            firstSeenAt = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (firstSeenAt == 0L) firstSeenAt = now;
        if (now - firstSeenAt < 300L) return;

        ChatUtils.sendWarningMessage("Failsafe: cobweb detected near player");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public void reset() {
        firstSeenAt = 0L;
    }
}
