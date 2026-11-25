package com.utilitysuite.jobs.schematic.generated;

import com.utilitysuite.jobs.planner.PackedPos;

/**
 * Engine-free progress tracker for one highway job: projects the player onto the path, decides which segment to
 * publish next (in order, never more than one ahead of the look-ahead), and builds the centre-line walking path
 * of a segment (feet cells) for the digging/pathing layer. Allocation free per tick; a segment's path array is
 * allocated once when the segment is published.
 */
public final class HighwayRun {

    public final int sx;
    public final int sy;
    public final int sz;
    public final int dx;
    public final int dz;
    public final int totalSteps;
    public final int segments;
    private final int segmentLength;
    private int published = -1;

    public HighwayRun(int sx, int sy, int sz, int ex, int ez, int segmentLength) {
        this.sx = sx;
        this.sy = sy;
        this.sz = sz;
        this.dx = HighwayDesign.dirX(ex - sx, ez - sz);
        this.dz = HighwayDesign.dirZ(ex - sx, ez - sz);
        this.totalSteps = HighwayDesign.steps(ex - sx, ez - sz);
        this.segmentLength = Math.max(1, segmentLength);
        this.segments = HighwayDesign.segmentCount(totalSteps, this.segmentLength);
    }

    /** Steps along the path the player has covered (projection, clamped to 0..total). */
    public int progress(double px, double pz) {
        int len2 = dx * dx + dz * dz;
        if (len2 == 0) {
            return 0;
        }
        double t = ((px - (sx + 0.5)) * dx + (pz - (sz + 0.5)) * dz) / len2;
        return (int) Math.max(0, Math.min(totalSteps, Math.floor(t + 0.5)));
    }

    /** Sideways distance of the player from the centre line, in blocks. */
    public double offPath(double px, double pz) {
        int len2 = dx * dx + dz * dz;
        if (len2 == 0) {
            return 0;
        }
        double rx = px - (sx + 0.5);
        double rz = pz - (sz + 0.5);
        return Math.abs(rx * dz - rz * dx) / Math.sqrt(len2);
    }

    /** Next segment index to publish for this progress, or -1 when the published ones already reach far enough. */
    public int nextToPublish(int progressSteps, int lookahead) {
        if (segments == 0) {
            return -1;
        }
        int want = HighwayDesign.segmentAhead(progressSteps, lookahead, segmentLength, segments);
        return want > published ? published + 1 : -1;
    }

    public void markPublished(int index) {
        published = Math.max(published, index);
    }

    public int published() {
        return published;
    }

    /** True once the last segment is published and the player reached the end. */
    public boolean finished(int progressSteps) {
        return segments > 0 && published >= segments - 1 && progressSteps >= totalSteps;
    }

    /** Feet cells of the centre line for steps {@code from..to}, packed with {@link PackedPos}. */
    public long[] centrePath(int from, int to) {
        int n = Math.max(0, to - from + 1);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            int s = from + i;
            out[i] = PackedPos.pack(sx + s * dx, sy, sz + s * dz);
        }
        return out;
    }
}
