package com.utilitysuite.world.oresim;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;

/**
 * Biomes the client has actually received: the noise biomes (quart resolution, 4 x 4 x quartsY per chunk) of chunks
 * that were loaded, kept in a bounded access-ordered cache so they stay usable after the chunk unloads. Lookups apply
 * vanilla's {@code BiomeManager} zoom across chunk borders, so a block's answer is exact whenever the quarts it can
 * select (its own and the neighbours toward +x/+y/+z) are cached. This is the fallback when the world's seed is not
 * known or the client cannot rebuild the generator, and the first layer otherwise (received data beats a rebuilt
 * guess on servers with custom biomes).
 * <p>
 * Engine-free: the client thread {@link #put}s id arrays it read from loaded chunks; the simulation thread reads.
 * All access is synchronized (uncontended in practice).
 */
public final class ObservedBiomeCache implements BiomeOracle {

    private final long zoomSeed;
    private final int minQy;
    private final int quartsY;
    private final int capacity;
    private final Long2ObjectLinkedOpenHashMap<String[]> chunks;

    /**
     * @param zoomSeed       {@code BiomeManager.obfuscateSeed(seed)}; the client receives it from the server in the login
     *                       packet even when the seed itself is unknown ({@code BiomeManager.biomeZoomSeed})
     * @param minQy          lowest quart Y of the dimension
     * @param quartsY        quart layers
     * @param capacityChunks chunk columns kept (each about quartsY * 16 references)
     */
    public ObservedBiomeCache(long zoomSeed, int minQy, int quartsY, int capacityChunks) {
        this.zoomSeed = zoomSeed;
        this.minQy = minQy;
        this.quartsY = quartsY;
        this.capacity = Math.max(1, capacityChunks);
        this.chunks = new Long2ObjectLinkedOpenHashMap<>(this.capacity + 1);
    }

    public long zoomSeed() {
        return zoomSeed;
    }

    public static int index(int dqx, int dqz, int dqy, int quartsY) {
        return (dqx * 4 + dqz) * quartsY + dqy;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** Stores one chunk's quart biome ids ({@link #index} layout; nulls allowed for unknown quarts). */
    public synchronized void put(int chunkX, int chunkZ, String[] quarts) {
        if (quarts.length != 16 * quartsY) {
            throw new IllegalArgumentException("expected 4x4x" + quartsY + " quarts");
        }
        long k = key(chunkX, chunkZ);
        if (!chunks.containsKey(k) && chunks.size() >= capacity) {
            chunks.removeFirst();
        }
        chunks.putAndMoveToLast(k, quarts);
    }

    public synchronized boolean contains(int chunkX, int chunkZ) {
        return chunks.containsKey(key(chunkX, chunkZ));
    }

    public synchronized int size() {
        return chunks.size();
    }

    /** Biome id of a quart, or null when its chunk was never observed. */
    public synchronized String quartBiome(int qx, int qy, int qz) {
        String[] arr = chunks.get(key(qx >> 2, qz >> 2));
        if (arr == null) {
            return null;
        }
        int dy = Math.max(0, Math.min(quartsY - 1, qy - minQy));
        return arr[index(qx & 3, qz & 3, dy, quartsY)];
    }

    @Override
    public String biomeAt(int x, int y, int z) {
        long q = QuartBiomeOracle.selectQuart(zoomSeed, x, y, z);
        return quartBiome((int) (q >> 42), (int) ((q << 22) >> 43), (int) ((q << 43) >> 43));
    }

    @Override
    public byte allows(OreSpec spec, int x, int y, int z) {
        if (spec.biomesAllowed() == null && spec.biomesDenied().isEmpty()) {
            return YES;
        }
        return BiomeOracle.answer(spec, biomeAt(x, y, z));
    }
}
