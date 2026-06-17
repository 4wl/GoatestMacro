package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.events.EventListener;
import com.justingoat.goat.client.events.impl.packet.TeleportPacketEvent;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.MacroClock;
import net.minecraft.item.ItemStack;

public class TeleportFailsafe extends Failsafe {

    private final MacroClock rightClickClock = new MacroClock();
    private final MacroClock commandClock = new MacroClock();
    private final MacroClock suppressClock = new MacroClock();
    private boolean rightClickMarked = false;
    private boolean commandMarked = false;
    private volatile boolean suppressing = false;

    @Override
    public int getPriority() { return 5; }

    @Override
    public String getName() { return "Teleport Check"; }

    @EventListener
    public void onTeleport(TeleportPacketEvent event) {
        if (client.player == null) return;
        if (!FailsafeManager.getInstance().isAnyMacroActive()) return;

        double distance = event.getDistance();
        if (shouldSuppress()) return;

        if (distance < 0.01) {
            double totalRot = event.getTotalRotation();
            if (totalRot > 0.1) {
                handleRotationTeleport(event, totalRot);
            }
            return;
        }

        if (distance < 1.0) return;

        String severity;
        if (distance < 2.0) severity = "MEDIUM";
        else if (distance < 3.0) severity = "HIGH";
        else severity = "VERY HIGH";

        ChatUtils.sendWarningMessage(String.format(
            "Failsafe: Teleport detected (%s) — %.1f blocks", severity, distance));
        ChatUtils.sendWarningMessage(String.format(
            "  From: %.1f, %.1f, %.1f → To: %.1f, %.1f, %.1f",
            event.getFromX(), event.getFromY(), event.getFromZ(),
            event.getToX(), event.getToY(), event.getToZ()));

        FailsafeManager.getInstance().triggerEmergency(this);
    }

    private void handleRotationTeleport(TeleportPacketEvent event, double totalRot) {
        String severity;
        if (totalRot < 5.0) return;
        else if (totalRot < 20.0) severity = "MEDIUM";
        else if (totalRot < 40.0) severity = "HIGH";
        else severity = "VERY HIGH";

        ChatUtils.sendWarningMessage(String.format(
            "Failsafe: Server rotation detected (%s) — %.1f°", severity, totalRot));
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    public void markRightClick() {
        rightClickClock.mark();
        rightClickMarked = true;
    }

    public void markCommand() {
        markCommand(750);
    }

    public void markCommand(long suppressMs) {
        commandClock.mark();
        commandMarked = true;
        suppressFor(suppressMs);
    }

    private boolean shouldSuppress() {
        if (suppressing && !suppressClock.ready(0L)) return true;
        suppressing = false;

        if (rightClickMarked && !rightClickClock.ready(1000L)) {
            if (isHoldingTeleportItem()) {
                suppressFor(500L);
                return true;
            }
        }
        rightClickMarked = false;

        if (commandMarked && !commandClock.ready(1000L)) {
            suppressFor(750L);
            return true;
        }
        commandMarked = false;

        return false;
    }

    private boolean isHoldingTeleportItem() {
        if (client.player == null) return false;
        ItemStack held = client.player.getMainHandStack();
        if (held.isEmpty()) return false;
        String name = held.getName().getString().toLowerCase();
        return name.contains("aspect of the") && !name.contains("dragon");
    }

    @Override
    public void reset() {
        rightClickMarked = false;
        commandMarked = false;
        suppressing = false;
        rightClickClock.resetReady();
        commandClock.resetReady();
        suppressClock.resetReady();
    }

    private void suppressFor(long millis) {
        suppressClock.delay(millis);
        suppressing = true;
    }
}
