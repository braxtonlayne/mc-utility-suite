package com.utilitysuite.jobs.schematic.gen;

import com.utilitysuite.jobs.schematic.model.BlockStateString;

import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Engine-free symmetry for the symmetry builder: the eight horizontal transforms of a square (dihedral group D4)
 * applied to positions relative to a centre block and to block-state strings.
 * <p>
 * A transform is encoded as an int {@code 0..7}: bit 2 = mirror X first (x becomes -x), bits 0-1 = then rotate
 * clockwise (seen from above) by that many quarter turns, Minecraft's {@code Rotation.CLOCKWISE_90} convention
 * ({@code (x, z) -> (-z, x)}, north turns east). Code 0 is the identity.
 * <p>
 * Block states: horizontal {@code facing}, {@code axis}, 16-step {@code rotation} (signs, banners, heads), the
 * per-direction properties of fences/walls/panes/redstone ({@code north=...}), door {@code hinge}, stair
 * {@code shape} (inner/outer left/right) and rail {@code shape} are mapped. Unknown properties pass through.
 */
public final class SymmetryTransform {

    public enum Mode {
        MIRROR_X("Mirror X", new int[]{4}),
        MIRROR_Z("Mirror Z", new int[]{6}), // mirror X then 180 = mirror Z
        MIRROR_XZ("Mirror X + Z", new int[]{4, 6, 2}),
        RADIAL_4("Radial 4x", new int[]{1, 2, 3}),
        RADIAL_8("Radial 8x", new int[]{1, 2, 3, 4, 5, 6, 7});

        private final String label;
        private final int[] images;

        Mode(String label, int[] images) {
            this.label = label;
            this.images = images;
        }

        public String label() {
            return label;
        }

        /** Transforms producing the counterparts of one placed block (identity excluded). Do not modify. */
        public int[] images() {
            return images;
        }

        public static Mode parse(String s) {
            if (s != null) {
                String n = s.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
                for (Mode m : values()) {
                    if (m.name().equals(n)) {
                        return m;
                    }
                }
            }
            return MIRROR_X;
        }
    }

    private static final String[] DIRS = {"north", "east", "south", "west"};

    private SymmetryTransform() {
    }

    public static boolean mirrors(int t) {
        return (t & 4) != 0;
    }

    public static int quarterTurns(int t) {
        return t & 3;
    }

    /** Transformed X offset of {@code (dx, dz)}. */
    public static int tx(int t, int dx, int dz) {
        if (mirrors(t)) {
            dx = -dx;
        }
        return switch (quarterTurns(t)) {
            case 1 -> -dz;
            case 2 -> -dx;
            case 3 -> dz;
            default -> dx;
        };
    }

    /** Transformed Z offset of {@code (dx, dz)}. */
    public static int tz(int t, int dx, int dz) {
        if (mirrors(t)) {
            dx = -dx;
        }
        return switch (quarterTurns(t)) {
            case 1 -> dx;
            case 2 -> -dz;
            case 3 -> -dx;
            default -> dz;
        };
    }

    /** Index in {@link #DIRS} of a horizontal direction name, or -1. */
    static int dirIndex(String d) {
        for (int i = 0; i < 4; i++) {
            if (DIRS[i].equals(d)) {
                return i;
            }
        }
        return -1;
    }

    /** Transforms a direction name; non-horizontal names (up, down) pass through. */
    public static String direction(int t, String d) {
        int i = dirIndex(d);
        if (i < 0) {
            return d;
        }
        if (mirrors(t) && (i == 1 || i == 3)) {
            i ^= 2; // east <-> west
        }
        return DIRS[(i + quarterTurns(t)) & 3];
    }

    /** Transforms a canonical block-state string. */
    public static String state(int t, String state) {
        if (t == 0 || state.indexOf('[') < 0) {
            return state;
        }
        String id = BlockStateString.blockId(state);
        int open = state.indexOf('['), close = state.lastIndexOf(']');
        Map<String, String> in = new TreeMap<>();
        for (String part : state.substring(open + 1, close < 0 ? state.length() : close).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                in.put(part.substring(0, eq), part.substring(eq + 1));
            }
        }
        boolean mirror = mirrors(t);
        boolean odd = (quarterTurns(t) & 1) == 1;
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<String, String> e : in.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (dirIndex(k) >= 0) {
                out.put(direction(t, k), v); // fence/wall/pane/redstone connection keys
                continue;
            }
            switch (k) {
                case "facing", "horizontal_facing", "orientation" -> v = k.equals("orientation") ? orientation(t, v)
                        : direction(t, v);
                case "axis" -> {
                    if (odd && (v.equals("x") || v.equals("z"))) {
                        v = v.equals("x") ? "z" : "x";
                    }
                }
                case "rotation" -> v = Integer.toString(rotation16(t, parseInt(v)));
                case "hinge" -> {
                    if (mirror) {
                        v = v.equals("left") ? "right" : v.equals("right") ? "left" : v;
                    }
                }
                case "shape" -> v = shape(t, id, v);
                default -> {
                }
            }
            out.put(k, v);
        }
        return BlockStateString.of(id, out);
    }

    /** 16-step rotation (0 = south, 4 = west, 8 = north, 12 = east). */
    public static int rotation16(int t, int r) {
        if (mirrors(t)) {
            r = (16 - r) & 15; // mirror X swaps east and west
        }
        return (r + 4 * quarterTurns(t)) & 15;
    }

    /** Jigsaw/crafter {@code orientation} values like {@code north_up}: maps each direction word. */
    private static String orientation(int t, String v) {
        int u = v.indexOf('_');
        if (u < 0) {
            return v;
        }
        return direction(t, v.substring(0, u)) + "_" + direction(t, v.substring(u + 1));
    }

    static String shape(int t, String id, String v) {
        if (v.startsWith("inner_") || v.startsWith("outer_")) {
            if (mirrors(t)) { // stairs: a mirror swaps left and right
                return v.substring(0, 6) + (v.endsWith("left") ? "right" : "left");
            }
            return v;
        }
        if (v.startsWith("ascending_")) {
            return "ascending_" + direction(t, v.substring(10));
        }
        int u = v.indexOf('_');
        if (u < 0) {
            return v;
        }
        String a = direction(t, v.substring(0, u)), b = direction(t, v.substring(u + 1));
        if (dirIndex(a) < 0 || dirIndex(b) < 0) {
            return v;
        }
        // rail names: straight north_south / east_west; curves list north or south first
        if ((a.equals("north") && b.equals("south")) || (a.equals("south") && b.equals("north"))) {
            return "north_south";
        }
        if ((a.equals("east") && b.equals("west")) || (a.equals("west") && b.equals("east"))) {
            return "east_west";
        }
        boolean aNs = a.equals("north") || a.equals("south");
        return aNs ? a + "_" + b : b + "_" + a;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
