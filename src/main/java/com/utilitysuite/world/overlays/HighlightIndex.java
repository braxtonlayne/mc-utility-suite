package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.knowledge.KnowledgeLedger;
import com.utilitysuite.world.knowledge.ObservationSource;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntMaps;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.Set;

/**
 * Blocks matched by the overlay scanner in loaded chunks, keyed by packed position, grouped per chunk section so a
 * rescan or an unload replaces whole sections. Value layout: bits 0..7 categories, bit 8 exposed-to-air, bits 16..31
 * block id index. Mutated on the client thread; a flat render list is rebuilt after changes so render callbacks iterate
 * plain arrays. Observation reads from other threads are synchronized.
 */
public final class HighlightIndex implements ObservationSource {

    public static final int EXPOSED = 1 << 8;

    private final Long2IntOpenHashMap blocks = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<LongArrayList> bySection = new Long2ObjectOpenHashMap<>();
    private final ObjectArrayList<String> idTable = new ObjectArrayList<>();
    private final Object2IntOpenHashMap<String> idIndex = new Object2IntOpenHashMap<>();
    private String dimension = "";
    private boolean renderDirty;

    // Render list (client thread only).
    private long[] renderPos = new long[256];
    private int[] renderVal = new int[256];
    private int renderCount;

    public static long sectionKey(int sx, int sy, int sz) {
        return BlockPosPacking.pack(sx, sy, sz);
    }

    public static long sectionOf(long pos) {
        return sectionKey(BlockPosPacking.x(pos) >> 4, BlockPosPacking.y(pos) >> 4, BlockPosPacking.z(pos) >> 4);
    }

    public synchronized void setDimension(String dim) {
        if (!dim.equals(dimension)) {
            clear();
            dimension = dim;
        }
    }

    public synchronized String dimension() {
        return dimension;
    }

    public synchronized int idIndex(String id) {
        int i = idIndex.getOrDefault(id, -1);
        if (i < 0) {
            i = idTable.size();
            idTable.add(id);
            idIndex.put(id, i);
        }
        return i;
    }

    public synchronized String idAt(int index) {
        return index >= 0 && index < idTable.size() ? idTable.get(index) : null;
    }

    public static int value(int categories, boolean exposed, int idIndex) {
        return (categories & 0xFF) | (exposed ? EXPOSED : 0) | (idIndex & 0xFFFF) << 16;
    }

    public static int categories(int value) {
        return value & 0xFF;
    }

    public static boolean exposed(int value) {
        return (value & EXPOSED) != 0;
    }

    public static int idIndexOf(int value) {
        return value >>> 16;
    }

    /** Replaces everything known about a section with the given matches (the list is copied). */
    public synchronized void replaceSection(long sectionKey, LongArrayList positions, it.unimi.dsi.fastutil.ints.IntArrayList values) {
        removeSectionInternal(sectionKey);
        if (positions.isEmpty()) {
            return;
        }
        LongArrayList members = new LongArrayList(positions.size());
        for (int i = 0; i < positions.size(); i++) {
            long p = positions.getLong(i);
            blocks.put(p, values.getInt(i));
            members.add(p);
        }
        bySection.put(sectionKey, members);
        renderDirty = true;
    }

    public synchronized void removeSection(long sectionKey) {
        removeSectionInternal(sectionKey);
    }

    private void removeSectionInternal(long sectionKey) {
        LongArrayList members = bySection.remove(sectionKey);
        if (members != null) {
            for (int i = 0; i < members.size(); i++) {
                blocks.remove(members.getLong(i));
            }
            renderDirty = true;
        }
    }

    /** Single block update: sets (value != 0) or clears (value == 0) one position. */
    public synchronized void setBlock(long pos, int value) {
        long key = sectionOf(pos);
        if (value == 0) {
            if (blocks.remove(pos) != blocks.defaultReturnValue()) {
                LongArrayList members = bySection.get(key);
                if (members != null) {
                    members.rem(pos);
                }
                renderDirty = true;
            }
            return;
        }
        int prev = blocks.put(pos, value);
        if (prev == blocks.defaultReturnValue()) {
            bySection.computeIfAbsent(key, k -> new LongArrayList()).add(pos);
        }
        if (prev != value) {
            renderDirty = true;
        }
    }

    public synchronized int get(long pos) {
        return blocks.get(pos);
    }

    public synchronized int size() {
        return blocks.size();
    }

    /**
     * Drops the category bits outside {@code keep} from every entry (entries left with none are removed). Used when a
     * module stops asking for a category: the other modules' matches stay, so nothing has to be rescanned. Rare (a
     * mask change), O(entries).
     */
    public synchronized void retainCategories(int keep) {
        var it = bySection.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            LongArrayList members = it.next().getValue();
            int w = 0;
            for (int i = 0, n = members.size(); i < n; i++) {
                long p = members.getLong(i);
                int v = blocks.get(p);
                int cats = categories(v) & keep;
                if (cats == 0) {
                    blocks.remove(p);
                    renderDirty = true;
                    continue;
                }
                if (cats != categories(v)) {
                    blocks.put(p, (v & ~0xFF) | cats);
                    renderDirty = true;
                }
                members.set(w++, p);
            }
            members.size(w);
            if (w == 0) {
                it.remove();
            }
        }
    }

    public synchronized void clear() {
        blocks.clear();
        bySection.clear();
        renderDirty = true;
    }

    /** Rebuilds the flat render list when the index changed. Client thread, call from the tick. */
    public synchronized void rebuildRenderListIfDirty() {
        if (!renderDirty) {
            return;
        }
        renderDirty = false;
        int n = blocks.size();
        if (renderPos.length < n) {
            int cap = Math.max(n, renderPos.length * 2);
            renderPos = new long[cap];
            renderVal = new int[cap];
        }
        int i = 0;
        for (Long2IntMap.Entry e : Long2IntMaps.fastIterable(blocks)) { // fast: reuses one entry
            renderPos[i] = e.getLongKey();
            renderVal[i] = e.getIntValue();
            i++;
        }
        renderCount = i;
    }

    public int renderCount() {
        return renderCount;
    }

    public long renderPos(int i) {
        return renderPos[i];
    }

    public int renderValue(int i) {
        return renderVal[i];
    }

    @Override
    public synchronized void forEachObserved(String dim, Set<String> blockIds, long origin, int radius,
                                             KnowledgeLedger.ObservedVisitor visitor) {
        if (!dim.equals(dimension)) {
            return;
        }
        long r2 = (long) radius * radius;
        for (Long2IntMap.Entry e : Long2IntMaps.fastIterable(blocks)) { // fast: reuses one entry
            String id = idAt(idIndexOf(e.getIntValue()));
            if (id != null && blockIds.contains(id) && BlockPosPacking.distSq(e.getLongKey(), origin) <= r2) {
                visitor.visit(e.getLongKey(), id);
            }
        }
    }
}
