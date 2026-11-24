package com.utilitysuite.core.input;

/**
 * Decides from the character-typed event flow whether the user is typing text into the open screen. Engine free.
 * <p>
 * NeoForge posts a "pre" event for every typed character, then lets the screen handle it, and posts the "post" event
 * only when nothing took the character. A pre without a post therefore means a text field (the screen's own, a nested
 * one this mod cannot see, or another mod's overlay search bar that cancelled the pre event) consumed it. The key press
 * and its character arrive in the same input poll, so the guard is already set on the tick that first sees the key held.
 * <p>
 * The guard stays up for {@link #HOLD_TICKS} after the last consumed character: longer than the longest OS key-repeat
 * delay, so a key held down in a text field never leaks through between repeats. Client thread only; allocation free.
 */
public final class TextEntryGuard {

    /** Ticks the guard stays up after the last consumed character (1.5 s; the longest OS repeat delay is 1 s). */
    public static final int HOLD_TICKS = 30;

    private boolean pending;
    private boolean consumed;
    private int hold;

    /** A character is about to be offered to the screen (the cancellable "pre" event, seen even when cancelled). */
    public void onCharOffered() {
        if (pending) {
            consumed = true; // the previous character never reached "post": something took it
        }
        pending = true;
    }

    /** Nothing handled the character just offered (the "post" event). */
    public void onCharUnhandled() {
        pending = false;
    }

    /**
     * Once per client tick.
     *
     * @param screenOpen false clears the guard: without a screen there is no text field
     */
    public void tick(boolean screenOpen) {
        if (!screenOpen) {
            reset();
            return;
        }
        if (pending || consumed) {
            hold = HOLD_TICKS;
        } else if (hold > 0) {
            hold--;
        }
        pending = false;
        consumed = false;
    }

    /** True while a character was consumed since the last tick or within the {@link #HOLD_TICKS} before it. */
    public boolean isTyping() {
        return pending || consumed || hold > 0;
    }

    public void reset() {
        pending = false;
        consumed = false;
        hold = 0;
    }
}
