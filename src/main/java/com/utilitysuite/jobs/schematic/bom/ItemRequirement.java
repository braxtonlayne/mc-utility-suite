package com.utilitysuite.jobs.schematic.bom;

/**
 * What one block state costs in items. Most states cost one item; a few cost two different items (a potted plant is
 * a flower pot plus the plant, a candle cake is a cake plus a candle). {@link Kind#NONE} costs nothing (air, the
 * upper half of a door, fluids), optionally with a note for the job card ("fluid: place with a bucket");
 * {@link Kind#UNOBTAINABLE} cannot be obtained in survival at all.
 */
public record ItemRequirement(Kind kind, String item, int count, String item2, int count2, String note) {

    public enum Kind { ITEM, NONE, UNOBTAINABLE }

    private static final ItemRequirement NOTHING = new ItemRequirement(Kind.NONE, null, 0, null, 0, null);

    public static ItemRequirement item(String item, int count) {
        return new ItemRequirement(Kind.ITEM, item, count, null, 0, null);
    }

    public static ItemRequirement item(String item, int count, String note) {
        return new ItemRequirement(Kind.ITEM, item, count, null, 0, note);
    }

    public static ItemRequirement two(String item, int count, String item2, int count2) {
        return new ItemRequirement(Kind.ITEM, item, count, item2, count2, null);
    }

    public static ItemRequirement none() {
        return NOTHING;
    }

    public static ItemRequirement flagged(String note) {
        return new ItemRequirement(Kind.NONE, null, 0, null, 0, note);
    }

    public static ItemRequirement unobtainable(String note) {
        return new ItemRequirement(Kind.UNOBTAINABLE, null, 0, null, 0, note);
    }

    public boolean isItem() {
        return kind == Kind.ITEM;
    }
}
