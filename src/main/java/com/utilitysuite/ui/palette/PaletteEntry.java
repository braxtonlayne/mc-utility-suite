package com.utilitysuite.ui.palette;

/**
 * One command palette result.
 *
 * @param kind   short category shown on the row ("Action", "Module", "Setting", "Waypoint", "Goal", "Intent")
 * @param label  row text
 * @param detail preview card text (what Enter will do, current value, lookup result)
 * @param search text the fuzzy scorer matches (label plus keywords)
 * @param run    runs it; returns a status line (null: nothing to report). Client thread, after the palette closed.
 */
public record PaletteEntry(String kind, String label, String detail, String search,
                           java.util.function.Supplier<String> run) {
}
