package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.events.EventListener;
import com.justingoat.goat.client.events.impl.packet.SlotChangePacketEvent;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;

public class SlotChangeFailsafe extends Failsafe {

    @Override
    public int getPriority() { return 7; }

    @Override
    public String getName() { return "Slot Change"; }

    @EventListener
    public void onSlotChange(SlotChangePacketEvent event) {
        if (!FailsafeManager.getInstance().isAnyMacroActive()) return;

        ChatUtils.sendWarningMessage(String.format(
            "Failsafe: Server changed held slot from %d to %d!",
            event.getFromSlot() + 1, event.getToSlot() + 1));
        FailsafeManager.getInstance().triggerEmergency(this);
    }
}
