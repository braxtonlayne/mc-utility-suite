package com.utilitysuite.jobs.schematic.world;

/**
 * Per-cell result of comparing the schematic with the world (stored as a {@code byte[]} indexed like the volume), and
 * the raw world classifications a {@link WorldProbe} returns.
 */
public final class PlacementStatus {

    // ---- per-cell status ------------------------------------------------------------------------------------------
    /** Not scanned yet. */
    public static final byte UNKNOWN = 0;
    /** The exact state is in place. */
    public static final byte MATCH = 1;
    /** The right block is in place with different properties (orientation, waterlogging...). Counts as placed. */
    public static final byte SAME_BLOCK = 2;
    /** Needs placing: the world is empty (air or a replaceable plant) there. */
    public static final byte MISSING = 3;
    /** A different block occupies the cell: clear it first (for an air cell: an obstruction to remove). */
    public static final byte WRONG = 4;
    /** A fluid occupies the cell (water/lava conflict). */
    public static final byte FLUID = 5;
    /** The chunk is not loaded (or the cell is outside the build height). */
    public static final byte UNLOADED = 6;
    /** Nothing to do: void cell, or an air cell that is empty in the world. */
    public static final byte CLEAR = 7;

    // ---- raw world classification (WorldProbe results) -------------------------------------------------------------
    public static final int W_MATCH = 1;
    public static final int W_SAME_BLOCK = 2;
    public static final int W_EMPTY = 3;
    public static final int W_FLUID = 4;
    public static final int W_OTHER = 5;
    public static final int W_UNLOADED = 6;

    private PlacementStatus() {
    }

    /** The block's material is already in the world. */
    public static boolean isPlaced(byte status) {
        return status == MATCH || status == SAME_BLOCK;
    }

    /** A block still has to be placed in this cell (after clearing it when {@link #WRONG} or {@link #FLUID}). */
    public static boolean needsPlacing(byte status) {
        return status == MISSING || status == WRONG || status == FLUID;
    }

    /** Converts a raw probe result for a cell that requires a block. */
    public static byte forBlockCell(int world) {
        return switch (world) {
            case W_MATCH -> MATCH;
            case W_SAME_BLOCK -> SAME_BLOCK;
            case W_EMPTY -> MISSING;
            case W_FLUID -> FLUID;
            case W_OTHER -> WRONG;
            default -> UNLOADED;
        };
    }

    /** Converts a raw probe result for a cell that must be air. */
    public static byte forAirCell(int world) {
        return switch (world) {
            case W_MATCH, W_EMPTY -> CLEAR;
            case W_FLUID -> FLUID;
            case W_SAME_BLOCK, W_OTHER -> WRONG;
            default -> UNLOADED;
        };
    }

    public static String name(byte status) {
        return switch (status) {
            case MATCH -> "match";
            case SAME_BLOCK -> "same_block";
            case MISSING -> "missing";
            case WRONG -> "wrong";
            case FLUID -> "fluid";
            case UNLOADED -> "unloaded";
            case CLEAR -> "clear";
            default -> "unknown";
        };
    }
}
