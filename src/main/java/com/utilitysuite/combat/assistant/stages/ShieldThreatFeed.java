package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.reflex.ShieldDecision;

/**
 * Threats to the user's shield other than the duel target's melee (an arrow, a blast, a second attacker), handed
 * from the shield reflex to the {@link ShieldDuelStage} while that stage is the one owner of USE in a fight.
 * <p>
 * The reflex keeps sensing and its own settings (module, "Shield reflex", front cone); the stage decides and presses.
 * Once per client tick the reflex calls {@link #begin}, {@link #offer} for each kind and {@link #publish}; the stage
 * calls {@link #take} once per step. A publication is good for {@value #FRESH_STEPS} stage steps, so the feed needs
 * no shared clock and goes quiet by itself when the reflex stops (module off, setting off, fight over).
 * Plain fields, client thread only, allocation free.
 */
public final class ShieldThreatFeed {

    /** Stage steps one publication stays valid (the two tick independently, in either order). */
    static final int FRESH_STEPS = 2;

    private int ticks = Integer.MAX_VALUE;
    private float damage;
    private float relYaw = Float.NaN;
    private double cone = ShieldDecision.DEFAULT_FRONT_CONE_DEG;
    private boolean standing;
    private int fresh;

    private float viewYaw;
    private int nextTicks = Integer.MAX_VALUE;
    private float nextDamage;
    private float nextBearing = Float.NaN;
    private boolean nextStanding;
    private double nextCone = ShieldDecision.DEFAULT_FRONT_CONE_DEG;

    // ---- reflex side ----------------------------------------------------------------------------------------------

    /** Starts this tick's publication: the player's view yaw and the reflex's legit front cone half-angle. */
    public void begin(float viewYawDeg, double frontConeDeg) {
        viewYaw = viewYawDeg;
        nextCone = frontConeDeg;
        nextTicks = Integer.MAX_VALUE;
        nextDamage = 0f;
        nextBearing = Float.NaN;
        nextStanding = false;
    }

    /**
     * One threat: the most urgent one worth a block is kept.
     *
     * @param ticksToImpact {@code Integer.MAX_VALUE} for none
     * @param bearingYawDeg world yaw from the player toward its source
     * @param standing      true for an attacker that stays in reach (its time to impact is a standing estimate, not
     *                      a countdown): the stage blocks it between its own swings. False for a single impact (an
     *                      arrow, a blast): the shield stays up until it has landed
     */
    public void offer(int ticksToImpact, float expectedDamage, float bearingYawDeg, boolean standing) {
        if (ticksToImpact < nextTicks && expectedDamage >= ShieldDecision.MIN_DAMAGE && !Float.isNaN(bearingYawDeg)) {
            nextTicks = ticksToImpact;
            nextDamage = expectedDamage;
            nextBearing = bearingYawDeg;
            nextStanding = standing;
        }
    }

    /** Ends this tick's publication (also when nothing was offered: "no threat" is news too). */
    public void publish() {
        ticks = nextTicks;
        damage = nextDamage;
        relYaw = nextTicks == Integer.MAX_VALUE ? Float.NaN : wrap(nextBearing - viewYaw);
        cone = nextCone;
        standing = nextStanding;
        fresh = FRESH_STEPS;
    }

    /** Nothing to report any more (the reflex stood down). */
    public void clear() {
        ticks = Integer.MAX_VALUE;
        damage = 0f;
        relYaw = Float.NaN;
        fresh = 0;
    }

    // ---- stage side -----------------------------------------------------------------------------------------------

    /**
     * One stage step: true while a threat from a fresh publication is live. Afterwards {@link #ticksToImpact},
     * {@link #damage}, {@link #relYawDeg} and {@link #frontConeDeg} describe it.
     */
    public boolean take() {
        if (fresh <= 0) {
            ticks = Integer.MAX_VALUE;
            return false;
        }
        fresh--;
        return ticks != Integer.MAX_VALUE;
    }

    public int ticksToImpact() {
        return ticks;
    }

    public float damage() {
        return damage;
    }

    /** Direction relative to the view the reflex published (0 ahead, -180..180); NaN for none. */
    public float relYawDeg() {
        return relYaw;
    }

    /** The threat is an attacker staying in reach, not a single impact (see {@link #offer}). */
    public boolean standing() {
        return standing;
    }

    public double frontConeDeg() {
        return cone;
    }

    private static float wrap(float deg) {
        float d = deg % 360f;
        if (d >= 180f) {
            d -= 360f;
        } else if (d < -180f) {
            d += 360f;
        }
        return d;
    }
}
