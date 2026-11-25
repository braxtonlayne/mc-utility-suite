package com.utilitysuite.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Bounded undo history of setting changes (default 10 steps), engine free. Each entry remembers the profile, module,
 * key and the value before the change (null = the key was unset). Undo writes the old value back into the given
 * profile JSON. A change that sets a key to the value it already had is not recorded. Ring buffer: the oldest entry
 * drops when full. Client thread only.
 */
public final class ConfigUndoStack {

    /** One recorded change. {@code before}/{@code after} are deep copies (null = unset). */
    public record Entry(String profileId, String moduleId, String key, JsonElement before, JsonElement after) {
    }

    private final Entry[] ring;
    private int top; // index of the next slot
    private int size;

    public ConfigUndoStack(int capacity) {
        ring = new Entry[Math.max(1, capacity)];
    }

    public int capacity() {
        return ring.length;
    }

    public int size() {
        return size;
    }

    public void clear() {
        java.util.Arrays.fill(ring, null);
        size = 0;
        top = 0;
    }

    /** Records a change (copies the values). Returns false for a no-op change. */
    public boolean record(String profileId, String moduleId, String key, JsonElement before, JsonElement after) {
        if (before == null ? after == null : before.equals(after)) {
            return false;
        }
        ring[top] = new Entry(profileId, moduleId, key, before == null ? null : before.deepCopy(),
                after == null ? null : after.deepCopy());
        top = (top + 1) % ring.length;
        size = Math.min(size + 1, ring.length);
        return true;
    }

    /** The most recent entry without removing it, or null. */
    public Entry peek() {
        return size == 0 ? null : ring[(top - 1 + ring.length) % ring.length];
    }

    /** Removes and returns the most recent entry, or null when empty. */
    public Entry pop() {
        if (size == 0) {
            return null;
        }
        top = (top - 1 + ring.length) % ring.length;
        Entry e = ring[top];
        ring[top] = null;
        size--;
        return e;
    }

    /** Writes an entry's old value back into a profile root ({@code root.<module>.<key>}). */
    public static void restore(Entry e, JsonObject root) {
        JsonObject m = root.getAsJsonObject(e.moduleId());
        if (m == null) {
            m = new JsonObject();
            root.add(e.moduleId(), m);
        }
        if (e.before() == null) {
            m.remove(e.key());
        } else {
            m.add(e.key(), e.before().deepCopy());
        }
    }
}
