package com.justingoat.goat;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GoatMod implements ModInitializer {
    public static final String MOD_ID = "goat";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("goat")
                .executes(context -> {
                    context.getSource().sendFeedback(() -> Text.literal("Goat mod is loaded."), false);
                    return 1;
                }))
        );

        LOGGER.info("Goat mod initialized.");
    }
}
