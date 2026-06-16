package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.events.EventListener;
import com.justingoat.goat.client.events.impl.packet.ChatMessageEvent;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;

public class BanwaveFailsafe extends Failsafe {
    @Override
    public int getPriority() { return 6; }

    @Override
    public String getName() { return "Banwave"; }

    @EventListener
    private void onChatMessage(ChatMessageEvent event) {
        if (event.isOverlay() || !FailsafeDetectionUtils.canCheckMacro()) return;
        String message = FailsafeDetectionUtils.normalize(event.getMessage());
        if (!isBanwaveMessage(message)) return;

        ChatUtils.sendWarningMessage("Failsafe: possible banwave detected");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    private boolean isBanwaveMessage(String message) {
        return message.contains("banwave")
            || message.contains("watchdog has banned")
            || message.contains("players have been banned")
            || message.contains("staff have banned");
    }

    @Override
    public boolean shouldRunReaction() {
        return false;
    }

    @Override
    public boolean shouldResumeMacros() {
        return false;
    }
}
