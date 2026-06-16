package com.justingoat.goat.client.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.justingoat.goat.client.module.combat.CombatMacro;
import com.justingoat.goat.client.module.farming.PestCleaner;
import com.justingoat.goat.client.module.farming.PlotCleaningHelper;
import com.justingoat.goat.client.module.farming.VisitorsMacro;
import com.justingoat.goat.client.module.movement.AutoSprint;
import com.justingoat.goat.client.module.movement.FarmingMacro;
import com.justingoat.goat.client.module.movement.ForagingMacro;
import com.justingoat.goat.client.module.movement.PathfinderTest;
import com.justingoat.goat.client.module.mining.MiningMacro;
import com.justingoat.goat.client.module.mining.NukerMacro;
import com.justingoat.goat.client.module.mining.OreMacro;
import com.justingoat.goat.client.module.mining.GemstoneMacro;
import com.justingoat.goat.client.module.mining.PowderMacro;
import com.justingoat.goat.client.module.mining.CommissionMacro;
import com.justingoat.goat.client.module.render.CustomFOV;
import com.justingoat.goat.client.module.render.FullBright;
import com.justingoat.goat.client.module.render.PestESP;
import com.justingoat.goat.client.module.render.TimeChanger;
import com.justingoat.goat.client.module.settings.FailsafeSettings;
import com.justingoat.goat.client.module.settings.RotationSettings;
import com.justingoat.goat.client.module.skills.AutoExperiments;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.ModuleValue;
import com.justingoat.goat.client.utils.BPSTracker;
import com.justingoat.goat.client.utils.LagDetector;
import com.justingoat.goat.client.utils.MouseUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

public final class ModuleManager {
    private static final List<GoatModule> MODULES = new ArrayList<>();
    private static boolean hadUngrabMacroActive = false;

    static {
        registerCombatModules();
        registerMovementModules();
        registerFarmingModules();
        registerMiningModules();
        registerSkillModules();
        registerRenderModules();
        registerSettingsModules();
    }

    private ModuleManager() {
    }

    private static void registerCombatModules() {
        register(new CombatMacro());
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
        register(new ForagingMacro());
        register(new PathfinderTest());
    }

    private static void registerFarmingModules() {
        register(new PestCleaner());
        register(new PlotCleaningHelper());
        register(new VisitorsMacro());
    }

    private static void registerMiningModules() {
        register(new MiningMacro());
        register(new NukerMacro());
        register(new OreMacro());
        register(new GemstoneMacro());
        register(new PowderMacro());
        register(new CommissionMacro());
    }

    private static void registerSkillModules() {
        register(new AutoExperiments());
        register(new MacroScheduler());
    }

    private static void registerRenderModules() {
        register(module("ClickGui", ModuleCategory.RENDER, true)
//            .bool("PauseGame", false)
//            .mode("Theme", "Light", "Light", "Dark")
            .build());
        register(module("Notification", ModuleCategory.RENDER, true)
            .mode("Mode", "Goat", "Goat")
            .build());
//        register(module("Crosshair", ModuleCategory.RENDER, true)
//            .number("Size", 4.0, 1.0, 12.0)
//            .build());
        register(new CustomFOV());
        register(new FullBright());
        register(new PestESP());
        register(new TimeChanger());
    }

    private static void registerSettingsModules() {
        register(module("ClientSettings", ModuleCategory.SETTINGS, true)
            .bool("BetterButton", true)
            .bool("ScreenAnimation", true)
            .bool("UngrabOnMacro", true)
//            .mode("Theme", "Light", "Light", "Dark")
            .build());
        register(new RotationSettings());
        register(new FailsafeSettings());
        if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
            register(new GoatModule("FailsafeTestMacro", ModuleCategory.MACRO, false));
        }
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
        BPSTracker.update(client);
        LagDetector.update(client);

        boolean ungrabMacroActive = shouldUngrabMouseForMacro();
        if (ungrabMacroActive && !hadUngrabMacroActive) {
            MouseUtils.ungrabMouse();
        } else if (!ungrabMacroActive && hadUngrabMacroActive) {
            MouseUtils.regrabMouse();
        }
        hadUngrabMacroActive = ungrabMacroActive;

        for (GoatModule module : MODULES) {
            module.tick(client);
        }
    }

    private static boolean shouldUngrabMouseForMacro() {
        if (!isBooleanSettingEnabled("ClientSettings", "UngrabOnMacro", true)) return false;
        for (GoatModule module : MODULES) {
            if (module.isEnabled() && module.getCategory() == ModuleCategory.MACRO) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBooleanSettingEnabled(String moduleName, String valueName, boolean fallback) {
        GoatModule module = findByName(moduleName);
        if (module == null) return fallback;
        for (ModuleValue value : module.getValues()) {
            if (value instanceof BooleanValue booleanValue && value.getName().equals(valueName)) {
                return booleanValue.getValue();
            }
        }
        return fallback;
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
