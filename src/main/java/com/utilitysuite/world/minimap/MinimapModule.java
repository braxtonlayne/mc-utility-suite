package com.utilitysuite.world.minimap;

import com.mojang.blaze3d.platform.NativeImage;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.overlays.ChunkAgeTracker;
import com.utilitysuite.world.overlays.WorldScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.Arrays;
import java.util.List;

/**
 * Minimap: a round (or square) top-down map drawn as a GUI layer ({@link MinimapHud}). Terrain lives in a
 * {@value #TEX}x{@value #TEX} wrapping texture filled incrementally on the client tick through {@link TexelRing}
 * (a budgeted number of columns per tick, centre first); frames never query the world. Entity dots are gathered on
 * the tick into reused arrays. Off by default.
 */
public final class MinimapModule extends WorldModule {

    public static final String ID = "minimap";
    /** Texture size in texels (power of two). The map shows a radius of {@link #VIEW_TEXELS} texels. */
    public static final int TEX = 256;
    public static final int VIEW_TEXELS = 64;
    static final int MAX_DOTS = 128;
    static final int DOT_HOSTILE = 0;
    static final int DOT_PLAYER = 1;
    static final int DOT_PASSIVE = 2;

    public enum Shape { ROUND, SQUARE }

    public enum Zoom {
        X1(1), X2(2), X4(4);
        final int blocks;

        Zoom(int blocks) {
            this.blocks = blocks;
        }
    }

    private final TexelRing ring = new TexelRing(TEX);
    private final MinimapHud hud = new MinimapHud(this);
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final TexelRing.Sampler sampler = this::sample;
    private final TexelRing.PixelSink sink = this::setPixel;

    // Settings.
    MinimapPalette.Style style = MinimapPalette.Style.MILITARY;
    Shape shape = Shape.ROUND;
    boolean rotate = true;
    int zoom = 1;
    int size = 128;
    double xFrac;
    double yFrac;
    boolean dotHostile = true;
    boolean dotPlayers = true;
    boolean dotPassive = true;
    boolean trailHeatmap;
    float opacity = 1f;
    int budget = 512;

    // Texture (render thread == client thread).
    NativeImage image;
    boolean dirty;
    private ClientLevel sampledLevel;
    private MinimapPalette.Style sampledStyle;
    private int sampledZoom;
    private boolean sampledTrailHeatmap;

    // Entity dots gathered on the tick.
    final Entity[] dots = new Entity[MAX_DOTS];
    final int[] dotKind = new int[MAX_DOTS];
    int dotCount;
    private int ticks;

    public MinimapModule() {
        super(ID, "Minimap", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice("style", "Style", MinimapPalette.Style.MILITARY, "",
                        "MILITARY: olive tactical palette with chunk grid. CLEAN: dark glass, no grid. CLASSIC: true "
                                + "map colours, square."),
                SettingSpec.choice("shape", "Shape", Shape.ROUND, "", "Round or square (CLASSIC is always square)."),
                SettingSpec.bool("rotate", "Rotate with player", true, "", "Your facing is always up; off = north up."),
                SettingSpec.choice("zoom", "Zoom (blocks per pixel)", Zoom.X1, "", "1, 2 or 4 blocks per map texel."),
                SettingSpec.integer("size", "Size", 128, MinimapLayout.MIN_SIZE, MinimapLayout.MAX_SIZE, "Layout",
                        "Diameter in GUI pixels. Also resizable in Edit HUD layout."),
                SettingSpec.decimal("x", "X position", 0.0, 0.0, 1.0, "Layout", "0 = left edge, 1 = right edge."),
                SettingSpec.decimal("y", "Y position", 0.0, 0.0, 1.0, "Layout", "0 = top edge, 1 = bottom edge."),
                SettingSpec.decimal("opacity", "Opacity", 1.0, 0.2, 1.0, "Layout", "Opacity of the whole minimap."),
                SettingSpec.bool("dot_hostile", "Hostile mobs", true, "Dots", "Red dots."),
                SettingSpec.bool("dot_players", "Players", true, "Dots", "Amber dots."),
                SettingSpec.bool("dot_passive", "Passive mobs", true, "Dots", "Green dots."),
                SettingSpec.bool("trail_heatmap", "Trail heatmap", false, "",
                        "Tint OLD explored chunks on the minimap."),
                SettingSpec.integer("budget", "Columns per tick", 512, 64, 4096, "Advanced",
                        "How many map columns are sampled per tick.").asAdvanced());
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        style = choice(p, "style", MinimapPalette.Style.class);
        shape = choice(p, "shape", Shape.class);
        rotate = bool(p, "rotate");
        zoom = choice(p, "zoom", Zoom.class).blocks;
        size = integer(p, "size");
        xFrac = decimal(p, "x");
        yFrac = decimal(p, "y");
        opacity = (float) decimal(p, "opacity");
        dotHostile = bool(p, "dot_hostile");
        dotPlayers = bool(p, "dot_players");
        dotPassive = bool(p, "dot_passive");
        trailHeatmap = bool(p, "trail_heatmap");
        budget = integer(p, "budget");
    }

    /** Effective shape (CLASSIC forces square). */
    public Shape effectiveShape() {
        return style == MinimapPalette.Style.CLASSIC ? Shape.SQUARE : shape;
    }

    /** The GUI layer (registered by the world bootstrap). */
    public MinimapHud hud() {
        return hud;
    }

    /** Opens the HUD edit screen; returns to the current screen afterwards. */
    public void openLayoutEditor() {
        Minecraft mc = Minecraft.getInstance();
        mc.gui.setScreen(new MinimapLayoutScreen(this, hud, mc.gui.screen()));
    }

