package com.utilitysuite.world.sonar;

/**
 * Engine-free timing of one sonar pulse: a sphere that grows from the origin at {@code speed} blocks per second up to
 * {@code range}; a point at distance d lights up when the front passes it and fades out over {@code fadeMs}.
 * All methods are allocation-free (called per frame).
 */
public final class Wavefront {

    private long startMs = Long.MIN_VALUE;
    private float speed = 32f;
    private float range = 48f;
    private float fadeMs = 1500f;

    public void start(long nowMs, float blocksPerSecond, float maxRange, float fadeMillis) {
        startMs = nowMs;
        speed = Math.max(0.1f, blocksPerSecond);
        range = Math.max(1f, maxRange);
        fadeMs = Math.max(1f, fadeMillis);
    }

    public void stop() {
        startMs = Long.MIN_VALUE;
    }

    public boolean started() {
        return startMs != Long.MIN_VALUE;
    }

    /** Elapsed milliseconds since the pulse began (negative before). */
    public float elapsed(long nowMs) {
        return started() ? (float) (nowMs - startMs) : -1f;
    }

    /** Current front radius in blocks (capped at the range), or -1 when no pulse. */
    public float radius(long nowMs) {
        float t = elapsed(nowMs);
        if (t < 0) {
            return -1f;
        }
        return Math.min(range, t * speed / 1000f);
    }

    /** Milliseconds the front needs to reach the range. */
    public float travelMs() {
        return range / speed * 1000f;
    }

    /** True while anything of the pulse is still visible (front travelling or the last points fading). */
    public boolean active(long nowMs) {
        float t = elapsed(nowMs);
        return t >= 0 && t < travelMs() + fadeMs;
    }

    /** True while the front itself is still expanding (the ring is drawn). */
    public boolean expanding(long nowMs) {
        float t = elapsed(nowMs);
        return t >= 0 && t < travelMs();
    }

    /** Brightness 0..1 of a point at distance {@code d}: 0 before the front reaches it and after it has faded. */
    public float alpha(float d, long nowMs) {
        float t = elapsed(nowMs);
        if (t < 0 || d > range) {
            return 0f;
        }
        float since = t - d / speed * 1000f;
        if (since < 0 || since >= fadeMs) {
            return 0f;
        }
        return 1f - since / fadeMs;
    }

    /** Smallest distance that is still visible at {@code nowMs} (points nearer have faded). */
    public float minVisible(long nowMs) {
        float t = elapsed(nowMs);
        return Math.max(0f, (t - fadeMs) * speed / 1000f);
    }

    public float range() {
        return range;
    }

    public float fadeMs() {
        return fadeMs;
    }
}
