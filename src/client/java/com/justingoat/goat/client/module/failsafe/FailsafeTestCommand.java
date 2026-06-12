package com.justingoat.goat.client.module.failsafe;

import com.justingoat.goat.client.events.EventManager;
import com.justingoat.goat.client.events.impl.packet.ChatMessageEvent;
import com.justingoat.goat.client.events.impl.packet.SlotChangePacketEvent;
import com.justingoat.goat.client.events.impl.packet.TeleportPacketEvent;
import com.justingoat.goat.client.events.impl.packet.VelocityPacketEvent;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.ModuleManager;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

public final class FailsafeTestCommand {
    private FailsafeTestCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) return;

        dispatcher.register(ClientCommandManager.literal("goatfailsafe")
            .then(ClientCommandManager.literal("reset")
                .executes(context -> reset(context.getSource())))
            .then(ClientCommandManager.literal("status")
                .executes(context -> status(context.getSource())))
            .then(ClientCommandManager.literal("test")
                .then(ClientCommandManager.literal("chat")
                    .executes(context -> test(context.getSource(), "chat")))
                .then(ClientCommandManager.literal("teleport")
                    .executes(context -> test(context.getSource(), "teleport")))
                .then(ClientCommandManager.literal("velocity")
                    .executes(context -> test(context.getSource(), "velocity")))
                .then(ClientCommandManager.literal("slot")
                    .executes(context -> test(context.getSource(), "slot")))
                .then(ClientCommandManager.literal("rotation")
                    .executes(context -> test(context.getSource(), "rotation")))
                .then(ClientCommandManager.literal("world")
                    .executes(context -> test(context.getSource(), "world"))))
        );
    }

    private static int reset(FabricClientCommandSource source) {
        FailsafeManager.getInstance().reset();
        source.sendFeedback(Text.literal("[Goat] Failsafe state reset."));
        return 1;
    }

    private static int status(FabricClientCommandSource source) {
        FailsafeManager manager = FailsafeManager.getInstance();
        String active = manager.getActiveFailsafe() == null ? "none" : manager.getActiveFailsafe().getName();
        source.sendFeedback(Text.literal("[Goat] Failsafe emergency=" + manager.hasEmergency()
            + ", active=" + active
            + ", reaction=" + manager.isReactionActive()));
        return 1;
    }

    private static int test(FabricClientCommandSource source, String testName) {
        MinecraftClient client = source.getClient();
        FailsafeManager manager = FailsafeManager.getInstance();

        manager.reset();
        if (requiresPlayer(testName) && !requirePlayer(source, client)) return 0;

        prepareTestMacro();

        switch (testName) {
            case "chat" -> EventManager.INSTANCE.fire(new ChatMessageEvent(
                "Tester: " + client.getSession().getUsername() + " macro?", false));
            case "teleport" -> {
                double x = client.player.getX();
                double y = client.player.getY();
                double z = client.player.getZ();
                EventManager.INSTANCE.fire(new TeleportPacketEvent(
                    x, y, z,
                    x + 3.0, y, z,
                    client.player.getYaw(), client.player.getPitch(),
                    client.player.getYaw(), client.player.getPitch()));
            }
            case "velocity" -> {
                EventManager.INSTANCE.fire(new VelocityPacketEvent(client.player.getId(), 2.0, 0.0, 0.0));
            }
            case "slot" -> EventManager.INSTANCE.fire(new SlotChangePacketEvent(0, 1));
            case "rotation" -> {
                manager.tick();
                client.player.setYaw(client.player.getYaw() + 90.0f);
            }
            case "world" -> {
                if (!manager.triggerDevEmergency("World Change")) {
                    source.sendError(Text.literal("[Goat] World Change failsafe not found."));
                    return 0;
                }
            }
            default -> {
                source.sendError(Text.literal("[Goat] Unknown failsafe test: " + testName));
                return 0;
            }
        }

        manager.tick();
        reportResult(source, testName, manager);
        return manager.hasEmergency() ? 1 : 0;
    }

    private static boolean requiresPlayer(String testName) {
        return testName.equals("teleport") || testName.equals("velocity") || testName.equals("rotation");
    }

    private static boolean requirePlayer(FabricClientCommandSource source, MinecraftClient client) {
        if (client.player != null) return true;
        source.sendError(Text.literal("[Goat] Join a world before running this failsafe test."));
        return false;
    }

    private static void prepareTestMacro() {
        for (GoatModule module : ModuleManager.getModules()) {
            if (module.getCategory() == ModuleCategory.MACRO) {
                module.setEnabled(false);
            }
        }

        GoatModule sentinel = ModuleManager.findByName("FailsafeTestMacro");
        if (sentinel == null) {
            sentinel = ModuleManager.findByName("Pathfinder");
        }
        if (sentinel != null) {
            sentinel.setEnabled(true);
        }
    }

    private static void reportResult(FabricClientCommandSource source, String testName, FailsafeManager manager) {
        if (!manager.hasEmergency()) {
            source.sendError(Text.literal("[Goat] Failsafe test failed: " + testName + " did not trigger."));
            return;
        }

        String active = manager.getActiveFailsafe() == null ? "unknown" : manager.getActiveFailsafe().getName();
        source.sendFeedback(Text.literal("[Goat] Failsafe test triggered: " + active));
    }
}
