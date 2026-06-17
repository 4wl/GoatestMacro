package com.justingoat.goat.client.module;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.KeybindValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.ModuleValue;
import com.justingoat.goat.client.module.value.NumberValue;
import com.justingoat.goat.client.utils.ChatUtils;
import com.justingoat.goat.client.utils.MacroControls;

import net.minecraft.client.MinecraftClient;

public class GoatModule {
    private final String name;
    private final ModuleCategory category;
    private final boolean canToggle;
    private final List<ModuleValue> values = new ArrayList<>();
    private boolean enabled;
    private long enabledAtMillis = 0;
    private int keyBind = -1;
    public transient boolean wasKeyDown;

    public GoatModule(String name, ModuleCategory category, boolean enabled) {
        this(name, category, enabled, true);
    }

    public GoatModule(String name, ModuleCategory category, boolean enabled, boolean canToggle) {
        this.name = name;
        this.category = category;
        this.enabled = enabled;
        this.canToggle = canToggle;
    }

    public void tick(MinecraftClient client) {
    }

    public String getName() {
        return name;
    }

    public ModuleCategory getCategory() {
        return category;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        this.enabledAtMillis = enabled ? System.currentTimeMillis() : 0;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public boolean canToggle() {
        return canToggle;
    }

    public int getKeyBind() {
        return keyBind;
    }

    public void setKeyBind(int keyBind) {
        this.keyBind = keyBind;
    }

    public long getEnabledDurationMillis() {
        return enabledAtMillis == 0 ? 0 : System.currentTimeMillis() - enabledAtMillis;
    }

    public boolean requiresMovement() {
        return true;
    }

    public List<ModuleValue> getValues() {
        return Collections.unmodifiableList(values);
    }

    protected BooleanValue addBoolean(String name, boolean value) {
        BooleanValue moduleValue = new BooleanValue(name, value);
        values.add(moduleValue);
        return moduleValue;
    }

    protected NumberValue addNumber(String name, double value, double min, double max) {
        NumberValue moduleValue = new NumberValue(name, value, min, max);
        values.add(moduleValue);
        return moduleValue;
    }

    protected ModeValue addMode(String name, String value, String... modes) {
        ModeValue moduleValue = new ModeValue(name, value, Arrays.asList(modes));
        values.add(moduleValue);
        return moduleValue;
    }

    protected KeybindValue addKeybind(String name, int keyCode) {
        KeybindValue moduleValue = new KeybindValue(name, keyCode);
        values.add(moduleValue);
        return moduleValue;
    }

    protected void releaseInputs() {
        MacroControls.stopAll();
    }

    protected void sendEnabledMessage() {
        ChatUtils.moduleSuccess(name, "enabled.");
    }

    protected void sendDisabledMessage() {
        ChatUtils.moduleWarning(name, "disabled.");
    }

    protected void sendModuleInfo(String message) {
        ChatUtils.moduleInfo(name, message);
    }

    protected void sendModuleWarning(String message) {
        ChatUtils.moduleWarning(name, message);
    }

    protected void sendModuleError(String message) {
        ChatUtils.moduleError(name, message);
    }
}
