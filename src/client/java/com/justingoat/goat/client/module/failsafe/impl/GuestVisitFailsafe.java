package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.events.EventListener;
import com.justingoat.goat.client.events.impl.packet.ChatMessageEvent;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;

public class GuestVisitFailsafe extends Failsafe {
    private String lastGuestName = "";

    @Override
    public int getPriority() { return 1; }

    @Override
    public String getName() { return "Guest Visit"; }

    @EventListener
    private void onChatMessage(ChatMessageEvent event) {
        if (event.isOverlay() || !FailsafeDetectionUtils.canCheckMacro()) return;
        String message = event.getMessage();
        if (message.contains(":")) return;
        if (!message.contains("is visiting Your Garden")) return;

        lastGuestName = message.replace("[SkyBlock] ", "").replace(" is visiting Your Garden!", "").trim();
        ChatUtils.sendWarningMessage("Failsafe: guest visiting garden" + (lastGuestName.isEmpty() ? "" : " (" + lastGuestName + ")"));
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    @Override
    public boolean shouldResumeMacros() {
        return false;
    }

    @Override
    public void reset() {
        lastGuestName = "";
    }
}
