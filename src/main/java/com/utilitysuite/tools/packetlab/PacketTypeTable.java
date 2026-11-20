package com.utilitysuite.tools.packetlab;

import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Interns packet types (any identity-stable key, e.g. vanilla {@code PacketType} constants) to small int ids so the ring
 * buffer stores primitives. Lookup of a known type allocates nothing; a new type is added once (map entry + name).
 * Writes come from the single Netty writer thread; {@link #name} may be called from any thread.
 */
public final class PacketTypeTable {

    private final Reference2IntOpenHashMap<Object> ids = new Reference2IntOpenHashMap<>();
    private final Function<Object, String> namer;
    private volatile String[] names = new String[64];
    private volatile int count;

    public PacketTypeTable(Function<Object, String> namer) {
        this.namer = namer;
        ids.defaultReturnValue(-1);
    }

    /** Writer thread only. */
    public int idOf(Object type) {
        int id = ids.getInt(type);
        if (id >= 0) {
            return id;
        }
        id = count;
        String[] n = names;
        if (id >= n.length) {
            n = Arrays.copyOf(n, n.length * 2);
        }
        n[id] = namer.apply(type);
        names = n;
        ids.put(type, id);
        count = id + 1;
        return id;
    }

    public String name(int id) {
        String[] n = names;
        return id >= 0 && id < count && id < n.length && n[id] != null ? n[id] : "?";
    }

    public int size() {
        return count;
    }
}
