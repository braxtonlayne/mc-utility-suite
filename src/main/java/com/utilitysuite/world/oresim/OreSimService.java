package com.utilitysuite.world.oresim;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;

/**
 * Runs {@link OreSimulator} for chunks around the player on a background executor and keeps the published immutable
 * {@link ChunkOreSnapshot}s. Game-agnostic: the caller supplies terrain/biome oracles captured on the client thread.
 * <p>
 * Threading: {@link #configure}, {@link #request}, {@link #drainCompleted} and {@link #evictOutside} are client-thread
 * calls; {@link #get} and {@link #snapshotsNear} may be called from any thread (guarded by the cache lock).
 */
public final class OreSimService {

    /**
     * What to simulate. {@code specs} are the selected features of the current dimension.
     *
     * @param uncertainWorld the generator profile is flagged as old-version / custom generation
     */
    public record Config(long seed, String dimension, OreSpec[] specs, int minY, int height, boolean uncertainWorld) {
    }

    /** Confidence for a fully exact prediction (still "ghost" data: never shown as confirmed). */
    public static final float EXACT_CONFIDENCE = 0.9f;
    public static final float UNCERTAIN_FACTOR = 0.3f;

    private final Executor executor;
    private final ThreadLocal<OreSimulator> simulators = ThreadLocal.withInitial(OreSimulator::new);
    private final Object lock = new Object();
    private final Long2ObjectOpenHashMap<ChunkOreSnapshot> cache = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet pending = new LongOpenHashSet();
    private final ConcurrentLinkedQueue<ChunkOreSnapshot> completed = new ConcurrentLinkedQueue<>();
    private final LongArrayList evictScratch = new LongArrayList();
    private volatile Config config;
    private volatile int generation;

    public OreSimService(Executor executor) {
        this.executor = executor;
    }

    public Config config() {
        return config;
    }

    public int generation() {
        return generation;
    }

    /** Replaces the configuration (null disables) and drops every snapshot. */
    public void configure(Config next) {
        synchronized (lock) {
            config = next;
            generation++;
            cache.clear();
        }
        pending.clear();
        completed.clear();
    }

    public boolean isConfigured() {
        return config != null;
    }

    /** True when a snapshot exists or is being computed. */
    public boolean isKnownOrPending(int chunkX, int chunkZ) {
        long key = BlockPosPacking.chunk(chunkX, chunkZ);
        if (pending.contains(key)) {
            return true;
        }
        synchronized (lock) {
            return cache.containsKey(key);
        }
    }

    /** Queues a simulation. Returns false when not configured or already known/pending. */
    public boolean request(int chunkX, int chunkZ, TerrainOracle terrain, BiomeOracle biomes, boolean uncertainChunk) {
        Config c = config;
        if (c == null || c.specs().length == 0 || isKnownOrPending(chunkX, chunkZ)) {
            return false;
        }
        pending.add(BlockPosPacking.chunk(chunkX, chunkZ));
        int gen = generation;
        executor.execute(() -> {
            // Work queued before a reconfigure/disable is obsolete: skip it instead of burning the shared world
            // thread (each chunk samples the generator's noise for its surface heights).
            if (gen != generation) {
                return;
            }
            completed.add(compute(c, gen, chunkX, chunkZ, terrain, biomes, uncertainChunk));
        });
        return true;
    }

    /** Synchronous simulation (worker thread or tests). */
    public ChunkOreSnapshot compute(Config c, int gen, int chunkX, int chunkZ, TerrainOracle terrain,
                                    BiomeOracle biomes, boolean uncertainChunk) {
        boolean uncertain = uncertainChunk || c.uncertainWorld();
        ChunkOreSnapshot.Builder b = new ChunkOreSnapshot.Builder(chunkX, chunkZ, gen, uncertain, c.specs());
        float base = EXACT_CONFIDENCE * (uncertain ? UNCERTAIN_FACTOR : 1f);
        simulators.get().simulateChunk(c.seed(), chunkX, chunkZ, c.specs(), c.minY(), c.height(), terrain, biomes,
                base, b);
        return b.build();
    }

    /** Moves up to {@code max} finished snapshots into the cache. Returns how many were published. */
    public int drainCompleted(int max) {
        int n = 0;
        int gen = generation;
        while (n < max) {
            ChunkOreSnapshot s = completed.poll();
            if (s == null) {
                break;
            }
            long key = BlockPosPacking.chunk(s.chunkX, s.chunkZ);
            pending.remove(key);
            if (s.generation != gen) {
                continue;
            }
            synchronized (lock) {
                cache.put(key, s);
            }
            n++;
        }
        return n;
    }

    public ChunkOreSnapshot get(int chunkX, int chunkZ) {
        synchronized (lock) {
            return cache.get(BlockPosPacking.chunk(chunkX, chunkZ));
        }
    }

    /** Drops snapshots further than {@code radius} chunks (Chebyshev) from the centre. Client thread. */
    public void evictOutside(int centerX, int centerZ, int radius) {
        synchronized (lock) {
            evictScratch.clear();
            for (LongIterator it = cache.keySet().iterator(); it.hasNext(); ) {
                long k = it.nextLong();
                if (Math.abs(BlockPosPacking.chunkX(k) - centerX) > radius
                        || Math.abs(BlockPosPacking.chunkZ(k) - centerZ) > radius) {
                    evictScratch.add(k);
                }
            }
            for (int i = 0; i < evictScratch.size(); i++) {
                cache.remove(evictScratch.getLong(i));
            }
        }
    }

    /** Copies the snapshots within {@code radius} chunks into {@code out} (any thread). */
    public void snapshotsNear(int centerX, int centerZ, int radius, List<ChunkOreSnapshot> out) {
        synchronized (lock) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    ChunkOreSnapshot s = cache.get(BlockPosPacking.chunk(centerX + dx, centerZ + dz));
                    if (s != null) {
                        out.add(s);
                    }
                }
            }
        }
    }

    public int cachedCount() {
        synchronized (lock) {
            return cache.size();
        }
    }
}
