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
    /** The view this far off the line to the target times the neo from a run along that line (degrees). */
    private static final float NEO_AIM_EDGE_DEG = 3f;
    /** Leading stages of the blocked order that take turns (corner cut keys, corner cut view, neo view). */
    private static final int BLOCKED_TURN_STAGES = 3;
    /** First stage of those (the keys-only corner cut, a small grid, still goes first on its own). */
    private static final int BLOCKED_TURN_FROM = 1;
    /** The last edge probe ended at a wall across the run (not at the end of the support). */
    private boolean lastEdgeWall;
    /** A straight run along the line to the target runs into a wall: a pillar stands in the line (J36). */
    private boolean pillarInLine;

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

    private int cursorStage;
    private int cursorIndex;

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

    /**
     * Order in which a pass walks the families when the straight line to the target is blocked by a wall: the corner
     * cut first (a small grid, and the shape a blocked line most often needs), then the older families.
     */
    private static final int[] BLOCKED_TEMPLATE = {TechniqueTemplates.CUT, TechniqueTemplates.CUT,
            TechniqueTemplates.NEO, TechniqueTemplates.CORNER, TechniqueTemplates.HEADHITTER, TechniqueTemplates.NEO,
            TechniqueTemplates.CORNER, TechniqueTemplates.HEADHITTER, TechniqueTemplates.FORTY_FIVE};
    /**
     * Whether a stage walks the family's yaw variants (skipped when the view may not be turned). The neo with the view
     * comes right after the corner cut: a pillar in the line (course J36) needs it, and its rolls end at the pillar
     * within a few ticks when the line is clipped from the side instead, so it costs the corner lanes little.
     */
    private static final boolean[] BLOCKED_YAW = {false, true, true, false, false, false, true, true, true};
    /** The order otherwise: the older families as before (keys only, then yaw), the corner cut last. */
    private static final int[] OPEN_TEMPLATE = {TechniqueTemplates.CORNER, TechniqueTemplates.HEADHITTER,
            TechniqueTemplates.NEO, TechniqueTemplates.CORNER, TechniqueTemplates.HEADHITTER,
            TechniqueTemplates.NEO, TechniqueTemplates.FORTY_FIVE, TechniqueTemplates.CUT, TechniqueTemplates.CUT};
    private static final boolean[] OPEN_YAW = {false, false, false, true, true, true, true, false, true};
    private int[] stageTemplate = OPEN_TEMPLATE;
    private boolean[] stageYaw = OPEN_YAW;

    @Override
    public void setLineBlocked(boolean blocked) {
        int[] order = blocked ? BLOCKED_TEMPLATE : OPEN_TEMPLATE;
        if (order != stageTemplate) {
            stageTemplate = order;
            stageYaw = blocked ? BLOCKED_YAW : OPEN_YAW;
            reset();
        }
    }
    @Override
    public void setNextLinkBias(NextLinkBias bias) {
        roller.nextLink = bias;
    }

    /** The most a script may turn the view from where it starts (the level's co-steer total). */
    private float maxTurn = TechniqueTemplates.DEFAULT_MAX_TURN;

    @Override
    public void setAuthority(float armed, float flight) {
        if (armed != roller.armedAuthority || flight != roller.flightAuthority) {
            roller.armedAuthority = armed;
            roller.flightAuthority = flight;
            reset();
        }
    }

    @Override
    public void setKeyLimits(int maxChanges, int minHoldTicks, int reactionTicks) {
        int hold = Math.max(1, minHoldTicks);
        int reaction = Math.max(0, reactionTicks);
        if (maxChanges != roller.keyMaxChanges || hold != roller.keyMinHold || reaction != roller.keyReaction) {
            roller.keyMaxChanges = maxChanges;
            roller.keyMinHold = hold;
            roller.keyReaction = reaction;
            reset(); // the kept script was rolled under other limits
        }
    }

    @Override
    public void setMaxTurn(float degrees) {
        float v = degrees > 0f ? degrees : 0f;
        if (v != maxTurn) {
            maxTurn = v;
            reset();
        }
    }

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
                best.earlyEnd = Math.max(0, best.earlyEnd - shift);
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
            // the neo with the view is timed from a run along the line to the target: a run-up looking well off the line
            // walks off a 1-wide strip's side long before the pillar (J36 from a view 18 degrees off: edge 8 instead of 12)
            int aimEdge = edge;
            pillarInLine = lastEdgeWall;
            if (stageTemplate == BLOCKED_TEMPLATE && yawAllowed && Math.abs(aim - yaw) > NEO_AIM_EDGE_DEG) {
                aimEdge = edgeTick(now, aim, sprinting, userS, w);
                pillarInLine = lastEdgeWall;
                ticks += lastEdgeProbeTicks;
                lastEdgeTick = edge;
                if (aimEdge < 0) {
                    aimEdge = edge;
                }
            }
            int decodes = 0;
            int maxDecodes = totalSize(yawAllowed);
            while (ticks + ROLL_COST <= MAX_SIM_TICKS_PER_CALL && decodes < maxDecodes) {
                decodes++;
                int t = stageTemplate[cursorStage];
                int i = cursorIndex;
                boolean yawPhase = stageYaw[cursorStage] && yawAllowed;
                advanceCursor(yawAllowed);
                int e = t == TechniqueTemplates.NEO && yawPhase ? aimEdge : edge;
                if (!TechniqueTemplates.decode(t, i, yawPhase, e, yaw, aim, maxTurn, cand)) {
                    continue;
                }
                boolean ok = roller.roll(now, yaw, sprinting, userS, cand, 0, target, yawAllowed,
                        maxYawDegPerTick, safeOnly, health, w, scratch);
                ticks += roller.ticks;
                if (!ok && !yawPhase && roller.keyReaction > 0 && ticks + ROLL_COST <= MAX_SIM_TICKS_PER_CALL
                        && pressOnTheJumpTick(cand, userS)) {
                    ok = roller.roll(now, yaw, sprinting, userS, cand, 0, target, yawAllowed, maxYawDegPerTick,
                            safeOnly, health, w, scratch);
                    ticks += roller.ticks;
                }
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

    /**
     * Under a reaction delay (LEGIT) a keys-only candidate that did not land with its strafe key down from the first
     * air tick is tried once more with that key down one tick earlier, on the tick of the jump key itself: the same
     * planned press ({@link KeyChangeLimiter#withTheJump}), on the other side of the jump key. On the ground the key
     * bites harder (more sideways, a little less reach), which is what a short corner needs and a long one cannot
     * afford. Held through the takeoff the commitment carries it ({@link JumpCommit#noteGroundKeys}) as one of the
     * jump's key changes. A candidate with its own run-up strafe is left as it is.
     *
     * @return true when the candidate was changed
     */
    private static boolean pressOnTheJumpTick(TechniqueTemplates.Candidate c, int userS) {
        if (c.groundStrafeTicks != 0 || c.segments == 0 || c.segStrafe[0] == 0 || c.segStrafe[0] == userS) {
            return false;
        }
        c.groundStrafe = c.segStrafe[0];
        c.groundStrafeTicks = 1;
        return true;
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
        lastEdgeWall = false;
        int edge = -1;
        for (int i = 0; i < MAX_GROUND; i++) {
            probeNext.copyFrom(probe).tick(1, userS, yaw, sprinting, false, false, w);
            lastEdgeProbeTicks++;
            if (!probeNext.supported(w) || probeNext.horizontalCollision) { // an edge, or a wall across the run-up
                edge = i;
                lastEdgeWall = probeNext.horizontalCollision;
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
    private int totalSize(boolean yawAllowed) {
        int n = 0;
        for (int s = 0; s < stageTemplate.length; s++) {
            if (yawAllowed || !stageYaw[s]) {
                n += TechniqueTemplates.size(stageTemplate[s], stageYaw[s]);
            }
        }
        return n;
    }

    /**
     * Next grid cell. A pass walks the stages in order: the corner cut (keys only, then with yaw), then every older
     * family keys-only (cheapest, and what LEGIT gets), then, with yaw allowed, those families again with their yaw
     * variants; then it wraps.
     */
    private void advanceCursor(boolean yawAllowed) {
        int from = BLOCKED_TURN_FROM;
        int group = stageTemplate == BLOCKED_TEMPLATE && pillarInLine ? BLOCKED_TURN_STAGES : 0;
        if (cursorStage >= from && cursorStage < group) {
            // blocked line: the corner cut (keys, view) and the neo with the view take turns, one candidate each.
            // Walking them one after another spent the whole run-up of J36 (2026-11-23) on the corner-cut grids and
            // reached the neo only at the edge. The older families follow one after another as before (letting them
            // take turns too broke a corner-wall lane: an older family's script was found first and followed)
            int largest = 0;
            for (int s = from; s < group; s++) {
                largest = Math.max(largest, TechniqueTemplates.size(stageTemplate[s], stageYaw[s]));
            }
            int guard = 0;
            do {
                if (++cursorStage >= group) {
                    cursorStage = from;
                    if (++cursorIndex >= largest) {
                        cursorStage = group;
                        cursorIndex = -1; // the sequential walk below steps it to 0
                        break;
                    }
                }
            } while ((cursorIndex >= TechniqueTemplates.size(stageTemplate[cursorStage], stageYaw[cursorStage])
                    || (stageYaw[cursorStage] && !yawAllowed)) && guard++ <= (group - from) * (largest + 1));
            if (cursorStage < group) {
                return;
            }
        }
        cursorIndex++;
        int guard = 0;
        while ((cursorIndex >= TechniqueTemplates.size(stageTemplate[cursorStage], stageYaw[cursorStage])
                || (stageYaw[cursorStage] && !yawAllowed)) && guard++ <= stageTemplate.length) {
            cursorIndex = 0;
            cursorStage = (cursorStage + 1) % stageTemplate.length;
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
        cursorStage = 0;
        cursorIndex = 0;
        keyed = false;
    }
}
