package com.justingoat.goat.client.module.settings;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.module.value.BooleanValue;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class FailsafeSettings extends GoatModule {
    private final List<Map.Entry<BooleanValue, Failsafe>> bindings = new ArrayList<>();

    public FailsafeSettings() {
        super("Failsafe", ModuleCategory.SETTINGS, true);
        for (Failsafe failsafe : FailsafeManager.getInstance().getFailsafes()) {
            BooleanValue value = addBoolean(failsafe.getName(), true);
            bindings.add(Map.entry(value, failsafe));
        }
    }

    @Override
    public void tick(MinecraftClient client) {
        for (Map.Entry<BooleanValue, Failsafe> entry : bindings) {
            entry.getValue().setEnabled(entry.getKey().getValue());
        }
    }
}
