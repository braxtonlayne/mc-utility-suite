package com.utilitysuite.core.autopilot;

import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IDangerModel;

/**
 * Turns the local player's vitals into danger reports (LOW_HEALTH, DROWNING, FIRE) and notices big hits and death for
 * the black box. The engine side ({@link VitalsSource}, implemented in {@code core.autopilot.mc}) fills a reusable
 * {@link Vitals}; everything here is engine free and allocation free.
 */
public final class VitalsMonitor {

    /** Filled by a {@link VitalsSource} each tick. */
    public static final class Vitals {
        public boolean present;
        public float health;
        public float maxHealth;
        public int air;
        public int maxAir;
        public int fireTicks;
        public boolean fireImmune;
        public boolean dead;
    }

    /** Reads the local player into {@code out}; sets {@code present = false} when there is none. */
    public interface VitalsSource {
        void sample(Vitals out);
    }

    public static final int NOTHING = 0;
    public static final int BIG_HIT = 1;
    public static final int DIED = 2;

    /** Health at or below this (half hearts) is reported as LOW_HEALTH. */
    public static final float LOW_HEALTH = 6f;
    /** Air at or below this (ticks; full is 300) is reported as DROWNING. */
    public static final int LOW_AIR = 60;

    private float lastHealth = Float.NaN;
    private boolean wasDead;
    private float lastDrop;

    /**
     * Reports what the vitals say and returns {@link #NOTHING}, {@link #BIG_HIT} (one tick's health drop at least
     * {@code bigHitDamage}) or {@link #DIED} (the first tick of a death).
     */
    public int update(Vitals v, IDangerModel danger, float bigHitDamage) {
        if (!v.present) {
            lastHealth = Float.NaN;
            wasDead = false;
            return NOTHING;
        }
        int result = NOTHING;
        if (v.dead || v.health <= 0f) {
            if (!wasDead) {
                result = DIED;
            }
            wasDead = true;
            lastDrop = Float.isNaN(lastHealth) ? 0f : lastHealth;
            lastHealth = 0f;
            return result;
        }
        wasDead = false;
        if (!Float.isNaN(lastHealth)) {
            float drop = lastHealth - v.health;
            if (drop >= bigHitDamage && bigHitDamage > 0f) {
                lastDrop = drop;
                result = BIG_HIT;
            }
        }
        lastHealth = v.health;

        if (v.health <= LOW_HEALTH) {
            // any hit may kill: everything left is at stake, within a second
            danger.report(HazardKind.LOW_HEALTH, 20, v.health, IDangerModel.NO_BEARING);
        }
        if (v.air <= LOW_AIR && v.maxAir > 0) {
            // air counts down 1 per tick; drowning hurts 2 at -20 and every second after
            danger.report(HazardKind.DROWNING, Math.max(0, v.air + 20), 2f, IDangerModel.NO_BEARING);
        }
        if (v.fireTicks > 0 && !v.fireImmune) {
            // 1 damage per second while burning
            float left = Math.min(v.fireTicks, 160) / 20f;
            danger.report(HazardKind.FIRE, 0, Math.max(1f, left), IDangerModel.NO_BEARING);
        }
        return result;
    }

    /** The health lost in the last big hit or death. */
    public float lastDrop() {
        return lastDrop;
    }

    public float lastHealth() {
        return lastHealth;
    }

    public void reset() {
        lastHealth = Float.NaN;
        wasDead = false;
    }
}
