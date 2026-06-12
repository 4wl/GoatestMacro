package com.justingoat.goat.client.commands.impl;

import com.justingoat.goat.client.commands.Argument;
import com.justingoat.goat.client.commands.Command;
import com.justingoat.goat.client.commands.CommandManager;
import com.justingoat.goat.client.utils.ChatUtils;

import java.util.ArrayList;
import java.util.List;

public class HelpCommand extends Command {
    public HelpCommand() {
        super("help", "Shows all available commands", "");
    }

    @Override
    public void execute(String[] args) {
        ChatUtils.sendHeader("Commands");

        if (args.length > 0) {
            Command command = CommandManager.INSTANCE.getCommand(args[0]);
            if (command != null) {
                ChatUtils.sendRawMessage("§7Command: §f" + command.getName());
                ChatUtils.sendRawMessage("§7Description: §f" + command.getDescription());
                ChatUtils.sendRawMessage("§7Usage: §f" + command.getUsage());
                ChatUtils.sendSeparator();
            } else {
                ChatUtils.sendErrorMessage("Unknown command: " + args[0]);
            }
            return;
        }

        for (Command command : CommandManager.INSTANCE.getCommands()) {
            ChatUtils.sendRawMessage(String.format("§6%s §7- §f%s", command.getName(), command.getDescription()));
        }

        ChatUtils.sendSeparator();
        ChatUtils.sendInfoMessage("Use /goat help <command> for detailed info");
    }

    @Override
    public List<Argument> getArguments() {
        List<Argument> commands = new ArrayList<>();
        for (Command command : CommandManager.INSTANCE.getCommands()) {
            if (!command.getName().equals("help")) {
                commands.add(new Argument(command.getName()));
            }
        }
        return commands;
    }
}
