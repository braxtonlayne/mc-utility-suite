package com.utilitysuite.world.atlas;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.minimap.MinimapPalette;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

import java.util.List;

/**
 * World Atlas (V1): a full-screen 2D / 3D map ({@link WorldAtlasScreen}) opened with the Open World Atlas key or the
 * card button. Terrain comes from {@link AtlasTileStore}, filled on the client tick a few chunks at a time (centre
 * first, loaded chunks only, re-sampled after {@link #REFRESH_TICKS}) with the minimap's palette and height shading.
 * The switch means "keep recording while you play"; with it off the atlas still samples while the screen is open.
 * Zero allocation per tick once a chunk's tile exists. Off by default.
 */
public final class WorldAtlasModule extends WorldModule {

    public static final String ID = "world_atlas";
    static final int REFRESH_TICKS = 1200;
    /** Chunk spiral radius around the player (covers render distances up to 32). */
    static final int RADIUS = 32;
    private static final int MAX_CHECKS = 512;

    final AtlasTileStore store = new AtlasTileStore(8192);
    private final int[] spiral = AtlasSpiral.build(RADIUS);
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private int cursor;
    private int lastCx = Integer.MIN_VALUE;
    private int lastCz = Integer.MIN_VALUE;
    private String sampledDimension = "";
    private MinimapPalette.Style sampledStyle;
    private long ticks;

    // Settings.
    int chunksPerTick = 2;
    MinimapPalette.Style style = MinimapPalette.Style.CLEAN;
    int maxChunks = 8192;
    boolean layerWaypoints = true;
    boolean layerSeed = true;
    boolean layerDiff = true;
    boolean layerTrail = true;
    double heightScale = 1.5;

    public WorldAtlasModule() {
        super(ID, "World Atlas", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice("style", "Map style", MinimapPalette.Style.CLEAN, "",
                        "Colours of the atlas terrain (same palettes as the minimap)."),
                SettingSpec.bool("layer_waypoints", "Waypoints and portal links", true, "Layers",
                        "Saved waypoints; portals from the other dimension at their linked coordinates."),
                SettingSpec.bool("layer_seed", "Seed Locator pins", true, "Layers",
                        "Predicted structures and slime spots (needs a known seed)."),
                SettingSpec.bool("layer_diff", "Seed Diff anomalies", true, "Layers",
                        "Sections that differ from the seed's generated terrain."),
                SettingSpec.bool("layer_trail", "Chunk age heat", true, "Layers",
                        "Tints chunks other players already loaded (OLD) and new ones (NEW)."),
                SettingSpec.decimal("height_scale", "3D height scale", 1.5, 0.5, 4.0, "3D",
                        "Vertical exaggeration of the hologram."),
                SettingSpec.integer("chunks_per_tick", "Chunks sampled per tick", 2, 1, 16, "Advanced",
                        "Map sampling budget (256 columns per chunk).").asAdvanced(),
                SettingSpec.integer("max_chunks", "Chunks kept in memory", 8192, 1024, 32768, "Advanced",
                        "About 1.5 KB each; the farthest are dropped first.").asAdvanced());
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        style = choice(p, "style", MinimapPalette.Style.class);
        layerWaypoints = bool(p, "layer_waypoints");
        layerSeed = bool(p, "layer_seed");
        layerDiff = bool(p, "layer_diff");
        layerTrail = bool(p, "layer_trail");
        heightScale = decimal(p, "height_scale");
        chunksPerTick = integer(p, "chunks_per_tick");
        maxChunks = integer(p, "max_chunks");
        store.setMaxTiles(maxChunks);
    }

    /** Opens the atlas (key handler and card button). Only in a world; replaces the current screen. */
    public void openScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        mc.gui.setScreen(new WorldAtlasScreen(this));
    }

    public AtlasTileStore store() {
        return store;
    }

    @Override
    public void onWorldUnload() {
        store.clear();
        sampledDimension = "";
        cursor = 0;
    }

    @Override
    public void onTick() {
        sample();
    }

    /** One budgeted sampling step (module tick, or the atlas screen's tick while it is open). Client thread. */
    void sample() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        ClientLevel level = mc.level;
        if (self == null || level == null) {
            return;
        }
        ticks++;
        String dim = WorldIds.dimension(level);
        if (!dim.equals(sampledDimension) || style != sampledStyle) {
            sampledDimension = dim;
            sampledStyle = style;
            store.clear();
            cursor = 0;
        }
        int pcx = self.getBlockX() >> 4;
        int pcz = self.getBlockZ() >> 4;
        if (pcx != lastCx || pcz != lastCz) {
            lastCx = pcx;
            lastCz = pcz;
            cursor = 0;
            if ((ticks & 63) == 0) {
                store.evictBeyondCap(pcx, pcz);
            }
        }
        int done = 0;
        int checks = 0;
        int n = spiral.length;
        while (done < chunksPerTick && checks < MAX_CHECKS) {
            if (cursor >= n) {
                cursor = 0;
            }
            int packed = spiral[cursor++];
            checks++;
            int cx = pcx + AtlasSpiral.dx(packed);
            int cz = pcz + AtlasSpiral.dz(packed);
            if (!level.hasChunk(cx, cz)) {
                continue;
            }
            AtlasTileStore.Tile t = store.get(cx, cz);
            if (t != null && ticks - t.sampledTick < REFRESH_TICKS) {
                continue;
            }
            sampleChunk(level, store.obtain(cx, cz));
            done++;
        }
        if (store.size() > store.maxTiles()) {
            store.evictBeyondCap(pcx, pcz);
        }
    }

    private void sampleChunk(ClientLevel level, AtlasTileStore.Tile t) {
        int minY = level.getMinY();
        int baseX = t.chunkX << 4;
        int baseZ = t.chunkZ << 4;
        boolean northLoaded = level.hasChunk(t.chunkX, t.chunkZ - 1);
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int bx = baseX + lx;
                int bz = baseZ + lz;
                int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
                int y = h - 1;
                MapColor color = MapColor.NONE;
                for (int i = 0; i < 8 && y >= minY; i++, y--) {
                    color = level.getBlockState(probe.set(bx, y, bz)).getMapColor(level, probe);
                    if (color != MapColor.NONE) {
                        break;
                    }
                }
                int idx = lz * 16 + lx;
                if (color == MapColor.NONE) {
                    t.argb[idx] = MinimapPalette.UNKNOWN;
                    t.height[idx] = (short) h;
                    continue;
                }
                boolean water = color == MapColor.WATER;
                int north = lz > 0 || northLoaded ? level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz - 1) : h;
                int shade = water ? MinimapPalette.SHADE_NORMAL : MinimapPalette.shadeFor(h, north);
                t.argb[idx] = MinimapPalette.apply(style, color.col, shade, water, false);
                t.height[idx] = (short) (water ? y + 1 : h);
            }
        }
        store.markWritten(t, ticks);
    }
}
