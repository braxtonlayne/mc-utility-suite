package com.utilitysuite.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.input.KeybindManager;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Inline key rebinding in the suite menu: a hotkey row's button starts listening, the next key press in the menu binds
 * it. Escape cancels, Delete unbinds. The actual rebind goes through {@link KeybindManager#rebind}.
 */
public final class KeyRebind {

    private static KeyMapping listening;

    private KeyRebind() {
    }

    public static KeyMapping listening() {
        return listening;
    }

    /** Starts listening for {@code m}; clicking the row that is already listening cancels. */
    public static void toggle(KeyMapping m) {
        listening = listening == m ? null : m;
    }

    public static void cancel() {
        listening = null;
    }

    /**
     * Menu key hook. Returns true when the press was taken for a rebind (or the cancel), so the menu does nothing else
     * with it. {@code result} receives a short status line.
     */
    static boolean onKey(int keyCode, int scanCode, java.util.function.Consumer<String> result) {
        KeyMapping m = listening;
        if (m == null) {
            return false;
        }
        listening = null;
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            result.accept("Rebind cancelled");
            return true;
        }
        InputConstants.Key key = keyCode == GLFW.GLFW_KEY_DELETE ? InputConstants.UNKNOWN
                : InputConstants.getKey(keyCode, scanCode);
        KeybindManager.rebind(m, key);
        String name = net.minecraft.network.chat.Component.translatable(m.getName()).getString();
        result.accept(key == InputConstants.UNKNOWN ? name + ": unbound"
                : name + ": " + m.getTranslatedKeyMessage().getString()
                + (KeybindManager.hasConflict(m) ? " (also used by another key)" : ""));
        return true;
    }
}
