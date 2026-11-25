package com.utilitysuite.jobs.planner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A saved 1.21 Crafter setup (engine free): which of the nine grid slots are disabled (slot lock) and which item each
 * enabled slot should be seeded with for the recipe. {@link #diff} compares it with what the open crafter shows and
 * returns the fixes: slot-disable toggles (vanilla only toggles an empty slot, so a wrong item is taken out first) and
 * items to put in. Slots are crafter menu slots 0..8 (row-major).
 *
 * @param name         user label
 * @param resultId     expected crafted item (for display/verification), may be null
 * @param disabledMask bit i set = slot i disabled
 * @param pattern      item id per slot, null for an empty enabled slot (disabled slots must be null)
 */
public record CrafterTemplate(String name, String resultId, int disabledMask, List<String> pattern) {

    public static final int SLOTS = 9;

    public CrafterTemplate {
        if (pattern == null || pattern.size() != SLOTS) {
            throw new IllegalArgumentException("crafter pattern needs 9 slots");
        }
        pattern = java.util.Collections.unmodifiableList(new ArrayList<>(pattern));
        disabledMask &= (1 << SLOTS) - 1;
    }

    /** Captures what an open crafter shows. */
    public static CrafterTemplate capture(String name, String resultId, int disabledMask, String[] items) {
        String[] p = Arrays.copyOf(items, SLOTS);
        for (int i = 0; i < SLOTS; i++) {
            if ((disabledMask & (1 << i)) != 0) {
                p[i] = null;
            }
        }
        return new CrafterTemplate(name, resultId, disabledMask, Arrays.asList(p));
    }

    public boolean disabled(int slot) {
        return (disabledMask & (1 << slot)) != 0;
    }

    /** One fix. */
    public enum FixKind {
        /** Take the item out of the slot (wrong item, or the slot must be disabled). */
        CLEAR,
        /** Toggle the slot's disabled state; {@code enable} says the target state. */
        TOGGLE,
        /** Put one {@code itemId} into the slot. */
        SEED
    }

    public record Fix(FixKind kind, int slot, String itemId, boolean enable) {
    }

    /**
     * Fixes that turn the observed crafter into this template, in executable order (clear, then toggle, then seed).
     *
     * @param observedMask  current disabled mask
     * @param observedItems current item per slot (null when empty)
     */
    public List<Fix> diff(int observedMask, String[] observedItems) {
        List<Fix> clear = new ArrayList<>();
        List<Fix> toggle = new ArrayList<>();
        List<Fix> seed = new ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            boolean wantOff = disabled(i);
            boolean isOff = (observedMask & (1 << i)) != 0;
            String have = observedItems == null || i >= observedItems.length ? null : observedItems[i];
            String want = pattern.get(i);
            if (!isOff && have != null && (wantOff || (want != null && !want.equals(have)))) {
                clear.add(new Fix(FixKind.CLEAR, i, have, false));
                have = null;
            }
            if (wantOff != isOff) {
                toggle.add(new Fix(FixKind.TOGGLE, i, null, !wantOff));
            }
            if (!wantOff && want != null && have == null) {
                seed.add(new Fix(FixKind.SEED, i, want, false));
            }
        }
        List<Fix> out = new ArrayList<>(clear.size() + toggle.size() + seed.size());
        out.addAll(clear);
        out.addAll(toggle);
        out.addAll(seed);
        return out;
    }

    public boolean matches(int observedMask, String[] observedItems) {
        return diff(observedMask, observedItems).isEmpty();
    }

    /** Compact text form: {@code name|result|mask|id,id,,...} (empty field = empty slot). */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        sb.append(name.replace('|', '/')).append('|').append(resultId == null ? "" : resultId).append('|')
                .append(disabledMask).append('|');
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) {
                sb.append(',');
            }
            if (pattern.get(i) != null) {
                sb.append(pattern.get(i));
            }
        }
        return sb.toString();
    }

    public static CrafterTemplate decode(String s) {
        String[] f = s.split("\\|", -1);
        if (f.length != 4) {
            throw new IllegalArgumentException("bad crafter template: " + s);
        }
        String[] slots = f[3].split(",", -1);
        String[] items = new String[SLOTS];
        for (int i = 0; i < SLOTS && i < slots.length; i++) {
            items[i] = slots[i].isEmpty() ? null : slots[i];
        }
        return new CrafterTemplate(f[0], f[1].isEmpty() ? null : f[1], Integer.parseInt(f[2]), Arrays.asList(items));
    }
}
