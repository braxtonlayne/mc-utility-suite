package com.utilitysuite.world.overlays;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

/**
 * Per-entity tag line cache for the radar ("❤ 20  ⛨ 15  12m"). The string (and its pixel width) is rebuilt only when
 * the rounded health, armour or distance changes, so a still scene allocates nothing per frame. Entries not used for
 * {@link #STALE_FRAMES} frames are swept by {@link #sweep()} (called rarely, from the tick).
 */
public final class NametagCache {

    public static final int STALE_FRAMES = 400;

    /** Builds the text (allocates; only called on change). */
    @FunctionalInterface
    public interface Formatter {
        String format(int health, int armor, int dist);
    }

    /** Measures the text in pixels (font width in game, a stub in tests). */
    @FunctionalInterface
    public interface Measure {
        int width(String s);
    }

    public static final class Entry {
        int health = Integer.MIN_VALUE;
        int armor;
        int dist;
        long lastFrame;
        public String text;
        public int width;
    }

    public static final Formatter DEFAULT_FORMAT = (h, a, d) -> "❤ " + h + "  ⛨ " + a + "  " + d + "m";

    private final Int2ObjectOpenHashMap<Entry> entries = new Int2ObjectOpenHashMap<>();
    private final Formatter formatter;
    private final Measure measure;
    private long frame;
    private int rebuilds;

    public NametagCache(Formatter formatter, Measure measure) {
        this.formatter = formatter;
        this.measure = measure;
    }

    /** Marks the start of a frame (for staleness). */
    public void beginFrame() {
        frame++;
    }

    /** Returns the cached entry for this entity, rebuilding its text only when a rounded value changed. */
    public Entry get(int entityId, int health, int armor, int dist) {
        Entry e = entries.get(entityId);
        if (e == null) {
            e = new Entry();
            entries.put(entityId, e);
        }
        if (e.health != health || e.armor != armor || e.dist != dist) {
            e.health = health;
            e.armor = armor;
            e.dist = dist;
            e.text = formatter.format(health, armor, dist);
            e.width = measure.width(e.text);
            rebuilds++;
        }
        e.lastFrame = frame;
        return e;
    }

    /** Drops entries not used recently. */
    public void sweep() {
        ObjectIterator<it.unimi.dsi.fastutil.ints.Int2ObjectMap.Entry<Entry>> it = entries.int2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            if (frame - it.next().getValue().lastFrame > STALE_FRAMES) {
                it.remove();
            }
        }
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    /** Text rebuilds since construction (tests). */
    public int rebuilds() {
        return rebuilds;
    }
}
