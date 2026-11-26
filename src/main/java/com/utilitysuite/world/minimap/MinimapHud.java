package com.utilitysuite.world.minimap;

import com.mojang.blaze3d.platform.NativeImage;
import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.FrameLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.kit.UiRenderUtils;
import net.minecraft.util.ARGB;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.client.gui.GuiLayer;

/**
 * Draws the minimap as a GUI layer: bezel ring with compass letters, the terrain texture as a textured fan (rotated
 * through its UVs, so the wrapping ring buffer needs no copy), entity dots, the player arrow and the coordinate plate.
 * Hidden while the module is off, in Clean-Screen, with F1 / F3, and while any screen is open (the HUD edit screen
 * calls {@link #draw} itself). The texture is uploaded only when the tick wrote texels (its sampler repeats, which
 * is DynamicTexture's default in 26.3). Geometry goes through recycled {@link GuiMesh} elements (GUI pipelines are
 * quad-only, so fans and strips are emitted as quads); per frame the only allocations are the GUI's own bounds records
 * and the frame/text states of the shared HUD kit; the coordinate string is rebuilt only when the block position
 * changes.
 */
public final class MinimapHud implements GuiLayer {

    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID,
            "minimap");
    private static final int SEGMENTS = 64;
    private static final float[] COS = new float[SEGMENTS + 1];
    private static final float[] SIN = new float[SEGMENTS + 1];
    private static final String[] LETTERS = {"N", "E", "S", "W"};
    /** World direction (x, z) of each compass letter. */
    private static final int[] LETTER_DX = {0, 1, 0, -1};
    private static final int[] LETTER_DZ = {-1, 0, 1, 0};

    static {
        for (int i = 0; i <= SEGMENTS; i++) {
            double a = Math.PI * 2 * i / SEGMENTS;
            COS[i] = (float) Math.cos(a);
            SIN[i] = (float) Math.sin(a);
        }
    }

    private final MinimapModule module;
    private DynamicTexture texture;
    private TextureSetup textureSetup;
    private final GuiMesh.Pool meshes = new GuiMesh.Pool();
    private final StringBuilder coordBuilder = new StringBuilder(32);
    private String coords = "";
    private int lastBx = Integer.MIN_VALUE;
    private int lastBy = Integer.MIN_VALUE;
    private int lastBz = Integer.MIN_VALUE;

    // Per-frame transform (world offset in blocks -> screen offset in px), set in draw().
    private float c;
    private float s;
    private float blocksPerPx;

    public MinimapHud(MinimapModule module) {
        this.module = module;
    }

    @Override
    public void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        if (!module.isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gui.hud.isHidden() || mc.gui.screen() != null || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        IModState st = Services.modState();
        if (st != null && st.isCleanScreen()) {
            return;
        }
        int w = g.guiWidth();
        int h = g.guiHeight();
        int size = MinimapLayout.clampSize(module.size, w, h);
        draw(g, MinimapLayout.toX(module.xFrac, w, size), MinimapLayout.toY(module.yFrac, h, size), size,
                delta.getGameTimeDeltaPartialTick(false));
    }

    /** Ensures the texture exists (render thread) so the tick can start filling it. */
    private void ensureTexture() {
        if (texture != null) {
            return;
        }
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, MinimapModule.TEX, MinimapModule.TEX, true);
        // 26.3 fillRect takes ARGB (setPixel); setPixelABGR is the raw write the tick uses
        img.fillRect(0, 0, MinimapModule.TEX, MinimapModule.TEX, MinimapPalette.UNKNOWN);
        // DynamicTexture's sampler is repeat + nearest, which is the wrap the ring-buffer UVs rely on
        texture = new DynamicTexture(() -> "utilitysuite minimap", img);
        Minecraft.getInstance().getTextureManager().register(TEXTURE, texture);
        textureSetup = TextureSetup.singleTexture(texture.getTextureView(), texture.getSampler());
        module.image = img;
        module.dirty = false;
    }

    /** Draws the minimap with its body's top-left at (x, y). Also used by the HUD edit screen. */
    public void draw(GuiGraphicsExtractor g, int x, int y, int size, float pt) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        if (self == null) {
            return;
        }
        ensureTexture();
        meshes.reset();
        if (module.dirty) {
            texture.upload();
            module.dirty = false;
        }
        MinimapPalette.Style style = module.style;
        boolean round = module.effectiveShape() == MinimapModule.Shape.ROUND;
        float op = module.opacity;
        float half = size / 2f;
        float cx = x + half;
        float cy = y + half;
        int zoom = module.zoom;

        // Map orientation: rotate so facing is up, or a fixed north-up (yaw 180).
        float yaw = module.rotate ? self.getViewYRot(pt) : 180f;
        float rad = yaw * Mth.DEG_TO_RAD;
        c = Mth.cos(rad);
        s = Mth.sin(rad);
        blocksPerPx = MinimapModule.VIEW_TEXELS * zoom / half;

        // Style = which tokens the frame, the letters and the north mark use (every style is a STEM bracket frame).
        HoloToken frameTok;
        HoloToken textTok;
        HoloToken northTok;
        switch (style) {
            case MILITARY -> {
                frameTok = HoloToken.LINE_STRONG;
                textTok = HoloToken.TEXT_SECONDARY;
                northTok = HoloToken.WARN;
            }
            case CLEAN -> {
                frameTok = HoloToken.LINE;
                textTok = HoloToken.TEXT_SECONDARY;
                northTok = HoloToken.ACCENT;
            }
            default -> {
                frameTok = HoloToken.LINE_STRONG;
                textTok = HoloToken.TEXT;
                northTok = HoloToken.ACCENT;
            }
        }

        // Frame: glass plate with hairline edge and corner brackets (square), or a hairline range ring with corner
        // brackets around it (round).
        int b = MinimapLayout.BEZEL;
        int arm = HoloTheme.bracketArm(size + 2 * b, size + 2 * b);
        if (round) {
            annulus(g, cx, cy, half + 1, half + 2, HoloTheme.argb(HoloToken.VOID, 0.6f * op));
            annulus(g, cx, cy, half, half + 1, HoloTheme.argb(HoloToken.LINE, op));
        } else {
            HoloFrames.frame(g, x - b, y - b, size + 2 * b, size + 2 * b, FrameLevel.QUIET, false, HoloToken.GLASS_HUD,
                    HoloToken.LINE, op);
        }
        HoloFrames.brackets(g, x - b, y - b, size + 2 * b, size + 2 * b, arm, HoloTheme.argb(frameTok, op));

        // Terrain: a fan whose UVs are the rotated world positions in texel space (texture wraps).
        float pxTex = (float) (Mth.lerp(pt, self.xOld, self.getX()) / zoom);
        float pzTex = (float) (Mth.lerp(pt, self.zOld, self.getZ()) / zoom);
        int baseX = Mth.floor(pxTex);
        int baseZ = Mth.floor(pzTex);
        float u0 = Math.floorMod(baseX, MinimapModule.TEX) + (pxTex - baseX);
        float v0 = Math.floorMod(baseZ, MinimapModule.TEX) + (pzTex - baseZ);
        float texPerPx = MinimapModule.VIEW_TEXELS / half;
        float inv = 1f / MinimapModule.TEX;
        int texColor = ARGB.white((int) (255 * op)); // white tint: the texture keeps its own colours
        GuiMesh tex = meshes.beginTextured(g, textureSetup);
        if (round) {
            // the fan (centre, rim i, rim i-1, ...) as quads: each quad (centre, rim i, rim i-1, rim i-2) covers two
            // fan triangles
            for (int i = SEGMENTS; i >= 2; i -= 2) {
                texVertex(tex, cx, cy, 0f, 0f, u0, v0, texPerPx, inv, texColor);
                texVertex(tex, cx, cy, COS[i] * half, SIN[i] * half, u0, v0, texPerPx, inv, texColor);
                texVertex(tex, cx, cy, COS[i - 1] * half, SIN[i - 1] * half, u0, v0, texPerPx, inv, texColor);
                texVertex(tex, cx, cy, COS[i - 2] * half, SIN[i - 2] * half, u0, v0, texPerPx, inv, texColor);
            }
        } else {
            texVertex(tex, cx, cy, half, -half, u0, v0, texPerPx, inv, texColor);
            texVertex(tex, cx, cy, -half, -half, u0, v0, texPerPx, inv, texColor);
            texVertex(tex, cx, cy, -half, half, u0, v0, texPerPx, inv, texColor);
            texVertex(tex, cx, cy, half, half, u0, v0, texPerPx, inv, texColor);
        }
        tex.submit(g);
        if (style == MinimapPalette.Style.CLEAN) {
            // Dark glass tint over the terrain.
            if (round) {
                disc(g, cx, cy, half, HoloTheme.argb(HoloToken.VOID, 0.31f * op));
            } else {
                HoloFrames.plate(g, x, y, size, size, HoloToken.VOID, 0.31f * op);
            }
        }

        // Hairline range ring at half range, and the cardinal ticks on the edge (rotating with the map).
        float rangeR = half * 0.5f;
        annulus(g, cx, cy, rangeR - 0.5f, rangeR + 0.5f, HoloTheme.argb(HoloToken.LINE_FAINT, op));
        cardinalTicks(g, cx, cy, half, round, HoloTheme.argb(HoloToken.LINE, op), HoloTheme.argb(northTok, op));

        // Entity dots.
        double selfX = Mth.lerp(pt, self.xOld, self.getX());
        double selfZ = Mth.lerp(pt, self.zOld, self.getZ());
        float limit = half - 2;
        for (int i = 0; i < module.dotCount; i++) {
            Entity e = module.dots[i];
            if (e == null || e.isRemoved()) {
                continue;
            }
            float wx = (float) (Mth.lerp(pt, e.xOld, e.getX()) - selfX);
            float wz = (float) (Mth.lerp(pt, e.zOld, e.getZ()) - selfZ);
            float sx = screenX(wx, wz);
            float sy = screenY(wx, wz);
            if (round ? sx * sx + sy * sy > limit * limit : Math.abs(sx) > limit || Math.abs(sy) > limit) {
                continue;
            }
            HoloToken dot = module.dotKind[i] == MinimapModule.DOT_HOSTILE ? HoloToken.DANGER
                    : module.dotKind[i] == MinimapModule.DOT_PLAYER ? HoloToken.WARN : HoloToken.ALLY;
            int ix = Math.round(cx + sx);
            int iy = Math.round(cy + sy);
            HoloFrames.plate(g, ix - 2, iy - 2, 4, 4, HoloToken.VOID, op * 0.7f);
            HoloFrames.plate(g, ix - 1, iy - 1, 2, 2, dot, op);
        }

        // Player arrow: facing direction on screen.
        float fyaw = self.getViewYRot(pt) * Mth.DEG_TO_RAD;
        float fwx = -Mth.sin(fyaw);
        float fwz = Mth.cos(fyaw);
        float fx = screenX(fwx, fwz) * blocksPerPx;
        float fy = screenY(fwx, fwz) * blocksPerPx;
        float rx = -fy;
        float ry = fx;
        // the player as an accent chevron over a dark outline
        arrow(g, cx, cy, fx, fy, rx, ry, 1.4f, HoloTheme.argb(HoloToken.VOID, 0.75f * op));
        arrow(g, cx, cy, fx, fy, rx, ry, 1f, HoloTheme.argb(HoloToken.ACCENT, op));

        // Compass letters on the bezel.
        Font font = mc.font;
        float ringR = half + b / 2f;
        for (int i = 0; i < 4; i++) {
            float lx = screenX(LETTER_DX[i], LETTER_DZ[i]) * blocksPerPx;
            float ly = screenY(LETTER_DX[i], LETTER_DZ[i]) * blocksPerPx;
            if (!round) {
                // Push onto the square frame.
                float k = Math.max(Math.abs(lx), Math.abs(ly));
                lx /= k;
                ly /= k;
            }
            String letter = LETTERS[i];
            int tx = Math.round(cx + lx * ringR) - font.width(letter) / 2;
            int ty = Math.round(cy + ly * ringR) - 4;
            UiRenderUtils.text(g, font, letter, tx, ty, HoloTheme.argb(i == 0 ? northTok : textTok, op));
        }

        // Coordinate plate.
        int bx = self.getBlockX();
        int by = self.getBlockY();
        int bz = self.getBlockZ();
        if (bx != lastBx || by != lastBy || bz != lastBz) {
            lastBx = bx;
            lastBy = by;
            lastBz = bz;
            coordBuilder.setLength(0);
            coordBuilder.append(bx).append(", ").append(by).append(", ").append(bz);
            coords = coordBuilder.toString();
        }
        int tw = font.width(coords);
        int px = Math.round(cx) - tw / 2 - 4;
        int py = y + size + b + 2;
        HoloFrames.frame(g, px, py, tw + 8, 11, FrameLevel.QUIET, false, HoloToken.GLASS_HUD, HoloToken.LINE, op);
        UiRenderUtils.text(g, font, coords, px + 4, py + 2, HoloTheme.argb(HoloToken.TEXT_SECONDARY, op));
    }

    /** Tick length of the north mark and of the other three cardinal ticks, in px. */
    private static final float TICK_NORTH = 5f;
    private static final float TICK_OTHER = 3f;
    private static final float TICK_WIDTH = 1f;

    /**
     * The four cardinal ticks on the edge of the map, pointing inwards, in one quad batch: north in
     * {@code northArgb}, the others in {@code argb}. On a square map they sit on the frame edge.
     */
    private void cardinalTicks(GuiGraphicsExtractor g, float cx, float cy, float half, boolean round, int argb,
                               int northArgb) {
        GuiMesh buf = meshes.begin(g);
        for (int i = 0; i < 4; i++) {
            float dx = screenX(LETTER_DX[i], LETTER_DZ[i]) * blocksPerPx;
            float dy = screenY(LETTER_DX[i], LETTER_DZ[i]) * blocksPerPx;
            if (!round) {
                float k = Math.max(Math.abs(dx), Math.abs(dy));
                dx /= k;
                dy /= k;
            }
            float outer = half; // the edge of the terrain
            float len = i == 0 ? TICK_NORTH : TICK_OTHER;
            float px = -dy * TICK_WIDTH * 0.5f;
            float py = dx * TICK_WIDTH * 0.5f;
            // the tick runs from the edge point inwards along the radial direction (unit for a round map)
            float ex = cx + dx * outer;
            float ey = cy + dy * outer;
            float ix = ex - dx * len;
            float iy = ey - dy * len;
            int col = i == 0 ? northArgb : argb;
            buf.quad(ex - px, ey - py, ex + px, ey + py, ix + px, iy + py, ix - px, iy - py, col);
        }
        buf.submit(g);
    }

    /** World offset (blocks) to screen offset (px). Inverse of the orthonormal map rotation. */
    private float screenX(float wx, float wz) {
        return (-c * wx - s * wz) / blocksPerPx;
    }

    private float screenY(float wx, float wz) {
        return (s * wx - c * wz) / blocksPerPx;
    }

    private void texVertex(GuiMesh buf, float cx, float cy, float sx, float sy, float u0, float v0,
                           float texPerPx, float inv, int argb) {
        // Screen offset -> world texel offset (rotation by the map yaw).
        float wx = (-c * sx + s * sy) * texPerPx;
        float wz = (-s * sx - c * sy) * texPerPx;
        buf.vertex(cx + sx, cy + sy, (u0 + wx) * inv, (v0 + wz) * inv, argb);
    }

    private void annulus(GuiGraphicsExtractor g, float cx, float cy, float r0, float r1, int argb) {
        GuiMesh buf = meshes.begin(g);
        for (int i = 0; i < SEGMENTS; i++) {
            buf.quad(cx + COS[i] * r1, cy + SIN[i] * r1, cx + COS[i] * r0, cy + SIN[i] * r0,
                    cx + COS[i + 1] * r0, cy + SIN[i + 1] * r0, cx + COS[i + 1] * r1, cy + SIN[i + 1] * r1, argb);
        }
        buf.submit(g);
    }

    private void disc(GuiGraphicsExtractor g, float cx, float cy, float r, int argb) {
        GuiMesh buf = meshes.begin(g);
        // fan as quads, two triangles each
        for (int i = SEGMENTS; i >= 2; i -= 2) {
            buf.quad(cx, cy, cx + COS[i] * r, cy + SIN[i] * r, cx + COS[i - 1] * r, cy + SIN[i - 1] * r,
                    cx + COS[i - 2] * r, cy + SIN[i - 2] * r, argb);
        }
        buf.submit(g);
    }

    /** Arrow from forward (fx, fy) and right (rx, ry) unit vectors in screen space. */
    private void arrow(GuiGraphicsExtractor g, float cx, float cy, float fx, float fy, float rx, float ry, float scale,
                       int argb) {
        float tipF = 6 * scale;
        float backF = -4 * scale;
        float notchF = -1.5f * scale;
        float side = 4 * scale;
        float tx = cx + fx * tipF;
        float ty = cy + fy * tipF;
        float nx = cx + fx * notchF;
        float ny = cy + fy * notchF;
        float lx = cx + fx * backF - rx * side;
        float ly = cy + fy * backF - ry * side;
        float qx = cx + fx * backF + rx * side;
        float qy = cy + fy * backF + ry * side;
        // The chevron as one quad (tip, left, notch, right) = triangles (tip, left, notch) and (notch, right, tip).
        GuiMesh buf = meshes.begin(g);
        buf.quad(tx, ty, lx, ly, nx, ny, qx, qy, argb);
        buf.submit(g);
    }
}
