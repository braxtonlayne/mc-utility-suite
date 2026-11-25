package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.jobs.survival.FoodSelector;

/**
 * Engine-free rules for the XP-farm AFK grinder: swing only at full charge (the same 0.95 threshold as the combat
 * assistant's swing gate) at a hostile mob in reach, never while the server looks stalled (a swing during a lag spike
 * is wasted and looks bot-like), eat by the survival autopilot's food rule, and swap to a backup weapon before the
 * held one breaks (mending weapons first, so XP from the farm repairs them). Allocation free.
 */
public final class AfkGrinderRules {

    /** Full-charge threshold, same as {@code combat.assistant.SwingGate.READY}. */
    public static final float READY = 0.95f;
    /** Pro default: swap out at 10 % durability left. */
    public static final double LOW_DURABILITY = 0.10;
    /** Pro default: a server tick gap longer than this counts as a stall. */
    public static final long STALL_MS = 400;

    private AfkGrinderRules() {
    }

    /**
     * True when the server looks stalled: no world-time/tick update for {@code stallMs}, or tick jitter (from
     * PacketLab's ServerPhysicsMonitor) above {@code stallMs / 2}.
     */
    public static boolean serverStalled(long msSinceServerTick, double tickJitterMs, long stallMs) {
        return msSinceServerTick > stallMs || tickJitterMs > stallMs / 2.0;
    }

    public static boolean shouldSwing(float attackStrength, boolean targetInReach, boolean stalled, boolean eating) {
        return targetInReach && !stalled && !eating && attackStrength >= READY;
    }

    /** Hostile mob in reach to swing at: nearest, ties broken by lowest health. -1 when none. */
    public static int pickTarget(boolean[] hostile, double[] distSq, float[] health, int n, double reachSq) {
        int best = -1;
        for (int i = 0; i < n; i++) {
            if (!hostile[i] || distSq[i] > reachSq || health[i] <= 0f) {
                continue;
            }
            if (best < 0 || distSq[i] < distSq[best] - 1e-6
                    || (Math.abs(distSq[i] - distSq[best]) <= 1e-6 && health[i] < health[best])) {
                best = i;
            }
        }
        return best;
    }

    /** Survival autopilot's eat rule (hunger threshold or missing health). */
    public static boolean shouldEat(int hunger, float health, float maxHealth, int hungerThreshold,
                                    float healthThreshold) {
        return FoodSelector.shouldEat(hunger, health, maxHealth, hungerThreshold, healthThreshold);
    }

    public static double durabilityFraction(int damage, int maxDamage) {
        return maxDamage <= 0 ? 1.0 : (maxDamage - damage) / (double) maxDamage;
    }

    /**
     * Weapon slot to hold. Keeps {@code current} while it is a weapon above {@code low}; otherwise the best weapon above
     * {@code low}: mending first, then highest attack damage, then most durability. -1 when no weapon is safe to use
     * (stop swinging rather than break a tool).
     */
    public static int weaponSlot(boolean[] weapon, int[] damage, int[] maxDamage, boolean[] mending, float[] attack,
                                 int n, int current, double low) {
        if (current >= 0 && current < n && weapon[current]
                && durabilityFraction(damage[current], maxDamage[current]) > low) {
            return current;
        }
        int best = -1;
        for (int i = 0; i < n; i++) {
            if (!weapon[i] || durabilityFraction(damage[i], maxDamage[i]) <= low) {
                continue;
            }
            if (best < 0 || better(i, best, mending, attack, damage, maxDamage)) {
                best = i;
            }
        }
        return best;
    }

    private static boolean better(int a, int b, boolean[] mending, float[] attack, int[] damage, int[] maxDamage) {
        if (mending[a] != mending[b]) {
            return mending[a];
        }
        if (attack[a] != attack[b]) {
            return attack[a] > attack[b];
        }
        return durabilityFraction(damage[a], maxDamage[a]) > durabilityFraction(damage[b], maxDamage[b]);
    }
}
