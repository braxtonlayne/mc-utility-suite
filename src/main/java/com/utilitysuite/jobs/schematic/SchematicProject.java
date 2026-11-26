package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.jobs.schematic.bom.ItemEquivalents;
import com.utilitysuite.jobs.schematic.bom.MaterialDiff;
import com.utilitysuite.jobs.schematic.mc.McInventoryView;
import com.utilitysuite.jobs.schematic.mc.McStateResolver;
import com.utilitysuite.jobs.schematic.mc.McWorldProbe;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.world.PlacementScan;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * One active schematic job on the client thread: async load, budgeted world diff, periodic background analysis
 * (bill of materials, supply diff, survey, build order, staging), gather-only hand-off to the goal engine.
 * {@link #tick} is called once per client tick; it allocates only when a pass completes (snapshots for the background
 * analysis), never in the steady state.
 */
final class SchematicProject {

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/schematic");
    /** Lease owner of the walk to a distant build site. */
    public static final String WALK_OWNER = SchematicToolkit.ID + ".walk";

    enum Phase { LOADING, SCANNING, READY, COMPLETE, FAILED }

    final GoalRequest.Build request;
    final String name;
    final int anchorX;
    final int anchorY;
    final int anchorZ;
    private final SchematicLibrary library;
    private final CompletableFuture<SchematicVolume> loading;

    private Phase phase = Phase.LOADING;
    private boolean paused;
    private SchematicVolume volume;
    private BlockState[] states;
    private Item[] items;
    private Item scaffoldItem;
    private McWorldProbe probe;
    private PlacementScan scan;
    private CompletableFuture<SchematicAnalysis> analyzing;
    private volatile SchematicAnalysis analysis;
    private int rescanCountdown;
    private boolean gatherSubmitted;
    private boolean navigationRequested;
    private String failure;
    private final List<String> notes = new ArrayList<>();

    SchematicProject(GoalRequest.Build request, String name, int anchorX, int anchorY, int anchorZ,
                     SchematicLibrary library) {
        this.request = request;
        this.name = name;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.library = library;
        this.loading = library.submit(() -> {
            if (request.schematicPath().startsWith(SchematicLibrary.GENERATED_PREFIX)) {
                SchematicVolume g = library.generated(request.schematicPath());
                if (g == null) {
                    throw new IllegalStateException("Generated design is gone: " + request.schematicPath());
                }
                return g;
            }
            java.nio.file.Path file = library.resolve(request.schematicPath());
            if (file == null) {
                throw new IllegalStateException("Schematic not found: " + request.schematicPath() + " (looked in "
                        + library.roots() + ")");
            }
            try {
                return com.utilitysuite.jobs.schematic.format.SchematicFormats.read(file);
            } catch (Exception e) {
                throw new IllegalStateException("Cannot read " + file.getFileName() + ": " + e.getMessage(), e);
            }
        });
    }

    Phase phase() { return phase; }
    boolean isPaused() { return paused; }
    boolean gatherSubmitted() { return gatherSubmitted; }
    void setPaused(boolean paused) { this.paused = paused; }
    SchematicVolume volume() { return volume; }
    PlacementScan scan() { return scan; }
    McWorldProbe probe() { return probe; }
    SchematicAnalysis analysis() { return analysis; }
    Item[] placementItems() { return items; }
    Item scaffoldItem() { return scaffoldItem; }
    String failure() { return failure; }

    void tick(Minecraft mc, SchematicSettings settings) {
        if (phase == Phase.FAILED || paused) {
            return;
        }
        ClientLevel level = mc.level;
        if (phase == Phase.LOADING) {
            if (!loading.isDone()) {
                return;
            }
            try {
                volume = loading.join();
            } catch (Exception e) {
                fail(e.getCause() != null ? e.getCause().getMessage() : e.getMessage());
                return;
            }
            states = McStateResolver.resolvePalette(volume);
            items = McStateResolver.placementItems(volume, ItemEquivalents.forPalette(volume));
            scaffoldItem = McStateResolver.item(settings.planOptions().scaffoldItem());
            int unknown = 0;
            for (int i = 1; i < states.length; i++) {
                if (states[i] == null) {
                    unknown++;
                }
            }
            if (unknown > 0) {
                notes.add(unknown + " palette entr" + (unknown == 1 ? "y" : "ies")
                        + " unknown to this game (missing mod?)");
            }
            probe = new McWorldProbe(states);
            scan = new PlacementScan(volume, anchorX, anchorY, anchorZ);
            phase = Phase.SCANNING;
            post("Loaded " + volume);
        }
        probe.level(level);
        if (analyzing != null && analyzing.isDone()) {
            CompletableFuture<SchematicAnalysis> f = analyzing;
            analyzing = null;
            try {
                analysis = f.join();
                onAnalysis(mc, settings);
            } catch (Exception e) {
                LOG.warn("Schematic analysis failed", e);
                notes.add("Analysis failed: " + e.getMessage());
            }
            rescanCountdown = settings.rescanTicks();
        }
        if (!scan.isComplete()) {
            if (scan.step(probe, settings.scanBudget()) && analyzing == null) {
                startAnalysis(mc, settings);
            }
        } else if (analyzing == null && --rescanCountdown <= 0) {
            scan.restart();
        }
    }

