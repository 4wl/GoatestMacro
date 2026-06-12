package com.justingoat.goat.client.commands.impl;

import com.justingoat.goat.client.commands.Argument;
import com.justingoat.goat.client.commands.Command;
import com.justingoat.goat.client.events.EventManager;
import com.justingoat.goat.client.events.impl.packet.ChatMessageEvent;
import com.justingoat.goat.client.events.impl.packet.SlotChangePacketEvent;
import com.justingoat.goat.client.events.impl.packet.TeleportPacketEvent;
import com.justingoat.goat.client.events.impl.packet.VelocityPacketEvent;
import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;
import com.justingoat.goat.client.module.ModuleManager;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.util.List;

public class FailsafeCommand extends Command {
    public FailsafeCommand() {
        super("failsafe", "Failsafe testing commands (dev only)", "<reset|status|test> [type]");
    }

    @Override
    public void execute(String[] args) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            ChatUtils.sendErrorMessage("This command is only available in development.");
            return;
        }

        if (args.length == 0) {
            ChatUtils.sendErrorMessage("Usage: " + getUsage());
            return;
        }

        switch (args[0]) {
            case "reset" -> {
                FailsafeManager.getInstance().reset();
                ChatUtils.sendSuccessMessage("Failsafe state reset.");
            }
            case "status" -> {
                FailsafeManager manager = FailsafeManager.getInstance();
                String active = manager.getActiveFailsafe() == null ? "none" : manager.getActiveFailsafe().getName();
                ChatUtils.sendInfoMessage("emergency=" + manager.hasEmergency()
                    + ", active=" + active
                    + ", reaction=" + manager.isReactionActive());
            }
            case "test" -> {
                if (args.length < 2) {
                    ChatUtils.sendErrorMessage("Usage: /goat failsafe test <chat|teleport|velocity|slot|rotation|world>");
                    return;
                }
                runTest(args[1]);
            }
            default -> ChatUtils.sendErrorMessage("Unknown subcommand: " + args[0]);
        }
    }

    private void runTest(String testName) {
        MinecraftClient client = MinecraftClient.getInstance();
        FailsafeManager manager = FailsafeManager.getInstance();
        manager.reset();

        if ((testName.equals("teleport") || testName.equals("velocity") || testName.equals("rotation"))
                && client.player == null) {
            ChatUtils.sendErrorMessage("Join a world before running this failsafe test.");
            return;
        }

        for (GoatModule module : ModuleManager.getModules()) {
            if (module.getCategory() == ModuleCategory.MACRO) {
                module.setEnabled(false);
            }
        }
        GoatModule sentinel = ModuleManager.findByName("FailsafeTestMacro");
        if (sentinel == null) sentinel = ModuleManager.findByName("Pathfinder");
        if (sentinel != null) sentinel.setEnabled(true);

        switch (testName) {
            case "chat" -> EventManager.INSTANCE.fire(new ChatMessageEvent(
                "Tester: " + client.getSession().getUsername() + " macro?", false));
            case "teleport" -> {
                double x = client.player.getX();
                double y = client.player.getY();
                double z = client.player.getZ();
                EventManager.INSTANCE.fire(new TeleportPacketEvent(
                    x, y, z, x + 3.0, y, z,
                    client.player.getYaw(), client.player.getPitch(),
                    client.player.getYaw(), client.player.getPitch()));
            }
            case "velocity" -> EventManager.INSTANCE.fire(
                new VelocityPacketEvent(client.player.getId(), 2.0, 0.0, 0.0));
            case "slot" -> EventManager.INSTANCE.fire(new SlotChangePacketEvent(0, 1));
            case "rotation" -> {
                manager.tick();
                client.player.setYaw(client.player.getYaw() + 90.0f);
            }
            case "world" -> {
                if (!manager.triggerDevEmergency("World Change")) {
                    ChatUtils.sendErrorMessage("World Change failsafe not found.");
                    return;
                }
            }
            default -> {
                ChatUtils.sendErrorMessage("Unknown failsafe test: " + testName);
                return;
            }
        }

        manager.tick();
        if (!manager.hasEmergency()) {
            ChatUtils.sendErrorMessage("Failsafe test failed: " + testName + " did not trigger.");
        } else {
            String active = manager.getActiveFailsafe() == null ? "unknown" : manager.getActiveFailsafe().getName();
            ChatUtils.sendSuccessMessage("Failsafe test triggered: " + active);
        }
    }

    @Override
    public List<Argument> getArguments() {
        Argument reset = new Argument("reset");
        Argument status = new Argument("status");
        Argument test = new Argument("test");
        test.addChild("chat");
        test.addChild("teleport");
        test.addChild("velocity");
        test.addChild("slot");
        test.addChild("rotation");
        test.addChild("world");
        return List.of(reset, status, test);
    }
}
