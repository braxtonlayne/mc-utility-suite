package com.utilitysuite.core.api.visual;

/** Why an assist holds a share of the movement control (the HUD shows it as a short constant label). */
public enum AuthorityReason {
    NONE(""), JUMP("JUMP"), CORNER("CORNER"), LANDING("LANDING"), EDGE_HOLD("EDGE HOLD"), CHAIN("CHAIN"),
    REFLEX("REFLEX");

    private final String label;

    AuthorityReason(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
