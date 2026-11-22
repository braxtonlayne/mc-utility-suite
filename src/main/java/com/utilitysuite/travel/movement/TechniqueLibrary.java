package com.utilitysuite.travel.movement;

/**
 * Technique planner for jumps that constant air keys cannot make (design 2026-11-22, section 8): corner, headhitter,
 * neo and 45-degree, as parametrised templates ({@link TechniqueTemplates}) rolled in the exact-shape replica
 * ({@link TechniqueRoller}).
 * <p>
 * Search: each {@link #plan} call first re-verifies the best script kept from earlier calls (re-based onto the current
 * body: the ticks the body has played since are dropped), then walks the template grid from a cursor until the
 * per-call budget of {@link TechniqueSource#MAX_SIM_TICKS_PER_CALL} replica ticks would be exceeded, keeping the best
 * valid script. The cursor wraps, so the grid is searched again from later bodies along the approach. A changed
 * target, changed permissions or {@link #reset()} clear the kept script and the cursor.
 * <p>
 * Ranking: margin of the stop point on the top first (in {@link TechniqueRoller#MARGIN_BUCKET} buckets up to
 * {@link TechniqueRoller#MARGIN_ENOUGH}), then least departure from the user's strafe key, fewest key changes, least
 * yaw travel, and no yaw at all over some yaw.
 * <p>
 * No rotation is ever written here: a script only records the yaw it was rolled with, the brain hands it to the core
 * co-steer (smoothed, capped). Engine free, client thread only, allocation free after construction.
 */
public final class TechniqueLibrary implements TechniqueSource {

    /** Ground ticks probed for the plain edge tick. */
    static final int MAX_GROUND = 20;
    /** Budget reserved for one roll: a roll never runs past the end of a script. */
    private static final int ROLL_COST = FlightScript.MAX_TICKS;

    private final TechniqueRoller roller = new TechniqueRoller();
    private final TechniqueTemplates.Candidate cand = new TechniqueTemplates.Candidate();
    private final TechniqueTemplates.Candidate best = new TechniqueTemplates.Candidate();
    private final FlightScript scratch = new FlightScript();
    private final FlightScript bestScript = new FlightScript();
    private final MovementPhysics probe = new MovementPhysics();
    private final MovementPhysics probeNext = new MovementPhysics();

    private boolean hasBest;
    private double bestScore;
    /** Body the kept script was rolled from (for a re-base when nothing was played). */
    private double bestFromX, bestFromY, bestFromZ;

    private int cursorTemplate;
    private int cursorIndex;
    private boolean cursorYawPhase;

    // identity of the target and permissions the kept search belongs to
    private boolean keyed;
    private double keyTop;
    private double keyMinX, keyMinZ, keyMaxX, keyMaxZ;
    private int keyCount;
    private boolean keyYaw;
    private float keyRate;
    private boolean keySafe;

    private int lastSimTicks;
    private int lastEdgeTick = -1;

    @Override
    public boolean plan(MovementPhysics now, float yaw, boolean sprinting, int userForward, int userStrafe,
                        SupportRegion target, boolean yawAllowed, float maxYawDegPerTick, boolean safeOnly,
                        float health, MovementPhysics.Voxels w) {
        lastSimTicks = 0;
        if (userForward <= 0 || target == null || target.count() == 0) {
            return false;
        }
        if (!sameKey(target, yawAllowed, maxYawDegPerTick, safeOnly)) {
            reset();
            setKey(target, yawAllowed, maxYawDegPerTick, safeOnly);
        }
        int ticks = 0;
        boolean fresh = false;
        int userS = userStrafe > 0 ? 1 : (userStrafe < 0 ? -1 : 0);

        // 1. the kept script, re-based onto the current body
        if (hasBest) {
            int shift = playedTicks(now);
            if (shift >= 0 && roller.roll(now, yaw, sprinting, userS, best, shift, target, yawAllowed,
                    maxYawDegPerTick, safeOnly, health, w, scratch)) {
                best.jump -= shift;
                keepBest(now);
                fresh = true;
            } else {
                hasBest = false;
            }
            ticks += roller.ticks;
        }

        // 2. new candidates from the cursor
        int edge = now.onGround ? edgeTick(now, yaw, sprinting, userS, w) : -1;
        ticks += now.onGround ? lastEdgeProbeTicks : 0;
        if (edge >= 0) { // no edge within reach yet: nothing to time a takeoff against
            float aim = aimYaw(now, yaw, target);
            int decodes = 0;
            int maxDecodes = totalSize(yawAllowed);
            while (ticks + ROLL_COST <= MAX_SIM_TICKS_PER_CALL && decodes < maxDecodes) {
                decodes++;
                int t = cursorTemplate;
                int i = cursorIndex;
                boolean yawPhase = cursorYawPhase;
                advanceCursor(yawAllowed);
                if (!TechniqueTemplates.decode(t, i, yawPhase, edge, yaw, aim, cand)) {
                    continue;
                }
                boolean ok = roller.roll(now, yaw, sprinting, userS, cand, 0, target, yawAllowed,
                        maxYawDegPerTick, safeOnly, health, w, scratch);
                ticks += roller.ticks;
                if (ok && (!fresh || roller.score > bestScore)) {
                    best.copyFrom(cand);
                    keepBest(now);
                    fresh = true;
                }
            }
        }
        lastSimTicks = ticks;
        return fresh;
    }

