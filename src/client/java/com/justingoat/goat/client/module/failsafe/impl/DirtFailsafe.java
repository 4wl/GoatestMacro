package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

public class DirtFailsafe extends Failsafe {
    private long firstSeenAt = 0L;
    private BlockPos detectedPos = null;

    @Override
    public int getPriority() { return 3; }

    @Override
    public String getName() { return "Dirt Block"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckGardenMacro()) {
            clear();
            return;
        }

        BlockPos suspicious = findSuspiciousBlock();
        if (suspicious == null) {
            clear();
            return;
        }

        long now = System.currentTimeMillis();
        if (!suspicious.equals(detectedPos)) {
            detectedPos = suspicious;
            firstSeenAt = now;
            return;
        }
        if (now - firstSeenAt < 700L) return;

        ChatUtils.sendWarningMessage("Failsafe: suspicious block placed near farm at " + suspicious.toShortString());
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    private BlockPos findSuspiciousBlock() {
        for (BlockPos pos : FailsafeDetectionUtils.nearbyPositions(1, 0, 2)) {
            BlockState state = client.world.getBlockState(pos);
            if (!FailsafeDetectionUtils.isSuspiciousPlacedBlock(state)) continue;
            if (!client.player.getBoundingBox().expand(0.08, 0.02, 0.08).intersects(new Box(pos))) continue;
            return pos.toImmutable();
        }
        return null;
    }

    private void clear() {
        firstSeenAt = 0L;
        detectedPos = null;
    }

    @Override
    public void reset() {
        clear();
    }
}
