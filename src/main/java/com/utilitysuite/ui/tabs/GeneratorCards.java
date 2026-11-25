package com.utilitysuite.ui.tabs;

import com.utilitysuite.jobs.schematic.SchematicBootstrap;
import com.utilitysuite.jobs.schematic.SchematicToolkit;
import com.utilitysuite.jobs.schematic.SymmetryBuilder;
import com.utilitysuite.jobs.schematic.gen.MapArtPlanner;
import com.utilitysuite.jobs.schematic.gen.ShapeVoxelizer;
import com.utilitysuite.jobs.schematic.gen.ShapeVoxelizer.Kind;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.kit.CardNode;
import com.utilitysuite.ui.kit.PillButton;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Jobs tab cards beside the schematic importer: the parametric shape generator, the map-art planner and the symmetry
 * builder's centre controls. Generation runs on the schematic thread; the result is started as a generated schematic
 * (ghost preview, Build Assist, bill of materials, supply trips).
 */
final class GeneratorCards {

    private static final String K_KIND = "gen.kind";
    private static final String K_RADIUS = "gen.radius";
    private static final String K_HEIGHT = "gen.height";
    private static final String K_THICK = "gen.thickness";
    private static final String K_MAT = "gen.material";
    private static final String K_END = "gen.end";
    private static final String K_WIDTH = "gen.width";
    private static final String K_ART = "mapart.file";
    private static final String K_MAPS = "mapart.maps";
    private static final String K_STAIR = "mapart.staircase";
    private static final String K_NODITHER = "mapart.noDither";
    private static final String K_PRICEY = "mapart.expensive";

    private static volatile String lastResult = "";
    private static volatile boolean working;

    private GeneratorCards() {
    }

    static void build(Panel p) {
        SchematicToolkit t = SchematicBootstrap.toolkit();
        if (t == null) {
            return;
        }
        shapes(p, t);
        mapArt(p, t);
        symmetry(p);
    }

    // ---- shapes -----------------------------------------------------------------------------------------------------

    private static void shapes(Panel p, SchematicToolkit t) {
        p.card("Shape generator", "ghost + Build Assist", 0, CardNode.Tone.NORMAL);
        int w = p.width();
        int cw = Math.min(170, w / 2);
        Kind kind = parseKind(p.state(K_KIND, "SPHERE"));
        p.rowLabel("Shape", 0, 80, Panel.WHITE);
        p.cycle(List.of(Kind.values()), kind, k -> k.name().charAt(0) + k.name().substring(1).toLowerCase(Locale.ROOT),
                w - cw, cw, k -> {
                    p.setState(K_KIND, k.name());
                    p.rebuild();
                });
        p.nextRow();
        if (kind == Kind.BRIDGE) {
            p.rowLabel("End (dx dy dz)", 0, 100, Panel.WHITE);
            p.textField(K_END, "16 4 0", w - cw, cw);
            p.nextRow();
            p.rowLabel("Width", 0, 80, Panel.WHITE);
            p.textField(K_WIDTH, "3", w - cw, cw);
            p.nextRow();
        } else {
            p.rowLabel(kind == Kind.ARCH ? "Half-span" : kind == Kind.TORUS ? "Ring radius" : "Radius", 0, 100,
                    Panel.WHITE);
            p.textField(K_RADIUS, "8", w - cw, cw);
            p.nextRow();
            if (kind == Kind.CYLINDER || kind == Kind.ARCH || kind == Kind.TORUS) {
                p.rowLabel(kind == Kind.TORUS ? "Tube radius" : kind == Kind.ARCH ? "Leg height" : "Height", 0, 100,
                        Panel.WHITE);
                p.textField(K_HEIGHT, kind == Kind.TORUS ? "2" : "6", w - cw, cw);
                p.nextRow();
            }
        }
        int thick = intOf(p.state(K_THICK, "1"), 1);
        p.rowLabel(kind == Kind.BRIDGE ? "Deck layers" : kind == Kind.ARCH ? "Ring thickness" : "Shell", 0, 100,
                Panel.WHITE);
        p.cycle(List.of(0, 1, 2, 3, 4), Math.max(0, Math.min(4, thick)),
                v -> v == 0 ? (kind == Kind.BRIDGE || kind == Kind.ARCH ? "Default" : "Solid") : v + " thick",
                w - cw, cw, v -> {
                    p.setState(K_THICK, Integer.toString(v));
                    p.rebuild();
                });
        p.nextRow();
        p.rowLabel("Material", 0, 80, Panel.WHITE);
        p.textField(K_MAT, "minecraft:stone", w - cw, cw);
        p.nextRow();
        p.paragraph("Anchored at the block you look at (on top of it), or where you stand.", Panel.GRAY);
        if (!lastResult.isEmpty()) {
            p.paragraph(lastResult, Panel.GRAY);
        }
        p.button(working ? "Generating..." : "Generate & build", "Voxelises the shape off-thread, then shows it as a "
                + "ghost and lets Build Assist place it", w - 140, 140, PillButton.Style.PRIMARY, b -> {
            if (working) {
                return;
            }
            ShapeVoxelizer.Params params = shapeParams(p, kind);
            long anchor = anchor(kind == Kind.SPHERE ? params.radius() : kind == Kind.TORUS
                    ? Math.min(params.height(), params.radius()) : 0);
            if (anchor == Long.MIN_VALUE) {
                p.toast("No player");
                return;
            }
            start(p, t, "shape_" + params.label(), () -> ShapeVoxelizer.voxelize(params), anchor);
        });
        p.nextRow();
    }

