package com.utilitysuite.world.atlas;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.NativeImage;
import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.minimap.GuiMesh;
import com.utilitysuite.world.minimap.MinimapPalette;
import com.utilitysuite.world.overlays.ChunkAgeTracker;
import com.utilitysuite.world.overlays.WorldScanner;
import com.utilitysuite.world.seed.SeedDiffModule;
import com.utilitysuite.world.seed.SeedDiffService;
import com.utilitysuite.world.seed.SeedLocatorModule;
import com.utilitysuite.world.seed.StructureLocator;
import com.utilitysuite.world.spawn.SlimeAfkFinder;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen World Atlas. 2D: region textures (256x256 blocks each, composed from {@link AtlasTileStore} a couple per
 * frame and uploaded only when their region version changed) under pan/zoom from {@link AtlasView}; chunk-age heat
 * and layer pins on top. 3D: the {@link AtlasMesh} grid projected by {@link AtlasIso} as a tiltable/rotatable
 * hologram, drawn back to front in one batched quad buffer, with the same pins as glowing beacons. Left click (no
 * drag) or right click opens "Add waypoint" / "Go here". Pins and labels are rebuilt once a second, not per frame.
 */
public final class WorldAtlasScreen extends Screen {

    private static final int TOP = 26;
    private static final int MAX_REGIONS = 48;
    private static final int REGION_BUILDS_PER_FRAME = 2;
    private static final int MESH_CELLS = 48;
    private static final int EMPTY = 0xFF0C1014;
    private static final int[] PIN_COLORS = {0xFFFFC857, 0xFFB57BFF, 0xFF4FD1FF, 0xFF7CFC6A, 0xFFFF5C5C};
    private static final String[] KIND_NAMES = {"Waypoint", "Portal link", "Structure", "Slime spot", "Anomaly"};
    private static final String NAV_OWNER = WorldAtlasModule.ID;

    private static final class RegionTex {
        final DynamicTexture tex;
        final Identifier id;
        final int rx;
        final int rz;
        int version = -1;

        RegionTex(int rx, int rz, DynamicTexture tex, Identifier id) {
            this.rx = rx;
            this.rz = rz;
            this.tex = tex;
            this.id = id;
        }
    }

    private final WorldAtlasModule module;
    private final AtlasView view = new AtlasView();
    private final AtlasIso iso = new AtlasIso();
    private final AtlasMesh mesh = new AtlasMesh(MESH_CELLS);
    private final AtlasPins pins = new AtlasPins(512);
    private final Long2ObjectOpenHashMap<RegionTex> regions = new Long2ObjectOpenHashMap<>();
    private final int[] compose = new int[AtlasTileStore.REGION_BLOCKS * AtlasTileStore.REGION_BLOCKS];
    private final float[] vx = new float[(MESH_CELLS + 1) * (MESH_CELLS + 1)];
    private final float[] vy = new float[(MESH_CELLS + 1) * (MESH_CELLS + 1)];
    private final List<PillButton> chips = new ArrayList<>();
    private final GuiMesh.Pool meshes = new GuiMesh.Pool();
    private boolean mode3d;
    private int texSerial;
    private int frame;
    private int tickCount;
    private String dimension = "";

    // Pointer state.
    private boolean dragging;
    private double pressX;
    private double pressY;
    private boolean moved;
    private int pressButton = -1;

    // Context menu.
    private boolean menuOpen;
    private int menuX;
    private int menuY;
    private int menuBlockX;
    private int menuBlockY;
    private int menuBlockZ;
    private PillButton addButton;
    private PillButton goButton;
    private String status = "";
    private int statusTicks;

