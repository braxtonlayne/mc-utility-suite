package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.jobs.schematic.mc.BuildAssist;
import com.utilitysuite.jobs.schematic.mc.GhostPreviewRenderer;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.nio.file.Path;
import java.util.List;
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

    private final SchematicLibrary library;
    private final BuildAssist assist = new BuildAssist(ID);
    private boolean enabled = true;
    private SchematicSettings settings = SchematicSettings.DEFAULTS;
    private SchematicProject project;
    private SchematicAnalysis installed;

    public SchematicToolkit(SchematicLibrary library) {
        this.library = library;
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
        }
    }

    @Override
    public void onStopAll() {
        assist.stop();
        installed = null;
        if (project != null) {
            project.setPaused(true);
        }
    }

    @Override
    public void onWorldUnload() {
        assist.stop();
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
        String path = request.schematicPath();
        String name = path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
        project = new SchematicProject(request, name, ax, ay, az, library);
        enabled = true;
        return true;
    }

    /** The current job's card, if any. */
    public Optional<SchematicJobCard> jobCard() {
        SchematicProject p = project;
        return p == null ? Optional.empty() : Optional.of(p.card(assist.isActive()));
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
