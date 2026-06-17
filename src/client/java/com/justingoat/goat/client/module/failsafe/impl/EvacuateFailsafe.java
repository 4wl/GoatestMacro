package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.events.EventListener;
import com.justingoat.goat.client.events.impl.packet.ChatMessageEvent;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.module.movement.FarmingMacro;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.CommandUtils;

public class EvacuateFailsafe extends Failsafe {
    @Override
    public int getPriority() { return 1; }

    @Override
    public String getName() { return "Evacuate"; }

    @EventListener
    private void onChatMessage(ChatMessageEvent event) {
        if (event.isOverlay() || !FailsafeDetectionUtils.canCheckMacro()) return;
        String message = FailsafeDetectionUtils.normalize(event.getMessage());
        if (!isEvacuateMessage(message)) return;
        if (isFarmingServerRecoveryActive()) return;

        CommandUtils.evacuate(client);
        ChatUtils.sendWarningMessage("Failsafe: server evacuation requested");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    private boolean isEvacuateMessage(String message) {
        return message.startsWith("you can't use this when the server is about to")
            || message.contains("server is about to restart")
            || message.contains("server is closing");
    }

    private boolean isFarmingServerRecoveryActive() {
        GoatModule module = ModuleManager.findByName("FarmingMacro");
        return module instanceof FarmingMacro farmingMacro && farmingMacro.isRecoveringFromServerMove();
    }
}
