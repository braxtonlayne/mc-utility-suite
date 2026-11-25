package com.utilitysuite.world.redstone;

/**
 * Engine-free description of one block for the redstone tracer, packed in an {@code int} so lookups allocate nothing.
 * Bits 0-5 kind, 6-8 direction (vanilla 3D order: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east), 9-12 parameter
 * (repeater delay 1-4, dust / daylight power 0-15), bit 13 powered / lit, bit 14 "parameter known".
 * <p>
 * Direction meaning per kind: repeater and comparator = OUTPUT side; observer = WATCHED side (it outputs on the
 * opposite one); torch, lever, button and pressure plate = the side of the block they are ATTACHED to.
 */
public final class RedstoneCell {

    public static final int NONE = 0;
    /** A full conductive block: can be strongly or weakly powered and passes the power on to adjacent components. */
    public static final int SOLID = 1;
    public static final int DUST = 2;
    public static final int REPEATER = 3;
    public static final int COMPARATOR = 4;
    public static final int TORCH = 5;
    public static final int LEVER = 6;
    /** Buttons and tripwire hooks. */
    public static final int BUTTON = 7;
    public static final int PRESSURE_PLATE = 8;
    public static final int OBSERVER = 9;
    public static final int REDSTONE_BLOCK = 10;
    public static final int TARGET = 11;
    public static final int DAYLIGHT = 12;
    /** Pistons and sticky pistons (quasi-connectivity). */
    public static final int PISTON = 13;
    /** Droppers and dispensers (quasi-connectivity). */
    public static final int DROPPER = 14;
    public static final int CRAFTER = 15;
    public static final int LAMP = 16;
    /** Doors, trapdoors, gates, note blocks, hoppers, powered rails, bells: consumers without outputs. */
    public static final int OTHER_SINK = 17;
    public static final int KIND_COUNT = 18;

    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    public static final int[] DX = {0, 0, 0, 0, -1, 1};
    public static final int[] DY = {-1, 1, 0, 0, 0, 0};
    public static final int[] DZ = {0, 0, -1, 1, 0, 0};

    private static final int POWERED = 1 << 13;
    private static final int PARAM_KNOWN = 1 << 14;

    private RedstoneCell() {
    }

    public static int of(int kind, int dir) {
        return kind | (dir & 7) << 6;
    }

    public static int of(int kind, int dir, int param, boolean powered) {
        return kind | (dir & 7) << 6 | (param & 15) << 9 | PARAM_KNOWN | (powered ? POWERED : 0);
    }

    public static int withPowered(int cell, boolean powered) {
        return powered ? cell | POWERED : cell & ~POWERED;
    }

    public static int kind(int cell) {
        return cell & 63;
    }

    public static int dir(int cell) {
        return cell >> 6 & 7;
    }

    public static int param(int cell) {
        return cell >> 9 & 15;
    }

    public static boolean powered(int cell) {
        return (cell & POWERED) != 0;
    }

    public static boolean paramKnown(int cell) {
        return (cell & PARAM_KNOWN) != 0;
    }

    public static int opposite(int dir) {
        return dir ^ 1;
    }

    /** True for the kinds the tracer can start from (anything but plain blocks and air). */
    public static boolean isComponent(int kind) {
        return kind > SOLID && kind < KIND_COUNT;
    }

    /** Pistons, droppers and dispensers also react to power at the block above them. */
    public static boolean quasiConnects(int kind) {
        return kind == PISTON || kind == DROPPER;
    }

    /** Game ticks a component adds on the way through (vanilla: one redstone tick = 2 game ticks). */
    public static int delayGt(int cell) {
        return switch (kind(cell)) {
            case REPEATER -> 2 * Math.max(1, paramKnown(cell) ? param(cell) : 1);
            case COMPARATOR, TORCH, OBSERVER -> 2;
            default -> 0;
        };
    }

    /**
     * Signal strength readable from the block state, or -1 when it cannot be known without the world (comparators,
     * consumers).
     */
    public static int strength(int cell) {
        return switch (kind(cell)) {
            case DUST, DAYLIGHT -> paramKnown(cell) ? param(cell) : -1;
            case REPEATER, TORCH, LEVER, BUTTON, OBSERVER -> powered(cell) ? 15 : 0;
            case REDSTONE_BLOCK -> 15;
            default -> -1;
        };
    }
}
