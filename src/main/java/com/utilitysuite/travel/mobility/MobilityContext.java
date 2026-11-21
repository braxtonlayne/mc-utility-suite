package com.utilitysuite.travel.mobility;

import com.utilitysuite.core.LegitPresets;
import com.utilitysuite.core.ServerAllowList;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.travel.TravelMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

/** Shared game-side reads for the mobility modules (allow list, tuning level, one-line messages). */
final class MobilityContext {

    private MobilityContext() {
    }

    static boolean singleplayer(Minecraft mc) {
        return mc.hasSingleplayerServer();
    }

    /** The current server is on the shared allow list ({@code core.ServerAllowList}, read-only). */
    static boolean allowListed(Minecraft mc) {
        try {
            String list = Services.modState().profile().getString(ServerAllowList.SOURCE_MODULE_ID,
                    ServerAllowList.SETTING_KEY, ServerAllowList.DEFAULT_LIST);
            ServerData server = mc.getCurrentServer();
            return server != null && ServerAllowList.isAllowedServer(server.ip, list);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The tuning level every knob matches, or null when mixed ("Custom"). Not for per-tick use. */
    static LegitPresets.Level level() {
        try {
            return LegitPresets.current(Services.modState().profile(), Services.modState().global());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** One action-bar line (suppressed in Clean-Screen); only on state changes. */
    static void say(String module, String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || Services.modState().isCleanScreen()) {
            return;
        }
        mc.player.displayClientMessage(Component.literal(module + ": " + text), true);
    }

    static void sayKey(String key) {
        TravelMessages.status(key);
    }
}
