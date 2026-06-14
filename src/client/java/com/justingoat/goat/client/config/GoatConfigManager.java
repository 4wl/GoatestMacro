package com.justingoat.goat.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.movement.FarmingMacro;
import com.justingoat.goat.client.module.value.BooleanValue;
import com.justingoat.goat.client.module.value.KeybindValue;
import com.justingoat.goat.client.module.value.ModeValue;
import com.justingoat.goat.client.module.value.ModuleValue;
import com.justingoat.goat.client.module.value.NumberValue;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GoatConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("goat-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
        .getConfigDir()
        .resolve("goat")
        .resolve("client.json");

    private GoatConfigManager() {
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonElement rootElement = JsonParser.parseReader(reader);
            if (!rootElement.isJsonObject()) {
                LOGGER.warn("Goat config is not a JSON object, using defaults.");
                return;
            }
            applyConfig(rootElement.getAsJsonObject());
        } catch (IOException | IllegalStateException exception) {
            LOGGER.warn("Failed to load Goat config, using defaults.", exception);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(createConfig(), writer);
            }
        } catch (IOException exception) {
            LOGGER.warn("Failed to save Goat config.", exception);
        }
    }

    private static JsonObject createConfig() {
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);

        JsonObject modules = new JsonObject();
        for (GoatModule module : ModuleManager.getModules()) {
            JsonObject moduleJson = new JsonObject();
            moduleJson.addProperty("enabled", module.isEnabled());
            moduleJson.addProperty("keybind", module.getKeyBind());

            JsonObject values = new JsonObject();
            for (ModuleValue value : module.getValues()) {
                if (value instanceof BooleanValue booleanValue) {
                    values.addProperty(value.getName(), booleanValue.getValue());
                } else if (value instanceof NumberValue numberValue) {
                    values.addProperty(value.getName(), numberValue.getValue());
                } else if (value instanceof ModeValue modeValue) {
                    values.addProperty(value.getName(), modeValue.getValue());
                } else if (value instanceof KeybindValue keybindValue) {
                    values.addProperty(value.getName(), keybindValue.getKeyCode());
                }
            }
            moduleJson.add("values", values);
            addModuleData(module, moduleJson);
            modules.add(module.getName(), moduleJson);
        }

        root.add("modules", modules);
        return root;
    }

    private static void applyConfig(JsonObject root) {
        JsonObject modules = getObject(root, "modules");
        if (modules == null) {
            return;
        }

        for (GoatModule module : ModuleManager.getModules()) {
            JsonObject moduleJson = getObject(modules, module.getName());
            if (moduleJson == null) {
                continue;
            }

            JsonElement enabled = moduleJson.get("enabled");
            if (enabled != null && enabled.isJsonPrimitive() && enabled.getAsJsonPrimitive().isBoolean() && module.canToggle()) {
                module.setEnabled(enabled.getAsBoolean());
            }

            JsonElement keybind = moduleJson.get("keybind");
            if (keybind != null && keybind.isJsonPrimitive() && keybind.getAsJsonPrimitive().isNumber()) {
                module.setKeyBind(keybind.getAsInt());
            }

            JsonObject values = getObject(moduleJson, "values");
            if (values != null) {
                applyValues(module, values);
            }

            JsonObject data = getObject(moduleJson, "data");
            if (data != null) {
                applyModuleData(module, data);
            }
        }
    }

    private static void applyValues(GoatModule module, JsonObject values) {
        for (ModuleValue value : module.getValues()) {
            JsonElement savedValue = values.get(value.getName());
            if (savedValue == null || !savedValue.isJsonPrimitive()) {
                continue;
            }

            try {
                if (value instanceof BooleanValue booleanValue && savedValue.getAsJsonPrimitive().isBoolean()) {
                    booleanValue.setValue(savedValue.getAsBoolean());
                } else if (value instanceof NumberValue numberValue && savedValue.getAsJsonPrimitive().isNumber()) {
                    numberValue.setValue(savedValue.getAsDouble());
                } else if (value instanceof ModeValue modeValue && savedValue.getAsJsonPrimitive().isString()) {
                    modeValue.setValue(savedValue.getAsString());
                } else if (value instanceof KeybindValue keybindValue && savedValue.getAsJsonPrimitive().isNumber()) {
                    keybindValue.setKeyCode(savedValue.getAsInt());
                }
            } catch (NumberFormatException exception) {
                LOGGER.warn("Invalid value for {}.{} in Goat config.", module.getName(), value.getName());
            }
        }
    }

    private static JsonObject getObject(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        return element.getAsJsonObject();
    }

    private static void addModuleData(GoatModule module, JsonObject moduleJson) {
        if (module instanceof FarmingMacro farmingMacro) {
            JsonObject data = new JsonObject();
            addBlockPos(data, "startPoint", farmingMacro.getStartPoint());
            addBlockPos(data, "endPoint", farmingMacro.getEndPoint());
            addBlockPos(data, "rewarpTriggerPoint", farmingMacro.getRewarpTriggerPoint());
            moduleJson.add("data", data);
        }
    }

    private static void applyModuleData(GoatModule module, JsonObject data) {
        if (module instanceof FarmingMacro farmingMacro) {
            farmingMacro.loadSavedPoints(
                readBlockPos(data, "startPoint"),
                readBlockPos(data, "endPoint"),
                readBlockPos(data, "rewarpTriggerPoint")
            );
        }
    }

    private static void addBlockPos(JsonObject object, String key, BlockPos pos) {
        if (pos == null) {
            return;
        }

        JsonObject posJson = new JsonObject();
        posJson.addProperty("x", pos.getX());
        posJson.addProperty("y", pos.getY());
        posJson.addProperty("z", pos.getZ());
        object.add(key, posJson);
    }

    private static BlockPos readBlockPos(JsonObject object, String key) {
        JsonObject posJson = getObject(object, key);
        if (posJson == null) {
            return null;
        }

        JsonElement x = posJson.get("x");
        JsonElement y = posJson.get("y");
        JsonElement z = posJson.get("z");
        if (!isNumber(x) || !isNumber(y) || !isNumber(z)) {
            LOGGER.warn("Invalid BlockPos for {} in Goat config.", key);
            return null;
        }

        return new BlockPos(x.getAsInt(), y.getAsInt(), z.getAsInt());
    }

    private static boolean isNumber(JsonElement element) {
        return element != null
            && element.isJsonPrimitive()
            && element.getAsJsonPrimitive().isNumber();
    }
}