    private static ShapeVoxelizer.Params shapeParams(Panel p, Kind kind) {
        String[] end = p.state(K_END, "16 4 0").trim().split("[\\s,]+");
        int ex = end.length > 0 ? intOf(end[0], 16) : 16;
        int ey = end.length > 1 ? intOf(end[1], 4) : 4;
        int ez = end.length > 2 ? intOf(end[2], 0) : 0;
        String mat = p.state(K_MAT, "").trim();
        return new ShapeVoxelizer.Params(kind, intOf(p.state(K_RADIUS, "8"), 8),
                intOf(p.state(K_HEIGHT, kind == Kind.TORUS ? "2" : "6"), 6), intOf(p.state(K_THICK, "1"), 1),
                mat.isEmpty() ? "minecraft:stone" : mat, ex, ey, ez, intOf(p.state(K_WIDTH, "3"), 3));
    }

    // ---- map art ----------------------------------------------------------------------------------------------------

    private static void mapArt(Panel p, SchematicToolkit t) {
        p.card("Map-art planner", "PNG / JPG to map colours", 0, CardNode.Tone.NORMAL);
        int w = p.width();
        int cw = Math.min(200, w * 3 / 5);
        p.rowLabel("Image", 0, 60, Panel.WHITE);
        p.textField(K_ART, "file in utilitysuite/mapart/ or full path", w - cw, cw);
        p.nextRow();
        int maps = Math.max(1, Math.min(MapArtPlanner.MAX_MAPS, intOf(p.state(K_MAPS, "1"), 1)));
        p.rowLabel("Maps wide", 0, 80, Panel.WHITE);
        p.cycle(List.of(1, 2, 3, 4), maps, v -> "1 x " + v, w - 120, 120, v -> {
            p.setState(K_MAPS, Integer.toString(v));
            p.rebuild();
        });
        p.nextRow();
        p.toggle("Staircase (3 shades per colour)", p.flag(K_STAIR), 0, w, v -> p.setFlag(K_STAIR, v));
        p.nextRow();
        p.toggle("No dithering", p.flag(K_NODITHER), 0, w, v -> p.setFlag(K_NODITHER, v));
        p.nextRow();
        p.toggle("Allow metal / gem blocks", p.flag(K_PRICEY), 0, w, v -> p.setFlag(K_PRICEY, v));
        p.nextRow();
        p.paragraph("The north-west corner goes on the block you look at; align it with a map's grid. "
                + "Row 0 is a cobblestone reference row north of the picture.", Panel.GRAY);
        p.button(working ? "Planning..." : "Plan & build", "Loads, scales and dithers the image off-thread",
                w - 120, 120, PillButton.Style.PRIMARY, b -> {
                    if (working) {
                        return;
                    }
                    String name = p.state(K_ART, "").trim();
                    if (name.isEmpty()) {
                        p.toast("Enter an image file name");
                        return;
                    }
                    long anchor = anchor(0);
                    if (anchor == Long.MIN_VALUE) {
                        p.toast("No player");
                        return;
                    }
                    MapArtPlanner.Options o = new MapArtPlanner.Options(maps, p.flag(K_STAIR), !p.flag(K_NODITHER),
                            !p.flag(K_PRICEY));
                    Path dir = FMLPaths.GAMEDIR.get().resolve("utilitysuite").resolve("mapart");
                    start(p, t, "mapart_" + name, () -> {
                        Path file = Path.of(name);
                        if (!file.isAbsolute()) {
                            file = dir.resolve(name);
                        }
                        try {
                            int[][] img = MapArtPlanner.readImage(file);
                            String n = file.getFileName().toString();
                            return MapArtPlanner.plan(n, img[1], img[0][0], img[0][1], o);
                        } catch (java.io.IOException e) {
                            throw new IllegalStateException(e.getMessage(), e);
                        }
                    }, anchor);
                });
        p.nextRow();
    }

