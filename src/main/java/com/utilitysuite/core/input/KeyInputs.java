package com.utilitysuite.core.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.sdl.SDLMouse;

/**
 * 26.3 key helpers for {@code core.input}: matching a hardware key (SDL3 scancode) or mouse button (SDL button,
 * 1 = left, 2 = middle, 3 = right) against a {@link KeyMapping}, and reading the physical state of a mapping's key.
 * Replaces the GLFW calls and {@code KeyMapping.matches(int, int)} of 1.21.1. Allocation free; client thread.
 */
public final class KeyInputs {

    /** SDL_SCANCODE_COUNT: the size of SDL's keyboard state array. */
    private static final int SCANCODE_COUNT = 512;

    private KeyInputs() {
    }

    /** True when {@code m} is bound to the keyboard key with this scancode. */
    public static boolean matchesKey(KeyMapping m, int scancode) {
        InputConstants.Key k = m.getKey();
        return k.getType() == InputConstants.Type.KEYBOARD && k.getValue() == scancode && !m.isUnbound();
    }

    /** True when {@code m} is bound to this mouse button. */
    public static boolean matchesButton(KeyMapping m, int button) {
        InputConstants.Key k = m.getKey();
        return k.getType() == InputConstants.Type.MOUSE && k.getValue() == button && !m.isUnbound();
    }

    /** True while the key or button the mapping is bound to is physically held. */
    public static boolean isPhysicallyDown(KeyMapping m) {
        if (m.isUnbound()) {
            return false;
        }
        InputConstants.Key k = m.getKey();
        if (k.getType() == InputConstants.Type.KEYBOARD) {
            return isKeyDown(k.getValue());
        }
        if (k.getType() == InputConstants.Type.MOUSE) {
            return isMouseButtonDown(k.getValue());
        }
        return false;
    }

    /** Physical state of a keyboard key (SDL scancode, e.g. {@code InputConstants.KEY_LCONTROL}). */
    public static boolean isKeyDown(int scancode) {
        return scancode > 0 && scancode < SCANCODE_COUNT && InputConstants.isKeyDown(scancode);
    }

    /** Physical state of a mouse button (SDL button number: SDL_BUTTON_MASK(b) = 1 << (b - 1)). */
    public static boolean isMouseButtonDown(int button) {
        if (button < 1 || button > 32) {
            return false;
        }
        int mask = SDLMouse.nSDL_GetMouseState(0L, 0L); // null x/y: buttons only, nothing allocated
        return (mask & (1 << (button - 1))) != 0;
    }
}
