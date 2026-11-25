package com.utilitysuite.travel.autopilot;

/**
 * Engine-free leg sequencing for a Nether-hub route ({@link PortalHubPlanner.Plan}). It survives the dimension change
 * (the client level and player are replaced, leases end): the driver feeds it the current dimension each tick, leg
 * arrivals and whether the player stands in a portal, and starts a fresh leased navigator leg whenever
 * {@link #currentStop()} changes.
 * <pre>
 * TO_ENTRY --arrived--> IN_ENTRY_PORTAL --dimension = nether--> NETHER_LEG --arrived--> IN_EXIT_PORTAL (portal known)
 *                                                                                  \--> AWAIT_PORTAL  (no portal)
 * IN_EXIT_PORTAL / AWAIT_PORTAL / NETHER_LEG --dimension = overworld--> FINAL_LEG --arrived--> DONE
 * </pre>
 * Entering the nether early (the player stepped in himself) skips ahead the same way. Any other dimension change, or
 * waiting in a portal too long, fails the route.
 */
public final class PortalHubRoute {

    public enum Phase { TO_ENTRY, IN_ENTRY_PORTAL, NETHER_LEG, IN_EXIT_PORTAL, AWAIT_PORTAL, FINAL_LEG, DONE, FAILED }

    /** Ticks to wait in a portal for the dimension change (survival portals take 80 ticks, plus loading). */
    static final int PORTAL_TIMEOUT_TICKS = 400;
    /** Ticks the player may stand next to (not in) the portal after the leg arrived before the route fails. */
    static final int NOT_IN_PORTAL_TICKS = 60;

    private final PortalHubPlanner.Plan plan;
    private final RouteParser.Stop target;
    private Phase phase = Phase.TO_ENTRY;
    private String lastDimension;
    private int waitTicks;
    private int outsideTicks;
    private String failure;

    public PortalHubRoute(PortalHubPlanner.Plan plan, RouteParser.Stop target) {
        this.plan = plan;
        this.target = target;
        this.lastDimension = PortalHubPlanner.OVERWORLD;
    }

    public Phase phase() {
        return phase;
    }

    public PortalHubPlanner.Plan plan() {
        return plan;
    }

    public RouteParser.Stop target() {
        return target;
    }

    /** Lang key suffix for why the route failed, or null. */
    public String failure() {
        return failure;
    }

    public boolean isWalking() {
        return phase == Phase.TO_ENTRY || phase == Phase.NETHER_LEG || phase == Phase.FINAL_LEG;
    }

    public boolean isFinished() {
        return phase == Phase.DONE || phase == Phase.FAILED;
    }

    /** The dimension the current phase runs in. */
    public String expectedDimension() {
        return switch (phase) {
            case NETHER_LEG, IN_EXIT_PORTAL, AWAIT_PORTAL -> PortalHubPlanner.NETHER;
            default -> PortalHubPlanner.OVERWORLD;
        };
    }

    /**
     * The stop the navigator should walk to in a walking phase (portal legs use range 0 onto the portal block), or
     * null while waiting. A nether stop without a recorded portal has {@code hasY} false: the driver keeps its height.
     */
    public RouteParser.Stop currentStop() {
        PortalHubPlanner.Portal p;
        switch (phase) {
            case TO_ENTRY -> p = plan.entry();
            case NETHER_LEG -> {
                p = plan.exitNether();
                return new RouteParser.Stop(p.x(), p.y(), p.z(), plan.exitPortalKnown(),
                        plan.exitPortalKnown() ? "Nether portal" : "Nether point under target");
            }
            case FINAL_LEG -> {
                return target;
            }
            default -> {
                return null;
            }
        }
        return new RouteParser.Stop(p.x(), p.y(), p.z(), true, "Overworld portal");
    }

    /** True when the current walking leg ends inside a portal (range 0). */
    public boolean legEndsInPortal() {
        return phase == Phase.TO_ENTRY || (phase == Phase.NETHER_LEG && plan.exitPortalKnown());
    }

    /**
     * Reports the current dimension; returns true when the phase changed because of a dimension change (the driver
     * then starts the next leg).
     */
    public boolean onDimension(String dimension) {
        if (dimension == null || dimension.equals(lastDimension) || isFinished()) {
            return false;
        }
        lastDimension = dimension;
        if (PortalHubPlanner.NETHER.equals(dimension)
                && (phase == Phase.TO_ENTRY || phase == Phase.IN_ENTRY_PORTAL)) {
            enter(Phase.NETHER_LEG);
            return true;
        }
        if (PortalHubPlanner.OVERWORLD.equals(dimension) && (phase == Phase.NETHER_LEG
                || phase == Phase.IN_EXIT_PORTAL || phase == Phase.AWAIT_PORTAL)) {
            enter(Phase.FINAL_LEG);
            return true;
        }
        fail("wrong_dimension");
        return true;
    }

    /** The navigator reached the current leg's stop. */
    public void onLegArrived() {
        switch (phase) {
            case TO_ENTRY -> enter(Phase.IN_ENTRY_PORTAL);
            case NETHER_LEG -> enter(plan.exitPortalKnown() ? Phase.IN_EXIT_PORTAL : Phase.AWAIT_PORTAL);
            case FINAL_LEG -> enter(Phase.DONE);
            default -> {
            }
        }
    }

    /**
     * One tick of a portal wait. AWAIT_PORTAL waits for the player indefinitely (cancel to stop); the portal phases
     * fail when the player is not in the portal or the dimension does not change in time.
     */
    public void tickWait(boolean inPortal) {
        if (phase != Phase.IN_ENTRY_PORTAL && phase != Phase.IN_EXIT_PORTAL) {
            return;
        }
        if (++waitTicks > PORTAL_TIMEOUT_TICKS) {
            fail("portal_timeout");
            return;
        }
        if (inPortal) {
            outsideTicks = 0;
        } else if (++outsideTicks > NOT_IN_PORTAL_TICKS) {
            fail("not_in_portal");
        }
    }

    public void cancel() {
        if (!isFinished()) {
            fail("cancelled");
        }
    }

    private void enter(Phase next) {
        phase = next;
        waitTicks = 0;
        outsideTicks = 0;
    }

    private void fail(String why) {
        failure = why;
        enter(Phase.FAILED);
    }
}
