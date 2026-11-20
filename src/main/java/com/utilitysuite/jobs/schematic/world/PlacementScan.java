package com.utilitysuite.jobs.schematic.world;

import com.utilitysuite.jobs.schematic.model.BlockCategory;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Budgeted, resumable comparison of a schematic with the world at an anchor ("Build Resume &amp; Diff"). The client
 * thread calls {@link #step} once per tick with a cell budget; the scan walks the volume in storage order, writes a
 * {@link PlacementStatus} per cell, and wraps around for continuous refresh via {@link #restart()}.
 * <p>
 * Hot path: {@link #step} allocates nothing; all arrays are sized once in the constructor. Gravity blocks on the bottom
 * layer also probe the world cell below them (counted against the budget) so the site survey can report unsupported
 * sand and gravel.
 */
public final class PlacementScan {

    private final SchematicVolume volume;
    private final int originX;
    private final int originY;
    private final int originZ;
    private final byte[] status;
    private final BitSet unsupportedBelow;
    private final boolean[] blockPalette;
    private final boolean[] airPalette;
    private final boolean[] gravityPalette;
    private final int cells;
    private int cursor;
    private int completedPasses;

    /**
     * @param anchorX world position of the schematic origin (the volume offset is added here)
     */
    public PlacementScan(SchematicVolume volume, int anchorX, int anchorY, int anchorZ) {
        this.volume = volume;
        this.originX = anchorX + volume.offsetX();
        this.originY = anchorY + volume.offsetY();
        this.originZ = anchorZ + volume.offsetZ();
        this.cells = volume.volume();
        this.status = new byte[cells];
        this.unsupportedBelow = new BitSet(cells);
        int p = volume.paletteSize();
        this.blockPalette = new boolean[p];
        this.airPalette = new boolean[p];
        this.gravityPalette = new boolean[p];
        for (int i = 0; i < p; i++) {
            blockPalette[i] = volume.isBlock(i);
            airPalette[i] = volume.isAir(i);
            gravityPalette[i] = blockPalette[i] && BlockCategory.of(volume.paletteEntry(i)) == BlockCategory.GRAVITY;
        }
    }

    public SchematicVolume volume() { return volume; }
    public int originX() { return originX; }
    public int originY() { return originY; }
    public int originZ() { return originZ; }

    /** Live status array (client thread). Use {@link #snapshot()} to hand it to another thread. */
    public byte[] status() {
        return status;
    }

    public byte[] snapshot() {
        return status.clone();
    }

    public byte status(int index) {
        return status[index];
    }

    /** True when the bottom-layer gravity block at {@code index} has an empty or fluid world cell below. */
    public boolean unsupportedBelow(int index) {
        return unsupportedBelow.get(index);
    }

    /** Copy of the bottom-layer support bits, for background analysis. */
    public BitSet unsupportedBelowSnapshot() {
        return (BitSet) unsupportedBelow.clone();
    }

    public boolean isComplete() {
        return cursor >= cells;
    }

    /** Number of full passes finished since construction. */
    public int completedPasses() {
        return completedPasses;
    }

    /** Fraction of the current pass done, 0..1. */
    public float progress() {
        return cells == 0 ? 1f : Math.min(1f, cursor / (float) cells);
    }

    /** Starts a new pass from the first cell, keeping the previous statuses until they are overwritten. */
    public void restart() {
        cursor = 0;
    }

    /** Forgets every status (e.g. after the anchor moved). */
    public void reset() {
        Arrays.fill(status, PlacementStatus.UNKNOWN);
        unsupportedBelow.clear();
        cursor = 0;
    }

    /**
     * Scans up to {@code budget} world cells. Void cells cost nothing.
     *
     * @return true when the pass is complete
     */
    public boolean step(WorldProbe probe, int budget) {
        int spent = 0;
        while (cursor < cells && spent < budget) {
            spent += scanCell(probe, cursor);
            cursor++;
            if (cursor == cells) {
                completedPasses++;
            }
        }
        return cursor >= cells;
    }

    /** Re-probes a single cell (after a placement). */
    public void refresh(WorldProbe probe, int index) {
        scanCell(probe, index);
    }

    private int scanCell(WorldProbe probe, int index) {
        int p = volume.paletteIndex(index);
        if (SchematicVolume.isVoid(p)) {
            status[index] = PlacementStatus.CLEAR;
            return 0;
        }
        int sx = volume.sizeX();
        int sz = volume.sizeZ();
        int x = index % sx;
        int rest = index / sx;
        int z = rest % sz;
        int y = rest / sz;
        int wx = originX + x, wy = originY + y, wz = originZ + z;
        int w = probe.probe(wx, wy, wz, p);
        if (airPalette[p]) {
            status[index] = PlacementStatus.forAirCell(w);
            return 1;
        }
        status[index] = PlacementStatus.forBlockCell(w);
        if (y == 0 && gravityPalette[p]) {
            int below = probe.probe(wx, wy - 1, wz, -1);
            unsupportedBelow.set(index, below == PlacementStatus.W_EMPTY || below == PlacementStatus.W_FLUID);
            return 2;
        }
        return 1;
    }

    /** Counts cells per status into {@code out} (length at least 8, indexed by status byte). Allocation-free. */
    public void countStatuses(int[] out) {
        Arrays.fill(out, 0, 8, 0);
        for (int i = 0; i < cells; i++) {
            out[status[i]]++;
        }
    }

    /** True when the palette index requires a block. */
    public boolean isBlockPalette(int paletteIndex) {
        return blockPalette[paletteIndex];
    }
}
