package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.events.EventListener;
import com.justingoat.goat.client.events.impl.packet.VelocityPacketEvent;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.MacroClock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

public class VelocityFailsafe extends Failsafe {

    private final MacroClock suppressClock = new MacroClock();
    private boolean suppressing = false;

    @Override
    public int getPriority() { return 6; }

    @Override
    public String getName() { return "Velocity Check"; }

    @EventListener
    public void onVelocity(VelocityPacketEvent event) {
        if (client.player == null || client.world == null) return;
        if (!FailsafeManager.getInstance().isAnyMacroActive()) return;

        if (suppressing && !suppressClock.ready(0L)) return;
        suppressing = false;

        double speed = event.getSpeed();

        if (isHoldingGrapplingHook()) return;

        BlockPos below = client.player.getBlockPos().down();
        BlockState blockBelow = client.world.getBlockState(below);
        if (blockBelow.isOf(Blocks.SLIME_BLOCK)) return;

        if (client.player.hurtTime > 0) {
            suppressFor(1000L);
            return;
        }

        if (!blockBelow.isAir() && speed < 1.0) {
            suppressFor(1000L);
            return;
        }

        if (speed < 0.5) return;

        String severity;
        if (speed < 1.0) severity = "MEDIUM";
        else if (speed < 2.0) severity = "HIGH";
        else severity = "VERY HIGH";

        ChatUtils.sendWarningMessage(String.format(
            "Failsafe: Velocity detected (%s) — speed %.2f", severity, speed));
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    private boolean isHoldingGrapplingHook() {
        if (client.player == null) return false;
        ItemStack held = client.player.getMainHandStack();
        if (held.isEmpty()) return false;
        return held.getName().getString().toLowerCase().contains("grappling");
    }

    @Override
    public void reset() {
        suppressing = false;
        suppressClock.resetReady();
    }

    private void suppressFor(long millis) {
        suppressClock.delay(millis);
        suppressing = true;
    }
}
