package com.utilitysuite.devtest;

import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;

import java.util.ArrayList;
import java.util.List;

/** Collects chat/system lines (spark output, command errors) so scenarios can read them back. */
final class ChatCapture {

    private static final List<String> LINES = new ArrayList<>();

    private ChatCapture() {
    }

    static void onChat(ClientChatReceivedEvent e) {
        String text = e.getMessage().getString();
        synchronized (LINES) {
            LINES.add(text);
            if (LINES.size() > 2000) {
                LINES.remove(0);
            }
        }
        PlaytestMod.LOG.info("[playtest] chat: {}", text);
    }

    static List<String> drain() {
        synchronized (LINES) {
            List<String> out = new ArrayList<>(LINES);
            LINES.clear();
            return out;
        }
    }
}
