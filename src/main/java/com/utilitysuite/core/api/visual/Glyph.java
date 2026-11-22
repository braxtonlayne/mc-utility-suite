package com.utilitysuite.core.api.visual;

/** The 7x7 status and control glyph set. Status is always colour plus one of these shapes. */
public enum Glyph {
    /** Idle or armed. */
    DOT,
    RUNNING,
    /** Acting right now (holding control). */
    ACTING,
    WAITING,
    WARNING,
    FAILED,
    DONE,
    CHEVRON_LEFT, CHEVRON_RIGHT, CHEVRON_UP, CHEVRON_DOWN,
    SEARCH, SETTINGS, BACK;

    /** Edge length of a glyph in GUI pixels. */
    public static final int SIZE = 7;
}
