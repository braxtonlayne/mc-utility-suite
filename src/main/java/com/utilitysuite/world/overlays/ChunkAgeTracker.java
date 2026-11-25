package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Exploration trail heuristic: freshly generated chunks still have liquids settling, so the server sends flowing-fluid
 * block updates for them right after they arrive; chunks that existed before are already settled. A chunk with at
 * least {@code minUpdates} fluid updates within {@code windowTicks} of loading is classified NEW, one that stayed quiet
 * for the window (and contained fluids) is classified OLD. Also records palette/block-entity forensic signals and
 * supports compact binary persistence. Pure logic; client thread.
 */
public final class ChunkAgeTracker {

    public static final byte UNKNOWN = 0;
    public static final byte NEW = 1;
    public static final byte OLD = 2;

    public static final byte FLAG_NONE = 0;
    public static final byte PALETTE_NEW = 1 << 0;
    public static final byte PALETTE_OLD = 1 << 1;
    public static final byte SPAWNER_ACTIVE = 1 << 2;
    public static final byte OXIDIZED_COPPER = 1 << 3;

    private static final int MAGIC = 0x5554524C; // "UTRL"
    private static final int VERSION = 1;

    private final Long2LongOpenHashMap loadTick = new Long2LongOpenHashMap();
    private final Long2ByteOpenHashMap updates = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap state = new Long2ByteOpenHashMap();
    private final Long2ByteOpenHashMap flags = new Long2ByteOpenHashMap();
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

    /**
     * Records palette and block-entity forensic signals for a chunk.
     *
     * @param paletteLooksOld true if the section palette suggests an older/modified chunk
     * @param modifiedSignals true if explicit modification signals (e.g. active spawner, oxidized copper) were found
     */
    public void onPaletteCheck(int chunkX, int chunkZ, boolean paletteLooksOld, boolean modifiedSignals) {
        onPaletteCheck(chunkX, chunkZ, paletteLooksOld, modifiedSignals, FLAG_NONE);
    }

    /** Records palette and block-entity forensic signals with explicit forensic flags. */
    public void onPaletteCheck(int chunkX, int chunkZ, boolean paletteLooksOld, boolean modifiedSignals,
                               byte forensicFlags) {
        long k = BlockPosPacking.chunk(chunkX, chunkZ);
        byte f = (byte) (flags.get(k) | forensicFlags);
        if (modifiedSignals || paletteLooksOld) {
            f |= PALETTE_OLD;
            if (state.get(k) == UNKNOWN || modifiedSignals) {
                state.put(k, OLD);
            }
        } else {
            f |= PALETTE_NEW;
        }
        flags.put(k, f);
    }

    public byte stateOf(int chunkX, int chunkZ) {
        return state.get(BlockPosPacking.chunk(chunkX, chunkZ));
    }

    public byte flagsOf(int chunkX, int chunkZ) {
        return flags.get(BlockPosPacking.chunk(chunkX, chunkZ));
    }

    Long2ByteOpenHashMap stateMap() {
        return state;
    }

    public void write(DataOutputStream out) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeInt(state.size());
        var it = ((Long2ByteMap.FastEntrySet) state.long2ByteEntrySet()).fastIterator();
        while (it.hasNext()) {
            Long2ByteMap.Entry e = it.next();
            long k = e.getLongKey();
            out.writeLong(k);
            out.writeByte(e.getByteValue());
            out.writeByte(flags.get(k));
        }
    }

    public void read(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != MAGIC) {
            throw new IOException("Invalid ChunkAgeTracker magic: 0x" + Integer.toHexString(magic));
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("Unsupported ChunkAgeTracker version: " + version);
        }
        int count = in.readInt();
        if (count < 0 || count > 10_000_000) {
            throw new IOException("Invalid ChunkAgeTracker entry count: " + count);
        }
        state.clear();
        flags.clear();
        state.ensureCapacity(count);
        flags.ensureCapacity(count);
        for (int i = 0; i < count; i++) {
            long k = in.readLong();
            byte s = in.readByte();
            byte f = in.readByte();
            state.put(k, s);
            if (f != FLAG_NONE) {
                flags.put(k, f);
            }
        }
    }

    public void clear() {
        loadTick.clear();
        updates.clear();
        state.clear();
        flags.clear();
        hasFluid.clear();
    }

    public int size() {
        return state.size();
    }
}
