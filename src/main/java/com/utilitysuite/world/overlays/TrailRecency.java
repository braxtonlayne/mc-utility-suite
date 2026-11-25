package com.utilitysuite.world.overlays;

/**
 * Recency maths for trail forensics (R5): fade by age, "how long ago" labels from precomputed tables (so the render
 * path never builds strings), alpha scaling of packed colours, and the speed of a trail from per-cell timestamps.
 * Engine-free; nothing here allocates after class init.
 */
public final class TrailRecency {

    /** Faded trails never drop below this share of their alpha, so old trails stay readable. */
    public static final float MIN_FADE = 0.2f;
    /** Alpha share for cells without a timestamp (loaded from an old save): between fresh and fully faded. */
    public static final float UNKNOWN_FADE = 0.5f;

    private static final long MINUTE = 60_000L;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    private static final String[] MINUTES = new String[60];
    private static final String[] HOURS = new String[24];
    private static final String[] DAYS = new String[61];

    static {
        for (int i = 1; i < 60; i++) {
            MINUTES[i] = i + " min ago";
        }
        for (int i = 1; i < 24; i++) {
            HOURS[i] = i + " h ago";
        }
        for (int i = 1; i <= 60; i++) {
            DAYS[i] = i == 1 ? "1 day ago" : i + " days ago";
        }
    }

    private TrailRecency() {
    }

    /**
     * Alpha share for a trail of the given age: 1 when fresh, falling linearly to {@link #MIN_FADE} at
     * {@code fadeMs}. {@code wallMs <= 0} (unknown) gives {@link #UNKNOWN_FADE}; {@code fadeMs <= 0} disables fading.
     */
    public static float fade(long wallMs, long nowMs, long fadeMs) {
        if (fadeMs <= 0) {
            return 1f;
        }
        if (wallMs <= 0) {
            return UNKNOWN_FADE;
        }
        long age = Math.max(0L, nowMs - wallMs);
        if (age >= fadeMs) {
            return MIN_FADE;
        }
        return 1f - (1f - MIN_FADE) * (age / (float) fadeMs);
    }

    /** {@code argb} with its alpha multiplied by {@code f} (0..1). */
    public static int withAlpha(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        int na = Math.round(a * Math.max(0f, Math.min(1f, f)));
        return (na << 24) | (argb & 0x00FFFFFF);
    }

    /** "just now", "12 min ago", "3 h ago", "5 days ago", "long ago"; "age unknown" for {@code wallMs <= 0}. */
    public static String ageLabel(long wallMs, long nowMs) {
        if (wallMs <= 0) {
            return "age unknown";
        }
        long age = Math.max(0L, nowMs - wallMs);
        if (age < MINUTE) {
            return "just now";
        }
        if (age < HOUR) {
            return MINUTES[(int) (age / MINUTE)];
        }
        if (age < DAY) {
            return HOURS[(int) (age / HOUR)];
        }
        long d = age / DAY;
        return d <= 60 ? DAYS[(int) d] : "long ago";
    }

    /**
     * Speed (blocks per second, unsigned) of the leading edge along a trail: the least-squares slope of the
     * along-axis position against time over the cells with a known time ({@code times[i] != 0}).
     *
     * @param alongBlocks position of each cell along the trail axis, in blocks
     * @param times       per cell time stamp (game ticks or epoch ms), 0 = unknown
     * @param unitsPerSec 20 for game ticks, 1000 for milliseconds
     * @param minSpan     least time span (same units) that gives a trustworthy slope
     * @return blocks per second, or NaN with fewer than 3 timed cells or a span under {@code minSpan}
     */
    public static double speed(double[] alongBlocks, long[] times, int n, double unitsPerSec, long minSpan) {
        if (times == null) {
            return Double.NaN;
        }
        int m = 0;
        long lo = Long.MAX_VALUE;
        long hi = Long.MIN_VALUE;
        double st = 0;
        double sa = 0;
        for (int i = 0; i < n; i++) {
            if (times[i] == 0) {
                continue;
            }
            m++;
            lo = Math.min(lo, times[i]);
            hi = Math.max(hi, times[i]);
        }
        if (m < 3 || hi - lo < minSpan) {
            return Double.NaN;
        }
        for (int i = 0; i < n; i++) {
            if (times[i] != 0) {
                st += (times[i] - lo) / unitsPerSec;
                sa += alongBlocks[i];
            }
        }
        double mt = st / m;
        double ma = sa / m;
        double cov = 0;
        double var = 0;
        for (int i = 0; i < n; i++) {
            if (times[i] != 0) {
                double t = (times[i] - lo) / unitsPerSec - mt;
                cov += t * (alongBlocks[i] - ma);
                var += t * t;
            }
        }
        return var < 1e-12 ? Double.NaN : Math.abs(cov / var);
    }
}
