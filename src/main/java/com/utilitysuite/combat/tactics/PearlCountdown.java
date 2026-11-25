package com.utilitysuite.combat.tactics;

/**
 * Landing countdown of our own thrown ender pearl for the HUD status line, shown only while one is in flight. Uses
 * {@link PearlLandingSim} (same thrown-projectile physics). The line is one of a fixed table of constant strings (one
 * per tenth of a second), so the HUD can compare it by reference and nothing is allocated per tick. Engine free.
 */
public final class PearlCountdown {

    /** Tenths of a second the table covers ({@link PearlLandingSim#MAX_TICKS} = 10 s). */
    private static final int TENTHS = PearlLandingSim.MAX_TICKS / 2;
    private static final String[] LINES = new String[TENTHS + 1];

    static {
        for (int i = 0; i <= TENTHS; i++) {
            LINES[i] = "PEARL - LANDS IN " + (i / 10) + "." + (i % 10) + "s";
        }
    }

    private final PearlLandingSim sim = new PearlLandingSim();
    private final double[] pearl = new double[6];
    private String line;
    private int ticks = -1;

    /**
     * @param on       the setting
     * @param source   pearls in flight
     * @param ownId    our entity id
     * @return the status line, or null when off, no own pearl is in flight, or its landing is unknown
     */
    public String tick(boolean on, PearlSource source, int ownId, CombatVoxels w) {
        line = null;
        ticks = -1;
        if (!on || !source.pearlOf(ownId, pearl)) {
            return null;
        }
        if (!sim.simulate(pearl[0], pearl[1], pearl[2], pearl[3], pearl[4], pearl[5], w, PearlLandingSim.MAX_TICKS)) {
            return null;
        }
        ticks = sim.flightTicks();
        line = lineFor(ticks);
        return line;
    }

    /** Constant line for a countdown of {@code ticks} (rounded to tenths of a second). */
    public static String lineFor(int ticks) {
        int tenths = Math.max(0, Math.min(TENTHS, (ticks + 1) / 2));
        return LINES[tenths];
    }

    public String line() {
        return line;
    }

    /** Ticks until the pearl lands, -1 when none. */
    public int ticks() {
        return ticks;
    }
}
