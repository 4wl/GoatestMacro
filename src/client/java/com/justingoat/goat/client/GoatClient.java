package com.justingoat.goat.client;

import com.justingoat.goat.client.gui.GoatMacroScreen;
import com.justingoat.goat.client.module.ModuleManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

public class GoatClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("goat-client");
    private static KeyBinding openGuiKeyBinding;

    @Override
    public void onInitializeClient() {
        openGuiKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.goat.open_gui",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_SHIFT,
            KeyBinding.Category.MISC
        ));

        ClientTickEvents.START_CLIENT_TICK.register(ModuleManager::tick);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKeyBinding.wasPressed()) {
                client.setScreen(new GoatMacroScreen());
            }
        });

        LOGGER.info("Goat client initialized.");
    }
}
