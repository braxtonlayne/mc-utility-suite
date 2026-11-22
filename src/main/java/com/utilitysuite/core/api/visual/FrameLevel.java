package com.utilitysuite.core.api.visual;

/** Emphasis of a bracket frame. */
public enum FrameLevel {
    /** Faint edge, no brackets: list rows, HUD plates. */
    QUIET,
    /** Faint edge, dim brackets: cards, popovers, notices, target tag. */
    FRAMED,
    /** Strong edge and brackets, outer glow: the one focused surface. */
    HERO
}
