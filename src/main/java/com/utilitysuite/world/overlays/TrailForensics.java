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

import java.util.ArrayList;
import java.util.List;

/**
 * Glue for {@link TrailClassifier} inside World Radar: every couple of seconds copies the NEW trail cells around the
 * player (plus a surface water/ice probe for loaded chunks) on the client thread, classifies them on the
 * {@link WorldExecutor}, and publishes an immutable segment list. Draws each segment as a smooth spline arrow.
 */
final class TrailForensics {

    private static final int MAX_CELLS = 65 * 65;
    private static final int MIN_SEGMENT_CELLS = 6;
    private static final int PER_SEG = 6;
    private static final String[] KIND_LABELS = {"Elytra highway", "Boat run", "Nether tunnel", "Search zig-zag",
            "Travel", "Wander"};

    private final TrailClassifier classifier = new TrailClassifier();
    private final long[] keys = new long[MAX_CELLS];
    private final boolean[] water = new boolean[MAX_CELLS];
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final float[] splineX = new float[(TrailClassifier.MAX_SPINE - 1) * PER_SEG + 1];
    private final float[] splineZ = new float[(TrailClassifier.MAX_SPINE - 1) * PER_SEG + 1];
    private volatile List<TrailClassifier.Segment> segments = List.of();
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
                n++;
            }
        }
        if (n < MIN_SEGMENT_CELLS) {
            segments = List.of();
            return;
        }
        long[] k = java.util.Arrays.copyOf(keys, n);
        boolean[] w = java.util.Arrays.copyOf(water, n);
        busy = true;
        WorldExecutor.get().execute(() -> {
            try {
                segments = classifyAll(classifier, k, w, nether, pcx, pcz);
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
    static List<TrailClassifier.Segment> classifyAll(TrailClassifier c, long[] keys, boolean[] wet, boolean nether,
                                                     double viewerCx, double viewerCz) {
        int n = keys.length;
        int[] label = TrailClassifier.cluster(keys, n);
        Int2ObjectOpenHashMap<IntArrayList> groups = new Int2ObjectOpenHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(label[i], x -> new IntArrayList()).add(i);
        }
        List<TrailClassifier.Segment> out = new ArrayList<>();
        for (IntArrayList g : groups.values()) {
            int m = g.size();
            if (m < MIN_SEGMENT_CELLS) {
                continue;
            }
            int[] xs = new int[m];
            int[] zs = new int[m];
            boolean[] w = new boolean[m];
            for (int j = 0; j < m; j++) {
                int i = g.getInt(j);
                xs[j] = BlockPosPacking.chunkX(keys[i]);
                zs[j] = BlockPosPacking.chunkZ(keys[i]);
                w[j] = wet[i];
            }
            TrailClassifier.Segment s = c.classify(xs, zs, m, w, null, nether, viewerCx, viewerCz);
            if (s != null) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    /** Render thread: spline arrow per segment at the given relative height. Allocates nothing. */
    void render(RenderContext ctx, float y) {
        List<TrailClassifier.Segment> list = segments;
        for (int i = 0, n = list.size(); i < n; i++) {
            TrailClassifier.Segment s = list.get(i);
            float[] px = s.spineX();
            float[] pz = s.spineZ();
            int w = TrailClassifier.catmullRom(px, pz, px.length, PER_SEG, splineX, splineZ);
            if (w < 2) {
                continue;
            }
            int argb = TrailClassifier.color(s.kind());
            for (int j = 1; j < w; j++) {
                WorldRenderKit.line(ctx.lines, ctx.pose, wx(ctx, splineX[j - 1]), y, wz(ctx, splineZ[j - 1]),
                        wx(ctx, splineX[j]), y, wz(ctx, splineZ[j]), argb);
            }
            // Arrowhead along the last spline step.
            float ex = wx(ctx, splineX[w - 1]);
            float ez = wz(ctx, splineZ[w - 1]);
            float dx = splineX[w - 1] - splineX[w - 2];
            float dz = splineZ[w - 1] - splineZ[w - 2];
            float len = (float) Math.sqrt(dx * dx + dz * dz);
            if (len < 1e-4f) {
                continue;
            }
            dx /= len;
            dz /= len;
            float size = 6f;
            float c = 0.906f; // cos 25 degrees
            float sn = 0.423f;
            WorldRenderKit.line(ctx.lines, ctx.pose, ex, y, ez, ex - size * (dx * c - dz * sn), y,
                    ez - size * (dz * c + dx * sn), argb);
            WorldRenderKit.line(ctx.lines, ctx.pose, ex, y, ez, ex - size * (dx * c + dz * sn), y,
                    ez - size * (dz * c - dx * sn), argb);
        }
    }

    /** Render thread: kind label at each segment's start. */
    void renderLabels(RenderContext ctx, float y) {
        List<TrailClassifier.Segment> list = segments;
        for (int i = 0, n = list.size(); i < n; i++) {
            TrailClassifier.Segment s = list.get(i);
            if (s.spineX().length == 0) {
                continue;
            }
            String label = KIND_LABELS[s.kind().ordinal()];
            org.joml.Matrix4f m = ctx.kit.beginLabel(ctx.pose, wx(ctx, s.spineX()[0]), y + 1.5f,
                    wz(ctx, s.spineZ()[0]));
            WorldRenderKit.text(ctx.font, ctx.buffers, m, label, -ctx.font.width(label) / 2f,
                    TrailClassifier.color(s.kind()));
        }
    }

    private static float wx(RenderContext ctx, float chunkX) {
        return ctx.rx(chunkX * 16.0 + 8.0);
    }

    private static float wz(RenderContext ctx, float chunkZ) {
        return ctx.rz(chunkZ * 16.0 + 8.0);
    }
}
