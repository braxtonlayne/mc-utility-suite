package com.utilitysuite.core.api.visual;

/**
 * Colour tokens. Modules name a token, never an ARGB value; the Shell resolves it against the active theme. The
 * accent family means "the system"; the status hues carry meaning and never move with the accent.
 */
public enum HoloToken {
    VOID, GLASS, GLASS_HUD, GLASS_RAISED, FIELD,
    LINE_FAINT, LINE, LINE_STRONG,
    ACCENT, ACCENT_DEEP, ACCENT_GLOW, ON_ACCENT,
    TEXT, TEXT_SECONDARY, TEXT_MUTED, TEXT_FAINT,
    /** A reflex or job is acting right now. */
    SYNC,
    WARN, DANGER, ALLY,
    /** The share of control that belongs to the user. */
    MANUAL,
    CAT_JOBS, CAT_WORLD, CAT_TRAVEL, CAT_COMBAT, CAT_TOOLS;

    private static final HoloToken[] VALUES = values();

    /** Cached values() so hot paths do not clone the array. */
    public static HoloToken[] all() {
        return VALUES;
    }
}
