package com.justingoat.goat.client.commands;

import java.util.ArrayList;
import java.util.List;

public abstract class Command {
    private final String name;
    private final String description;
    private final String syntax;

    public Command(String name, String description, String syntax) {
        this.name = name;
        this.description = description;
        this.syntax = syntax;
    }

    public abstract void execute(String[] args);

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getSyntax() {
        return syntax;
    }

    public String getUsage() {
        String usage = "/goat " + name;
        if (syntax != null && !syntax.isEmpty()) {
            usage += " " + syntax;
        }
        return usage;
    }

    public List<Argument> getArguments() {
        return new ArrayList<>();
    }
}
