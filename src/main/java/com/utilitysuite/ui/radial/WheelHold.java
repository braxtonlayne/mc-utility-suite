package com.utilitysuite.ui.radial;

/**
 * Engine-free hold gate for the action wheel key. A wheel closed without running anything by Stop-All, leaving the
 * world or switching the module off stays closed until the key has been released: otherwise the still-held key would
 * reopen it on the next tick and take the camera again right after an emergency stop. Primitive state, allocation free.
 */
public final class WheelHold {

    private boolean suppressed;

    /**
     * @param keyDown the wheel key is down this tick
     * @return true when the key counts as held for the wheel (down, and not suppressed since the last cancel)
     */
    public boolean held(boolean keyDown) {
        if (!keyDown) {
            suppressed = false;
        }
        return keyDown && !suppressed;
    }

    /** Keeps the wheel closed until the key has been released once. */
    public void suppressUntilRelease() {
        suppressed = true;
    }

    public boolean suppressed() {
        return suppressed;
    }
}
