package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.ConditionTimer;
import net.minecraft.item.AxeItem;
import net.minecraft.item.HoeItem;
import net.minecraft.item.ItemStack;

public class ItemChangeFailsafe extends Failsafe {
    private final ConditionTimer invalidToolTimer = new ConditionTimer();

    @Override
    public int getPriority() { return 3; }

    @Override
    public String getName() { return "Item Change"; }

    @Override
    public void onTick() {
        if (!FailsafeDetectionUtils.canCheckMacro() || !isFarmingMacroActive()) {
            invalidToolTimer.reset();
            return;
        }

        // PestCleaner intentionally switches from hoe to vacuum — suppress detection
        if (isPestCleanerActive()) {
            invalidToolTimer.reset();
            return;
        }

        ItemStack held = client.player.getMainHandStack();
        if (!held.isEmpty() && (held.getItem() instanceof HoeItem || held.getItem() instanceof AxeItem)) {
            invalidToolTimer.reset();
            return;
        }

        if (!invalidToolTimer.confirmed(1_000L)) return;

        ChatUtils.sendWarningMessage("Failsafe: farming tool is no longer held");
        FailsafeManager.getInstance().triggerEmergency(this);
    }

    private boolean isFarmingMacroActive() {
        GoatModule farming = ModuleManager.findByName("FarmingMacro");
        return farming != null && farming.isEnabled();
    }

    private boolean isPestCleanerActive() {
        GoatModule pest = ModuleManager.findByName("PestCleaner");
        return pest != null && pest.isEnabled();
    }

    @Override
    public void reset() {
        invalidToolTimer.reset();
    }
}
