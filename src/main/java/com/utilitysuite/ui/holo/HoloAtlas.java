package com.utilitysuite.ui.holo;

import com.mojang.blaze3d.platform.NativeImage;
import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.visual.Glyph;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

/**
 * The kit atlas, drawn from code at first use (own art only, no PNG and no generator script): white texels whose
 * alpha carries the shape, tinted by the vertex colour. Layout ({@link HoloKit.Geometry} holds the constants):
 * <ul>
 *     <li>three falloff rows (hard, soft, glow), each 16 texels wide and duplicated over two texel rows so linear
 *     filtering at the row centre never bleeds into a neighbour;</li>
 *     <li>a 16x16 radial glow sprite (9-slice: 6 px corners) at x 16;</li>
 *     <li>the 7x7 {@link Glyph} set from x 32, one every 8 texels.</li>
 * </ul>
 * {@link #pixels()} is plain Java (testable); {@link #ensureUploaded()} creates the {@link DynamicTexture} once, on the
 * render thread.
 */
public final class HoloAtlas {

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID,
            "holo/atlas");

    private static boolean uploaded;

    private HoloAtlas() {
    }

    /** Registers the atlas texture with the texture manager once. Render thread only. */
    public static void ensureUploaded() {
        if (uploaded) {
            return;
        }
        uploaded = true;
        int w = HoloKit.Geometry.ATLAS_W;
        int h = HoloKit.Geometry.ATLAS_H;
        int[] argb = pixels();
        NativeImage img = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = argb[y * w + x];
                // NativeImage is ABGR; the colour is white, so only alpha needs care
                img.setPixelRGBA(x, y, (c & 0xFF000000) | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF));
            }
        }
        Minecraft.getInstance().getTextureManager().register(ID, new DynamicTexture(img));
    }

    /** The atlas as ARGB (row-major, {@code ATLAS_W x ATLAS_H}). */
    public static int[] pixels() {
        int w = HoloKit.Geometry.ATLAS_W;
        int[] px = new int[w * HoloKit.Geometry.ATLAS_H];
        for (int row = 0; row < 3; row++) {
            for (int x = 0; x < 16; x++) {
                int a = falloff(row, Math.abs(x + 0.5f - 8f) / 8f);
                px[(row * 2) * w + x] = white(a);
                px[(row * 2 + 1) * w + x] = white(a);
            }
        }
        // radial glow sprite
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                float dx = (x + 0.5f - 8f) / 8f;
                float dy = (y + 0.5f - 8f) / 8f;
                float d = Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy));
                float f = 1f - d;
                px[y * w + HoloKit.Geometry.GLOW_X + x] = white((int) (f * f * 200f));
            }
        }
        Glyph[] glyphs = Glyph.values();
        for (int i = 0; i < glyphs.length; i++) {
            String[] rows = bitmap(glyphs[i]);
            int gx = HoloKit.Geometry.GLYPH_X + i * HoloKit.Geometry.GLYPH_STEP;
            for (int y = 0; y < Glyph.SIZE; y++) {
                for (int x = 0; x < Glyph.SIZE; x++) {
                    if (rows[y].charAt(x) == '#') {
                        px[(HoloKit.Geometry.GLYPH_Y + y) * w + gx + x] = 0xFFFFFFFF;
                    }
                }
            }
        }
        return px;
    }

    /** Alpha of falloff row {@code row} at normalised distance {@code d} (0 = centre, 1 = edge). */
    static int falloff(int row, float d) {
        float a;
        switch (row) {
            case HoloKit.Geometry.ROW_HARD -> a = d < 0.55f ? 1f : Math.max(0f, 1f - (d - 0.55f) / 0.3f);
            case HoloKit.Geometry.ROW_SOFT -> {
                float t = d < 0.2f ? 1f : Math.max(0f, 1f - (d - 0.2f) / 0.8f);
                a = t * t;
            }
            default -> a = (float) Math.exp(-4.0 * d) * (1f - d) * 0.55f;
        }
        return Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
    }

    private static int white(int alpha) {
        return (Math.max(0, Math.min(255, alpha)) << 24) | 0xFFFFFF;
    }

    /** The 7x7 bitmap of a glyph, '#' = lit. */
    static String[] bitmap(Glyph g) {
        return switch (g) {
            case DOT -> new String[]{".......", "..###..", ".#####.", ".#####.", ".#####.", "..###..", "......."};
            case RUNNING -> new String[]{".#.....", ".##....", ".###...", ".####..", ".###...", ".##....", ".#....."};
            case ACTING -> new String[]{"...#...", "..###..", ".#####.", "#######", ".#####.", "..###..", "...#..."};
            case WAITING -> new String[]{".......", ".##.##.", ".##.##.", ".##.##.", ".##.##.", ".##.##.", "......."};
            case WARNING -> new String[]{"...#...", "..###..", "..#.#..", ".##.##.", ".#####.", "##.#.##", "#######"};
            case FAILED -> new String[]{"#.....#", ".#...#.", "..#.#..", "...#...", "..#.#..", ".#...#.", "#.....#"};
            case DONE -> new String[]{"......#", ".....##", "#...##.", "##.##..", ".###...", "..#....", "......."};
            case CHEVRON_LEFT -> new String[]{"....##.", "...##..", "..##...", ".##....", "..##...", "...##..",
                    "....##."};
            case CHEVRON_RIGHT -> new String[]{".##....", "..##...", "...##..", "....##.", "...##..", "..##...",
                    ".##...."};
            case CHEVRON_UP -> new String[]{".......", "...#...", "..###..", ".##.##.", "##...##", "#.....#",
                    "......."};
            case CHEVRON_DOWN -> new String[]{".......", "#.....#", "##...##", ".##.##.", "..###..", "...#...",
                    "......."};
            case SEARCH -> new String[]{".###...", "#...#..", "#...#..", "#...#..", ".###...", "....##.", ".....##"};
            case SETTINGS -> new String[]{"..#.#..", ".#####.", "##...##", ".#...#.", "##...##", ".#####.",
                    "..#.#.."};
            case BACK -> new String[]{".......", "..#....", ".##....", "#######", ".##....", "..#....", "......."};
        };
    }
}
