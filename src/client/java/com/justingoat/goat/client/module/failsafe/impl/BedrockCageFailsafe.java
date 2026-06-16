package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import net.minecraft.block.Blocks;

public class BedrockCageFailsafe extends Failsafe {
    private long firstSeenAt = 0L;

    @Override
    public int getPriority() { return 1; }

    @Override
    public String getName() { return "Bedrock Cage"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckGardenMacro()) {
            firstSeenAt = 0L;
            return;
        }

        int bedrock = FailsafeDetectionUtils.countNearby(Blocks.BEDROCK, 2, 1, 3);
        if (bedrock < 4 || client.player.getY() < 66.0) {
            firstSeenAt = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (firstSeenAt == 0L) firstSeenAt = now;
        if (now - firstSeenAt < 200L) return;

        ChatUtils.sendWarningMessage("Failsafe: bedrock cage detected (" + bedrock + " bedrock blocks)");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public void reset() {
        firstSeenAt = 0L;
    }
}
