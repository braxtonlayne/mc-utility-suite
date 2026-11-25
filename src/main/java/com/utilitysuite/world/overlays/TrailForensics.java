package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldExecutor;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Glue for {@link TrailClassifier} inside World Radar: every couple of seconds copies the NEW trail cells around the
 * player (plus a surface water/ice probe for loaded chunks and each cell's first-seen game tick and wall time) on the
 * client thread, classifies them on the {@link WorldExecutor}, and publishes an immutable list. Draws each segment as a
 * smooth spline with direction chevrons along it and an arrowhead at the newest end, faded by age per stretch when a
 * fade window is set; the label at the start reads kind and measured speed, and when you are near the trail or look at
 * it, how long ago that stretch was made.
 */
final class TrailForensics {

    private static final int MAX_CELLS = 65 * 65;
    private static final int MIN_SEGMENT_CELLS = 6;
    private static final int PER_SEG = 6;
    /** A chevron every this many spline samples (one per spine step). */
    private static final int CHEVRON_EVERY = PER_SEG;
    /** Within this many blocks of a spine point the age label shows. */
    private static final double NEAR_BLOCKS = 48.0;
    /** cos(3 degrees): looking this close to a spine point counts as hovering it. */
    private static final float HOVER_COS = 0.9986f;
    private static final String[] KIND_LABELS = {"Elytra highway", "Boat run", "Nether tunnel", "Search zig-zag",
            "Travel", "Wander"};

    /** A classified segment and its label (built off-thread, so drawing never builds strings). */
    record Shown(TrailClassifier.Segment segment, String title) {
    }

    private final TrailClassifier classifier = new TrailClassifier();
    private final long[] keys = new long[MAX_CELLS];
    private final boolean[] water = new boolean[MAX_CELLS];
    private final long[] ticks = new long[MAX_CELLS];
    private final long[] walls = new long[MAX_CELLS];
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final float[] splineX = new float[(TrailClassifier.MAX_SPINE - 1) * PER_SEG + 1];
    private final float[] splineZ = new float[(TrailClassifier.MAX_SPINE - 1) * PER_SEG + 1];
    private volatile List<Shown> segments = List.of();
    private volatile boolean busy;

    void clear() {
        segments = List.of();
    }

    /** Client thread, every ~40 ticks. Bounded by the trail radius (at most 65x65 map lookups). */
    void gather(ChunkAgeTracker tracker, ClientLevel level, int pcx, int pcz, int r, boolean nether) {
        if (busy) {
            return;
        }
        int n = 0;
        for (int dx = -r; dx <= r && n < MAX_CELLS; dx++) {
            for (int dz = -r; dz <= r && n < MAX_CELLS; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (tracker.stateOf(cx, cz) != ChunkAgeTracker.NEW) {
                    continue;
                }
                keys[n] = BlockPosPacking.chunk(cx, cz);
                water[n] = surfaceWet(level, cx, cz);
                ticks[n] = tracker.seenTickOf(cx, cz);
                walls[n] = tracker.seenWallOf(cx, cz);
                n++;
            }
        }
        if (n < MIN_SEGMENT_CELLS) {
            segments = List.of();
            return;
        }
        long[] k = java.util.Arrays.copyOf(keys, n);
        boolean[] w = java.util.Arrays.copyOf(water, n);
        long[] t = java.util.Arrays.copyOf(ticks, n);
        long[] wl = java.util.Arrays.copyOf(walls, n);
        busy = true;
        WorldExecutor.get().execute(() -> {
            try {
                segments = classifyAll(classifier, k, w, t, wl, nether, pcx, pcz);
            } finally {
                busy = false;
            }
        });
    }

    private boolean surfaceWet(ClientLevel level, int cx, int cz) {
        if (level == null || !level.hasChunk(cx, cz)) {
            return false;
        }
        int x = (cx << 4) + 8;
        int z = (cz << 4) + 8;
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        BlockState s = level.getBlockState(probe.set(x, y, z));
        return s.getFluidState().is(FluidTags.WATER) || s.is(BlockTags.ICE);
    }

    /** Off-thread: clusters the cells and classifies each segment. */
    static List<Shown> classifyAll(TrailClassifier c, long[] keys, boolean[] wet, long[] tick, long[] wall,
                                   boolean nether, double viewerCx, double viewerCz) {
        int n = keys.length;
        int[] label = TrailClassifier.cluster(keys, n);
        Int2ObjectOpenHashMap<IntArrayList> groups = new Int2ObjectOpenHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(label[i], x -> new IntArrayList()).add(i);
        }
        List<Shown> out = new ArrayList<>();
        for (IntArrayList g : groups.values()) {
            int m = g.size();
            if (m < MIN_SEGMENT_CELLS) {
                continue;
            }
            int[] xs = new int[m];
            int[] zs = new int[m];
            boolean[] w = new boolean[m];
            long[] t = tick == null ? null : new long[m];
            long[] wl = wall == null ? null : new long[m];
            for (int j = 0; j < m; j++) {
                int i = g.getInt(j);
                xs[j] = BlockPosPacking.chunkX(keys[i]);
                zs[j] = BlockPosPacking.chunkZ(keys[i]);
                w[j] = wet[i];
                if (t != null) {
                    t[j] = tick[i];
                }
                if (wl != null) {
                    wl[j] = wall[i];
                }
            }
            TrailClassifier.Segment s = c.classify(xs, zs, m, w, t, wl, nether, viewerCx, viewerCz);
            if (s != null) {
                out.add(new Shown(s, title(s)));
            }
        }
        return List.copyOf(out);
    }

    /** "Elytra highway  34 b/s" (speed only when measured). */
    static String title(TrailClassifier.Segment s) {
        String kind = KIND_LABELS[s.kind().ordinal()];
        return s.hasSpeed() ? kind + "  " + Math.round(s.speedBps()) + " b/s" : kind;
    }

    /**
     * Render thread: spline per segment with chevrons and an arrowhead, at the given relative height, each stretch
     * faded by its age ({@code fadeMs <= 0}: no fading). Allocates nothing.
     */
    void render(RenderContext ctx, float y, long nowMs, long fadeMs) {
        List<Shown> list = segments;
        for (int i = 0, n = list.size(); i < n; i++) {
            TrailClassifier.Segment s = list.get(i).segment();
            float[] px = s.spineX();
            float[] pz = s.spineZ();
            long[] pw = s.spineWallMs();
            int w = TrailClassifier.catmullRom(px, pz, px.length, PER_SEG, splineX, splineZ);
            if (w < 2) {
                continue;
            }
            int base = TrailClassifier.color(s.kind());
            for (int j = 1; j < w; j++) {
                int spine = Math.min(px.length - 1, j / PER_SEG);
                int argb = TrailRecency.withAlpha(base, TrailRecency.fade(pw[spine], nowMs, fadeMs));
                float x0 = wx(ctx, splineX[j - 1]);
                float z0 = wz(ctx, splineZ[j - 1]);
                float x1 = wx(ctx, splineX[j]);
                float z1 = wz(ctx, splineZ[j]);
                WorldRenderKit.line(ctx.lines, ctx.pose, x0, y, z0, x1, y, z1, argb);
                boolean head = j == w - 1;
                if (head || j % CHEVRON_EVERY == 0) {
                    chevron(ctx, x1, y, z1, splineX[j] - splineX[j - 1], splineZ[j] - splineZ[j - 1],
                            head ? 6f : 3f, argb);
                }
            }
        }
    }

    /** Two strokes behind the tip, 25 degrees either side of the direction (dx, dz in chunk units). */
    private static void chevron(RenderContext ctx, float ex, float y, float ez, float dx, float dz, float size,
                                int argb) {
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len < 1e-4f) {
            return;
        }
        dx /= len;
        dz /= len;
        float c = 0.906f; // cos 25 degrees
        float sn = 0.423f;
        WorldRenderKit.line(ctx.lines, ctx.pose, ex, y, ez, ex - size * (dx * c - dz * sn), y,
                ez - size * (dz * c + dx * sn), argb);
        WorldRenderKit.line(ctx.lines, ctx.pose, ex, y, ez, ex - size * (dx * c + dz * sn), y,
                ez - size * (dz * c - dx * sn), argb);
    }

    /**
     * Render thread: kind (and speed) label at each segment's start; when the camera is within {@link #NEAR_BLOCKS}
     * of the trail or looks straight at a stretch of it, how long ago the nearest / looked-at stretch was made.
     * {@code look} is the camera's look vector (read, not allocated). Allocates nothing.
     */
    void renderLabels(RenderContext ctx, float y, long nowMs, Vector3f look) {
        List<Shown> list = segments;
        for (int i = 0, n = list.size(); i < n; i++) {
            Shown shown = list.get(i);
            TrailClassifier.Segment s = shown.segment();
            float[] px = s.spineX();
            float[] pz = s.spineZ();
            if (px.length == 0) {
                continue;
            }
            int color = TrailClassifier.color(s.kind());
            String label = shown.title();
            org.joml.Matrix4f m = ctx.kit.beginLabel(ctx.pose, wx(ctx, px[0]), y + 1.5f, wz(ctx, pz[0]));
            WorldRenderKit.text(ctx.font, ctx.buffers, m, label, -ctx.font.width(label) / 2f, color);
            int at = ageLabelPoint(ctx, px, pz, y, look);
            if (at >= 0) {
                String age = TrailRecency.ageLabel(s.spineWallMs()[at], nowMs);
                org.joml.Matrix4f a = ctx.kit.beginLabel(ctx.pose, wx(ctx, px[at]), y + (at == 0 ? 0.9f : 1.2f),
                        wz(ctx, pz[at]));
                WorldRenderKit.text(ctx.font, ctx.buffers, a, age, -ctx.font.width(age) / 2f,
                        TrailRecency.withAlpha(color, 0.85f));
            }
        }
    }

    /** The spine point being looked at, else the nearest one within reach, else -1. */
    private static int ageLabelPoint(RenderContext ctx, float[] px, float[] pz, float y, Vector3f look) {
        int nearest = -1;
        double nearestD2 = NEAR_BLOCKS * NEAR_BLOCKS;
        int hovered = -1;
        float bestCos = HOVER_COS;
        for (int k = 0; k < px.length; k++) {
            float x = wx(ctx, px[k]);
            float z = wz(ctx, pz[k]);
            double d2 = (double) x * x + (double) z * z;
            if (d2 < nearestD2) {
                nearestD2 = d2;
                nearest = k;
            }
            if (look != null) {
                float len = (float) Math.sqrt(d2 + (double) y * y);
                if (len > 1e-3f) {
                    float cos = (x * look.x() + y * look.y() + z * look.z()) / len;
                    if (cos > bestCos) {
                        bestCos = cos;
                        hovered = k;
                    }
                }
            }
        }
        return hovered >= 0 ? hovered : nearest;
    }

    private static float wx(RenderContext ctx, float chunkX) {
        return ctx.rx(chunkX * 16.0 + 8.0);
    }

    private static float wz(RenderContext ctx, float chunkZ) {
        return ctx.rz(chunkZ * 16.0 + 8.0);
    }
}
