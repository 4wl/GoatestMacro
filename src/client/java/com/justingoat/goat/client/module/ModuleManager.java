package com.justingoat.goat.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.justingoat.goat.client.module.movement.AutoSprint;
import com.justingoat.goat.client.module.movement.FarmingMacro;
import com.justingoat.goat.client.module.movement.PathfinderTest;
import com.justingoat.goat.client.module.render.CustomFOV;
import com.justingoat.goat.client.module.render.FullBright;
import com.justingoat.goat.client.module.render.TimeChanger;
import net.minecraft.client.MinecraftClient;

public final class ModuleManager {
    private static final List<GoatModule> MODULES = new ArrayList<>();

    static {
        registerCombatModules();
        registerMovementModules();
        registerRenderModules();
        registerSettingsModules();
    }

    private ModuleManager() {
    }

    private static void registerCombatModules() {
        register(module("KillAura", ModuleCategory.COMBAT, false)
            .number("Range", 3.2, 3.0, 6.0)
            .mode("Priority", "Health", "Health", "Distance")
            .build());
        register(module("Velocity", ModuleCategory.COMBAT, false)
            .number("Horizontal", 80.0, 0.0, 100.0)
            .number("Vertical", 100.0, 0.0, 100.0)
            .build());
        register(new GoatModule("AntiBot", ModuleCategory.COMBAT, true));
    }

    private static void registerMovementModules() {
        register(new AutoSprint());
        register(new GoatModule("KeepSprint", ModuleCategory.MOVEMENT, true));
        register(new GoatModule("NoSlow", ModuleCategory.MOVEMENT, false));
        register(module("Speed", ModuleCategory.MOVEMENT, false)
            .mode("Mode", "Hypixel", "Hypixel", "Vanilla")
            .number("Speed", 1.2, 0.1, 3.0)
            .build());
        register(new FarmingMacro());
        register(new PathfinderTest());
    }

    private static void registerRenderModules() {
        register(module("ClickGui", ModuleCategory.RENDER, true)
            .bool("PauseGame", false)
            .mode("Theme", "Light", "Light", "Dark")
            .build());
        register(module("Notification", ModuleCategory.RENDER, true)
            .mode("Mode", "Goat", "Goat", "Simple")
            .build());
        register(module("Crosshair", ModuleCategory.RENDER, true)
            .number("Size", 4.0, 1.0, 12.0)
            .build());
        register(new CustomFOV());
        register(new FullBright());
        register(new TimeChanger());
    }

    private static void registerSettingsModules() {
        register(module("ClientSettings", ModuleCategory.SETTINGS, true)
            .bool("BetterButton", true)
            .bool("ScreenAnimation", true)
            .mode("Theme", "Light", "Light", "Dark")
            .build());
    }

    public static List<GoatModule> getModules() {
        return Collections.unmodifiableList(MODULES);
    }

    public static GoatModule findByName(String name) {
        for (GoatModule module : MODULES) {
            if (module.getName().equals(name)) {
                return module;
            }
        }
        return null;
    }

    public static void tick(MinecraftClient client) {
        for (GoatModule module : MODULES) {
            module.tick(client);
        }
    }

    private static void register(GoatModule module) {
        MODULES.add(module);
    }

    private static ModuleBuilder module(String name, ModuleCategory category, boolean enabled) {
        return new ModuleBuilder(name, category, enabled);
    }

    private static final class ModuleBuilder extends GoatModule {
        private ModuleBuilder(String name, ModuleCategory category, boolean enabled) {
            super(name, category, enabled);
        }

        private ModuleBuilder bool(String name, boolean value) {
            addBoolean(name, value);
            return this;
        }

        private ModuleBuilder number(String name, double value, double min, double max) {
            addNumber(name, value, min, max);
            return this;
        }

        private ModuleBuilder mode(String name, String value, String... modes) {
            addMode(name, value, modes);
            return this;
        }

        private GoatModule build() {
            return this;
        }
    }
}
