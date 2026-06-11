package com.justingoat.goat.client;

import com.justingoat.goat.client.config.GoatConfigManager;
import com.justingoat.goat.client.gui.GoatMacroScreen;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.movement.PathfinderTest;
import com.justingoat.goat.client.module.pathfinder.PathRenderer;
import com.justingoat.goat.client.utils.RotationInterpolator;
import com.justingoat.goat.client.utils.HypixelUtils;
import com.justingoat.goat.client.utils.SkyBlockUtils;
import com.justingoat.goat.client.events.EventManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

public class GoatClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("goat-client");
    private static KeyBinding openGuiKeyBinding;

    @Override
    public void onInitializeClient() {
        GoatConfigManager.load();

        openGuiKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.goat.open_gui",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_SHIFT,
            KeyBinding.Category.MISC
        ));

        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            // Unused
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> GoatConfigManager.save());

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ModuleManager.tick(client);
            FailsafeManager.getInstance().tick();
            while (openGuiKeyBinding.wasPressed()) {
                client.setScreen(new GoatMacroScreen());
            }
            if (client.currentScreen == null) {
                for (GoatModule module : ModuleManager.getModules()) {
                    int key = module.getKeyBind();
                    if (key > 0 && InputUtil.isKeyPressed(client.getWindow(), key)) {
                        if (!module.wasKeyDown) {
                            module.toggle();
                            GoatConfigManager.save();
                        }
                        module.wasKeyDown = true;
                    } else {
                        module.wasKeyDown = false;
                    }
                }
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("goto")
                .then(ClientCommandManager.argument("x", IntegerArgumentType.integer())
                    .then(ClientCommandManager.argument("y", IntegerArgumentType.integer())
                        .then(ClientCommandManager.argument("z", IntegerArgumentType.integer())
                            .executes(context -> {
                                int x = IntegerArgumentType.getInteger(context, "x");
                                int y = IntegerArgumentType.getInteger(context, "y");
                                int z = IntegerArgumentType.getInteger(context, "z");
                                
                                BlockPos target = new BlockPos(x, y, z);
                                
                                GoatModule module = ModuleManager.getModules().stream()
                                    .filter(m -> m.getName().equals("Pathfinder"))
                                    .findFirst().orElse(null);
                                    
                                if (module instanceof PathfinderTest) {
                                    ((PathfinderTest) module).pathTarget(target);
                                    context.getSource().sendFeedback(Text.literal("[Goat] Pathing to " + x + " " + y + " " + z));
                                } else {
                                    context.getSource().sendError(Text.literal("[Goat] Pathfinder module not found."));
                                }
                                
                                return 1;
                            })
                        )
                    )
                )
            );
        });

        PathRenderer.register();
        RotationInterpolator.register();

        HypixelUtils.init();
        EventManager.INSTANCE.registerEvents(new SkyBlockUtils());

        LOGGER.info("Goat client initialized.");
    }
}
