package com.utilitysuite.core.api.visual;

/** Where the HUD host places an element. Elements in one slot stack by priority and reflow when one leaves. */
public enum HudSlot {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    /** Centred on the crosshair: the element draws around (x, y) = the screen centre. */
    CROSSHAIR,
    RIGHT_MID, BOTTOM_RIGHT,
    /** The element places itself: (x, y) is the screen origin. */
    FREE
}
