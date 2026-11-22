package com.utilitysuite.travel;

import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.NoticeKind;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Status output for the travel modules. Sent on state changes only, never per tick. Status lines go to the notice
 * board (toasts) and are dropped in Clean-Screen; warnings (a mode was switched off for safety) are WARN toasts and,
 * because they are safety-relevant, fall back to chat while Clean-Screen is on (toasts are dropped there).
 */
public final class TravelMessages {

    private TravelMessages() {
    }

    /** One status toast, e.g. "Autopilot: arrived". Dropped in Clean-Screen. */
    public static void status(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        IModState state = Services.modState();
        if (mc.player == null || state.isCleanScreen()) {
            return;
        }
        Services.visuals().notices().post(NoticeKind.INFO, Component.translatable(key, args).getString(), null);
    }

    /** A warning toast that is still seen in Clean-Screen as a chat line (safety-relevant). */
    public static void warn(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        Component text = Component.translatable(key, args);
        if (Services.modState().isCleanScreen()) {
            mc.player.displayClientMessage(text.copy().withStyle(ChatFormatting.GOLD), false);
            return;
        }
        Services.visuals().notices().post(NoticeKind.WARN, text.getString(), null);
    }
}
