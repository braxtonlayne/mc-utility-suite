package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Exploration trail heuristic: freshly generated chunks still have liquids settling, so the server sends flowing-fluid
 * block updates for them right after they arrive; chunks that existed before are already settled. A chunk with at
 * least {@code minUpdates} fluid updates within {@code windowTicks} of loading is classified NEW, one that stayed quiet
 * for the window (and contained fluids) is classified OLD. Pure logic; client thread.
 */
public final class ChunkAgeTracker {

    public static final byte UNKNOWN = 0;
    public static final byte NEW = 1;
    public static final byte OLD = 2;

    private final Long2LongOpenHashMap loadTick = new Long2LongOpenHashMap();
    private final Long2ByteOpenHashMap updates = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap state = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap hasFluid = new Long2ByteOpenHashMap();
    private int windowTicks = 60;
    private int minUpdates = 2;

    public void configure(int windowTicks, int minUpdates) {
        this.windowTicks = Math.max(1, windowTicks);
        this.minUpdates = Math.max(1, minUpdates);
    }

    public void onChunkLoad(int chunkX, int chunkZ, long tick, boolean containsFluid) {
        long k = BlockPosPacking.chunk(chunkX, chunkZ);
        loadTick.put(k, tick);
        updates.remove(k);
        hasFluid.put(k, containsFluid ? (byte) 1 : 0);
        if (!state.containsKey(k)) {
            state.put(k, UNKNOWN);
        }
    }

    public void onChunkUnload(int chunkX, int chunkZ) {
        long k = BlockPosPacking.chunk(chunkX, chunkZ);
        loadTick.remove(k);
        updates.remove(k);
        hasFluid.remove(k);
        // the classification is kept so the trail stays visible after the chunk unloads
    }

    /** A flowing (non-source) fluid state arrived for a block in the chunk. */
    public void onFluidUpdate(int chunkX, int chunkZ, long tick) {
        long k = BlockPosPacking.chunk(chunkX, chunkZ);
        if (!loadTick.containsKey(k)) {
            return;
        }
        if (tick - loadTick.get(k) > windowTicks) {
            return;
        }
        byte n = (byte) Math.min(127, updates.get(k) + 1);
        updates.put(k, n);
        if (n >= minUpdates) {
            state.put(k, NEW);
        }
    }

    /** Settles chunks whose window passed without updates. Call once per second; cost O(loaded chunks). */
    public void settle(long tick) {
        for (Long2LongOpenHashMap.Entry e : loadTick.long2LongEntrySet()) {
            long k = e.getLongKey();
            if (state.get(k) == UNKNOWN && tick - e.getLongValue() > windowTicks && hasFluid.get(k) == 1) {
                state.put(k, OLD);
            }
        }
    }

    public byte stateOf(int chunkX, int chunkZ) {
        return state.get(BlockPosPacking.chunk(chunkX, chunkZ));
    }

    public void clear() {
        loadTick.clear();
        updates.clear();
        state.clear();
        hasFluid.clear();
    }

    public int size() {
        return state.size();
    }
}