    private void startAnalysis(Minecraft mc, SchematicSettings settings) {
        Map<String, Integer> inventory = McInventoryView.snapshot(mc.player).counts();
        IStorageIndex storage = Services.storageOrNull();
        ClientLevel level = mc.level;
        int minY = level == null ? -64 : level.getMinY();
        int maxY = level == null ? 320 : level.getMaxY() + 1;
        byte[] status = scan.snapshot();
        java.util.BitSet below = scan.unsupportedBelowSnapshot();
        PlacementScan s = scan;
        SchematicVolume v = volume;
        analyzing = library.submit(() -> SchematicAnalysis.compute(new SchematicAnalysis.Inputs(v, s, status, below,
                inventory, MaterialDiff.storageCounts(storage), settings.rules(), settings.planOptions(), minY, maxY,
                settings.projectChests(), settings.layersPerPhase(), 4096)));
    }

    private void onAnalysis(Minecraft mc, SchematicSettings settings) {
        SchematicAnalysis a = analysis;
        boolean first = phase == Phase.SCANNING;
        phase = a.survey().placed() >= a.survey().totalBlocks() && a.survey().unloaded() == 0 ? Phase.COMPLETE
                : Phase.READY;
        if (first) {
            post(a.survey().summaryLines().get(0) + ", " + a.diff().totalMissing() + " items missing");
        }
        if (request.gatherOnly() && !gatherSubmitted) {
            gatherSubmitted = true;
            Map<String, Integer> missing = a.diff().missing();
            if (missing.isEmpty()) {
                notes.add("All materials are available");
            } else {
                IGoalEngine goals = Services.goalsOrNull();
                if (goals == null) {
                    notes.add("Goal engine not available: gather the missing materials manually");
                } else {
                    long job = goals.submit(new GoalRequest.GatherMany(missing, "schematic " + name, request.cost(),
                            request.dataSource()));
                    notes.add("Gather job " + job + " submitted for " + missing.size() + " item types");
                }
            }
        }
        if (!request.gatherOnly() && settings.autoNavigate() && !navigationRequested && mc.player != null) {
            double cx = scan.originX() + volume.sizeX() / 2.0;
            double cz = scan.originZ() + volume.sizeZ() / 2.0;
            double dx = mc.player.getX() - cx, dz = mc.player.getZ() - cz;
            INavigator nav = Services.navigatorOrNull();
            if (dx * dx + dz * dz > 32 * 32 && nav != null && nav.isAvailable()) {
                navigationRequested = true;
                // Leased walk (GOAL, its own owner id so it neither replaces nor is replaced by BuildAssist's lease):
                // cancelled by manual input, higher-priority automation and Stop-All.
                if (nav.goToLeased(WALK_OWNER, ControlPriority.GOAL, (int) cx, scan.originY(), (int) cz,
                        Math.max(4, Math.max(volume.sizeX(), volume.sizeZ()) / 2)).isPresent()) {
                    notes.add("Navigating to the build site");
                } else {
                    notes.add("Could not walk to the build site: movement is busy or automation is stopped");
                }
            }
        }
    }

    private void fail(String message) {
        failure = message;
        phase = Phase.FAILED;
        notes.add(message);
        post("Failed: " + message);
    }

    private void post(String message) {
        LOG.info("[{}] {}", name, message);
        ISuiteEventBus events = Services.eventsOrNull();
        if (events != null) {
            events.post(new SuiteEvent.Diagnostic(System.currentTimeMillis(), SchematicToolkit.ID, name + ": " + message));
        }
    }

    void removeNote(String note) {
        notes.remove(note);
    }

    void addNote(String note) {
        if (!notes.contains(note)) {
            notes.add(note);
        }
    }

    SchematicJobCard card(boolean building) {
        return card(building, false);
    }

    /** @param cleaningUp recorded scaffolding is still being removed: a finished build reads BUILDING until done */
    SchematicJobCard card(boolean building, boolean cleaningUp) {
        SchematicAnalysis a = analysis;
        SchematicVolume v = volume;
        boolean busy = (phase == Phase.READY && building) || (phase == Phase.COMPLETE && cleaningUp);
        String phaseName = paused ? "PAUSED" : busy ? "BUILDING" : phase.name();
        return new SchematicJobCard(name, v == null ? "" : v.format(), v == null ? 0 : v.sizeX(),
                v == null ? 0 : v.sizeY(), v == null ? 0 : v.sizeZ(), anchorX, anchorY, anchorZ, phaseName,
                scan == null ? 0f : scan.isComplete() ? 1f : scan.progress(), request.gatherOnly(),
                a == null ? 0 : a.survey().totalBlocks(), a == null ? 0 : a.survey().placed(),
                a == null ? Map.of() : a.bom().asMap(), a == null ? Map.of() : a.diff().missing(),
                a == null ? Map.of() : a.bom().unobtainable(), a == null ? Map.of() : a.bom().flagged(),
                a == null ? List.of() : a.survey().summaryLines(), a == null ? 0 : a.plan().size(),
                a == null ? 0 : a.plan().count(com.utilitysuite.jobs.schematic.plan.BuildPlan.SCAFFOLD),
                a == null ? 0 : a.plan().unsupportedPlacements(), a == null ? 0 : a.staging().chestsNeeded(),
                notes);
    }
}
