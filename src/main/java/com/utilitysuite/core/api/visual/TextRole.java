package com.utilitysuite.core.api.visual;

/** Which face a text run uses. The Shell maps roles to fonts and line heights. */
public enum TextRole {
    /** Body and titles. */
    UI,
    /** Small caps captions and labels. */
    MICRO,
    /** Numerals: tabular digits that do not jitter. */
    MONO
}