    private void keepBest(MovementPhysics now) {
        bestScript.copyFrom(scratch);
        bestScore = roller.score;
        bestFromX = now.x;
        bestFromY = now.y;
        bestFromZ = now.z;
        hasBest = true;
    }

    /**
     * Ticks of the kept script the body has played since it was rolled: 0 when the body is still where the script
     * started, k + 1 when it is on the predicted position after tick k, -1 when it is on neither (dropped).
     */
    private int playedTicks(MovementPhysics now) {
        double dx = now.x - bestFromX;
        double dy = now.y - bestFromY;
        double dz = now.z - bestFromZ;
        if (dx * dx + dy * dy + dz * dz < 1.0e-12) {
            return 0;
        }
        int bestK = -1;
        double bestD = FlightScript.DRIFT_LIMIT * FlightScript.DRIFT_LIMIT;
        for (int k = 0; k < bestScript.landIndex; k++) { // past the touchdown there is nothing left to plan
            dx = now.x - bestScript.x[k];
            dy = now.y - bestScript.y[k];
            dz = now.z - bestScript.z[k];
            double d = dx * dx + dy * dy + dz * dz;
            if (d <= bestD) {
                bestD = d;
                bestK = k;
            }
        }
        return bestK < 0 ? -1 : bestK + 1;
    }

    private int lastEdgeProbeTicks;

    /** The plain edge tick: the last supported tick of a straight run-up with the user's keys and view. */
    private int edgeTick(MovementPhysics now, float yaw, boolean sprinting, int userS, MovementPhysics.Voxels w) {
        lastEdgeProbeTicks = 0;
        probe.copyFrom(now);
        int edge = -1;
        for (int i = 0; i < MAX_GROUND; i++) {
            probeNext.copyFrom(probe).tick(1, userS, yaw, sprinting, false, false, w);
            lastEdgeProbeTicks++;
            if (!probeNext.supported(w) || probeNext.horizontalCollision) { // an edge, or a wall across the run-up
                edge = i;
                break;
            }
            probe.copyFrom(probeNext);
        }
        lastEdgeTick = edge;
        return edge;
    }

    /** Yaw from the body to the centre of the target's top, unwrapped to within 180 degrees of {@code yaw}. */
    private static float aimYaw(MovementPhysics now, float yaw, SupportRegion target) {
        double cx = (target.topBound(0) + target.topBound(2)) * 0.5;
        double cz = (target.topBound(1) + target.topBound(3)) * 0.5;
        double dx = cx - now.x;
        double dz = cz - now.z;
        if (dx * dx + dz * dz < 1.0e-9) {
            return yaw;
        }
        float raw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float d = raw - yaw;
        d = d % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return yaw + d;
    }

    /** Candidates of one full pass: the keys-only grid, then (with yaw) the yaw grid. */
    private static int totalSize(boolean yawAllowed) {
        int n = 0;
        for (int t = 0; t < TechniqueTemplates.COUNT; t++) {
            n += TechniqueTemplates.size(t, false);
            if (yawAllowed) {
                n += TechniqueTemplates.size(t, true);
            }
        }
        return n;
    }

    /**
     * Next grid cell. A pass walks every family keys-only first (cheapest, and what LEGIT gets), then, with yaw
     * allowed, every family again with its yaw variants; then it wraps.
     */
    private void advanceCursor(boolean yawAllowed) {
        cursorIndex++;
        int guard = 0;
        while (cursorIndex >= TechniqueTemplates.size(cursorTemplate, cursorYawPhase)
                && guard++ <= 2 * TechniqueTemplates.COUNT + 1) {
            cursorIndex = 0;
            cursorTemplate++;
            if (cursorTemplate >= TechniqueTemplates.COUNT) {
                cursorTemplate = 0;
                cursorYawPhase = yawAllowed && !cursorYawPhase;
            }
        }
    }

    private boolean sameKey(SupportRegion t, boolean yawAllowed, float rate, boolean safeOnly) {
        return keyed && keyTop == t.top() && keyCount == t.count() && keyMinX == t.topBound(0)
                && keyMinZ == t.topBound(1) && keyMaxX == t.topBound(2) && keyMaxZ == t.topBound(3)
                && keyYaw == yawAllowed && keyRate == rate && keySafe == safeOnly;
    }

    private void setKey(SupportRegion t, boolean yawAllowed, float rate, boolean safeOnly) {
        keyed = true;
        keyTop = t.top();
        keyCount = t.count();
        keyMinX = t.topBound(0);
        keyMinZ = t.topBound(1);
        keyMaxX = t.topBound(2);
        keyMaxZ = t.topBound(3);
        keyYaw = yawAllowed;
        keyRate = rate;
        keySafe = safeOnly;
    }

    @Override
    public FlightScript script() {
        return bestScript;
    }

    @Override
    public int lastSimTicks() {
        return lastSimTicks;
    }

    /** The plain edge tick of the last call (diagnostics). */
    int lastEdgeTick() {
        return lastEdgeTick;
    }

    @Override
    public void reset() {
        hasBest = false;
        bestScore = -Double.MAX_VALUE;
        bestScript.clear();
        cursorTemplate = 0;
        cursorIndex = 0;
        cursorYawPhase = false;
        keyed = false;
    }
}
