package com.utilitysuite.world.seed;

import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldExecutor;
import com.utilitysuite.world.overlays.HighlightIndex;
import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatMaps;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Off-thread seed-diff service: consumes immutable chunk-section occupancy snapshots from the client thread, evaluates
 * {@link TerrainBaseline} on {@link WorldExecutor}, and publishes flagged section confidences back to a client-thread
 * {@link Long2FloatOpenHashMap} keyed by {@link HighlightIndex#sectionKey(int, int, int)}.
 */
public final class SeedDiffService {

    /** Immutable snapshot of one 16x16x16 section captured on the client thread. */
    public record SectionSnapshot(long seed, int cx, int sy, int cz, int generation,
                                  long[] solidMask64, int[] surfaceHeights256) {
    }

    /** Immutable diff result published from the worker back to the client thread. */
    public record SectionDiff(long sectionKey, int cx, int sy, int cz, int generation,
                              float confidence, int flags, long[] hints) {
    }

    private final Executor executor;
    private final ThreadLocal<TerrainBaseline> baselines = ThreadLocal.withInitial(TerrainBaseline::new);
    private final ConcurrentLinkedQueue<SectionDiff> completed = new ConcurrentLinkedQueue<>();

    // Client-thread state.
    private final Long2FloatOpenHashMap confidenceBySection = new Long2FloatOpenHashMap();
    private final Long2IntOpenHashMap flagsBySection = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<long[]> hintsBySection = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet pendingSections = new LongOpenHashSet();
    private final LongOpenHashSet scannedSections = new LongOpenHashSet();
    private final LongArrayList evictScratch = new LongArrayList();
    private volatile int generation;

    // Flat render list (client thread only, zero allocation in renderLines).
    private boolean renderDirty;
    private long[] renderKeys = new long[64];
    private float[] renderConfidence = new float[64];
    private int[] renderFlags = new int[64];
    private int renderCount;

    public SeedDiffService() {
        this(WorldExecutor.get());
    }

    public SeedDiffService(Executor executor) {
        this.executor = executor;
        confidenceBySection.defaultReturnValue(0f);
    }

    public int generation() {
        return generation;
    }

    /** True if the section is already scanned or currently queued on the executor. Client thread. */
    public boolean isKnownOrPending(int cx, int sy, int cz) {
        long key = HighlightIndex.sectionKey(cx, sy, cz);
        return pendingSections.contains(key) || scannedSections.contains(key);
    }

    /**
     * Submits an immutable section snapshot to run on {@link WorldExecutor}. Returns {@code false} if already queued or
     * scanned for the current generation. Client thread.
     */
    public boolean submit(long seed, int cx, int sy, int cz, long[] immutableMask64, int[] immutableHeights256) {
        long key = HighlightIndex.sectionKey(cx, sy, cz);
        if (pendingSections.contains(key) || scannedSections.contains(key)) {
            return false;
        }
        pendingSections.add(key);
        int gen = generation;
        SectionSnapshot snapshot = new SectionSnapshot(seed, cx, sy, cz, gen, immutableMask64, immutableHeights256);
        executor.execute(() -> {
            if (snapshot.generation() != generation) {
                return;
            }
            TerrainBaseline.DiffResult res = baselines.get().diffSection(
                    snapshot.seed(), snapshot.cx(), snapshot.sy(), snapshot.cz(),
                    snapshot.solidMask64(), snapshot.surfaceHeights256());
            completed.add(new SectionDiff(key, cx, sy, cz, snapshot.generation(),
                    res.score(), res.flags(), res.hints()));
        });
        return true;
    }

    /** Drains up to {@code max} completed diffs from the worker queue into the client-thread maps. */
    public int drainCompleted(int max) {
        int drained = 0;
        int gen = generation;
        while (drained < max) {
            SectionDiff diff = completed.poll();
            if (diff == null) {
                break;
            }
            if (diff.generation() != gen) {
                continue;
            }
            long key = diff.sectionKey();
            pendingSections.remove(key);
            scannedSections.add(key);
            if (diff.confidence() > 0f && diff.flags() != 0) {
                confidenceBySection.put(key, diff.confidence());
                flagsBySection.put(key, diff.flags());
                hintsBySection.put(key, diff.hints());
                renderDirty = true;
            }
            drained++;
        }
        if (renderDirty) {
            rebuildRenderList();
        }
        return drained;
    }

    /** Drops all cached and pending sections (on seed/dimension change or disable). Client thread. */
    public void clear() {
        generation++;
        pendingSections.clear();
        scannedSections.clear();
        completed.clear();
        confidenceBySection.clear();
        flagsBySection.clear();
        hintsBySection.clear();
        renderCount = 0;
        renderDirty = false;
    }

    /** Evicts sections further than {@code radiusChunks} (Chebyshev) from {@code (centerCx, centerCz)}. Client thread. */
    public void evictOutside(int centerCx, int centerCz, int radiusChunks) {
        evictScratch.clear();
        LongIterator it = scannedSections.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int cx = BlockPosPacking.x(key);
            int cz = BlockPosPacking.z(key);
            if (Math.abs(cx - centerCx) > radiusChunks || Math.abs(cz - centerCz) > radiusChunks) {
                evictScratch.add(key);
            }
        }
        for (int i = 0; i < evictScratch.size(); i++) {
            long key = evictScratch.getLong(i);
            scannedSections.remove(key);
            if (confidenceBySection.remove(key) != 0f) {
                flagsBySection.remove(key);
                hintsBySection.remove(key);
                renderDirty = true;
            }
        }
        if (renderDirty) {
            rebuildRenderList();
        }
    }

    public float confidence(long sectionKey) {
        return confidenceBySection.get(sectionKey);
    }

    public int flags(long sectionKey) {
        return flagsBySection.get(sectionKey);
    }

    public long[] hints(long sectionKey) {
        return hintsBySection.get(sectionKey);
    }

    public int flaggedCount() {
        return confidenceBySection.size();
    }

    public Long2FloatOpenHashMap confidenceMap() {
        return confidenceBySection;
    }

    // Flat render list accessors (client thread, zero allocation).
    public int renderCount() {
        return renderCount;
    }

    public long renderKey(int index) {
        return renderKeys[index];
    }

    public float renderConfidence(int index) {
        return renderConfidence[index];
    }

    public int renderFlags(int index) {
        return renderFlags[index];
    }

    private void rebuildRenderList() {
        renderDirty = false;
        int n = confidenceBySection.size();
        if (renderKeys.length < n) {
            int cap = Math.max(n, renderKeys.length * 2);
            renderKeys = new long[cap];
            renderConfidence = new float[cap];
            renderFlags = new int[cap];
        }
        int i = 0;
        for (Long2FloatMap.Entry e : Long2FloatMaps.fastIterable(confidenceBySection)) {
            long key = e.getLongKey();
            renderKeys[i] = key;
            renderConfidence[i] = e.getFloatValue();
            renderFlags[i] = flagsBySection.get(key);
            i++;
        }
        renderCount = i;
    }
}
