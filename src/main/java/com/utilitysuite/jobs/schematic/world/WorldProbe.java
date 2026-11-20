package com.utilitysuite.jobs.schematic.world;

/**
 * Reads one world cell and classifies it against a schematic palette entry. The Minecraft implementation compares
 * pre-resolved {@code BlockState}s by identity (no allocation); tests use an array- or map-backed fake.
 */
public interface WorldProbe {

    /**
     * @param paletteIndex the schematic's palette index for the cell, or {@code -1} to classify only
     *                     (empty / fluid / other)
     * @return one of {@link PlacementStatus#W_MATCH}, {@code W_SAME_BLOCK}, {@code W_EMPTY}, {@code W_FLUID},
     * {@code W_OTHER}, {@code W_UNLOADED}
     */
    int probe(int worldX, int worldY, int worldZ, int paletteIndex);
}
