package com.utilitysuite.core.input;

import com.mojang.blaze3d.platform.InputConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Pins the 1.21.1 (GLFW) -> 26.3 (SDL3) keybind migration to the game's own 26.3 key constants. */
class LegacyKeyCodesTest {

    @Test
    void glfwKeyCodesMapToTheGamesScancodes() {
        assertEquals(InputConstants.KEY_A, LegacyKeyCodes.keyboardScancode(65));
        assertEquals(InputConstants.KEY_Z, LegacyKeyCodes.keyboardScancode(90));
        assertEquals(InputConstants.KEY_0, LegacyKeyCodes.keyboardScancode(48));
        assertEquals(InputConstants.KEY_1, LegacyKeyCodes.keyboardScancode(49));
        assertEquals(InputConstants.KEY_9, LegacyKeyCodes.keyboardScancode(57));
        assertEquals(InputConstants.KEY_F1, LegacyKeyCodes.keyboardScancode(290));
        assertEquals(InputConstants.KEY_F12, LegacyKeyCodes.keyboardScancode(301));
        assertEquals(InputConstants.KEY_SPACE, LegacyKeyCodes.keyboardScancode(32));
        assertEquals(InputConstants.KEY_ESCAPE, LegacyKeyCodes.keyboardScancode(256));
        assertEquals(InputConstants.KEY_RETURN, LegacyKeyCodes.keyboardScancode(257));
        assertEquals(InputConstants.KEY_TAB, LegacyKeyCodes.keyboardScancode(258));
        assertEquals(InputConstants.KEY_BACKSPACE, LegacyKeyCodes.keyboardScancode(259));
        assertEquals(InputConstants.KEY_LSHIFT, LegacyKeyCodes.keyboardScancode(340));
        assertEquals(InputConstants.KEY_LCONTROL, LegacyKeyCodes.keyboardScancode(341));
        assertEquals(InputConstants.KEY_LALT, LegacyKeyCodes.keyboardScancode(342));
        assertEquals(InputConstants.KEY_RSHIFT, LegacyKeyCodes.keyboardScancode(344));
        assertEquals(InputConstants.KEY_UP, LegacyKeyCodes.keyboardScancode(265));
        assertEquals(InputConstants.KEY_LEFT, LegacyKeyCodes.keyboardScancode(263));
        assertEquals(InputConstants.KEY_NUMPAD0, LegacyKeyCodes.keyboardScancode(320));
        assertEquals(InputConstants.KEY_NUMPAD9, LegacyKeyCodes.keyboardScancode(329));
        assertEquals(LegacyKeyCodes.SDL_UNKNOWN, LegacyKeyCodes.keyboardScancode(-1));
        assertEquals(LegacyKeyCodes.SDL_UNKNOWN, LegacyKeyCodes.keyboardScancode(9999));
    }

    @Test
    void glfwMouseButtonsMapToSdlButtons() {
        assertEquals(InputConstants.MOUSE_BUTTON_LEFT, LegacyKeyCodes.mouseButton(0));
        assertEquals(InputConstants.MOUSE_BUTTON_RIGHT, LegacyKeyCodes.mouseButton(1));
        assertEquals(InputConstants.MOUSE_BUTTON_MIDDLE, LegacyKeyCodes.mouseButton(2));
        assertEquals(5, LegacyKeyCodes.mouseButton(4)); // side buttons shift up by one
        assertEquals(LegacyKeyCodes.SDL_UNKNOWN, LegacyKeyCodes.mouseButton(-1));
    }

    @Test
    void savedNamesMigrateOnlyWhereTheyChanged() {
        String unchanged = "key.keyboard.backspace";
        assertSame(unchanged, LegacyKeyCodes.migrateName(unchanged));
        assertSame("key.mouse.left", LegacyKeyCodes.migrateName("key.mouse.left"));
        assertEquals("key.keyboard.1", LegacyKeyCodes.migrateName("key.keyboard.1")); // the digit key, not code 1
        assertEquals("key.keyboard.keypad.period", LegacyKeyCodes.migrateName("key.keyboard.keypad.decimal"));
        assertEquals("key.keyboard.unknown", LegacyKeyCodes.migrateName("key.keyboard.f25"));
        assertEquals("key.keyboard.unknown", LegacyKeyCodes.migrateName("key.keyboard.-1"));
        assertEquals("key.keyboard." + InputConstants.KEY_A, LegacyKeyCodes.migrateName("key.keyboard.65"));
        assertEquals("key.keyboard.unknown", LegacyKeyCodes.migrateName("key.keyboard.9999"));
    }

    @Test
    void saveStringsKeepTheirModifierSuffix() {
        assertEquals("key.keyboard.keypad.period:SHIFT", LegacyKeyCodes.migrateSaveString("key.keyboard.keypad.decimal:SHIFT"));
        assertEquals("key.keyboard.r", LegacyKeyCodes.migrateSaveString("key.keyboard.r"));
        assertEquals(null, LegacyKeyCodes.migrateSaveString(null));
    }
}