    public WorldAtlasScreen(WorldAtlasModule module) {
        super(Component.literal("World Atlas"));
        this.module = module;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            view.center(p.getX(), p.getZ());
        }
    }

    @Override
    protected void init() {
        view.setViewport(width, height - TOP);
        rebuildChips();
        addButton = new PillButton(font, "Add waypoint", 84, PillButton.Style.SECONDARY, b -> addWaypoint());
        goButton = new PillButton(font, "Go here", 84, PillButton.Style.PRIMARY, b -> goHere());
        refreshPins();
    }

    private void rebuildChips() {
        chips.clear();
        chip(mode3d ? "3D" : "2D", true, () -> {
            mode3d = !mode3d;
            menuOpen = false;
        });
        chip("Waypoints", module.layerWaypoints, () -> setLayer("layer_waypoints", !module.layerWaypoints));
        chip("Seed pins", module.layerSeed, () -> setLayer("layer_seed", !module.layerSeed));
        chip("Seed diff", module.layerDiff, () -> setLayer("layer_diff", !module.layerDiff));
        chip("Chunk heat", module.layerTrail, () -> setLayer("layer_trail", !module.layerTrail));
        chip("Centre on me", false, () -> {
            LocalPlayer p = Minecraft.getInstance().player;
            if (p != null) {
                view.center(p.getX(), p.getZ());
            }
        });
        int x = 6;
        for (PillButton b : chips) {
            b.x = x;
            b.y = 5;
            x += b.w + 4;
        }
    }

    private void chip(String label, boolean on, Runnable action) {
        PillButton b = new PillButton(font, label, font.width(label) + 16,
                on ? PillButton.Style.PRIMARY : PillButton.Style.OUTLINE, x -> {
                    action.run();
                    rebuildChips();
                }).pill(true).selected(on);
        chips.add(b);
    }

    /** Layer chips are module settings, so the choice persists in the profile like any other setting. */
    private void setLayer(String key, boolean on) {
        IModState s = Services.modState();
        ProfileConfig p = s.profile();
        p.set(WorldAtlasModule.ID, key, on);
        s.markDirty();
        module.onSettingChanged(key);
        refreshPins();
    }

    @Override
    public void tick() {
        if (!module.isEnabled()) {
            module.sample(); // the module tick is not running; keep the open map filling
        }
        if (++tickCount % 20 == 0) {
            refreshPins();
        }
        if (statusTicks > 0) {
            statusTicks--;
        }
    }

    // ---- layer data -----------------------------------------------------------------------------------------------

    private int kindMask() {
        int m = 0;
        if (module.layerWaypoints) {
            m |= 1 << AtlasPins.WAYPOINT | 1 << AtlasPins.PORTAL_LINK;
        }
        if (module.layerSeed) {
            m |= 1 << AtlasPins.STRUCTURE | 1 << AtlasPins.SLIME;
        }
        if (module.layerDiff) {
            m |= 1 << AtlasPins.ANOMALY;
        }
        return m;
    }

    private void refreshPins() {
        pins.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        dimension = WorldIds.dimension(mc.level);
        int py = mc.player.getBlockY();
        IWaypointService wps = Services.waypointsOrNull();
        if (module.layerWaypoints && wps != null) {
            for (Waypoint w : wps.all()) {
                if (dimension.equals(w.dimension())) {
                    pins.add(AtlasPins.WAYPOINT, w.x() + 0.5, w.y(), w.z() + 0.5,
                            0xFF000000 | w.argbColor(), w.name());
                } else if ("Portals".equals(w.group())) {
                    // A portal recorded in the other dimension, shown where it links to here (8:1).
                    long pos = wps.positionIn(w, dimension);
                    pins.add(AtlasPins.PORTAL_LINK, BlockPosPacking.x(pos) + 0.5, BlockPosPacking.y(pos),
                            BlockPosPacking.z(pos) + 0.5, PIN_COLORS[AtlasPins.PORTAL_LINK], "Link: " + w.name());
                }
            }
        }
        if (module.layerSeed) {
            Services.modules().find(SeedLocatorModule.class).ifPresent(loc -> {
                SeedLocatorModule.Snapshot s = loc.snapshot();
                if (s.seedKnown() && s.dim() == dimOf(dimension)) {
                    for (StructureLocator.Candidate c : s.structures()) {
                        pins.add(AtlasPins.STRUCTURE, c.chunkX() * 16 + 8, py, c.chunkZ() * 16 + 8,
                                PIN_COLORS[AtlasPins.STRUCTURE], c.type());
                    }
                    for (SlimeAfkFinder.Spot sp : s.slimeSpots()) {
                        pins.add(AtlasPins.SLIME, sp.chunkX() * 16 + 8, py, sp.chunkZ() * 16 + 8,
                                PIN_COLORS[AtlasPins.SLIME], "Slime AFK (" + sp.slimeChunks() + " chunks)");
                    }
                }
            });
        }
        if (module.layerDiff) {
            Services.modules().find(SeedDiffModule.class).ifPresent(sd -> {
                SeedDiffService svc = sd.service();
                int n = Math.min(svc.renderCount(), 128);
                for (int i = 0; i < n; i++) {
                    long key = svc.renderKey(i);
                    int cx = BlockPosPacking.x(key);
                    int sy = BlockPosPacking.y(key);
                    int cz = BlockPosPacking.z(key);
                    pins.add(AtlasPins.ANOMALY, cx * 16 + 8, sy * 16 + 8, cz * 16 + 8, PIN_COLORS[AtlasPins.ANOMALY],
                            "Anomaly " + Math.round(svc.renderConfidence(i) * 100) + "%");
                }
            });
        }
    }

    private static StructureLocator.Dim dimOf(String dim) {
        if (dim.endsWith("the_nether")) {
            return StructureLocator.Dim.NETHER;
        }
        return dim.endsWith("the_end") ? StructureLocator.Dim.END : StructureLocator.Dim.OVERWORLD;
    }

    // ---- rendering ------------------------------------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        HoloFrames.plate(g, 0, 0, width, height, HoloToken.VOID, 0.92f);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        // (Screen extracts the background itself, in its own stratum, before this)
        frame++;
        meshes.reset();
        g.enableScissor(0, TOP, width, height);
        g.pose().pushMatrix();
        g.pose().translate(0, TOP);
        int my = mouseY - TOP;
        if (mode3d) {
            render3d(g);
        } else {
            render2d(g);
        }
        drawPins(g, mouseX, my);
        drawPlayer(g);
        g.pose().popMatrix();
        g.disableScissor();
        // Top bar.
        HoloFrames.plate(g, 0, 0, width, TOP, HoloToken.GLASS_RAISED, 0.85f);
        HoloFrames.hLine(g, 0, width, TOP - 1, HoloTheme.argb(HoloToken.LINE_STRONG));
        for (PillButton b : chips) {
            b.render(g, font, b.x, b.y, mouseX, mouseY);
        }
        String right = coordsText(mouseX, mouseY);
        UiRenderUtils.text(g, font, right, width - font.width(right) - 8, 9, HoloTheme.argb(HoloToken.TEXT));
        if (statusTicks > 0) {
            UiRenderUtils.text(g, font, status, 8, height - 14, HoloTheme.argb(HoloToken.ACCENT));
        } else {
            String hint = mode3d ? "Drag: rotate/tilt  Right-drag: pan  Wheel: zoom  Click: menu"
                    : "Drag: pan  Wheel: zoom  Click: menu";
            UiRenderUtils.text(g, font, hint, 8, height - 14, HoloTheme.argb(HoloToken.TEXT_MUTED));
        }
        if (menuOpen) {
            drawMenu(g, mouseX, mouseY);
        }
    }

    private String coordsText(int mouseX, int mouseY) {
        if (mouseY < TOP) {
            return module.store().size() + " chunks mapped";
        }
        if (mode3d) {
            int i = nearestVertex(mouseX, mouseY - TOP, 24);
            if (i < 0) {
                return module.store().size() + " chunks mapped";
            }
            return "X " + mesh.blockX(i % mesh.side) + "  Z " + mesh.blockZ(i / mesh.side);
        }
        return "X " + (int) Math.floor(view.toWorldX(mouseX)) + "  Z " + (int) Math.floor(view.toWorldZ(mouseY - TOP));
    }

    private void render2d(GuiGraphicsExtractor g) {
        AtlasTileStore store = module.store();
        int rx0 = Math.floorDiv((int) Math.floor(view.minWorldX()), AtlasTileStore.REGION_BLOCKS);
        int rx1 = Math.floorDiv((int) Math.floor(view.maxWorldX()), AtlasTileStore.REGION_BLOCKS);
        int rz0 = Math.floorDiv((int) Math.floor(view.minWorldZ()), AtlasTileStore.REGION_BLOCKS);
        int rz1 = Math.floorDiv((int) Math.floor(view.maxWorldZ()), AtlasTileStore.REGION_BLOCKS);
        int builds = 0;
        float s = (float) view.scale();
        for (int rz = rz0; rz <= rz1; rz++) {
            for (int rx = rx0; rx <= rx1; rx++) {
                int ver = store.regionVersion(rx, rz);
                if (ver == 0) {
                    continue; // nothing sampled there
                }
                RegionTex t = regions.get(AtlasTileStore.key(rx, rz));
                if ((t == null || t.version != ver) && builds < REGION_BUILDS_PER_FRAME) {
                    t = buildRegion(rx, rz, t, ver);
                    builds++;
                }
                if (t == null) {
                    continue;
                }
                g.pose().pushMatrix();
                g.pose().translate((float) view.toScreenX(rx * AtlasTileStore.REGION_BLOCKS),
                        (float) view.toScreenY(rz * AtlasTileStore.REGION_BLOCKS));
                g.pose().scale(s, s);
                int r = AtlasTileStore.REGION_BLOCKS;
                g.blit(t.tex.getTextureView(), t.tex.getSampler(), 0, 0, r, r, 0f, 1f, 0f, 1f);
                g.pose().popMatrix();
            }
        }
        if (module.layerTrail) {
            drawChunkHeat(g);
        }
    }

    private RegionTex buildRegion(int rx, int rz, RegionTex t, int ver) {
        if (t == null) {
            if (regions.size() >= MAX_REGIONS) {
                evictFarRegion();
            }
            int r = AtlasTileStore.REGION_BLOCKS;
            Identifier id = Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID,
                    "atlas/region_" + (texSerial++));
            DynamicTexture tex = new DynamicTexture(id::toString,
                    new NativeImage(NativeImage.Format.RGBA, r, r, false));
            Minecraft.getInstance().getTextureManager().register(id, tex);
            t = new RegionTex(rx, rz, tex, id);
            regions.put(AtlasTileStore.key(rx, rz), t);
        }
        module.store().composeRegion(rx, rz, compose, EMPTY);
        NativeImage img = t.tex.getPixels();
        int r = AtlasTileStore.REGION_BLOCKS;
        for (int y = 0; y < r; y++) {
            int row = y * r;
            for (int x = 0; x < r; x++) {
                img.setPixelABGR(x, y, MinimapPalette.toAbgr(compose[row + x]));
            }
        }
        t.tex.upload();
        t.version = ver;
        return t;
    }

    private void evictFarRegion() {
        double cx = view.centerX() / AtlasTileStore.REGION_BLOCKS;
        double cz = view.centerZ() / AtlasTileStore.REGION_BLOCKS;
        long worstKey = 0;
        double worst = -1;
        ObjectIterator<it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<RegionTex>> it =
                regions.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            var e = it.next();
            RegionTex t = e.getValue();
            double d = Math.abs(t.rx - cx) + Math.abs(t.rz - cz);
            if (d > worst) {
                worst = d;
                worstKey = e.getLongKey();
            }
        }
        RegionTex t = regions.remove(worstKey);
        if (t != null) {
            Minecraft.getInstance().getTextureManager().release(t.id);
        }
    }

    private void drawChunkHeat(GuiGraphicsExtractor g) {
        ChunkAgeTracker tracker = WorldScanner.activeChunkAge();
        double s = view.scale();
        if (tracker == null || s * 16 < 2) {
            return; // too far out to read per-chunk tints
        }
        int cx0 = (int) Math.floor(view.minWorldX()) >> 4;
        int cx1 = (int) Math.floor(view.maxWorldX()) >> 4;
        int cz0 = (int) Math.floor(view.minWorldZ()) >> 4;
        int cz1 = (int) Math.floor(view.maxWorldZ()) >> 4;
        if ((long) (cx1 - cx0 + 1) * (cz1 - cz0 + 1) > 8192) {
            return;
        }
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                byte st = tracker.stateOf(cx, cz);
                if (st == ChunkAgeTracker.UNKNOWN) {
                    continue;
                }
                int color = st == ChunkAgeTracker.OLD ? 0x40FFAA28 : 0x2828C8FF;
                int x0 = (int) Math.floor(view.toScreenX(cx << 4));
                int y0 = (int) Math.floor(view.toScreenY(cz << 4));
                int x1 = (int) Math.floor(view.toScreenX((cx + 1) << 4));
                int y1 = (int) Math.floor(view.toScreenY((cz + 1) << 4));
                g.fill(x0, y0, x1, y1, color);
            }
        }
    }

    private int meshStep() {
        return Math.max(1, (int) Math.round(4 / view.scale()));
    }

    private void render3d(GuiGraphicsExtractor g) {
        LocalPlayer p = Minecraft.getInstance().player;
        int fallback = p == null ? 64 : p.getBlockY();
        if (frame % 10 == 0 || mesh.rebuilds() == 0) {
            mesh.rebuildIfNeeded(module.store(), (int) Math.floor(view.centerX()), (int) Math.floor(view.centerZ()),
                    meshStep(), fallback, EMPTY);
        }
        int side = mesh.side;
        int vh = height - TOP;
        iso.setOrigin(width * 0.5, vh * 0.55);
        iso.setScale(Math.min(width, vh) * 0.9 / (mesh.cells * (double) mesh.step()));
        iso.setHeightScale(module.heightScale);
        float base = mesh.baseHeight();
        double half = mesh.cells / 2.0 * mesh.step();
        for (int iz = 0; iz < side; iz++) {
            for (int ix = 0; ix < side; ix++) {
                int i = mesh.index(ix, iz);
                iso.project(ix * mesh.step() - half, mesh.height[i] - base, iz * mesh.step() - half);
                vx[i] = (float) iso.sx;
                vy[i] = (float) iso.sy;
            }
        }
        // one GUI element for the whole grid, quads in back-to-front order (the GUI pipeline is QUADS already)
        GuiMesh buf = meshes.begin(g);
        int order = iso.paintOrder();
        boolean xUp = (order & 1) != 0;
        boolean zUp = (order & 2) != 0;
        int cells = mesh.cells;
        for (int kz = 0; kz < cells; kz++) {
            int iz = zUp ? kz : cells - 1 - kz;
            for (int kx = 0; kx < cells; kx++) {
                int ix = xUp ? kx : cells - 1 - kx;
                int a = mesh.index(ix, iz);
                int b = mesh.index(ix + 1, iz);
                int c = mesh.index(ix + 1, iz + 1);
                int d = mesh.index(ix, iz + 1);
                int col = mesh.argb[a];
                float slope = (mesh.height[b] - mesh.height[a]) + (mesh.height[a] - mesh.height[d]);
                int light = slope > 0 ? 28 : slope < 0 ? -28 : 0;
                int alpha = mesh.known[a] ? 0xE6 : 0x50;
                int r = clamp(((col >> 16) & 0xFF) + light);
                int gg = clamp(((col >> 8) & 0xFF) + light);
                int bb = clamp((col & 0xFF) + light + 12); // slight cyan holo cast
                buf.quad(vx[a], vy[a], vx[d], vy[d], vx[c], vy[c], vx[b], vy[b],
                        (alpha << 24) | (r << 16) | (gg << 8) | bb);
            }
        }
        buf.submit(g);
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    /** Projects every pin into screen space (2D or 3D) and draws the visible ones. */
    private void drawPins(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int mask = kindMask();
        int n = pins.count();
        double half = mesh.cells / 2.0 * mesh.step();
        for (int i = 0; i < n; i++) {
            if (mode3d) {
                double dx = pins.x[i] - mesh.centerX();
                double dz = pins.z[i] - mesh.centerZ();
                pins.visible[i] = Math.abs(dx) <= half && Math.abs(dz) <= half;
                iso.project(dx, pins.y[i] - mesh.baseHeight(), dz);
            } else {
                iso.sx = view.toScreenX(pins.x[i]);
                iso.sy = view.toScreenY(pins.z[i]);
                pins.visible[i] = iso.sx >= -8 && iso.sx <= width + 8 && iso.sy >= -8 && iso.sy <= height + 8;
            }
            pins.screenX[i] = iso.sx;
            pins.screenY[i] = iso.sy;
        }
        int hover = pins.hitTest(mouseX, mouseY, 6, mask);
        for (int i = 0; i < n; i++) {
            if (!pins.visible[i] || (mask & (1 << pins.kind[i])) == 0) {
                continue;
            }
            int x = (int) pins.screenX[i];
            int y = (int) pins.screenY[i];
            int col = pins.argb[i];
            if (mode3d) {
                // Glowing beacon: a faint beam above the marker.
                g.fill(x, y - 24, x + 1, y, (col & 0x00FFFFFF) | 0x60000000);
            }
            g.fill(x - 3, y - 3, x + 4, y + 4, (col & 0x00FFFFFF) | 0x50000000);
            g.fill(x - 2, y - 2, x + 3, y + 3, col);
            if (pins.kind[i] == AtlasPins.PORTAL_LINK) {
                g.fill(x - 1, y - 1, x + 2, y + 2, 0xFF000000);
            }
        }
        if (hover >= 0) {
            String text = pins.label[hover] + "  (" + KIND_NAMES[pins.kind[hover]] + ")";
            int x = (int) pins.screenX[hover] + 8;
            int y = (int) pins.screenY[hover] - 4;
            HoloFrames.plate(g, x - 2, y - 2, font.width(text) + 4, 12, HoloToken.GLASS_RAISED, 0.9f);
            UiRenderUtils.text(g, font, text, x, y, HoloTheme.argb(HoloToken.TEXT));
        }
    }

    private void drawPlayer(GuiGraphicsExtractor g) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return;
        }
        double sx;
        double sy;
        if (mode3d) {
            iso.project(p.getX() - mesh.centerX(), p.getY() - mesh.baseHeight(), p.getZ() - mesh.centerZ());
            sx = iso.sx;
            sy = iso.sy;
        } else {
            sx = view.toScreenX(p.getX());
            sy = view.toScreenY(p.getZ());
        }
        int x = (int) sx;
        int y = (int) sy;
        int accent = HoloTheme.argb(HoloToken.ACCENT);
        g.fill(x - 3, y - 3, x + 4, y + 4, 0xFF000000);
        g.fill(x - 2, y - 2, x + 3, y + 3, accent);
    }

    private void drawMenu(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String title = menuBlockX + ", " + menuBlockY + ", " + menuBlockZ;
        int w = 96;
        int h = 18 + 2 * (PillButton.H + 4);
        HoloFrames.plate(g, menuX, menuY, w, h, HoloToken.GLASS_RAISED, 0.95f);
        HoloFrames.edge(g, menuX, menuY, w, h, HoloTheme.argb(HoloToken.LINE_STRONG));
        UiRenderUtils.text(g, font, title, menuX + 6, menuY + 5, HoloTheme.argb(HoloToken.TEXT_MUTED));
        addButton.x = menuX + 6;
        addButton.y = menuY + 16;
        goButton.x = menuX + 6;
        goButton.y = menuY + 16 + PillButton.H + 4;
        addButton.render(g, font, addButton.x, addButton.y, mouseX, mouseY);
        goButton.render(g, font, goButton.x, goButton.y, mouseX, mouseY);
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (menuOpen) {
            if (addButton.contains(mx, my, addButton.x, addButton.y)
                    && addButton.onClick(mx, my, button, addButton.x, addButton.y)) {
                return true;
            }
            if (goButton.contains(mx, my, goButton.x, goButton.y)
                    && goButton.onClick(mx, my, button, goButton.x, goButton.y)) {
                return true;
            }
            menuOpen = false;
            return true;
        }
        for (PillButton b : List.copyOf(chips)) {
            if (b.contains(mx, my, b.x, b.y) && b.onClick(mx, my, button, b.x, b.y)) {
                return true;
            }
        }
        if (my < TOP) {
            return super.mouseClicked(event, doubleClick);
        }
        dragging = true;
        moved = false;
        pressX = mx;
        pressY = my;
        pressButton = button;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (!dragging) {
            return super.mouseDragged(event, dx, dy);
        }
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (Math.abs(mx - pressX) + Math.abs(my - pressY) > 3) {
            moved = true;
        }
        if (mode3d && button == InputConstants.MOUSE_BUTTON_LEFT) {
            iso.rotate(dx * 0.01, -dy * 0.01);
        } else if (mode3d) {
            // Pan in the rotated frame: screen drag maps back through the yaw.
            double c = Math.cos(iso.yaw());
            double s = Math.sin(iso.yaw());
            double k = 1 / iso.scale();
            view.center(view.centerX() - (dx * c + dy * s) * k, view.centerZ() - (-dx * s + dy * c) * k);
        } else {
            view.pan(dx, dy);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        double mx = event.x();
        double my = event.y();
        int button = event.button();
        if (dragging && button == pressButton) {
            dragging = false;
            if (!moved && (button == InputConstants.MOUSE_BUTTON_LEFT || button == InputConstants.MOUSE_BUTTON_RIGHT)) {
                openMenu(mx, my);
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY == 0) {
            return false;
        }
        double factor = scrollY > 0 ? 1.25 : 0.8;
        view.zoomAt(mx, my - TOP, factor);
        return true;
    }

    private int nearestVertex(double mx, double my, double radius) {
        int best = -1;
        double bestD2 = radius * radius;
        for (int i = 0; i < vx.length; i++) {
            double dx = vx[i] - mx;
            double dy = vy[i] - my;
            double d2 = dx * dx + dy * dy;
            if (d2 < bestD2) {
                bestD2 = d2;
                best = i;
            }
        }
        return best;
    }

    private void openMenu(double mx, double my) {
        double ly = my - TOP;
        int pin = pins.hitTest(mx, ly, 6, kindMask());
        if (pin >= 0) {
            menuBlockX = (int) Math.floor(pins.x[pin]);
            menuBlockY = (int) Math.floor(pins.y[pin]);
            menuBlockZ = (int) Math.floor(pins.z[pin]);
        } else if (mode3d) {
            int i = nearestVertex(mx, ly, 24);
            if (i < 0) {
                return;
            }
            menuBlockX = mesh.blockX(i % mesh.side);
            menuBlockZ = mesh.blockZ(i / mesh.side);
            menuBlockY = heightOr(menuBlockX, menuBlockZ);
        } else {
            menuBlockX = (int) Math.floor(view.toWorldX(mx));
            menuBlockZ = (int) Math.floor(view.toWorldZ(ly));
            menuBlockY = heightOr(menuBlockX, menuBlockZ);
        }
        menuX = (int) Math.min(mx, width - 100);
        menuY = (int) Math.min(my, height - 60);
        menuOpen = true;
    }

    private int heightOr(int bx, int bz) {
        int h = module.store().heightAt(bx, bz);
        if (h != AtlasTileStore.NO_HEIGHT) {
            return h;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 64 : p.getBlockY();
    }

    private void addWaypoint() {
        menuOpen = false;
        IWaypointService wps = Services.waypointsOrNull();
        Minecraft mc = Minecraft.getInstance();
        if (wps == null || mc.level == null) {
            say("Waypoints are not available");
            return;
        }
        String name = "Atlas " + (wps.all().size() + 1);
        wps.add(name, "Atlas", menuBlockX, menuBlockY, menuBlockZ, dimension);
        say("Waypoint '" + name + "' added");
        refreshPins();
    }

    private void goHere() {
        menuOpen = false;
        INavigator nav = Services.navigatorOrNull();
        Minecraft mc = Minecraft.getInstance();
        if (nav == null || !nav.isAvailable() || mc.player == null) {
            say("Pathing is not available (Baritone not installed)");
            return;
        }
        if (nav.goToLeased(NAV_OWNER, ControlPriority.TRAVEL, menuBlockX, menuBlockY, menuBlockZ, 2).isEmpty()) {
            say("Movement is busy or automation is stopped");
            return;
        }
        say("Going to " + menuBlockX + ", " + menuBlockZ);
        onClose(); // hand the screen back so movement is visible
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        net.minecraft.client.KeyMapping k = com.utilitysuite.core.input.KeybindManager.mapping(
                com.utilitysuite.core.input.KeybindManager.KeyId.OPEN_WORLD_ATLAS);
        if (k != null && !k.isUnbound() && k.matches(event)) {
            onClose(); // the open key also closes the atlas
            return true;
        }
        return super.keyPressed(event);
    }

    private void say(String s) {
        status = s;
        statusTicks = 80;
    }

    @Override
    public void removed() {
        for (RegionTex t : regions.values()) {
            Minecraft.getInstance().getTextureManager().release(t.id);
        }
        regions.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
