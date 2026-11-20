package com.utilitysuite.world.waypoints;

import com.utilitysuite.world.BlockPosPacking;

/** Overworld ↔ nether coordinate conversion (8:1 horizontally, Y unchanged). Other dimensions are not converted. */
public final class DimensionScale {

    public static final String OVERWORLD = "minecraft:overworld";
    public static final String NETHER = "minecraft:the_nether";

    private DimensionScale() {
    }

    /** Horizontal scale of a dimension relative to the overworld (1 for the overworld, 8 for the nether). */
    public static int scale(String dimension) {
        return NETHER.equals(dimension) ? 8 : 1;
    }

    /** True when a position can be shown in {@code to} (same dimension, or an overworld/nether pair). */
    public static boolean convertible(String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        return (OVERWORLD.equals(from) && NETHER.equals(to)) || (NETHER.equals(from) && OVERWORLD.equals(to));
    }

    /** Converts a horizontal coordinate. Nether → overworld multiplies by 8; overworld → nether floors the division. */
    public static int convert(int coordinate, String from, String to) {
        if (from.equals(to) || !convertible(from, to)) {
            return coordinate;
        }
        if (NETHER.equals(to)) {
            return Math.floorDiv(coordinate, 8);
        }
        return coordinate * 8;
    }

    /** Converts a position; returns the input unchanged when not convertible. */
    public static long convert(int x, int y, int z, String from, String to) {
        return BlockPosPacking.pack(convert(x, from, to), y, convert(z, from, to));
    }
}
