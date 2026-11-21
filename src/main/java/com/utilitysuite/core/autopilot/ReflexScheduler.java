package com.utilitysuite.core.autopilot;

import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.IDangerModel;

/**
 * Decides which reflex acts when several see a danger at once (idea A1). Each {@link #mayAct} call records a bid for
 * the current tick; a bid stays live for {@link #BID_LIFE_TICKS} so reflexes ticked earlier in the frame still see
 * the ones ticked later. A reflex wins when:
 * <ol>
 *   <li>no other reflex is acting (an acting reflex holds the turn until {@link #noteDone}, or until it stays silent
 *       for {@link #ACTING_TIMEOUT_TICKS});</li>
 *   <li>no other live bid ranks higher. Rank: a reflex that can finish before its hazard's
 *       {@link IDangerModel#ticksToImpact} beats one that cannot; then the higher {@code damageAverted / max(1,
 *       ticksNeeded)}; ties go to the lower ordinal.</li>
 * </ol>
 * Callers count their own turn time in {@code ticksNeeded}: the smoother never turns faster than
 * {@code IInputSmoother.MAX_TURN_DEG_PER_SEC} (450 deg/s = 22.5 deg per tick), so a 90 degree turn costs at least 4
 * ticks before the act itself. Engine free; allocation free. Client thread only.
 */
public final class ReflexScheduler {

    public static final int BID_LIFE_TICKS = 2;
    public static final int ACTING_TIMEOUT_TICKS = 200;

    private static final Reflex[] REFLEXES = Reflex.values();
    private static final int N = REFLEXES.length;
    /** Per reflex: bit mask of the {@link HazardKind}s it answers (its deadline is the nearest live one). */
    private static final int[] HAZARDS = new int[N];

    static {
        HAZARDS[Reflex.FALL_CLUTCH.ordinal()] = bit(HazardKind.FALL) | bit(HazardKind.VOID);
        HAZARDS[Reflex.ELYTRA_SWAP.ordinal()] = bit(HazardKind.FALL) | bit(HazardKind.VOID);
        HAZARDS[Reflex.SHIELD.ordinal()] = bit(HazardKind.PROJECTILE) | bit(HazardKind.EXPLOSION)
                | bit(HazardKind.MELEE);
        HAZARDS[Reflex.DODGE.ordinal()] = bit(HazardKind.PROJECTILE);
        HAZARDS[Reflex.THREAT_ESCAPE.ordinal()] = bit(HazardKind.EXPLOSION) | bit(HazardKind.LAVA)
                | bit(HazardKind.FIRE);
        HAZARDS[Reflex.TOTEM.ordinal()] = bit(HazardKind.LOW_HEALTH);
        HAZARDS[Reflex.OFFHAND.ordinal()] = bit(HazardKind.PROJECTILE) | bit(HazardKind.LOW_HEALTH);
        // PARKOUR and PARKOUR_BLOCKS answer no hazard: no deadline, they always count as in time
    }

    private static int bit(HazardKind k) {
        return 1 << k.ordinal();
    }

    private final TickClock clock;
    private final long[] bidTick = new long[N];
    private final float[] bidScore = new float[N];
    private final boolean[] bidInTime = new boolean[N];
    private final boolean[] hasBid = new boolean[N];
    private int acting = -1;
    private long actingSeen;

    public ReflexScheduler(TickClock clock) {
        this.clock = clock;
    }

    /**
     * Records the bid and answers whether this reflex may act now.
     *
     * @param allowed the autopilot's {@code allows(reflex)} (setting and reach); a refused reflex records no bid
     */
    public boolean mayAct(Reflex reflex, boolean allowed, int ticksNeeded, float damageAverted, IDangerModel danger) {
        if (reflex == null || !allowed) {
            return false;
        }
        long now = clock.now();
        int r = reflex.ordinal();
        int needed = Math.max(1, ticksNeeded);
        float score = Math.max(0f, damageAverted) / needed;
        boolean inTime = needed <= deadline(r, danger);
        bidTick[r] = now;
        bidScore[r] = score;
        bidInTime[r] = inTime;
        hasBid[r] = true;

        int a = actingNow(now);
        if (a == r) {
            actingSeen = now;
            return true;
        }
        if (a >= 0) {
            return false; // the acting reflex holds the turn
        }
        for (int j = 0; j < N; j++) {
            if (j == r || !hasBid[j] || now - bidTick[j] >= BID_LIFE_TICKS) {
                continue;
            }
            if (ranksAbove(j, r)) {
                return false;
            }
        }
        return true;
    }

    private boolean ranksAbove(int j, int r) {
        if (bidInTime[j] != bidInTime[r]) {
            return bidInTime[j];
        }
        if (bidScore[j] != bidScore[r]) {
            return bidScore[j] > bidScore[r];
        }
        return j < r;
    }

    private static int deadline(int r, IDangerModel danger) {
        int mask = HAZARDS[r];
        if (mask == 0 || danger == null) {
            return Integer.MAX_VALUE;
        }
        int best = Integer.MAX_VALUE;
        HazardKind[] kinds = HazardKind.all();
        for (int k = 0; k < kinds.length; k++) {
            if ((mask & (1 << k)) != 0) {
                best = Math.min(best, danger.ticksToImpact(kinds[k]));
            }
        }
        return best;
    }

    private int actingNow(long now) {
        if (acting >= 0 && now - actingSeen >= ACTING_TIMEOUT_TICKS) {
            acting = -1;
        }
        return acting;
    }

    /** The reflex started acting: it holds the turn until {@link #noteDone}. */
    public void noteActed(Reflex reflex) {
        if (reflex == null) {
            return;
        }
        acting = reflex.ordinal();
        actingSeen = clock.now();
    }

    /** The reflex finished or gave up. */
    public void noteDone(Reflex reflex) {
        if (reflex == null) {
            return;
        }
        int r = reflex.ordinal();
        hasBid[r] = false;
        if (acting == r) {
            acting = -1;
        }
    }

    /** The acting reflex, or null. */
    public Reflex active() {
        int a = actingNow(clock.now());
        return a < 0 ? null : REFLEXES[a];
    }

    /** Stop-All, world unload, autopilot off: forget every bid and the acting reflex. */
    public void reset() {
        for (int i = 0; i < N; i++) {
            hasBid[i] = false;
        }
        acting = -1;
    }
}
