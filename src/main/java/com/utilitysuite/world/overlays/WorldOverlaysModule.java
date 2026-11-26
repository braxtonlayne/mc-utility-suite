package com.utilitysuite.world.overlays;

import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.IWorldKnowledgeService;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.WorldRuntime;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * X-ray filters: outlines of blocks seen in loaded chunks that match a preset (ores, storage, spawners, fluid pockets,
 * custom ids), an exposed-only mode, cave and fluid-pocket wireframes near the player, and the optional mode that hides
 * every other block (vanilla chunk renderer only). Observed data only; seed predictions are drawn by the OreSim module
 * as ghosts. Follows the World Knowledge overlay data setting (nothing observed is drawn in SIMULATED_ONLY).
 */
public final class WorldOverlaysModule extends WorldModule {

    public static final String ID = "world_overlays";

    private final WorldRuntime runtime;
    private final WorldScanner scanner;
    private final IWorldKnowledgeService knowledge;

    private FilterPreset preset = FilterPreset.VALUABLE_ORES;
    private Set<String> customIds = Set.of();
    private boolean exposedOnly;
    private boolean caves;
    private boolean fluids;
    private int nearRadius;
    private int renderRadius;
    private int maxBoxes;
    private boolean hideOthers;
    private OverlayStyle.Focus focus = OverlayStyle.Focus.ALL;
    private float lineOpacity = 1f;
    private float caveOpacity = 0.35f;
    private final Int2IntOpenHashMap colorByIdIndex = new Int2IntOpenHashMap();

    public WorldOverlaysModule(WorldRuntime runtime, IWorldKnowledgeService knowledge) {
        super(ID, "X-Ray Filters", false);
        this.runtime = runtime;
        this.scanner = runtime.scanner();
        this.knowledge = knowledge;
        colorByIdIndex.defaultReturnValue(0);
    }

    @Override
    public List<SettingSpec> settings() {
        return specs();
    }

