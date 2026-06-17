package com.justingoat.goat.client.module.render;

import com.justingoat.goat.client.module.GoatModule;
import com.justingoat.goat.client.module.ModuleCategory;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

public final class NicknameHider extends GoatModule {
    private static final String REPLACEMENT = "You";

    public NicknameHider() {
        super("NicknameHider", ModuleCategory.RENDER, false);
    }

    public static String getPlayerName() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.getSession() == null) return null;
        return client.getSession().getUsername();
    }

    public static String replaceInString(String input) {
        String name = getPlayerName();
        if (name == null || name.isEmpty()) return input;
        return input.replace(name, REPLACEMENT);
    }

    public static Text replaceInText(Text original) {
        String name = getPlayerName();
        if (name == null || name.isEmpty()) return original;

        String full = original.getString();
        if (!full.contains(name)) return original;

        MutableText result = rebuildText(original, name);
        return result;
    }

    private static MutableText rebuildText(Text text, String name) {
        MutableText result = Text.empty();

        String literal = getDirectContent(text);
        if (!literal.isEmpty()) {
            appendReplaced(result, literal, name, text.getStyle());
        }

        for (Text sibling : text.getSiblings()) {
            result.append(rebuildText(sibling, name));
        }

        return result;
    }

    private static String getDirectContent(Text text) {
        if (text.getContent() instanceof net.minecraft.text.PlainTextContent plain) {
            return plain.string();
        }
        return "";
    }

    private static void appendReplaced(MutableText result, String literal, String name, Style style) {
        int idx = 0;
        while (idx < literal.length()) {
            int found = literal.indexOf(name, idx);
            if (found == -1) {
                result.append(Text.literal(literal.substring(idx)).setStyle(style));
                break;
            }
            if (found > idx) {
                result.append(Text.literal(literal.substring(idx, found)).setStyle(style));
            }
            result.append(Text.literal(REPLACEMENT).setStyle(style));
            idx = found + name.length();
        }
    }
}
