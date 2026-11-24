package com.utilitysuite.core.api;

/** Why the arbiter refused a {@link LeaseRequest}, as recorded in {@link IArbiterHistory}. */
public enum LeaseDenial {
    /** Stop-All is active until automation is resumed. */
    AUTOMATION_STOPPED("stopped"),
    /** The user's own input on the channel started the manual-override pause. */
    OVERRIDE_PAUSE("override pause"),
    /** Another module holds the channel at the same or a higher priority. */
    HIGHER_HOLDER("held higher"),
    /** The shared MOVEMENT grant outranks the request. */
    SHARED_GRANT("shared grant");

    private final String label;

    LeaseDenial(String label) {
        this.label = label;
    }

    /** Constant lowercase label for the HUD (no text built per frame). */
    public String label() {
        return label;
    }
}
