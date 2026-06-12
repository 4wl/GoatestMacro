package com.justingoat.goat.client.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.justingoat.goat.client.gui.GoatMacroScreen;
import com.justingoat.goat.client.utils.ChatUtils;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CommandManager {
    public static final CommandManager INSTANCE = new CommandManager();

    private final List<Command> registeredCommands;
    private static final String COMMAND_PREFIX = "goat";

    private CommandManager() {
        this.registeredCommands = new ArrayList<>();
    }

    public void registerCommands(Command... commands) {
        this.registeredCommands.addAll(Arrays.asList(commands));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            registerAllCommandsWithFabric(dispatcher);
        });
    }

    private void registerAllCommandsWithFabric(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> mainCommand = createMainCommand();

        for (Command command : registeredCommands) {
            LiteralArgumentBuilder<FabricClientCommandSource> subCommand = createSubCommand(command);
            addArgumentsToCommand(subCommand, command);
            mainCommand.then(subCommand);
        }

        dispatcher.register(mainCommand);
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> createMainCommand() {
        return ClientCommandManager.literal(COMMAND_PREFIX).executes(context -> {
            MinecraftClient.getInstance().execute(() ->
                MinecraftClient.getInstance().setScreen(new GoatMacroScreen())
            );
            return 1;
        });
    }

    private LiteralArgumentBuilder<FabricClientCommandSource> createSubCommand(Command command) {
        return ClientCommandManager.literal(command.getName())
                .executes(context -> executeCommand(command, new String[0]));
    }

    private void addArgumentsToCommand(LiteralArgumentBuilder<FabricClientCommandSource> subCommand, Command command) {
        List<Argument> arguments = command.getArguments();
        if (arguments != null && !arguments.isEmpty()) {
            buildArguments(subCommand, command, arguments, new ArrayList<>());
        }
    }

    private void buildArguments(
            ArgumentBuilder<FabricClientCommandSource, ?> builder,
            Command command,
            List<Argument> argumentNodes,
            List<Argument> currentPath) {

        for (Argument argumentNode : argumentNodes) {
            currentPath.add(argumentNode);

            if (argumentNode.getType() == Argument.ArgumentType.LITERAL) {
                processLiteralArgument(builder, command, argumentNode, currentPath);
            } else {
                processTypedArgument(builder, command, argumentNode, currentPath);
            }

            currentPath.removeLast();
        }
    }

    private void processLiteralArgument(
            ArgumentBuilder<FabricClientCommandSource, ?> builder,
            Command command,
            Argument argumentNode,
            List<Argument> currentPath) {

        LiteralArgumentBuilder<FabricClientCommandSource> literalBuilder =
            ClientCommandManager.literal(argumentNode.getName());

        addExecutionToBuilder(literalBuilder, command, currentPath);
        addChildrenToBuilder(literalBuilder, command, argumentNode, currentPath);

        builder.then(literalBuilder);
    }

    private void processTypedArgument(
            ArgumentBuilder<FabricClientCommandSource, ?> builder,
            Command command,
            Argument argumentNode,
            List<Argument> currentPath) {

        var typedBuilder = ClientCommandManager.argument(
            argumentNode.getName(),
            argumentNode.getType().getBrigadierType()
        );

        addExecutionToBuilder(typedBuilder, command, currentPath);
        addChildrenToBuilder(typedBuilder, command, argumentNode, currentPath);

        builder.then(typedBuilder);
    }

    private void addExecutionToBuilder(
            ArgumentBuilder<FabricClientCommandSource, ?> builder,
            Command command,
            List<Argument> currentPath) {

        List<Argument> pathSnapshot = new ArrayList<>(currentPath);
        builder.executes(context -> executeCommandWithContext(command, context, pathSnapshot));
    }

    private void addChildrenToBuilder(
            ArgumentBuilder<FabricClientCommandSource, ?> builder,
            Command command,
            Argument argumentNode,
            List<Argument> currentPath) {

        if (argumentNode.hasChildren()) {
            buildArguments(builder, command, argumentNode.getChildren(), currentPath);
        }
    }

    private int executeCommand(Command command, String[] args) {
        try {
            command.execute(args);
            return 1;
        } catch (Exception e) {
            ChatUtils.sendErrorMessage("Error executing command: " + e.getMessage());
            return 0;
        }
    }

    private int executeCommandWithContext(
            Command command,
            CommandContext<FabricClientCommandSource> context,
            List<Argument> argumentPath) {
        try {
            String[] extractedArgs = extractArgumentValues(context, argumentPath);
            command.execute(extractedArgs);
            return 1;
        } catch (Exception e) {
            ChatUtils.sendErrorMessage("Error executing command: " + e.getMessage());
            return 0;
        }
    }

    private String[] extractArgumentValues(
            CommandContext<FabricClientCommandSource> context,
            List<Argument> argumentPath) {

        List<String> extractedValues = new ArrayList<>();

        for (Argument argument : argumentPath) {
            if (argument.getType() == Argument.ArgumentType.LITERAL) {
                extractedValues.add(argument.getName());
            } else {
                extractedValues.add(argument.getType().getValue(context, argument.getName()));
            }
        }

        return extractedValues.toArray(new String[0]);
    }

    public List<Command> getCommands() {
        return new ArrayList<>(registeredCommands);
    }

    public Command getCommand(String name) {
        return registeredCommands.stream()
                .filter(cmd -> cmd.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElse(null);
    }

    public String getPrefix() {
        return COMMAND_PREFIX;
    }
}
