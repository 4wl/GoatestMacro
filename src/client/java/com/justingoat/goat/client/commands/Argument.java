package com.justingoat.goat.client.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import java.util.ArrayList;
import java.util.List;

public class Argument {
    private final String name;
    private final ArgumentType type;
    private final List<Argument> children;

    public Argument(String name) {
        this.name = name;
        this.type = ArgumentType.LITERAL;
        this.children = new ArrayList<>();
    }

    public Argument(String name, ArgumentType type) {
        this.name = name;
        this.type = type;
        this.children = new ArrayList<>();
    }

    public Argument addChild(String childName) {
        Argument child = new Argument(childName);
        children.add(child);
        return child;
    }

    public Argument addChild(String childName, ArgumentType type) {
        Argument child = new Argument(childName, type);
        children.add(child);
        return child;
    }

    public ArgumentType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public List<Argument> getChildren() {
        return new ArrayList<>(children);
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public enum ArgumentType {
        INTEGER,
        WORD,
        STRING,
        LITERAL;

        public com.mojang.brigadier.arguments.ArgumentType<?> getBrigadierType() {
            switch (this) {
                case INTEGER:
                    return IntegerArgumentType.integer();
                case WORD:
                    return StringArgumentType.word();
                case STRING:
                    return StringArgumentType.string();
                case LITERAL:
                default:
                    return null;
            }
        }

        public String getValue(CommandContext<FabricClientCommandSource> context, String name) {
            switch (this) {
                case INTEGER:
                    return String.valueOf(IntegerArgumentType.getInteger(context, name));
                case WORD, STRING:
                    return StringArgumentType.getString(context, name);
                case LITERAL:
                default:
                    return name;
            }
        }
    }
}
