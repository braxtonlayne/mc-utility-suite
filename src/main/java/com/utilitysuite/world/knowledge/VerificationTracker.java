package com.utilitysuite.world.knowledge;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

/**
 * Checks predicted ore positions near the player against the real (loaded) blocks, a few per tick: a non-ore block is
 * recorded as a verified miss (which downranks/suppresses its cluster), the ore is recorded as observed. Positions in
 * unloaded chunks are skipped. Client thread; allocation-free per step (the refill reuses its buffers).
 */
public final class VerificationTracker {

    /** Real block lookup. Returns the registry id, or null when the chunk is not loaded. */
    @FunctionalInterface
    public interface BlockLookup {
        String blockIdAt(int x, int y, int z);
    }

    private final WorldKnowledgeService service;
    private final LongArrayList queue = new LongArrayList();
    private final ObjectArrayList<String> ids = new ObjectArrayList<>();
    private final ObjectArrayList<String> altIds = new ObjectArrayList<>();
    private int cursor;
    private String queueDimension;
    private final PredictionSource.Visitor collector = this::collect;
    private int collectLimit;

    public VerificationTracker(WorldKnowledgeService service) {
        this.service = service;
    }

    private void collect(long pos, String id, String alt, float conf, long cluster) {
        if (queue.size() < collectLimit) {
            queue.add(pos);
            ids.add(id);
            altIds.add(alt);
        }
    }

    /** Re-collects the predictions within {@code range} blocks to verify (call every second or so). */
    public void refill(PredictionSource source, String dimension, long origin, int range, int limit) {
        queue.clear();
        ids.clear();
        altIds.clear();
        cursor = 0;
        queueDimension = dimension;
        collectLimit = limit;
        source.forEachPrediction(dimension, origin, range, collector);
    }

    public int pending() {
        return queue.size() - cursor;
    }

    /** Verifies up to {@code budget} positions. Returns the number of new misses. */
    public int step(BlockLookup lookup, int budget) {
        if (queueDimension == null) {
            return 0;
        }
        KnowledgeLedger ledger = service.ledger();
        int misses = 0;
        int end = Math.min(queue.size(), cursor + budget);
        for (; cursor < end; cursor++) {
            long pos = queue.getLong(cursor);
            if (ledger.isMiss(queueDimension, pos) || ledger.observedAt(queueDimension, pos) != null) {
                continue;
            }
            String actual = lookup.blockIdAt(com.utilitysuite.world.BlockPosPacking.x(pos),
                    com.utilitysuite.world.BlockPosPacking.y(pos), com.utilitysuite.world.BlockPosPacking.z(pos));
            if (actual == null) {
                continue; // not loaded / not known: nothing verified
            }
            String expected = ids.get(cursor);
            String alt = altIds.get(cursor);
            if (actual.equals(expected) || actual.equals(alt)) {
                service.recordObserved(queueDimension, pos, actual);
            } else {
                service.recordVerifiedMiss(queueDimension, pos, actual);
                misses++;
            }
        }
        return misses;
    }
}
