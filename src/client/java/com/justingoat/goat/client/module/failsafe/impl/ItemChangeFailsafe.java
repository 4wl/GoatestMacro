package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import net.minecraft.item.AxeItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;

public class ItemChangeFailsafe extends Failsafe {
    private long invalidToolSince = 0L;

    @Override
    public int getPriority() { return 3; }

    @Override
    public String getName() { return "Item Change"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckMacro() || !isFarmingMacroActive()) {
            invalidToolSince = 0L;
            return;
        }

        ItemStack held = client.player.getMainHandStack();
        if (!held.isEmpty() && (held.getItem() instanceof HoeItem || held.getItem() instanceof AxeItem)) {
            invalidToolSince = 0L;
            return;
        }

        long now = System.currentTimeMillis();
        if (invalidToolSince == 0L) invalidToolSince = now;
        if (now - invalidToolSince < 1_000L) return;

        ChatUtils.sendWarningMessage("Failsafe: farming tool is no longer held");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    private boolean isFarmingMacroActive() {
        GoatModule farming = ModuleManager.findByName("FarmingMacro");
        return farming != null && farming.isEnabled();
    }

    @Override
    public void reset() {
        invalidToolSince = 0L;
    }
}
