package com.utilitysuite.jobs.storage.hall;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteAction;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloWorld;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.core.api.visual.NoticeKind;
import com.utilitysuite.jobs.goal.WorldActions;
import com.utilitysuite.jobs.planner.HallJobSource;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.storage.StorageMemory;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import java.util.List;

/**
 * Hall Sorter: tags the looked-at chest with a category (unbound key, cycles), and plans "sort hall" for the goal
 * engine ({@link HallJobSource}): {@link HallSortPlanner} over the remembered chests near the player (captured per
 * slot by Storage Memory), turned into HALL_TAKE / HALL_PUT / HALL_MERGE steps by {@link HallSortSteps}. The job runs
 * in the goal engine's queue like any other (plan preview, pause/resume, Stop-All, retries); the {@code sort_hall}
 * suite action just queues it. Nearly-full chests can be outlined in the world through the Shell's world kit (one
 * batched pass, any number of chests) and reported as a notice when a sort starts. Off by default.
 */
public final class HallSorterModule implements IModule, IConfigurable, HallJobSource {

    public static final String ID = "hall_sorter";
    public static final String KEY_TAG = "key.utilitysuite.hall_tag";
    /** Ticks between nearly-full rescans (a pass over remembered containers; never per frame). */
    static final int RESCAN_TICKS = 100;
    /** Most outlines kept; the Shell's world kit draws the nearest it has room for. */
    static final int MAX_OUTLINES = 4096;

    private final StorageMemory memory;
    private final WorldActions actions;
    private final HallSortPlanner planner = new HallSortPlanner();
    private boolean enabled;
    private int radius = 24;
    private int nearlyFullPct = 90;
    private boolean fullNotice;
    private boolean fullOverlay;
    private KeyMapping tagKey;
    private String lastResult = "";

    public HallSorterModule(StorageMemory memory, WorldActions actions) {
        this.memory = memory;
        this.actions = actions;
        com.utilitysuite.core.api.ISuiteActions a = Services.actionsOrNull();
        if (a != null) {
            a.register(new SuiteAction("sort_hall", "Sort storage hall", "storage chest organize tidy categories",
                    () -> post(sortHall())));
            a.register(new SuiteAction("hall_tag_chest", "Tag looked-at chest category",
                    "storage chest category label hall", () -> post(tagLookedAt())));
        }
    }

    public void onRegisterKeys(RegisterKeyMappingsEvent event) {
        tagKey = new KeyMapping(KEY_TAG, KeyConflictContext.IN_GAME, InputConstants.UNKNOWN,
                com.utilitysuite.core.input.KeybindManager.CATEGORY);
        event.register(tagKey);
    }

    public String lastResult() {
        return lastResult;
    }

