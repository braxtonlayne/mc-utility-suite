package com.utilitysuite.jobs.survival;

import com.utilitysuite.jobs.planner.PackedPos;

import java.util.ArrayList;
import java.util.List;

/**
 * User-marked {@code PROTECTED_NO_MINE} boxes. Automation (Baritone routes, mining candidates) must never break blocks
 * inside them. Lookups are allocation-free; the box array is replaced atomically on edits.
 * <p>
 * Text form (profile setting): {@code x1 y1 z1 x2 y2 z2 [dimension]; ...}, dimension defaulting to the overworld.
 */
public final class ProtectedAreas {

    public record Box(String dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public static Box of(String dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
            return new Box(dimension, Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2), Math.max(x1, x2),
                    Math.max(y1, y2), Math.max(z1, z2));
        }

        public boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    private volatile Box[] boxes = new Box[0];

    public List<Box> boxes() {
        return List.of(boxes);
    }

    public void set(List<Box> list) {
        boxes = list.toArray(new Box[0]);
    }

    public void add(Box b) {
        List<Box> l = new ArrayList<>(List.of(boxes));
        l.add(b);
        set(l);
    }

    public boolean isEmpty() {
        return boxes.length == 0;
    }

    public boolean isProtected(String dimension, int x, int y, int z) {
        Box[] b = boxes;
        for (Box box : b) {
            if (box.contains(x, y, z) && box.dimension().equals(dimension)) {
                return true;
            }
        }
        return false;
    }

    public boolean isProtected(String dimension, long packedPos) {
        return isProtected(dimension, PackedPos.x(packedPos), PackedPos.y(packedPos), PackedPos.z(packedPos));
    }

    /** Parses the text form; malformed entries are skipped and reported in {@code errors}. */
    public static List<Box> parse(String text, List<String> errors) {
        List<Box> out = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return out;
        }
        for (String part : text.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            String[] t = p.split("[\\s,]+");
            if (t.length != 6 && t.length != 7) {
                errors.add("Expected 'x1 y1 z1 x2 y2 z2 [dimension]': " + p);
                continue;
            }
            try {
                int[] v = new int[6];
                for (int i = 0; i < 6; i++) {
                    v[i] = Integer.parseInt(t[i]);
                }
                String dim = t.length == 7 ? (t[6].contains(":") ? t[6] : "minecraft:" + t[6]) : "minecraft:overworld";
                out.add(Box.of(dim, v[0], v[1], v[2], v[3], v[4], v[5]));
            } catch (NumberFormatException e) {
                errors.add("Not a number in: " + p);
            }
        }
        return out;
    }

    public static String format(List<Box> boxes) {
        StringBuilder sb = new StringBuilder();
        for (Box b : boxes) {
            if (!sb.isEmpty()) {
                sb.append("; ");
            }
            sb.append(b.minX()).append(' ').append(b.minY()).append(' ').append(b.minZ()).append(' ')
                    .append(b.maxX()).append(' ').append(b.maxY()).append(' ').append(b.maxZ()).append(' ')
                    .append(b.dimension());
        }
        return sb.toString();
    }
}
