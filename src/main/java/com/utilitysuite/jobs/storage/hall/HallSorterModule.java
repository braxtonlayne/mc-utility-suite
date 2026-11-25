package com.utilitysuite.jobs.storage.hall;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteAction;
import com.utilitysuite.core.api.visual.NoticeKind;
import com.utilitysuite.jobs.goal.WorldActions;
import com.utilitysuite.jobs.storage.StorageMemory;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Hall Sorter: tags the looked-at chest with a category (unbound key, cycles), and runs "sort hall" (goal console or
 * the {@code sort_hall} suite action): plans {@link HallSortPlanner} over the remembered chests near the player
 * (captured per slot by Storage Memory), then walks the trips with {@link HallSortRunner} under a GOAL lease. Nearly
 * full chests are reported as a notice. Off by default; refuses while a goal job runs (they share the world actions).
 */
public final class HallSorterModule implements IModule, IConfigurable {

    public static final String ID = "hall_sorter";
    public static final String KEY_TAG = "key.utilitysuite.hall_tag";

    private final StorageMemory memory;
    private final WorldActions actions;
    private final HallSortRunner runner;
    private final HallSortPlanner planner = new HallSortPlanner();
    private boolean enabled;
    private int radius = 24;
    private int nearlyFullPct = 90;
    private boolean fullNotice;
    private boolean fullOverlay;
    private ControlLease lease;
    private KeyMapping tagKey;
    private String lastResult = "";

    public HallSorterModule(StorageMemory memory, WorldActions actions) {
        this.memory = memory;
        this.actions = actions;
        this.runner = new HallSortRunner(actions);
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
        return runner.isRunning() ? runner.status() : lastResult;
    }

    /** Plans and starts the sort. Returns a one-line result for the console / notice. */
    public String sortHall() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled) {
            return lastResult = "Hall Sorter is off (Jobs tab)";
        }
        if (mc.player == null) {
            return lastResult = "Not in a world";
        }
        if (runner.isRunning()) {
            return lastResult = "Already sorting";
        }
        IGoalEngine goals = Services.goalsOrNull();
        if (goals != null && goals.activeJob().isPresent()) {
            return lastResult = "A goal job is running; sort after it finishes";
        }
        if (Services.arbiter().isAutomationStopped()) {
            return lastResult = "Automation is stopped";
        }
        String dim = actions.dimension();
        List<HallSortPlanner.Chest> chests = memory.hallChests(dim, actions.playerPos(), radius);
        if (chests.size() < 2) {
            return lastResult = "Open each hall chest once so its slots are remembered (" + chests.size() + " known)";
        }
        HallSortPlanner.Plan plan = planner.plan(chests, nearlyFullPct / 100.0);
        HallSortTrips.Result trips = HallSortTrips.build(chests, plan);
        if (fullNotice && !plan.nearlyFull().isEmpty()) {
            post(plan.nearlyFull().size() + " hall chest(s) nearly full");
        }
        runner.start(trips.trips());
        lastResult = runner.status() + (trips.skipped() > 0 ? " (" + trips.skipped() + " in-chest moves skipped)" : "");
        return lastResult;
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
        updateFullMarks();
        if (!runner.isRunning()) {
            releaseLease();
            return;
        }
        if (lease == null || !lease.isActive()) {
            Optional<ControlLease> l = Services.arbiter().request(new LeaseRequest(ID, ControlPriority.GOAL,
                    EnumSet.allOf(ControlChannel.class), 0, null));
            if (l.isEmpty()) {
                return; // manual input or higher priority holds control; retry next tick
            }
            lease = l.get();
        }
        runner.tick(lease);
        if (!runner.isRunning()) {
            lastResult = runner.status();
            post(lastResult);
            releaseLease();
        }
    }

    // ---- nearly-full outline (world marks, drawn batched by the Shell) ------------------------------------------------

    private static final int MAX_MARKS = 4;
    private static final String[] MARK_OWNERS = {"hall_full_0", "hall_full_1", "hall_full_2", "hall_full_3"};
    private static final String MARK_LABEL = "Nearly full";
    private final int[] markSlot = {-1, -1, -1, -1};
    private final long[] fullPos = new long[MAX_MARKS];
    private int fullCount;
    private int markTicks;

    /** Rescans every 100 ticks (budgeted), re-publishes the boxes each tick while the overlay is on. */
    private void updateFullMarks() {
        com.utilitysuite.core.api.visual.IWorldMarks marks = Services.visuals().marks();
        if (!enabled || !fullOverlay || Services.modState().isCleanScreen()) {
            clearMarks(marks);
            return;
        }
        if (markTicks-- <= 0) {
            markTicks = 100;
            fullCount = Minecraft.getInstance().player == null ? 0
                    : memory.nearlyFull(actions.dimension(), actions.playerPos(), radius, nearlyFullPct / 100.0,
                    fullPos);
        }
        for (int i = 0; i < MAX_MARKS; i++) {
            if (i >= fullCount) {
                if (markSlot[i] >= 0) {
                    marks.clear(markSlot[i]);
                }
                continue;
            }
            if (markSlot[i] < 0) {
                markSlot[i] = marks.claim(MARK_OWNERS[i]);
                if (markSlot[i] < 0) {
                    continue;
                }
            }
            long p = fullPos[i];
            int x = net.minecraft.core.BlockPos.getX(p);
            int y = net.minecraft.core.BlockPos.getY(p);
            int z = net.minecraft.core.BlockPos.getZ(p);
            marks.setBox(markSlot[i], com.utilitysuite.core.api.visual.MarkKind.OBJECTIVE, x, y, z, x + 1, y + 1,
                    z + 1, MARK_LABEL, com.utilitysuite.core.api.visual.IWorldMarks.FLAG_TAG);
        }
    }

    private void clearMarks(com.utilitysuite.core.api.visual.IWorldMarks marks) {
        if (fullCount == 0) {
            return;
        }
        for (int i = 0; i < MAX_MARKS; i++) {
            if (markSlot[i] >= 0) {
                marks.clear(markSlot[i]);
            }
        }
        fullCount = 0;
        markTicks = 0;
    }

    private void releaseLease() {
        if (lease != null) {
            if (lease.isActive()) {
                Services.arbiter().release(lease);
            }
            lease = null;
        }
    }

    @Override
    public void onStopAll() {
        runner.cancel("Hall sort stopped");
        lease = null;
    }

    @Override
    public void onWorldUnload() {
        clearMarks(Services.visuals().marks());
        runner.cancel("");
        lease = null;
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
            runner.cancel("Hall Sorter turned off");
            releaseLease();
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
                        "Post a notice listing how many hall chests are nearly full when a sort is planned"),
                SettingSpec.bool("nearly_full_overlay", "Outline nearly-full chests", false, "",
                        "Outline up to four nearly-full remembered chests near you in the world"));
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        radius = Math.max(4, Math.min(64, p.getInt(ID, "radius", 24)));
        nearlyFullPct = Math.max(50, Math.min(100, p.getInt(ID, "nearly_full_pct", 90)));
        fullNotice = p.getBool(ID, "nearly_full_notice", false);
        fullOverlay = p.getBool(ID, "nearly_full_overlay", false);
        markTicks = 0;
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
