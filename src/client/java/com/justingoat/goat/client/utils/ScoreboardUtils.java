package com.justingoat.goat.client.utils;

import net.minecraft.client.MinecraftClient;
import net.minecraft.scoreboard.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.regex.Pattern;

public class ScoreboardUtils {
    private static final Comparator<ScoreboardEntry> SCOREBOARD_ENTRY_COMPARATOR =
        Comparator.comparing(ScoreboardEntry::value)
            .reversed()
            .thenComparing(ScoreboardEntry::owner, String.CASE_INSENSITIVE_ORDER);

    public static ArrayList<String> getScoreboardLines() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return new ArrayList<>();

        Scoreboard sb = client.world.getScoreboard();
        ScoreboardObjective objective = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) return new ArrayList<>();

        Collection<ScoreboardEntry> scores = sb.getScoreboardEntries(objective)
            .stream()
            .filter(scoreboardEntry -> !scoreboardEntry.hidden())
            .sorted(SCOREBOARD_ENTRY_COMPARATOR)
            .limit(15)
            .toList();

        ArrayList<String> lines = new ArrayList<>();
        for (ScoreboardEntry score : scores) {
            Team team = sb.getScoreHolderTeam(score.owner());
            String text = Team.decorateName(team, score.name()).getString();
            text = stripColorNoLimit(text);
            text = keepScoreboardChars(text).trim();
            lines.add(text);
        }

        return lines;
    }

    public static String getScoreboardTitle() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null) return "";

        Scoreboard sb = client.world.getScoreboard();
        ScoreboardObjective objective = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (objective == null) return "";

        return stripColorNoLimit(objective.getDisplayName().getString());
    }

    private static String keepScoreboardChars(String message) {
        return Pattern.compile("[^a-z A-Z:0-9/'.,]").matcher(message).replaceAll("");
    }

    private static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("§[a-zA-Z]");

    private static String stripColorNoLimit(String input) {
        if (input == null || input.isEmpty()) return input;
        return STRIP_COLOR_PATTERN.matcher(input).replaceAll("");
    }
}
