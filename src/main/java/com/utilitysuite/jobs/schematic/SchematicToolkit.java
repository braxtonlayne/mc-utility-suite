package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.jobs.schematic.bom.SupplyTrip;
import com.utilitysuite.jobs.schematic.mc.BuildAssist;
import com.utilitysuite.jobs.schematic.mc.GhostPreviewRenderer;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.walk.ReachWalker;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Schematic Toolkit module: multi-format schematic import, bill of materials with substitutions, live diff against
 * inventory / storage / world, site survey, build order, material staging, Build Assist and ghost preview.
 * <p>
 * Entry point for the goal engine: {@link #handleBuild(GoalRequest.Build)}. Gather-only requests submit a
 * {@link GoalRequest.GatherMany} for the missing materials back to the goal engine once the first diff is done.
 */
public final class SchematicToolkit implements IModule, IConfigurable {

    public static final String ID = "schematic_toolkit";
    /** Card note while Build Assist skips blocks it cannot aim at from the player's spot. */
    static final String BLOCKED_NOTE = "Some blocks are hidden behind others from here: move around the build";
    /** Card notes for "Walk to reach blocks". */
    static final String WALKING_NOTE = "Walking to a spot that reaches the remaining blocks";
    static final String WALK_PAUSED_NOTE = "Walk to reach blocks paused by your input: move to let it continue";
    static final String WALK_UNAVAILABLE_NOTE = "Cannot walk to the remaining blocks without Baritone: move around the build";

    private final SchematicLibrary library;
    private final BuildAssist assist = new BuildAssist(ID, SchematicProject.WALK_OWNER);
    private boolean enabled = true;
    private SchematicSettings settings = SchematicSettings.DEFAULTS;
    private SchematicProject project;
    private SchematicAnalysis installed;
    private String shownRestockNote;
    private String shownCleanupNote;
    private BuildAssist.RestockHook goalHook;
    private java.util.function.BooleanSupplier goalManaged;
    private Map<String, Integer> pendingSupply;
    private final Map<String, Integer> standaloneTrips = new HashMap<>();

    public SchematicToolkit(SchematicLibrary library) {
        this.library = library;
        assist.setRestockHook(new SupplyHook());
        applySettings();
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.JOBS;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            assist.stop();
            installed = null;
        }
    }

    @Override
    public void onTick() {
        SchematicProject p = project;
        if (p == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        p.tick(mc, settings);
        if (p.isPaused() || p.request.gatherOnly() || !settings.buildAssist()) {
            return;
        }
        SchematicAnalysis a = p.analysis();
        if (a != null && a != installed) {
            installed = a;
            assist.install(a.plan(), p.scan(), p.probe(), p.placementItems(), p.scaffoldItem());
            if (a.plan().unsupportedPlacements() > 0) {
                p.addNote(a.plan().unsupportedPlacements() + " block(s) have no support even with scaffolding");
            }
        }
        if (assist.hasPlan()) {
            assist.tick(mc);
            if (assist.blockedCount() > 0) {
                p.addNote(BLOCKED_NOTE);
            } else {
                p.removeNote(BLOCKED_NOTE);
            }
            ReachWalker.Phase walk = assist.walkPhase();
            setNote(p, WALKING_NOTE, walk == ReachWalker.Phase.WALKING);
            setNote(p, WALK_PAUSED_NOTE, walk == ReachWalker.Phase.PAUSED);
            setNote(p, WALK_UNAVAILABLE_NOTE, walk == ReachWalker.Phase.UNAVAILABLE);
            String restock = assist.restockNotice();
            if (restock != shownRestockNote) { // the assist builds a new string only when the notice changes
                if (shownRestockNote != null) {
                    p.removeNote(shownRestockNote);
                }
                if (restock != null) {
                    p.addNote(restock);
                }
                shownRestockNote = restock;
            }
            String cleanup = assist.cleanupNotice();
            if (cleanup != shownCleanupNote) {
                if (shownCleanupNote != null) {
                    p.removeNote(shownCleanupNote);
                }
                if (cleanup != null) {
                    p.addNote(cleanup);
                }
                shownCleanupNote = cleanup;
            }
            if (pendingSupply != null) {
                handOverSupply(p); // outside Build Assist's tick
            }
        }
    }

    /**
     * Goal-engine side of restock / supply (set by the bridge). {@code managed} says whether the current build is a
     * goal job the bridge hands over itself; otherwise the toolkit queues the supply trip as goal jobs on its own.
     */
    void setRestockHook(BuildAssist.RestockHook hook, java.util.function.BooleanSupplier managed) {
        goalHook = hook;
        goalManaged = managed;
    }

    /** Build Assist's hook: the bridge for goal-job builds, a standalone hand-over for builds started directly. */
    private final class SupplyHook implements BuildAssist.RestockHook {
        @Override
        public boolean requestRestock(Map<String, Integer> wanted, long fromPacked, String dimension) {
            if (!settings.autoSupply()) {
                return false;
            }
            if (goalHook != null && goalManaged != null && goalManaged.getAsBoolean()) {
                return goalHook.requestRestock(wanted, fromPacked, dimension);
            }
            return queueStandalone(wanted, fromPacked, dimension);
        }

        @Override
        public boolean requestSupply(Map<String, Integer> shortfall, long fromPacked, String dimension) {
            if (!settings.autoSupply()) {
                return false;
            }
            if (goalHook != null && goalManaged != null && goalManaged.getAsBoolean()) {
                return goalHook.requestSupply(shortfall, fromPacked, dimension);
            }
            return queueStandalone(shortfall, fromPacked, dimension);
        }
    }

    /** A build started directly (not a goal job): decide the trip now, hand over on this module's next tick. */
    private boolean queueStandalone(Map<String, Integer> shortfall, long fromPacked, String dimension) {
        SchematicProject p = project;
        if (p == null || p.request.gatherOnly() || Services.goalsOrNull() == null) {
            return false;
        }
        String key = p.request.schematicPath() + "@" + p.anchorX + "," + p.anchorY + "," + p.anchorZ;
        if (standaloneTrips.getOrDefault(key, 0) >= SchematicBuildBridge.MAX_RESTOCK_ROUNDS) {
            return false;
        }
        SupplyTrip.Decision d = SupplyTrip.decide(shortfall, Services.storageOrNull(), dimension, fromPacked,
                settings.maxTripDistance(), SchematicToolkit::stackSize, SUPPLY_STACKS_PER_ITEM);
        if (!d.trip()) {
            return false;
        }
        standaloneTrips.merge(key, 1, Integer::sum);
        pendingSupply = d.fetch();
        return true;
    }

    /** Stacks of each item one supply trip carries at most (inventory room). */
    static final int SUPPLY_STACKS_PER_ITEM = 4;

    /** Max stack size of an item id (64 when unknown). */
    static int stackSize(String itemId) {
        try {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
            if (rl == null) {
                return 64;
            }
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl).getDefaultMaxStackSize();
        } catch (RuntimeException | LinkageError notBootstrapped) {
            return 64;
        }
    }

    /**
     * Standalone supply trip: the goal engine's GatherMany fetches from storage (FETCH steps: walk to the chest,
     * withdraw through the container clicker, both leased), then the queued Build walks back to the site and
     * continues from what is already placed. This build ends here; the next pass is a goal job.
     */
    private boolean handOverSupply(SchematicProject p) {
        Map<String, Integer> fetch = pendingSupply;
        pendingSupply = null;
        com.utilitysuite.core.api.IGoalEngine goals = Services.goalsOrNull();
        if (fetch == null || goals == null) {
            return false;
        }
        GoalRequest.Build r = p.request;
        long anchor = BlockPos.asLong(p.anchorX, p.anchorY, p.anchorZ);
        goals.submit(new GoalRequest.GatherMany(fetch, "supply for schematic " + p.name, r.cost(), r.dataSource()));
        goals.submit(new GoalRequest.Build(r.schematicPath(), anchor, false, r.cost(), r.dataSource()));
        cancel();
        return true;
    }

    /** Menu button: remove the recorded temporary scaffolding now. */
    public void removeScaffolding() {
        assist.requestScaffoldCleanup();
    }

    /** Recorded temporary scaffold blocks not yet removed. */
    public int scaffoldCount() {
        return assist.scaffoldCount();
    }

    private static void setNote(SchematicProject p, String note, boolean on) {
        if (on) {
            p.addNote(note);
        } else {
            p.removeNote(note);
        }
    }

    @Override
    public void onStopAll() {
        assist.stop();
        pendingSupply = null;
        installed = null;
        if (project != null) {
            project.setPaused(true);
        }
    }

    @Override
    public void onWorldUnload() {
        assist.stop();
        assist.clearScaffolding();
        pendingSupply = null;
        standaloneTrips.clear();
        installed = null;
        project = null;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        applySettings();
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Schematic Toolkit";
    }

    @Override
    public List<SettingSpec> settings() {
        return SchematicSettings.specs();
    }

    @Override
    public void onSettingChanged(String key) {
        applySettings();
        if (!settings.buildAssist()) {
            assist.stop();
            installed = null;
        }
    }

    private void applySettings() {
        IModState state = modState();
        settings = state == null ? SchematicSettings.DEFAULTS : SchematicSettings.read(state.profile(), ID);
        assist.configure(settings.silentRotation(), settings.placementsPerWindow(), settings.windowTicks(),
                settings.placementDelayTicks(), settings.swapSlot());
        assist.setWalkToReach(settings.walkToReach());
        assist.setRequireLineOfSight(settings.requireLineOfSight());
        assist.setAutoSupply(settings.autoSupply());
        assist.setCleanupScaffold(settings.cleanupScaffold());
    }

    private static IModState modState() {
        try {
            return Services.modState();
        } catch (NullPointerException notRegistered) {
            return null;
        }
    }

    // ---- public API (goal engine, UI) -------------------------------------------------------------------------------

    /**
     * Starts a schematic job: loads the file asynchronously, diffs it against the world at the anchor (the player's
     * position when {@link GoalRequest.Build#anchor()} is {@link Long#MIN_VALUE}), then either submits the missing
     * materials as a gather goal ({@code gatherOnly}) or runs Build Assist. Replaces any current job. Client thread.
     *
     * @return false when no anchor can be determined (no player and no explicit anchor)
     */
    public boolean handleBuild(GoalRequest.Build request) {
        Minecraft mc = Minecraft.getInstance();
        int ax, ay, az;
        if (request.anchor() == Long.MIN_VALUE) {
            if (mc.player == null) {
                return false;
            }
            BlockPos pos = mc.player.blockPosition();
            ax = pos.getX();
            ay = pos.getY();
            az = pos.getZ();
        } else {
            ax = BlockPos.getX(request.anchor());
            ay = BlockPos.getY(request.anchor());
            az = BlockPos.getZ(request.anchor());
        }
        assist.stop();
        installed = null;
        shownRestockNote = null;
        String path = request.schematicPath();
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        project = new SchematicProject(request, name, ax, ay, az, library);
        enabled = true;
        return true;
    }

    /**
     * Generates a volume on the schematic thread (shape voxeliser, map-art planner), registers it as
     * {@code generated:<key>} and starts it like a schematic file at the anchor: ghost preview, Build Assist, bill of
     * materials and supply trips all apply. Through the goal engine when it is loaded, else directly. Client thread.
     *
     * @param onDone called on the client thread with the volume, or null and the error message
     */
    public void startGenerated(String key, java.util.function.Supplier<SchematicVolume> generator, long anchor,
                               boolean gatherOnly, java.util.function.BiConsumer<SchematicVolume, String> onDone) {
        Minecraft mc = Minecraft.getInstance();
        library.submit(generator).whenComplete((v, err) -> mc.execute(() -> {
            if (err != null || v == null) {
                Throwable c = err != null && err.getCause() != null ? err.getCause() : err;
                onDone.accept(null, c == null ? "nothing generated" : String.valueOf(c.getMessage()));
                return;
            }
            String path = library.registerGenerated(key, v);
            com.utilitysuite.core.api.IGoalEngine goals = Services.goalsOrNull();
            com.utilitysuite.core.api.IWorldKnowledgeService wk = Services.worldKnowledgeOrNull();
            com.utilitysuite.core.api.DataSourceMode mode = wk == null
                    ? com.utilitysuite.core.api.DataSourceMode.OBSERVED_ONLY : wk.routingMode();
            GoalRequest.Build build = new GoalRequest.Build(path, anchor, gatherOnly,
                    com.utilitysuite.core.api.CostProfile.FASTEST, mode);
            if (goals != null) {
                goals.submit(build);
            } else {
                handleBuild(build);
            }
            onDone.accept(v, null);
        }));
    }

    /**
     * Swaps in a new version of a generated design that is built directly (the symmetry layer): registers it and
     * restarts the job at the anchor. No goal job and no walk to the site. Client thread.
     */
    public void replaceGenerated(String key, SchematicVolume v, long anchor) {
        String path = library.registerGenerated(key, v);
        handleBuild(new GoalRequest.Build(path, anchor, false, com.utilitysuite.core.api.CostProfile.FASTEST,
                com.utilitysuite.core.api.DataSourceMode.OBSERVED_ONLY));
    }

    /** True while the current job builds the given generated key. */
    public boolean isBuildingGenerated(String key) {
        SchematicProject p = project;
        return p != null && p.request.schematicPath().equals(SchematicLibrary.GENERATED_PREFIX + key);
    }

    /** The current job's card, if any. */
    public Optional<SchematicJobCard> jobCard() {
        SchematicProject p = project;
        return p == null ? Optional.empty() : Optional.of(p.card(assist.isActive(), assist.cleanupPending()));
    }

    /** Plan steps of the current job, for "Show Plan" (first {@code limit}). */
    public List<String> planSteps(int limit) {
        SchematicProject p = project;
        SchematicAnalysis a = p == null ? null : p.analysis();
        if (a == null) {
            return List.of();
        }
        int n = Math.min(limit, a.plan().size());
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            out[i] = a.plan().step(i).describe();
        }
        return List.of(out);
    }

    /** Pauses or resumes the current job (scan, analysis and Build Assist). */
    public void setPaused(boolean paused) {
        SchematicProject p = project;
        if (p != null) {
            p.setPaused(paused);
            if (paused) {
                assist.stop();
                installed = null;
            }
        }
    }

    public void cancel() {
        assist.stop();
        pendingSupply = null;
        installed = null;
        project = null;
    }

    /** Schematic files in the library folders (async). */
    public CompletableFuture<List<Path>> listSchematics() {
        return library.list();
    }

    /** Parses a schematic for a preview (async; the UI may compute a bill of materials from it off-thread). */
    public CompletableFuture<SchematicVolume> loadSchematic(Path file) {
        return library.load(file);
    }

    public SchematicLibrary library() {
        return library;
    }

    /** True once a gather-only job has handed its missing materials to the goal engine. */
    public boolean gatherSubmitted() {
        SchematicProject p = project;
        return p != null && p.gatherSubmitted();
    }

    public SchematicSettings currentSettings() {
        return settings;
    }

    // ---- rendering --------------------------------------------------------------------------------------------------

    void onRenderLevel(RenderLevelStageEvent event) {
        SchematicProject p = project;
        if (!enabled || p == null || !settings.ghostPreview() || p.request.gatherOnly()
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        IModState state = modState();
        if (state != null && state.isCleanScreen()) {
            return;
        }
        SchematicAnalysis a = p.analysis();
        if (a == null) {
            return;
        }
        GhostPreviewRenderer.render(event, p.scan(), a.ghostCells(), settings.ghostDistance());
    }
}
