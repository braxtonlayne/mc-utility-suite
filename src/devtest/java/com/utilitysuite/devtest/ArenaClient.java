package com.utilitysuite.devtest;

import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.core.ServerAllowList;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.tools.player.AutoRespawn;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only client side of the local bot benchmark arena ({@code ./gradlew runArena}, inert unless
 * {@code -Dutilitysuite.arena=true}). The run quick-plays into the arena server (tools/bot-arena, 127.0.0.1:25570);
 * once in the world this sets the arena profile the way a player would in the menu: Combat Assistant armed,
 * Proactive engagement of players, duel mode (never disengage), Auto-Respawn on. Everything else stays at the
 * defaults, so the benchmark measures the shipped behaviour. The orchestrator (tools/bot-arena/bots/arena.js) resets
 * rounds over RCON and scores them from death messages; this class only logs what it sees.
 */
final class ArenaClient {

    private int readyTicks = -1;
    private boolean configured;

    void register(IEventBus bus) {
        bus.addListener(this::onTick);
        bus.addListener(this::onChat);
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || mc.hasSingleplayerServer()) {
            readyTicks = -1;
            configured = false;
            return;
        }
        if (configured) {
            return;
        }
        if (readyTicks < 0) {
            readyTicks = 0;
        }
        if (++readyTicks < 40) {
            return; // let the server profile load first
        }
        String allow = Services.modState().profile().getString(ServerAllowList.SOURCE_MODULE_ID,
                ServerAllowList.SETTING_KEY, ServerAllowList.DEFAULT_LIST);
        if (!allow.contains("127.0.0.1")) {
            H.setting(ServerAllowList.SOURCE_MODULE_ID, ServerAllowList.SETTING_KEY, allow + ",127.0.0.1");
        }
        H.enable(CombatAssistant.ID, true);
        H.setting(CombatAssistant.ID, CombatSettings.ENGAGE_MODE, "PROACTIVE");
        H.setting(CombatAssistant.ID, CombatSettings.TARGETS, "PLAYERS");
        H.setting(CombatAssistant.ID, CombatSettings.ENGAGE_RANGE, 24.0);
        H.setting(CombatAssistant.ID, CombatSettings.DUEL_MODE, true);
        H.enable(AutoRespawn.ID, true);
        configured = true;
        PlaytestMod.LOG.info("[arena-client] configured: combat assistant proactive on players, duel mode, auto"
                + " respawn; server {}", mc.getCurrentServer() == null ? "?" : mc.getCurrentServer().ip);
    }

    private void onChat(ClientChatReceivedEvent e) {
        String s = e.getMessage().getString();
        if (s.startsWith("[arena]") || s.contains(" was slain by ")) {
            PlaytestMod.LOG.info("[arena-client] {}", s);
        }
    }
}
