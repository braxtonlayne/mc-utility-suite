package com.utilitysuite.jobs.schematic.plan;

import com.utilitysuite.jobs.schematic.model.SchematicVolume;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable ordered build plan: parallel primitive arrays of volume cell indices and step kinds, so the build assist
 * walks it on the client thread without allocating. Scaffold steps place {@link #scaffoldItem()} in an air cell and
 * are undone by {@link #REMOVE_SCAFFOLD} steps at the end (top-down).
 */
public final class BuildPlan {

    public static final byte PLACE = 0;
    public static final byte SCAFFOLD = 1;
    public static final byte REMOVE_SCAFFOLD = 2;

    /** A step as a value, for tests and the "Show Plan" view. */
    public record Step(int x, int y, int z, byte kind, String state) {
        public String describe() {
            return switch (kind) {
                case SCAFFOLD -> "scaffold at " + x + " " + y + " " + z;
                case REMOVE_SCAFFOLD -> "remove scaffold at " + x + " " + y + " " + z;
                default -> state + " at " + x + " " + y + " " + z;
            };
        }
    }

    private final SchematicVolume volume;
    private final int[] cells;
    private final byte[] kinds;
    private final String scaffoldItem;
    private final int unsupported;

    BuildPlan(SchematicVolume volume, int[] cells, byte[] kinds, String scaffoldItem, int unsupported) {
        this.volume = volume;
        this.cells = cells;
        this.kinds = kinds;
        this.scaffoldItem = scaffoldItem;
        this.unsupported = unsupported;
    }

    public SchematicVolume volume() { return volume; }
    public int size() { return cells.length; }
    public int cell(int step) { return cells[step]; }
    public byte kind(int step) { return kinds[step]; }
    public String scaffoldItem() { return scaffoldItem; }

    /** Placements left without any support even after scaffolding (column too tall or blocked). */
    public int unsupportedPlacements() {
        return unsupported;
    }

    public int count(byte kind) {
        int c = 0;
        for (byte k : kinds) {
            if (k == kind) {
                c++;
            }
        }
        return c;
    }

    public Step step(int i) {
        int c = cells[i];
        String state = kinds[i] == PLACE ? volume.paletteEntry(volume.paletteIndex(c)) : scaffoldItem;
        return new Step(volume.xOf(c), volume.yOf(c), volume.zOf(c), kinds[i], state);
    }

    public List<Step> steps() {
        List<Step> out = new ArrayList<>(cells.length);
        for (int i = 0; i < cells.length; i++) {
            out.add(step(i));
        }
        return out;
    }
}
