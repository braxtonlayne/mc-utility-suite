package com.utilitysuite.core.autopilot;

import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IDangerModel;

/**
 * The autopilot's {@link IDangerModel}: one slot per {@link HazardKind} in primitive arrays indexed by ordinal (impact
 * tick, expected damage, bearing, expiry tick). Times are absolute ticks of a {@link TickClock}, so entries count down
 * and expire on their own without a per-tick sweep. A report lives until {@link #STALE_TICKS} after it was made, or
 * one tick past its impact, whichever comes first. Engine free; every call is allocation free. Client thread only.
 */
public final class DangerModel implements IDangerModel {

    /** A report not refreshed for this many ticks is dropped. */
    public static final int STALE_TICKS = 10;

    private static final int N = HazardKind.all().length;

    private final TickClock clock;
    private final long[] impactTick = new long[N];
    private final long[] expiryTick = new long[N];
    private final float[] damage = new float[N];
    private final float[] bearing = new float[N];
    private final boolean[] live = new boolean[N];

    public DangerModel(TickClock clock) {
        this.clock = clock;
    }

    @Override
    public void report(HazardKind kind, int ticksToImpact, float expectedDamage, float bearingYawDeg) {
        if (kind == null) {
            return;
        }
        long now = clock.now();
        int i = kind.ordinal();
        long impact = now + Math.max(0, ticksToImpact);
        if (isLive(i, now) && impactTick[i] < impact) {
            return; // the nearer report already held wins
        }
        impactTick[i] = impact;
        expiryTick[i] = Math.min(now + STALE_TICKS, impact + 1);
        damage[i] = Math.max(0f, expectedDamage);
        bearing[i] = bearingYawDeg;
        live[i] = true;
    }

    @Override
    public void clear(HazardKind kind) {
        if (kind != null) {
            live[kind.ordinal()] = false;
        }
    }

    /** Drops every report (Stop-All, world unload). */
    public void clearAll() {
        for (int i = 0; i < N; i++) {
            live[i] = false;
        }
    }

    private boolean isLive(int i, long now) {
        if (!live[i]) {
            return false;
        }
        if (now >= expiryTick[i]) {
            live[i] = false;
            return false;
        }
        return true;
    }

    @Override
    public boolean any() {
        long now = clock.now();
        boolean any = false;
        for (int i = 0; i < N; i++) {
            any |= isLive(i, now);
        }
        return any;
    }

    @Override
    public boolean has(HazardKind kind) {
        return kind != null && isLive(kind.ordinal(), clock.now());
    }

    @Override
    public int ticksToImpact(HazardKind kind) {
        if (kind == null) {
            return Integer.MAX_VALUE;
        }
        long now = clock.now();
        int i = kind.ordinal();
        if (!isLive(i, now)) {
            return Integer.MAX_VALUE;
        }
        return (int) Math.max(0L, impactTick[i] - now);
    }

    @Override
    public float expectedDamage(HazardKind kind) {
        return kind != null && isLive(kind.ordinal(), clock.now()) ? damage[kind.ordinal()] : 0f;
    }

    @Override
    public float bearingYawDeg(HazardKind kind) {
        return kind != null && isLive(kind.ordinal(), clock.now()) ? bearing[kind.ordinal()] : NO_BEARING;
    }

    @Override
    public HazardKind worst() {
        long now = clock.now();
        int best = -1;
        float bestRate = -1f;
        for (int i = 0; i < N; i++) {
            if (!isLive(i, now)) {
                continue;
            }
            float rate = damage[i] / Math.max(1L, impactTick[i] - now);
            if (rate > bestRate) {
                bestRate = rate;
                best = i;
            }
        }
        return best < 0 ? null : HazardKind.all()[best];
    }
}
