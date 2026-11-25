package com.utilitysuite.travel.autopilot;

import java.util.List;

/**
 * Engine-free route choice for Nether-hub travel: is a trip through recorded overworld/nether portals cheaper than
 * walking straight to a far overworld destination, and through which portals?
 * <p>
 * Cost model (in overworld-walk blocks, horizontal): {@code direct = |player - target|};
 * {@code hub = |player - entry| + transit + factor * |entryNether - exitNether| + transit + |exitOverworld - target|}.
 * Entering an overworld portal lands at the recorded nether portal nearest {@code entry / 8} (within
 * {@link #ARRIVAL_SEARCH_NETHER} blocks, the game's own search radius), else at {@code entry / 8}. The exit is the
 * recorded nether portal minimising the remaining cost, whose overworld side is the recorded overworld portal nearest
 * {@code exit * 8} (within {@link #ARRIVAL_SEARCH_OVERWORLD}), else {@code exit * 8}. When no recorded nether portal
 * beats it, the exit falls back to {@code target / 8} with no portal there ({@link Plan#exitPortalKnown()} false): the
 * route then waits in the Nether for the player to take a portal (nothing is lit or built).
 * <p>
 * Planning runs once when a route starts; it is not a hot path.
 */
public final class PortalHubPlanner {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";
    public static final String GROUP_PORTALS = "Portals";

    /** Overworld → nether: the game looks for an existing nether portal this far (nether blocks) from x/8. */
    static final int ARRIVAL_SEARCH_NETHER = 16;
    /** Nether → overworld: the game looks this far (overworld blocks) from x*8. */
    static final int ARRIVAL_SEARCH_OVERWORLD = 128;

    /** A recorded portal (from the Portals waypoint group). */
    public record Portal(String dimension, int x, int y, int z) {
    }

    /**
     * @param minDistance       only plan a hub when the destination is at least this far (overworld blocks)
     * @param netherCostFactor  cost of one nether block relative to one overworld block (hazards, slower ground)
     * @param portalTransit     fixed cost of each portal trip (waiting, loading), in overworld blocks
     * @param unknownExitCost   extra cost when no recorded nether portal serves the exit (the player must make one)
     */
    public record Config(int minDistance, double netherCostFactor, int portalTransit, int unknownExitCost) {
        public static Config defaults() {
            return new Config(1000, 1.5, 64, 256);
        }
    }

    /**
     * A chosen hub route. Leg 1: walk the overworld to {@code entry}. Leg 2: walk the nether from the arrival point to
     * {@code exitNether}. Leg 3: walk the overworld from the exit portal to the target.
     */
    public record Plan(Portal entry, Portal arrivalNether, boolean arrivalKnown, Portal exitNether,
                       boolean exitPortalKnown, Portal exitOverworld, double directCost, double hubCost) {
    }

    private PortalHubPlanner() {
    }

    /** Horizontal distance. */
    static double dist(int ax, int az, int bx, int bz) {
        double dx = ax - bx;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Plans a hub route from an overworld position to an overworld target, or returns null when walking directly is
     * as cheap, the target is nearer than {@link Config#minDistance}, or no overworld portal is recorded.
     */
    public static Plan plan(int px, int pz, int tx, int ty, int tz, List<Portal> portals, Config cfg) {
        double direct = dist(px, pz, tx, tz);
        if (direct < cfg.minDistance() || portals == null || portals.isEmpty()) {
            return null;
        }
        int tnx = Math.floorDiv(tx, 8);
        int tnz = Math.floorDiv(tz, 8);
        Plan best = null;
        for (Portal entry : portals) {
            if (!OVERWORLD.equals(entry.dimension())) {
                continue;
            }
            double toEntry = dist(px, pz, entry.x(), entry.z());
            if (toEntry + 2.0 * cfg.portalTransit() >= direct) {
                continue; // cannot beat walking even with a free nether leg
            }
            Portal arrival = nearest(portals, NETHER, Math.floorDiv(entry.x(), 8), Math.floorDiv(entry.z(), 8),
                    ARRIVAL_SEARCH_NETHER);
            boolean arrivalKnown = arrival != null;
            if (arrival == null) {
                arrival = new Portal(NETHER, Math.floorDiv(entry.x(), 8), entry.y(), Math.floorDiv(entry.z(), 8));
            }
            // Fallback exit: target/8 with no portal (the player has to provide one).
            Portal exitN = new Portal(NETHER, tnx, arrival.y(), tnz);
            Portal exitO = new Portal(OVERWORLD, tx, ty, tz);
            boolean exitKnown = false;
            double rest = cfg.netherCostFactor() * dist(arrival.x(), arrival.z(), tnx, tnz) + cfg.unknownExitCost();
            for (Portal n : portals) {
                if (!NETHER.equals(n.dimension())) {
                    continue;
                }
                Portal out = nearest(portals, OVERWORLD, n.x() * 8, n.z() * 8, ARRIVAL_SEARCH_OVERWORLD);
                Portal outPos = out != null ? out : new Portal(OVERWORLD, n.x() * 8, n.y(), n.z() * 8);
                double c = cfg.netherCostFactor() * dist(arrival.x(), arrival.z(), n.x(), n.z())
                        + dist(outPos.x(), outPos.z(), tx, tz);
                if (c < rest) {
                    rest = c;
                    exitN = n;
                    exitO = outPos;
                    exitKnown = true;
                }
            }
            double hub = toEntry + 2.0 * cfg.portalTransit() + rest;
            if (hub < direct && (best == null || hub < best.hubCost())) {
                best = new Plan(entry, arrival, arrivalKnown, exitN, exitKnown, exitO, direct, hub);
            }
        }
        return best;
    }

    /** Nearest recorded portal in {@code dimension} within {@code radius} (horizontal) of (x, z), or null. */
    static Portal nearest(List<Portal> portals, String dimension, int x, int z, int radius) {
        Portal best = null;
        double bestD = radius;
        for (Portal p : portals) {
            if (!dimension.equals(p.dimension())) {
                continue;
            }
            double d = dist(p.x(), p.z(), x, z);
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
