package com.justingoat.goat.client.module.failsafe.impl;

import com.justingoat.goat.client.events.EventListener;
import com.justingoat.goat.client.events.impl.packet.ChatMessageEvent;
import com.justingoat.goat.client.module.failsafe.Failsafe;
import com.justingoat.goat.client.module.failsafe.FailsafeManager;
import com.justingoat.goat.client.utils.ChatUtils;

import java.util.Set;

public class ChatMentionFailsafe extends Failsafe {

    private static final Set<String> HIGH_KEYWORDS = Set.of(
        "wdr", "report", "macro", "cheat", "exploit", "hack", "bot",
        "autofarm", "automine", "aimbot", "killaura"
    );

    private static final Set<String> MEDIUM_KEYWORDS = Set.of(
        "afk", "sus", "suspicious", "ban", "banned", "staff", "admin", "watchdog"
    );

    @Override
    public int getPriority() { return 3; }

    @Override
    public String getName() { return "Chat Mention"; }

    @EventListener
    public void onChatMessage(ChatMessageEvent event) {
        if (event.isOverlay()) return;
        if (!FailsafeManager.getInstance().isAnyMacroActive()) return;

        String message = event.getMessage().toLowerCase();

        int colonIdx = message.indexOf(':');
        if (colonIdx < 0) return;
        String content = message.substring(colonIdx + 1).trim();

        String playerName = client.getSession().getUsername().toLowerCase();

        boolean mentionsPlayer = content.contains(playerName);
        if (!mentionsPlayer) return;

        for (String keyword : HIGH_KEYWORDS) {
            if (content.contains(keyword)) {
                ChatUtils.sendWarningMessage("Failsafe: Chat mention detected (HIGH) — \"" + keyword + "\"");
                FailsafeManager.getInstance().triggerEmergency(this);
                return;
            }
        }

        for (String keyword : MEDIUM_KEYWORDS) {
            if (content.contains(keyword)) {
                ChatUtils.sendWarningMessage("Failsafe: Chat mention detected (MEDIUM) — \"" + keyword + "\"");
                FailsafeManager.getInstance().triggerEmergency(this);
                return;
            }
        }
    }
}
