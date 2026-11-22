package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.tactics.CombatVoxels;
import com.utilitysuite.combat.tactics.PearlLandingSim;
import com.utilitysuite.combat.tactics.PearlSource;

/**
 * Decides when the Combat Assistant should run to where its target's ender pearl comes down, instead of walking after
 * the target. Engine free (pearls from a {@link PearlSource}, the landing from a {@link Landing} over
 * {@link CombatVoxels}), allocation free after construction, client thread only.
 * <ul>
 *   <li><b>Start</b>: the target has a pearl in flight, the landing simulation succeeds and the landing is farther than
 *       {@code spacingMax + }{@value #START_EXTRA} blocks from us.</li>
 *   <li><b>Retarget</b>: the landing moved by more than {@value #RETARGET_DISTANCE} blocks (a newer pearl, or the sim
 *       sees more of the world now).</li>
 *   <li><b>Stop</b>: once the pearl is gone, when the target is within normal pursuit range of us again
 *       ({@code spacingMax + pursuitExtra}) or {@value #LINGER_TICKS} ticks after the pearl vanished.</li>
 * </ul>
 * While the pearl is still flying the target stands where it threw from (often right next to us), so the range
 * check only applies after the pearl vanished.
 */
public final class PearlIntercept {

    /** The landing must be at least spacing max + this far from us to be worth a run. */
    public static final double START_EXTRA = 2.0;
    /** Ticks the run continues after the pearl vanished (the thrower teleported) before normal pursuit takes over. */
    public static final int LINGER_TICKS = 60;
    /** A landing that moved more than this counts as a new destination. */
    public static final double RETARGET_DISTANCE = 1.0;
    /** The route stops this close to the landing. */
    public static final double STOP_RANGE = 1.5;
    /** Extra blocks kept on top of the landing distance while the run lasts (the disengage radius check). */
    public static final double RADIUS_SLACK = 8.0;

    /** One landing simulation (the shape of {@link PearlLandingSim}; tests script it). */
    public interface Landing {
        boolean simulate(double x, double y, double z, double vx, double vy, double vz, CombatVoxels w, int maxTicks);

        double landX();

        double landY();

        double landZ();
    }

    /** Production adapter over the (final) simulation. Made once. */
    public static Landing of(PearlLandingSim sim) {
        return new Landing() {
            @Override
            public boolean simulate(double x, double y, double z, double vx, double vy, double vz, CombatVoxels w,
                                    int maxTicks) {
                return sim.simulate(x, y, z, vx, vy, vz, w, maxTicks);
            }

            @Override
            public double landX() {
                return sim.landX();
            }

            @Override
            public double landY() {
                return sim.landY();
            }

            @Override
            public double landZ() {
                return sim.landZ();
            }
        };
    }

    private final double[] pearl = new double[6];
    private PearlSource source = PearlSource.NONE;
    private Landing landing;
    private CombatVoxels voxels;

    private boolean active;
    private boolean pearlSeen;
    private long lastSeenTick;
    private double landX, landY, landZ;
    private int starts;
    private int retargets;

    public PearlIntercept(Landing landing) {
        this.landing = landing;
    }

    public PearlIntercept() {
        this(of(new PearlLandingSim()));
    }

    public void useSource(PearlSource source) {
        this.source = source == null ? PearlSource.NONE : source;
    }

    public void useLanding(Landing landing) {
        if (landing != null) {
            this.landing = landing;
        }
    }

    /** Blocks the simulation stops on; null = the feature stays idle. */
    public void useVoxels(CombatVoxels voxels) {
        this.voxels = voxels;
    }

    /**
     * One engaged tick.
     *
     * @param pursuitRange horizontal distance inside which normal pursuit is not needed (spacing max + its enter extra)
     * @return true while the run to the landing should go on
     */
    public boolean update(int targetId, long tick, double px, double py, double pz, double tx, double tz,
                          double spacingMax, double pursuitRange) {
        CombatVoxels w = voxels;
        if (w == null) {
            reset();
            return false;
        }
        double[] p = pearl;
        if (source.pearlOf(targetId, p)) {
            pearlSeen = true;
            lastSeenTick = tick;
            if (landing.simulate(p[0], p[1], p[2], p[3], p[4], p[5], w, PearlLandingSim.MAX_TICKS)) {
                double lx = landing.landX();
                double ly = landing.landY();
                double lz = landing.landZ();
                double dx = lx - px;
                double dz = lz - pz;
                boolean far = dx * dx + dz * dz > sq(spacingMax + START_EXTRA);
                if (!active) {
                    if (far) {
                        active = true;
                        starts++;
                        setLanding(lx, ly, lz);
                    }
                } else if (sq(lx - landX) + sq(ly - landY) + sq(lz - landZ) > sq(RETARGET_DISTANCE)) {
                    retargets++;
                    setLanding(lx, ly, lz);
                }
            }
            return active; // a failed sim mid-run keeps the last landing
        }
        if (!active) {
            pearlSeen = false;
            return false;
        }
        double dx = tx - px;
        double dz = tz - pz;
        if (dx * dx + dz * dz <= pursuitRange * pursuitRange || tick - lastSeenTick > LINGER_TICKS) {
            reset();
            return false;
        }
        return true;
    }

    private void setLanding(double x, double y, double z) {
        landX = x;
        landY = y;
        landZ = z;
    }

    private static double sq(double v) {
        return v * v;
    }

    /** Ends any run (arrived, disengage, target change, world unload). */
    public void reset() {
        active = false;
        pearlSeen = false;
    }

    public boolean active() {
        return active;
    }

    /** True while the target's pearl is still flying. */
    public boolean pearlInFlight() {
        return pearlSeen;
    }

    public double landX() {
        return landX;
    }

    public double landY() {
        return landY;
    }

    public double landZ() {
        return landZ;
    }

    /** Disengage radius needed while the run lasts: the landing distance plus slack (0 when not running). */
    public double radiusNeeded(double px, double py, double pz) {
        if (!active) {
            return 0.0;
        }
        double dx = landX - px;
        double dy = landY - py;
        double dz = landZ - pz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz) + RADIUS_SLACK;
    }

    /** Runs started (diagnostics, tests). */
    public int starts() {
        return starts;
    }

    /** Destination changes during a run (diagnostics, tests). */
    public int retargets() {
        return retargets;
    }
}
