package com.utilitysuite.core.input;

/**
 * One-time migration of key codes saved by the 1.21.1 builds (GLFW key codes and mouse buttons) to the 26.3 codes
 * (SDL3 scancodes, SDL mouse buttons). Pure logic, no Minecraft classes, so it is unit-testable.
 * <p>
 * Key names ({@code key.keyboard.backspace}) mostly carry over unchanged; the exceptions are handled by
 * {@link #migrateName}: the numpad decimal key (GLFW's {@code keypad.decimal} is SDL's {@code keypad.period}), F25
 * (gone) and numeric fallback names ({@code key.keyboard.<glfw code>}).
 */
public final class LegacyKeyCodes {

    /** Old GLFW "unknown" key code. */
    public static final int GLFW_UNKNOWN = -1;
    /** New "unknown" value ({@code InputConstants.UNKNOWN}). */
    public static final int SDL_UNKNOWN = 0;

    private static final String KEYBOARD = "key.keyboard.";
    private static final String UNKNOWN_NAME = "key.keyboard.unknown";

    private LegacyKeyCodes() {
    }

    /** SDL scancode for a GLFW key code, or {@link #SDL_UNKNOWN} when there is none. */
    public static int keyboardScancode(int glfw) {
        if (glfw >= 65 && glfw <= 90) {
            return glfw - 65 + 4; // A..Z
        }
        if (glfw == 48) {
            return 39; // 0
        }
        if (glfw >= 49 && glfw <= 57) {
            return glfw - 49 + 30; // 1..9
        }
        if (glfw >= 290 && glfw <= 301) {
            return glfw - 290 + 58; // F1..F12
        }
        if (glfw >= 302 && glfw <= 313) {
            return glfw - 302 + 104; // F13..F24
        }
        if (glfw == 320) {
            return 98; // keypad 0
        }
        if (glfw >= 321 && glfw <= 329) {
            return glfw - 321 + 89; // keypad 1..9
        }
        return switch (glfw) {
            case 32 -> 44;   // space
            case 39 -> 52;   // apostrophe
            case 44 -> 54;   // comma
            case 45 -> 45;   // minus
            case 46 -> 55;   // period
            case 47 -> 56;   // slash
            case 59 -> 51;   // semicolon
            case 61 -> 46;   // equal
            case 91 -> 47;   // left bracket
            case 92 -> 49;   // backslash
            case 93 -> 48;   // right bracket
            case 96 -> 53;   // grave accent
            case 161 -> 100; // world 1
            case 162 -> 50;  // world 2
            case 256 -> 41;  // escape
            case 257 -> 40;  // enter
            case 258 -> 43;  // tab
            case 259 -> 42;  // backspace
            case 260 -> 73;  // insert
            case 261 -> 76;  // delete
            case 262 -> 79;  // right
            case 263 -> 80;  // left
            case 264 -> 81;  // down
            case 265 -> 82;  // up
            case 266 -> 75;  // page up
            case 267 -> 78;  // page down
            case 268 -> 74;  // home
            case 269 -> 77;  // end
            case 280 -> 57;  // caps lock
            case 281 -> 71;  // scroll lock
            case 282 -> 83;  // num lock
            case 283 -> 70;  // print screen
            case 284 -> 72;  // pause
            case 330 -> 99;  // keypad decimal (the numpad '.')
            case 331 -> 84;  // keypad divide
            case 332 -> 85;  // keypad multiply
            case 333 -> 86;  // keypad subtract
            case 334 -> 87;  // keypad add
            case 335 -> 88;  // keypad enter
            case 336 -> 103; // keypad equal
            case 340 -> 225; // left shift
            case 341 -> 224; // left control
            case 342 -> 226; // left alt
            case 343 -> 227; // left super
            case 344 -> 229; // right shift
            case 345 -> 228; // right control
            case 346 -> 230; // right alt
            case 347 -> 231; // right super
            case 348 -> 118; // menu
            default -> SDL_UNKNOWN;
        };
    }

    /** SDL mouse button for a GLFW mouse button (GLFW 0 left, 1 right, 2 middle; SDL 1 left, 2 middle, 3 right). */
    public static int mouseButton(int glfwButton) {
        return switch (glfwButton) {
            case 0 -> 1;
            case 1 -> 3;
            case 2 -> 2;
            default -> glfwButton < 0 ? SDL_UNKNOWN : glfwButton + 1;
        };
    }

    /**
     * Migrates a saved 1.21.1 key name to its 26.3 name. Names that did not change are returned as they are (the same
     * instance). Single-digit numbers are the digit keys' own names ({@code key.keyboard.1}), not numeric codes.
     */
    public static String migrateName(String name) {
        if (name == null || !name.startsWith(KEYBOARD)) {
            return name;
        }
        String rest = name.substring(KEYBOARD.length());
        if (rest.equals("keypad.decimal")) {
            return KEYBOARD + "keypad.period";
        }
        if (rest.equals("f25")) {
            return UNKNOWN_NAME;
        }
        if (rest.length() >= 2 && isDigits(rest)) {
            int code;
            try {
                code = Integer.parseInt(rest);
            } catch (NumberFormatException e) {
                return UNKNOWN_NAME;
            }
            int scan = keyboardScancode(code);
            return scan == SDL_UNKNOWN ? UNKNOWN_NAME : KEYBOARD + scan;
        }
        if (rest.equals("-1")) {
            return UNKNOWN_NAME;
        }
        return name;
    }

    /** Migrates a saved {@code <key name>[:<modifier>]} value (profile bundles). */
    public static String migrateSaveString(String value) {
        if (value == null) {
            return null;
        }
        int colon = value.indexOf(':');
        if (colon < 0) {
            return migrateName(value);
        }
        return migrateName(value.substring(0, colon)) + value.substring(colon);
    }

    private static boolean isDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