    /** Writes the layout chosen in the HUD edit screen to the profile. */
    public void saveLayout(double xf, double yf, int newSize) {
        IModState s = Services.modState();
        ProfileConfig p = s.profile();
        p.set(ID, "x", xf);
        p.set(ID, "y", yf);
        p.set(ID, "size", newSize);
        s.markDirty();
        onSettingChanged("size");
    }

    @Override
    protected void onDisable() {
        clearDots();
    }

    @Override
    public void onWorldUnload() {
        clearDots();
        sampledLevel = null;
        ring.clear();
    }

    private void clearDots() {
        Arrays.fill(dots, null);
        dotCount = 0;
    }

    // ---- tick ---------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        ClientLevel level = mc.level;
        if (self == null || level == null || image == null) {
            return; // texture is created by the first frame
        }
        if (level != sampledLevel || style != sampledStyle || zoom != sampledZoom
                || trailHeatmap != sampledTrailHeatmap) {
            sampledLevel = level;
            sampledStyle = style;
            sampledZoom = zoom;
            sampledTrailHeatmap = trailHeatmap;
            ring.clear();
        }
        int cx = Math.floorDiv(self.getBlockX(), zoom);
        int cz = Math.floorDiv(self.getBlockZ(), zoom);
        if (ring.update(cx, cz, budget, 65536, budget / 8, sampler, sink) > 0) {
            dirty = true;
        }
        if (++ticks % 2 == 0) {
            gatherDots(self, level);
        }
    }

    private void setPixel(int x, int y, int argb) {
        image.setPixelABGR(x, y, MinimapPalette.toAbgr(argb));
    }

    /** Samples one world texel (client thread, level loaded). Zero allocation. */
    private int sample(int tx, int tz) {
        ClientLevel level = sampledLevel;
        int z = zoom;
        int bx = tx * z + (z >> 1);
        int bz = tz * z + (z >> 1);
        if (!level.hasChunk(bx >> 4, bz >> 4)) {
            return MinimapPalette.UNKNOWN;
        }
        int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
        int y = h - 1;
        int minY = level.getMinY();
        MapColor color = MapColor.NONE;
        BlockState state = null;
        for (int i = 0; i < 8 && y >= minY; i++, y--) {
            state = level.getBlockState(probe.set(bx, y, bz));
            color = state.getMapColor(level, probe);
            if (color != MapColor.NONE) {
                break;
            }
        }
        if (color == MapColor.NONE) {
            return MinimapPalette.UNKNOWN;
        }
        boolean water = color == MapColor.WATER;
        int shade;
        if (water) {
            // Depth shading like vanilla maps: deeper water is darker.
            int depth = 0;
            int wy = y - 1;
            while (depth < 10 && wy >= minY && level.getBlockState(probe.set(bx, wy, bz)).getMapColor(level, probe)
                    == MapColor.WATER) {
                depth++;
                wy--;
            }
            shade = depth > 6 ? MinimapPalette.SHADE_DARK : depth > 2 ? MinimapPalette.SHADE_NORMAL
                    : MinimapPalette.SHADE_BRIGHT;
        } else {
            int north = level.hasChunk(bx >> 4, (bz - z) >> 4)
                    ? level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz - z) : h;
            shade = MinimapPalette.shadeFor(h, north);
        }
        boolean grid = style == MinimapPalette.Style.MILITARY
                && (Math.floorMod(tx * z, 16) == 0 || Math.floorMod(tz * z, 16) == 0);
        int argb = MinimapPalette.apply(style, color.col, shade, water, grid);
        if (trailHeatmap) {
            ChunkAgeTracker tracker = WorldScanner.activeChunkAge();
            if (tracker != null && tracker.stateOf(bx >> 4, bz >> 4) == ChunkAgeTracker.OLD) {
                argb = tintOldTrail(argb);
            }
        }
        return argb;
    }

    private static int tintOldTrail(int argb) {
        int r = (((argb >> 16) & 0xFF) * 3 + 220) / 4;
        int g = (((argb >> 8) & 0xFF) * 3 + 170) / 4;
        int b = ((argb & 0xFF) * 3 + 40) / 4;
        return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    private void gatherDots(LocalPlayer self, ClientLevel level) {
        int n = 0;
        if (dotHostile || dotPlayers || dotPassive) {
            double r = VIEW_TEXELS * zoom * 1.5;
            double r2 = r * r;
            for (Entity e : level.entitiesForRendering()) {
                if (n >= MAX_DOTS) {
                    break;
                }
                if (e == self || !(e instanceof LivingEntity) || e.isInvisible()) {
                    continue;
                }
                double dx = e.getX() - self.getX();
                double dz = e.getZ() - self.getZ();
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                int kind;
                if (e instanceof Player) {
                    if (!dotPlayers || ((Player) e).isSpectator()) {
                        continue;
                    }
                    kind = DOT_PLAYER;
                } else if (e instanceof Enemy) {
                    if (!dotHostile) {
                        continue;
                    }
                    kind = DOT_HOSTILE;
                } else {
                    if (!dotPassive) {
                        continue;
                    }
                    kind = DOT_PASSIVE;
                }
                dotKind[n] = kind;
                dots[n++] = e;
            }
        }
        for (int i = n; i < dotCount; i++) {
            dots[i] = null;
        }
        dotCount = n;
    }

    /** Dimension id (diagnostics). */
    String dimension() {
        return sampledLevel == null ? "" : WorldIds.dimension(sampledLevel);
    }
}
