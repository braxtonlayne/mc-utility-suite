package com.utilitysuite.combat.hud;

import java.util.Locale;

/** Formats the target HUD line. Called only when a shown value changes (allocates). */
public final class TargetLine {

    private TargetLine() {
    }

    /**
     * @param health   health plus absorption, in health points (2 per heart)
     * @param armorPct average armour durability left, or -1 for no armour
     * @param held     held item name, or null
     * @param pops     totem pops seen this session
     */
    public static String format(String name, double health, int armorPct, String held, int pops) {
        StringBuilder sb = new StringBuilder(48);
        sb.append(name).append("  ").append(String.format(Locale.ROOT, "%.1f", health)).append(" hp");
        if (armorPct >= 0) {
            sb.append("  armor ").append(armorPct).append('%');
        }
        if (held != null) {
            sb.append("  [").append(held).append(']');
        }
        if (pops > 0) {
            sb.append("  pops ").append(pops);
        }
        return sb.toString();
    }
}
