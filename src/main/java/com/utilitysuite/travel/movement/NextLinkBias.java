package com.utilitysuite.travel.movement;

/**
 * Next-link landing bias (roadmap 2.1). Each jump's plan used to maximise the safe margin of its own landing: the
 * air brake stopped the body in the middle of a one-block top, the takeoff shape picked the deepest touchdown. In a
 * chain that forces a stop, or a sharp turn, before the next jump. When the user is going on down a
 * {@link ChainLookahead} path, this ranks landings so that, among those that keep at least {@link #FLOOR} of margin,
 * margin beyond {@link #ENOUGH} no longer counts and a landing that carries its speed toward the next link, and comes
 * to rest nearer it, wins. Speed across the line to the next link counts against a landing (that is the speed a sharp
 * turn has to kill), so on a turn of more than 45 degrees the bias prefers the slower touchdown, as before.
 * <p>
 * Safety first, by construction of {@link #rank}:
 * <ul>
 *   <li>Without a next link, or below the floor, the rank is the margin itself (the old order, unchanged).</li>
 *   <li>Every landing at or above the floor ranks above every landing below it, whatever its distance or speed.</li>
 *   <li>Between the floor and {@link #ENOUGH} margin still counts one for one, against at most
 *       {@code PER_BLOCK * MAX_DIST} for distance and about 0.15 for a sprint touchdown's speed: the bias settles on
 *       landings with about {@link #ENOUGH} to spare, and only gives up margin toward the floor for a large gain in
 *       flow.</li>
 * </ul>
 * Margins are the planners' own (the {@link SupportRegion} margin, which counts the hitbox overhang: 0.3 is the body's
 * centre over the block's edge, 0.8 the middle of a one-block top).
 * The margin is always the no-key stop point's (what the landing hold's latch and sneak guard hold the body to), so a
 * faster touchdown is ranked with the slide it really has. One mutable instance is owned by the brain and updated once
 * a tick; the planners read it. Engine-free, allocation free.
 */
public final class NextLinkBias {

    /** Margin (blocks) a landing must keep for the bias to touch it at all. */
    public static final double FLOOR = 0.15;
    /** Margin beyond this buys nothing while a next link pulls (blocks). */
    public static final double ENOUGH = 0.4;
    /** Rank per block of distance between the stop point and the next link's centre (margin units). */
    public static final double PER_BLOCK = 0.1;
    /**
     * Rank per block/tick of touchdown speed toward the next link, less the speed across that line (a straight sprint
     * touchdown is about 0.25 b/t: 0.125).
     */
    public static final double PER_SPEED = 0.5;
    /** Distances beyond this (blocks) all rank the same (a far link says nothing about where to stand). */
    public static final double MAX_DIST = 4.0;
    /**
     * The chain must turn by at most this much at the landing (degrees between the way in and the way on to the next
     * link) for the bias to apply. A sharper turn wants the stop the margin order gives: carried speed and a stop
     * point toward the far side leave less ground to turn on (a 45-degree link two blocks across was missed with the
     * bias at 8 degrees a tick of mouse in the chain sweep).
     */
    public static final double MAX_TURN_DEG = 30.0;
    /** Offset that lifts every landing at or above the floor over every landing below it. */
    static final double TIER = 2.0;

    private boolean valid;
    private double cx, cz;

    /** The next link is the block at ({@code x}, {@code z}); landings are biased toward its centre. */
    public void set(int x, int z) {
        valid = true;
        cx = x + 0.5;
        cz = z + 0.5;
    }

    /**
     * Sets the next link ({@code x}, {@code z}) after the landing block ({@code viaX}, {@code viaZ}) when the chain
     * turns there by at most {@link #MAX_TURN_DEG} from the way in ({@code inX}, {@code inZ}); clears it otherwise.
     *
     * @return {@link #valid()}
     */
    public boolean offer(int x, int z, double inX, double inZ, int viaX, int viaZ) {
        if (turnDeg(inX, inZ, viaX + 0.5, viaZ + 0.5, x + 0.5, z + 0.5) > MAX_TURN_DEG) {
            valid = false;
        } else {
            set(x, z);
        }
        return valid;
    }

    public void clear() {
        valid = false;
    }

    public boolean valid() {
        return valid;
    }

    public double centreX() {
        return cx;
    }

    public double centreZ() {
        return cz;
    }

    /** Horizontal distance from a point to the next link's centre (0 without one), capped at {@link #MAX_DIST}. */
    public double distance(double px, double pz) {
        if (!valid) {
            return 0.0;
        }
        double dx = px - cx;
        double dz = pz - cz;
        return Math.min(MAX_DIST, Math.sqrt(dx * dx + dz * dz));
    }

    /**
     * Flow of a horizontal velocity at ({@code px}, {@code pz}): its component toward the next link's centre less its
     * component across that line (positive within 45 degrees of the line, negative beyond).
     */
    public double flow(double px, double pz, double vx, double vz) {
        if (!valid) {
            return 0.0;
        }
        double dx = cx - px;
        double dz = cz - pz;
        double d = Math.sqrt(dx * dx + dz * dz);
        if (d < 1.0e-6) {
            return 0.0;
        }
        double along = (vx * dx + vz * dz) / d;
        double across = Math.abs(vx * dz - vz * dx) / d;
        return along - across;
    }

    /**
     * Rank of a landing (higher is better): {@code margin} of its no-key stop point ({@code sx}, {@code sz}), the
     * touchdown at ({@code px}, {@code pz}) with horizontal velocity ({@code vx}, {@code vz}). Without a next link,
     * or below {@link #FLOOR}, the margin itself.
     */
    public double rank(double margin, double sx, double sz, double px, double pz, double vx, double vz) {
        if (!valid || !(margin >= FLOOR)) {
            return margin;
        }
        double flow = Math.max(-0.5, Math.min(0.5, flow(px, pz, vx, vz)));
        return TIER + Math.min(margin, ENOUGH) - PER_BLOCK * distance(sx, sz) + PER_SPEED * flow;
    }

    /**
     * Turn of a chain at a landing (degrees, 0..180): between the way in ({@code inX}, {@code inZ}) (a velocity or a
     * direction) and the way on, from the landing's centre ({@code viaX}, {@code viaZ}) to the next link's centre
     * ({@code toX}, {@code toZ}). 180 when either direction is degenerate.
     */
    public static double turnDeg(double inX, double inZ, double viaX, double viaZ, double toX, double toZ) {
        double ox = toX - viaX;
        double oz = toZ - viaZ;
        double a = Math.sqrt(inX * inX + inZ * inZ);
        double b = Math.sqrt(ox * ox + oz * oz);
        if (a < 1.0e-6 || b < 1.0e-6) {
            return 180.0;
        }
        double c = (inX * ox + inZ * oz) / (a * b);
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, c))));
    }

    /** Static form for a nullable bias. */
    public static double rank(NextLinkBias b, double margin, double sx, double sz, double px, double pz, double vx,
                              double vz) {
        return b == null ? margin : b.rank(margin, sx, sz, px, pz, vx, vz);
    }
}