    /** The settings specs (static so presets can be checked against them without a world runtime). */
    public static List<SettingSpec> specs() {
        return List.of(
                SettingSpec.choice("preset", "Preset", FilterPreset.VALUABLE_ORES, "Filter",
                        "Which kinds of blocks are outlined. Custom ids are always included."),
                SettingSpec.text("custom_ids", "Custom block ids", "", "Filter",
                        "Comma list of block ids to outline in addition to the preset, e.g. minecraft:amethyst_cluster"),
                SettingSpec.bool("exposed_only", "Exposed only", false, "Filter",
                        "Only blocks that touch air or fluid (what you could see or reach from a cave)."),
                SettingSpec.bool("caves", "Cave wireframe", false, "Near view",
                        "Coarse 4-block cells of open space below the surface, near you."),
                SettingSpec.bool("fluids", "Fluid pockets", false, "Near view",
                        "Underground lava and water surfaces near you."),
                SettingSpec.integer("near_radius", "Near view radius", 48, 16, 96, "Near view",
                        "Reach of the cave and fluid wireframes."),
                SettingSpec.integer("render_radius", "Outline radius", 96, 16, 256, "Display",
                        "Outlines farther than this are not drawn."),
                SettingSpec.integer("max_boxes", "Max outlines", 6000, 100, 50000, "Display",
                        "Upper bound on outlines per frame.").asAdvanced(),
                SettingSpec.choice("focus", "Focus layer", OverlayStyle.Focus.ALL, "Display",
                        "Keeps one layer (ores, caves, fluids or OreSim predictions) at full strength and dims the "
                        + "others to 25%."),
                SettingSpec.integer("line_opacity", "Line opacity (%)", 100, 10, 100, "Display",
                        "Strength of every outline drawn by this module and of the OreSim prediction ghosts."),
                SettingSpec.integer("cave_opacity", "Cave line opacity (%)", 35, 10, 100, "Display",
                        "Extra fade for the cave wireframe so it does not hide ore outlines."),
                SettingSpec.bool("hide_others", "Hide other blocks", false, "Display",
                        XRayHiding.rendererIncompatible()
                                ? "Not available: Sodium/Embeddium replace the chunk renderer this mode hooks into. "
                                + "The outlines above work with them."
                                : "Rebuilds the visible chunks without the blocks that are not outlined. "
                                + "Switching it on or off rebuilds every chunk once."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        preset = choice(p, "preset", FilterPreset.class);
        Set<String> ids = new LinkedHashSet<>();
        for (String s : text(p, "custom_ids").split(",")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) {
                ids.add(t.indexOf(':') < 0 ? "minecraft:" + t : t);
            }
        }
        customIds = Set.copyOf(ids);
        exposedOnly = bool(p, "exposed_only");
        caves = bool(p, "caves");
        fluids = bool(p, "fluids");
        nearRadius = integer(p, "near_radius");
        renderRadius = integer(p, "render_radius");
        maxBoxes = integer(p, "max_boxes");
        hideOthers = bool(p, "hide_others");
        focus = choice(p, "focus", OverlayStyle.Focus.class);
        lineOpacity = integer(p, "line_opacity") / 100f;
        caveOpacity = integer(p, "cave_opacity") / 100f;
        publishPredictionScale();
        colorByIdIndex.clear();
        applyScanOptions();
        applyHiding();
    }

    /** OreSim ghosts follow this module's focus and line opacity while it is enabled. */
    private void publishPredictionScale() {
        OverlayStyle.setPredictionScale(isEnabled()
                ? OverlayStyle.layerScale(focus, OverlayStyle.Focus.PREDICTIONS, lineOpacity) : 1f);
    }

    private void applyScanOptions() {
        runtime.setScanOptions(customIds, isEnabled() && caves, isEnabled() && fluids, nearRadius);
    }

    /** Blocks kept visible by "hide other blocks": every registered block whose categories match the preset. */
    private void applyHiding() {
        if (!isEnabled() || !hideOthers || XRayHiding.rendererIncompatible()) {
            XRayHiding.set(false, Set.of());
            return;
        }
        int mask = preset.mask();
        Set<Block> visible = new HashSet<>();
        for (Block b : BuiltInRegistries.BLOCK) {
            Identifier key = BuiltInRegistries.BLOCK.getKey(b);
            if ((BlockCategories.classify(key.toString(), customIds) & mask) != 0) {
                visible.add(b);
            }
        }
        XRayHiding.set(true, visible);
    }

    @Override
    protected void onEnable() {
        publishPredictionScale();
        applyScanOptions();
        applyHiding();
    }

    @Override
    protected void onDisable() {
        OverlayStyle.setPredictionScale(1f);
        applyScanOptions();
        XRayHiding.set(false, Set.of());
    }

    @Override
    public void onWorldUnload() {
        XRayHiding.set(false, Set.of());
    }

    @Override
    public int scanMask() {
        return preset.mask();
    }

    @Override
    public void onTick() {
        // Scanning runs in the shared runtime; nothing per tick here.
    }

    private int colorFor(int value) {
        int idx = HighlightIndex.idIndexOf(value);
        int c = colorByIdIndex.get(idx);
        if (c == 0) {
            String id = scanner.observed.idAt(idx);
            String path = id == null ? "" : id.substring(id.indexOf(':') + 1);
            c = BlockCategories.color(HighlightIndex.categories(value), path);
            colorByIdIndex.put(idx, c);
        }
        return c;
    }

    @Override
    public void renderLines(RenderContext ctx) {
        if (knowledge.overlayMode() == DataSourceMode.SIMULATED_ONLY
                || !ctx.dimension.equals(scanner.dimension())) {
            return;
        }
        int mask = preset.mask();
        float oreScale = OverlayStyle.layerScale(focus, OverlayStyle.Focus.ORES, lineOpacity);
        double r2 = WorldRuntime.sq(renderRadius);
        HighlightIndex idx = scanner.observed;
        int drawn = 0;
        // FPS governor hint: fewer boxes while the render budget is tight (1.0 = the setting as is).
        int maxBoxes = Math.max(1, (int) (this.maxBoxes * com.utilitysuite.core.api.RenderBudget.overlayScale()));
        for (int i = 0, n = idx.renderCount(); i < n && drawn < maxBoxes; i++) {
            int v = idx.renderValue(i);
            if ((HighlightIndex.categories(v) & mask) == 0 || exposedOnly && !HighlightIndex.exposed(v)) {
                continue;
            }
            long p = idx.renderPos(i);
            int x = BlockPosPacking.x(p);
            int y = BlockPosPacking.y(p);
            int z = BlockPosPacking.z(p);
            if (ctx.distSq(x, y, z) > r2) {
                continue;
            }
            float rx = ctx.rx(x);
            float ry = ctx.ry(y);
            float rz = ctx.rz(z);
            WorldRenderKit.box(ctx.lines, ctx.pose, rx, ry, rz, rx + 1, ry + 1, rz + 1, OverlayStyle.scaleAlpha(colorFor(v), oreScale));
            drawn++;
        }
        if (fluids) {
            float fluidScale = OverlayStyle.layerScale(focus, OverlayStyle.Focus.FLUIDS, lineOpacity);
            HighlightIndex f = scanner.fluids;
            for (int i = 0, n = f.renderCount(); i < n && drawn < maxBoxes; i++) {
                long p = f.renderPos(i);
                int cat = HighlightIndex.categories(f.renderValue(i));
                float rx = ctx.rx(BlockPosPacking.x(p));
                float ry = ctx.ry(BlockPosPacking.y(p));
                float rz = ctx.rz(BlockPosPacking.z(p));
                int color = OverlayStyle.scaleAlpha((cat & BlockCategories.LAVA) != 0 ? 0x90FF6010 : 0x703070FF,
                        fluidScale);
                WorldRenderKit.square(ctx.lines, ctx.pose, rx, ry + 0.9f, rz, rx + 1, rz + 1, color);
                drawn++;
            }
        }
        if (caves) {
            // Base alpha 0x60 times the cave opacity (default 35% = 0x22, fainter than the old fixed 0x30).
            int caveColor = OverlayStyle.scaleAlpha(0x6080E0A0,
                    OverlayStyle.layerScale(focus, OverlayStyle.Focus.CAVES, lineOpacity) * caveOpacity);
            CaveCells c = scanner.caves;
            for (int i = 0, n = c.renderCount(); i < n && drawn < maxBoxes; i++) {
                long p = c.renderCell(i);
                float rx = ctx.rx(BlockPosPacking.x(p));
                float ry = ctx.ry(BlockPosPacking.y(p));
                float rz = ctx.rz(BlockPosPacking.z(p));
                WorldRenderKit.box(ctx.lines, ctx.pose, rx, ry, rz, rx + 4, ry + 4, rz + 4, caveColor);
                drawn++;
            }
        }
    }
}