    // ---- symmetry ---------------------------------------------------------------------------------------------------

    private static void symmetry(Panel p) {
        SymmetryBuilder s = SchematicBootstrap.symmetry();
        if (s == null || !s.isEnabled()) {
            return;
        }
        p.card("Symmetry centre", s.currentSettings().mode().label());
        p.dynamic(() -> s.status().isEmpty() ? "Centre set on the next tick" : s.status(), Panel.WHITE);
        p.dynamic(() -> s.layerSize() + " counterpart blocks in the layer", Panel.GRAY);
        p.button("Set centre at crosshair", null, 0, 150, PillButton.Style.SECONDARY, b -> {
            s.setCentreFromCrosshair();
            p.rebuild();
        });
        p.button("Clear layer", null, 156, 90, PillButton.Style.DANGER_SOFT, b -> {
            s.clearLayer();
            p.rebuild();
        });
        p.nextRow();
    }

    // ---- shared -----------------------------------------------------------------------------------------------------

    private static void start(Panel p, SchematicToolkit t, String key,
                              java.util.function.Supplier<SchematicVolume> gen, long anchor) {
        working = true;
        lastResult = "";
        p.rebuild();
        t.startGenerated(key, gen, anchor, false, (v, err) -> {
            working = false;
            lastResult = v == null ? "Failed: " + err
                    : v.name() + ": " + v.sizeX() + "x" + v.sizeY() + "x" + v.sizeZ() + ", " + v.blockCount()
                    + " blocks. Bill of materials in the schematic job card.";
            p.toast(v == null ? "Generation failed" : "Generated " + v.name());
            p.rebuild();
        });
    }

    /** Block above the crosshair block (or the player's feet), lifted by {@code lift}; MIN_VALUE without a player. */
    private static long anchor(int lift) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return Long.MIN_VALUE;
        }
        HitResult hit = mc.hitResult;
        BlockPos base = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK
                ? b.getBlockPos().above() : mc.player.blockPosition();
        return base.above(lift).asLong();
    }

    private static Kind parseKind(String s) {
        try {
            return Kind.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Kind.SPHERE;
        }
    }

    private static int intOf(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }
}
