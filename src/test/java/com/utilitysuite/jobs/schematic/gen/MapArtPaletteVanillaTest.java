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
        assertEquals(argbToRgb(MapColor.GRASS.calculateARGBColor(MapColor.Brightness.LOW)),
                MapArtPalette.shade(rgb, MapArtPalette.SHADE_LOW));
        assertEquals(argbToRgb(MapColor.GRASS.calculateARGBColor(MapColor.Brightness.NORMAL)),
                MapArtPalette.shade(rgb, MapArtPalette.SHADE_FLAT));
        assertEquals(argbToRgb(MapColor.GRASS.calculateARGBColor(MapColor.Brightness.HIGH)),
                MapArtPalette.shade(rgb, MapArtPalette.SHADE_HIGH));
    }

    /** calculateARGBColor returns opaque ARGB; drop the alpha to get 0xRRGGBB. */
    private static int argbToRgb(int argb) {
        return argb & 0xFFFFFF;
    }
}
