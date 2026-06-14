package com.justingoat.goat.client.module;

import java.util.List;

public interface MacroHudInfo {
    String getHudName();

    String getHudState();

    default List<String> getHudExtraLines() {
        return List.of();
    }

    default int getHudPriority() {
        return 0;
    }
}
