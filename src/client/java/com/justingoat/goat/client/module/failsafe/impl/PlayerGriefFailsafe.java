package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.MacroClock;
import net.minecraft.client.network.AbstractClientPlayerEntity;

public class PlayerGriefFailsafe extends Failsafe {

    private static final double PROXIMITY_DISTANCE = 3.0;
    private static final double INSIDE_DISTANCE = 0.5;
    private final MacroClock insideCheckClock = new MacroClock();
    private final MacroClock nearbyCheckClock = new MacroClock();

    @Override
    public int getPriority() { return 4; }

    @Override
    public String getName() { return "Player Grief"; }

    @Override
    public void onTick() {
        if (client.player == null || client.world == null) return;
        if (!FailsafeManager.getInstance().isAnyMacroActive()) return;

        if (insideCheckClock.ready(5000)) {
            insideCheckClock.mark();
            checkPlayerInside();
        }

        if (nearbyCheckClock.ready(3000)) {
            nearbyCheckClock.mark();
            checkPlayerNearby();
        }
    }

    private void checkPlayerInside() {
        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;
            if (isNpc(player)) continue;

            double dx = player.getX() - px;
            double dy = player.getY() - py;
            double dz = player.getZ() - pz;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < INSIDE_DISTANCE * INSIDE_DISTANCE) {
                ChatUtils.sendWarningMessage("Failsafe: " + player.getName().getString() + " is standing inside you!");
                FailsafeManager.getInstance().triggerEmergency(this);
                return;
            }
        }
    }

    private void checkPlayerNearby() {
        double px = client.player.getX();
        double py = client.player.getY();
        double pz = client.player.getZ();

        for (AbstractClientPlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;
            if (isNpc(player)) continue;

            double dx = player.getX() - px;
            double dy = player.getY() - py;
            double dz = player.getZ() - pz;
            double distSq = dx * dx + dy * dy + dz * dz;

            if (distSq < PROXIMITY_DISTANCE * PROXIMITY_DISTANCE && distSq > 1.0) {
                double dist = Math.sqrt(distSq);
                ChatUtils.sendWarningMessage(String.format(
                    "Failsafe: %s is %.1f blocks away!",
                    player.getName().getString(), dist));
                FailsafeManager.getInstance().triggerEmergency(this);
                return;
            }
        }
    }

    private boolean isNpc(AbstractClientPlayerEntity player) {
        return player.getUuid().version() == 2;
    }

    @Override
    public void reset() {
        insideCheckClock.resetReady();
        nearbyCheckClock.resetReady();
    }
}
