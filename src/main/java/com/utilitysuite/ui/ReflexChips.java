package com.utilitysuite.ui;

import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IAutopilot.Reflex;

/**
 * Engine-free state for the autopilot bar's reflex chips and the HUD's "Autopilot: ..." line: constant labels per
 * reflex (indexed by ordinal, so nothing is concatenated per frame), which chips fit in a given width, and which are
 * lit. The four original reflexes always come first; the newer ones appear only when the width allows.
 */
final class ReflexChips {

    /** Chips shown first, in this order, whenever they fit. */
    static final int CORE_COUNT = 4;

    /** Display order: the four original reflexes, then the newer ones. */
    static final Reflex[] ORDER = {Reflex.FALL_CLUTCH, Reflex.PARKOUR, Reflex.PARKOUR_BLOCKS, Reflex.TOTEM,
            Reflex.SHIELD, Reflex.DODGE, Reflex.THREAT_ESCAPE, Reflex.ELYTRA_SWAP, Reflex.OFFHAND};

    /** Short chip label per reflex ordinal. */
    static final String[] CHIP_LABEL = new String[Reflex.values().length];
    /** Full HUD status line per reflex ordinal ("Autopilot: ..."), built once. */
    static final String[] STATUS_LINE = new String[Reflex.values().length];

    static {
        label(Reflex.FALL_CLUTCH, "Clutch", "fall clutch");
        label(Reflex.PARKOUR, "Parkour", "parkour");
        label(Reflex.PARKOUR_BLOCKS, "Blocks", "parkour block");
        label(Reflex.TOTEM, "Totem", "totem");
        label(Reflex.SHIELD, "Shield", "shield");
        label(Reflex.DODGE, "Dodge", "dodge");
        label(Reflex.THREAT_ESCAPE, "Escape", "threat escape");
        label(Reflex.ELYTRA_SWAP, "Elytra", "elytra swap");
        label(Reflex.OFFHAND, "Offhand", "offhand");
    }

    private ReflexChips() {
    }

    private static void label(Reflex r, String chip, String status) {
        CHIP_LABEL[r.ordinal()] = chip;
        STATUS_LINE[r.ordinal()] = "Autopilot: " + status;
    }

    /** The constant status line for a reflex, or null for none. */
    static String statusLine(Reflex r) {
        return r == null ? null : STATUS_LINE[r.ordinal()];
    }

    /**
     * How many chips of {@link #ORDER} fit in {@code room} px, given each chip's width (indexed like ORDER) and the gap
     * between chips. The core chips come first; extra chips are added only while the whole set still fits.
     */
    static int visibleCount(int room, int[] widthsInOrder, int gap) {
        int used = 0;
        int n = 0;
        for (int i = 0; i < widthsInOrder.length && i < ORDER.length; i++) {
            int need = widthsInOrder[i] + (n > 0 ? gap : 0);
            if (used + need > room) {
                break;
            }
            used += need;
            n++;
        }
        return n;
    }

    /** Fills {@code lit} (indexed like ORDER): lit while the autopilot is on and allows that reflex. */
    static void fillLit(IAutopilot ap, boolean[] lit) {
        boolean on = ap != null && ap.isOn();
        for (int i = 0; i < ORDER.length && i < lit.length; i++) {
            lit[i] = on && ap.allows(ORDER[i]);
        }
    }
}