    /** Queues a "sort hall" job (the suite action; the goal console parses "sort hall" itself). */
    public String sortHall() {
        if (!enabled) {
            return lastResult = "Hall Sorter is off (Jobs tab)";
        }
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            return lastResult = "The goal engine is not loaded";
        }
        long id = goals.submit(new GoalRequest.SortHall(CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY));
        return lastResult = "Queued job #" + id + ": sort storage hall";
    }

    // ---- HallJobSource ----------------------------------------------------------------------------------------------

    @Override
    public Result hallPlan(String dimension, long from, boolean forRun) {
        if (!enabled) {
            return Result.error("Hall Sorter is off (Jobs tab)");
        }
        List<HallSortPlanner.Chest> chests = memory.hallChests(dimension, from, radius);
        if (chests.size() < 2) {
            return Result.error("Open each hall chest once so its slots are remembered (" + chests.size()
                    + " known within " + radius + " blocks)");
        }
        HallSortPlanner.Plan plan = planner.plan(chests, nearlyFullPct / 100.0);
        if (forRun && fullNotice && !plan.nearlyFull().isEmpty()) {
            post(plan.nearlyFull().size() + " hall chest(s) nearly full");
        }
        return new Result(HallSortSteps.build(chests, plan, dimension), null);
    }

    /** Cycles the category tag of the chest under the crosshair. */
    public String tagLookedAt() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return lastResult = "Look at a chest first";
        }
        String dim = mc.level.dimension().location().toString();
        long pos = hit.getBlockPos().asLong();
        if (!memory.contains(dim, pos)) {
            return lastResult = "Open this chest once first";
        }
        String t = memory.cycleTag(dim, pos);
        return lastResult = t.isEmpty() ? "Chest tag cleared (category inferred)" : "Chest tagged: " + t;
    }

    private static void post(String text) {
        if (!Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(NoticeKind.INFO, text, null);
        }
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        KeyMapping k = tagKey;
        if (k != null) {
            while (k.consumeClick()) {
                if (enabled) {
                    post(tagLookedAt());
                }
            }
        }
        updateFullScan();
    }

    // ---- nearly-full outline: own batched pass through the Shell's world kit ------------------------------------------

    private long[] fullPos = new long[64];
    private int fullCount;
    private int scanTicks;

    /** Budgeted rescan of remembered containers (every {@link #RESCAN_TICKS}); grows the buffer only when it fills. */
    private void updateFullScan() {
        if (!enabled || !fullOverlay || Services.modState().isCleanScreen()) {
            fullCount = 0;
            scanTicks = 0;
            return;
        }
        if (scanTicks-- > 0) {
            return;
        }
        scanTicks = RESCAN_TICKS;
        if (Minecraft.getInstance().player == null) {
            fullCount = 0;
            return;
        }
        String dim = actions.dimension();
        long at = actions.playerPos();
        double ratio = nearlyFullPct / 100.0;
        int n = memory.nearlyFull(dim, at, radius, ratio, fullPos);
        while (n == fullPos.length && fullPos.length < MAX_OUTLINES) {
            fullPos = new long[Math.min(MAX_OUTLINES, fullPos.length * 2)];
            n = memory.nearlyFull(dim, at, radius, ratio, fullPos);
        }
        fullCount = n;
    }

    /** Outlines the nearly-full chests found by the last scan. Nothing when idle, off or in Clean-Screen. */
    public void onRenderLevel(RenderLevelStageEvent event) {
        if (fullCount == 0 || !enabled || !fullOverlay
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || Services.modState().isCleanScreen()) {
            return;
        }
        IVisuals visuals = Services.visualsOrNull();
        IHoloWorld world = visuals != null ? visuals.world() : null;
        if (world == null) {
            return; // no world pass (no Shell)
        }
        Vec3 cam = event.getCamera().getPosition();
        outline(world, fullPos, fullCount, cam.x, cam.y, cam.z);
    }

    /** Lift of the outline off the block faces, against z-fighting. */
    static final float OUTSET = 0.02f;

    /** One box per packed position, camera relative, in the warning token. Allocation free. */
    static void outline(IHoloWorld world, long[] pos, int count, double camX, double camY, double camZ) {
        for (int i = 0; i < count; i++) {
            long p = pos[i];
            float x = (float) (PackedPos.x(p) - camX);
            float y = (float) (PackedPos.y(p) - camY);
            float z = (float) (PackedPos.z(p) - camZ);
            world.boxEdges(x - OUTSET, y - OUTSET, z - OUTSET, x + 1 + OUTSET, y + 1 + OUTSET, z + 1 + OUTSET,
                    HoloToken.WARN, 0.85f);
        }
    }

    int outlineCount() {
        return fullCount;
    }

    @Override
    public void onWorldUnload() {
        fullCount = 0;
        scanTicks = 0;
    }

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
            fullCount = 0;
            cancelSortJobs();
        }
    }

    /** Turning the sorter off cancels its running or queued sort jobs. */
    private static void cancelSortJobs() {
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            return;
        }
        String label = new GoalRequest.SortHall(CostProfile.FASTEST, DataSourceMode.OBSERVED_ONLY).label();
        goals.activeJob().filter(j -> label.equals(j.goalLabel())).ifPresent(j -> goals.cancel(j.jobId()));
        for (JobSnapshot j : goals.queuedJobs()) {
            if (label.equals(j.goalLabel())) {
                goals.cancel(j.jobId());
            }
        }
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Hall Sorter";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("radius", "Hall radius", 24, 4, 64, "",
                        "Remembered chests within this many blocks of you take part in \"sort hall\""),
                SettingSpec.integer("nearly_full_pct", "Nearly full at (%)", 90, 50, 100, "",
                        "A chest counts as nearly full when this share of its slots is used"),
                SettingSpec.bool("nearly_full_notice", "Nearly-full notice", false, "",
                        "Post a notice saying how many hall chests are nearly full when a sort starts"),
                SettingSpec.bool("nearly_full_overlay", "Outline nearly-full chests", false, "",
                        "Outline every nearly-full remembered chest within the hall radius in the world"));
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        radius = Math.max(4, Math.min(64, p.getInt(ID, "radius", 24)));
        nearlyFullPct = Math.max(50, Math.min(100, p.getInt(ID, "nearly_full_pct", 90)));
        fullNotice = p.getBool(ID, "nearly_full_notice", false);
        fullOverlay = p.getBool(ID, "nearly_full_overlay", false);
        scanTicks = 0;
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
