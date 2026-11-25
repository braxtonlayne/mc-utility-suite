package com.utilitysuite.jobs.schematic.gen;

import com.utilitysuite.jobs.schematic.model.SchematicVolume;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Engine-free map-art planner: scales an image to 128 x 128 per map (N maps wide along X), quantises it to the map
 * colour palette with Floyd-Steinberg dithering, then lays the blocks out flat (shade 220 only) or as a staircase
 * (each column's height steps up/down from north to south so shades 255/180 appear). The output volume is
 * {@code 128N} wide, {@code 129} long (row 0 is the northern reference row the first map row is shaded against) and
 * as tall as the tallest column; it is anchored with its north-west corner at the anchor. Runs on the schematic
 * thread.
 */
public final class MapArtPlanner {

    public static final int MAP = 128;
    public static final int MAX_MAPS = 4;

    public record Options(int mapsWide, boolean staircase, boolean dither, boolean cheapOnly) {
        public Options {
            mapsWide = Math.max(1, Math.min(MAX_MAPS, mapsWide));
        }
    }

    /** Quantised picture: palette entry index per pixel, row-major ({@code z * width + x}). */
    public record Quantized(int width, int height, int[] entry, MapArtPalette palette) {
    }

    private MapArtPlanner() {
    }

    /** Reads a PNG/JPG with ImageIO into ARGB pixels (call off-thread). */
    public static int[][] readImage(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("No such image: " + file);
        }
        BufferedImage img = ImageIO.read(file.toFile());
        if (img == null) {
            throw new IOException("Not a readable PNG/JPG: " + file.getFileName());
        }
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        return new int[][]{{w, h}, px};
    }

    /** Box-filter (area average) scale of ARGB pixels; transparent pixels count as white. */
    public static int[] scale(int[] src, int sw, int sh, int dw, int dh) {
        int[] out = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int y0 = y * sh / dh, y1 = Math.max(y0 + 1, (y + 1) * sh / dh);
            for (int x = 0; x < dw; x++) {
                int x0 = x * sw / dw, x1 = Math.max(x0 + 1, (x + 1) * sw / dw);
                long r = 0, g = 0, b = 0, n = 0;
                for (int yy = y0; yy < y1; yy++) {
                    for (int xx = x0; xx < x1; xx++) {
                        int c = src[yy * sw + xx];
                        int a = (c >>> 24) & 0xFF;
                        r += (((c >> 16) & 0xFF) * a + 255 * (255 - a)) / 255;
                        g += (((c >> 8) & 0xFF) * a + 255 * (255 - a)) / 255;
                        b += ((c & 0xFF) * a + 255 * (255 - a)) / 255;
                        n++;
                    }
                }
                out[y * dw + x] = (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
            }
        }
        return out;
    }

    /** Quantises RGB pixels to the palette, with Floyd-Steinberg error diffusion when {@code dither}. */
    public static Quantized quantize(int[] rgb, int w, int h, MapArtPalette pal, boolean dither) {
        float[] err = new float[w * h * 3];
        for (int i = 0; i < w * h; i++) {
            err[i * 3] = (rgb[i] >> 16) & 0xFF;
            err[i * 3 + 1] = (rgb[i] >> 8) & 0xFF;
            err[i * 3 + 2] = rgb[i] & 0xFF;
        }
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                float r = clamp(err[i * 3]), g = clamp(err[i * 3 + 1]), b = clamp(err[i * 3 + 2]);
                int e = pal.nearest(r, g, b);
                out[i] = e;
                if (!dither) {
                    continue;
                }
                int c = pal.entry(e).rgb();
                float er = r - ((c >> 16) & 0xFF), eg = g - ((c >> 8) & 0xFF), eb = b - (c & 0xFF);
                spread(err, w, h, x + 1, y, er, eg, eb, 7 / 16f);
                spread(err, w, h, x - 1, y + 1, er, eg, eb, 3 / 16f);
                spread(err, w, h, x, y + 1, er, eg, eb, 5 / 16f);
                spread(err, w, h, x + 1, y + 1, er, eg, eb, 1 / 16f);
            }
        }
        return new Quantized(w, h, out, pal);
    }

    private static void spread(float[] err, int w, int h, int x, int y, float er, float eg, float eb, float f) {
        if (x < 0 || x >= w || y >= h) {
            return;
        }
        int i = (y * w + x) * 3;
        err[i] += er * f;
        err[i + 1] += eg * f;
        err[i + 2] += eb * f;
    }

    private static float clamp(float v) {
        return v < 0 ? 0 : v > 255 ? 255 : v;
    }

    /**
     * Column heights from the shades: index {@code z * width + x} for z in {@code 0..height} (z = 0 is the reference
     * row). Each column is lifted so its lowest block sits at 0.
     */
    public static int[] staircase(Quantized q) {
        int w = q.width(), h = q.height();
        int[] hts = new int[w * (h + 1)];
        for (int x = 0; x < w; x++) {
            int cur = 0, min = 0;
            for (int z = 0; z < h; z++) {
                int shade = q.palette().entry(q.entry()[z * w + x]).shade();
                cur += shade == MapArtPalette.SHADE_HIGH ? 1 : shade == MapArtPalette.SHADE_LOW ? -1 : 0;
                hts[(z + 1) * w + x] = cur;
                min = Math.min(min, cur);
            }
            for (int z = 0; z <= h; z++) {
                hts[z * w + x] -= min;
            }
        }
        return hts;
    }

    /** Full pipeline from ARGB pixels to a buildable volume. */
    public static SchematicVolume plan(String name, int[] argb, int sw, int sh, Options o) {
        int w = MAP * o.mapsWide(), h = MAP;
        int[] scaled = scale(argb, sw, sh, w, h);
        Quantized q = quantize(scaled, w, h, MapArtPalette.of(o.staircase(), o.cheapOnly()), o.dither());
        return toVolume(name, q, o.staircase());
    }

    public static SchematicVolume toVolume(String name, Quantized q, boolean staircase) {
        int w = q.width(), h = q.height();
        int[] hts = staircase ? staircase(q) : new int[w * (h + 1)];
        int sy = 1;
        for (int v : hts) {
            sy = Math.max(sy, v + 1);
        }
        SchematicVolume.Builder b = SchematicVolume.builder(name, "map_art", w, sy, h + 1);
        int ref = b.paletteId("minecraft:cobblestone");
        for (int x = 0; x < w; x++) {
            b.setId(x, hts[x], 0, ref); // reference row: shades the first map row, not part of the picture
            for (int z = 0; z < h; z++) {
                int y = hts[(z + 1) * w + x];
                b.setId(x, y, z + 1, b.paletteId(q.palette().entry(q.entry()[z * w + x]).block()));
            }
        }
        return b.build();
    }

    /** Blocks per id for the card (order of first use). */
    public static Map<String, Integer> blockCounts(SchematicVolume v) {
        int[] hist = v.histogram();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 1; i < hist.length; i++) {
            if (hist[i] > 0 && v.isBlock(i)) {
                out.merge(v.paletteEntry(i), hist[i], Integer::sum);
            }
        }
        return out;
    }
}
