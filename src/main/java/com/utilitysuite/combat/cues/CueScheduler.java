package com.utilitysuite.combat.cues;

/**
 * Engine-free gate for the holo-audio cues: per-cue toggle, per-cue minimum gap, and a global token bucket (at most
 * {@code burst} cues, refilled by one every {@code refillTicks}) so a busy moment never turns into a wall of beeps.
 * Volumes are the master volume times a per-cue base. Allocation-free.
 */
public final class CueScheduler {

    public enum Cue {
        /** Weapon fully charged (higher pitch when the swing would crit). */
        SWING_READY(6, 0.45f),
        /** One step of the rising mace-fall tone. */
        MACE_RISE(2, 0.5f),
        /** The fall now deals lethal smash damage. */
        MACE_LETHAL(10, 0.7f),
        /** A player not on the ally roster came into range. */
        PLAYER_BLIP(10, 0.35f),
        /** Automation took or handed back control. */
        LEASE_CLICK(3, 0.3f);

        final int minGapTicks;
        final float baseVolume;

        Cue(int minGapTicks, float baseVolume) {
            this.minGapTicks = minGapTicks;
            this.baseVolume = baseVolume;
        }
    }

    private static final Cue[] CUES = Cue.values();

    private final boolean[] enabled = new boolean[CUES.length];
    private final long[] lastPlayed = new long[CUES.length];
    private float master = 0.6f;
    private int burst = 4;
    private int refillTicks = 5;
    private int tokens = burst;
    private long lastRefill;

    public CueScheduler() {
        java.util.Arrays.fill(lastPlayed, Long.MIN_VALUE / 2);
    }

    public void setEnabled(Cue c, boolean on) {
        enabled[c.ordinal()] = on;
    }

    public boolean isEnabled(Cue c) {
        return enabled[c.ordinal()];
    }

    public void setMaster(float v) {
        master = v < 0f ? 0f : Math.min(1f, v);
    }

    public void setRate(int burst, int refillTicks) {
        this.burst = Math.max(1, burst);
        this.refillTicks = Math.max(1, refillTicks);
        tokens = Math.min(tokens, this.burst);
    }

    /** True when the cue may play now; records it as played. */
    public boolean request(Cue c, long tick) {
        int i = c.ordinal();
        if (!enabled[i] || master <= 0f) {
            return false;
        }
        refill(tick);
        if (tick - lastPlayed[i] < c.minGapTicks || tokens <= 0) {
            return false;
        }
        tokens--;
        lastPlayed[i] = tick;
        return true;
    }

    private void refill(long tick) {
        if (tokens >= burst) {
            lastRefill = tick;
            return;
        }
        long elapsed = tick - lastRefill;
        if (elapsed >= refillTicks) {
            long add = elapsed / refillTicks;
            tokens = (int) Math.min(burst, tokens + add);
            lastRefill += add * refillTicks;
        }
    }

    public float volume(Cue c) {
        return master * c.baseVolume;
    }

    public void reset() {
        java.util.Arrays.fill(lastPlayed, Long.MIN_VALUE / 2);
        tokens = burst;
    }
}
