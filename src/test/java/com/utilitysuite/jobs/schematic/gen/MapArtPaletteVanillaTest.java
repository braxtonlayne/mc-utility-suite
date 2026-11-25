package com.utilitysuite.jobs.schematic.gen;

import net.minecraft.world.level.material.MapColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Pins the hand-typed map-art palette to the game's own MapColor table and shade multipliers. */
class MapArtPaletteVanillaTest {

    @Test
    void baseColoursMatchVanillaMapColor() {
        for (int id = 1; id < MapArtPalette.BASE.length; id++) {
            assertEquals(MapColor.byId(id).col, MapArtPalette.base(id), "map colour id " + id);
        }
    }

    @Test
    void shadesMatchVanillaBrightness() {
        int rgb = MapColor.GRASS.col;
        assertEquals(argbToRgb(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.LOW)),
                MapArtPalette.shade(rgb, MapArtPalette.SHADE_LOW));
        assertEquals(argbToRgb(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.NORMAL)),
                MapArtPalette.shade(rgb, MapArtPalette.SHADE_FLAT));
        assertEquals(argbToRgb(MapColor.GRASS.calculateRGBColor(MapColor.Brightness.HIGH)),
                MapArtPalette.shade(rgb, MapArtPalette.SHADE_HIGH));
    }

    /** calculateRGBColor returns ABGR for the map texture; convert to 0xRRGGBB. */
    private static int argbToRgb(int abgr) {
        int r = abgr & 0xFF, g = (abgr >> 8) & 0xFF, b = (abgr >> 16) & 0xFF;
        return (r << 16) | (g << 8) | b;
    }
}
