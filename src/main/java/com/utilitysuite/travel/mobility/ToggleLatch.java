package com.utilitysuite.travel.mobility;

/**
 * Toggle Sprint / Toggle Sneak latch state, engine free. A toggle press latches the key; pressing the real key or the
 * toggle again releases it; Stop-All releases; while a screen is open that needs the keys free the latch is dropped.
 */
public final class ToggleLatch {

    private boolean latched;
    private boolean realWasDown;

    /** The toggle key was pressed. Returns the new latch state. */
    public boolean pressToggle() {
        latched = !latched;
        return latched;
    }

    /**
     * Once per tick. A fresh press of the real key releases the latch (manual input wins).
     *
     * @param realDown    the user's own key is physically down
     * @param screenBlocks a screen is open that needs the keys released
     * @return whether the automation should hold the key now
     */
    public boolean tick(boolean realDown, boolean screenBlocks) {
        if (realDown && !realWasDown && latched) {
            latched = false;
        }
        realWasDown = realDown;
        if (screenBlocks) {
            latched = false;
        }
        return latched;
    }

    public void release() {
        latched = false;
    }

    public boolean latched() {
        return latched;
    }
}
