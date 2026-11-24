package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.PlanPreview;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.jobs.planner.DependencyPlanner;
import com.utilitysuite.jobs.planner.ErrandBatcher;
import com.utilitysuite.jobs.planner.Plan;
import com.utilitysuite.jobs.planner.PlanContext;
import com.utilitysuite.jobs.planner.PlanStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * GoalConsole + job queue as one module: parses commands, produces dry-run previews off-thread, and runs jobs through
 * {@link JobExecutor}. Registered as {@code IGoalEngine}.
 * <p>
 * Build hook: the schematic toolkit calls {@link #registerBuildHandler(BuildHandler)}; until then build jobs stay
 * queued as "waiting for schematic toolkit".
 */
public final class GoalEngine implements IGoalEngine, IModule, IConfigurable {

    public static final String ID = JobExecutor.MODULE_ID;

    private static volatile GoalEngine instance;
    private static volatile BuildHandler pendingHandler;

    private final GoalParser parser;
    private final Supplier<DependencyPlanner> planner;
    private final JobExecutor executor;
    private final Executor previewExecutor;
    private volatile PlanPreview lastPreview;
    private boolean enabled = true;
    private CostProfile defaultCost = CostProfile.FASTEST;

    public GoalEngine(GoalParser parser, Supplier<DependencyPlanner> planner, JobExecutor executor,
                      Executor previewExecutor) {
        this.parser = parser;
        this.planner = planner;
        this.executor = executor;
        this.previewExecutor = previewExecutor;
        instance = this;
        if (pendingHandler != null) {
            executor.setBuildHandler(pendingHandler);
        }
    }

    /**
     * Hook for the schematic toolkit: registers the handler that turns {@link GoalRequest.Build} jobs into material
     * needs and block placement. Safe to call before or after the goal engine is constructed. Client thread.
     */
    public static void registerBuildHandler(BuildHandler handler) {
        pendingHandler = handler;
        GoalEngine e = instance;
        if (e != null) {
            e.executor.setBuildHandler(handler);
        }
    }

    public JobExecutor executor() {
        return executor;
    }

    // ---- IGoalEngine ------------------------------------------------------------------------------------------------

    @Override
    public ParseResult parse(String command) {
        ParseResult r = parser.parse(command);
        if (r.isOk() && !command.toLowerCase(java.util.Locale.ROOT).contains("cost=")
                && r.request().cost() != defaultCost) {
            return ParseResult.ok(withCost(r.request(), defaultCost));
        }
        return r;
    }

    @Override
    public void requestPreview(GoalRequest request) {
        if (request instanceof GoalRequest.Travel t) {
            lastPreview = new PlanPreview(request, t.label() + " (" + t.dimension() + ")", List.of(t.label()),
                    Map.of(), List.of());
            return;
        }
        List<DependencyPlanner.Target> targets = executor.targetsFor(request);
        if (request instanceof GoalRequest.Build b && targets.isEmpty()) {
            lastPreview = new PlanPreview(request, b.label(), List.of("Waiting for schematic toolkit"), Map.of(),
                    List.of("No schematic toolkit is registered yet"));
            return;
        }
        PlanContext ctx = executor.context(request, Set.of(), Map.of(), true);
        List<ErrandBatcher.JobPlan> queued = executor.provisionalPlans();
        DependencyPlanner p = planner.get();
        previewExecutor.execute(() -> lastPreview = buildPreview(request, p.plan(targets, ctx), queued));
    }

    static PlanPreview buildPreview(GoalRequest request, Plan plan, List<ErrandBatcher.JobPlan> queued) {
        List<String> steps = new ArrayList<>();
        for (PlanStep s : plan.steps()) {
            steps.add(s.milestone() + " — " + s.describe());
        }
        List<String> warnings = new ArrayList<>(plan.warnings());
        warnings.add("Data source: " + request.dataSource() + ", cost profile: " + request.cost());
        warnings.add(DependencyPlanner.profileNote(request.cost()));
        List<ErrandBatcher.JobPlan> all = new ArrayList<>(queued);
        all.add(new ErrandBatcher.JobPlan(-1, plan));
        for (ErrandBatcher.Errand e : ErrandBatcher.batch(all, ErrandBatcher.DEFAULT_BAND_HEIGHT)) {
            if (e.jobIds().contains(-1L)) {
                warnings.add("Shared errand " + e.key() + " with queued jobs " + e.jobIds());
            }
        }
        return new PlanPreview(request, interpret(request), steps, plan.missing(), warnings,
                com.utilitysuite.jobs.planner.PlanEstimate.seconds(plan.steps()),
                com.utilitysuite.jobs.planner.PlanEstimate.requiredTools(plan.steps()));
    }

    static String interpret(GoalRequest r) {
        return switch (r) {
            case GoalRequest.Gather g -> g.count() + " x " + g.itemId();
            case GoalRequest.Craft c -> c.count() + " x " + c.itemId() + " (crafted)";
            case GoalRequest.GatherMany m -> m.items().toString();
            default -> r.label();
        };
    }

    @Override
    public Optional<PlanPreview> lastPreview() {
        return Optional.ofNullable(lastPreview);
    }

    @Override
    public long submit(GoalRequest request) {
        return executor.submit(request);
    }

    @Override
    public Optional<JobSnapshot> activeJob() {
        return executor.activeJob();
    }

    @Override
    public List<JobSnapshot> queuedJobs() {
        return executor.queuedJobs();
    }

    @Override
    public List<String> planSteps(long jobId) {
        return executor.planSteps(jobId);
    }

    @Override
    public void pause(long jobId) {
        executor.pause(jobId);
    }

    @Override
    public void resume(long jobId) {
        executor.resume(jobId);
    }

    @Override
    public void cancel(long jobId) {
        executor.cancel(jobId);
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
        if (!enabled && this.enabled) {
            executor.haltAll("Goal engine disabled");
        }
        this.enabled = enabled;
    }

    @Override
    public void onTick() {
        executor.tick();
    }

    @Override
    public void onStopAll() {
        executor.haltAll("Stopped by Stop-All; resume from the job card");
    }

    @Override
    public void onWorldUnload() {
        executor.clear();
        lastPreview = null;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        JobExecutor.Settings s = executor.settings();
        s.stuckTicks = 20 * profile.getInt(ID, "stuck_seconds", 15);
        s.maxReplans = profile.getInt(ID, "max_replans", 3);
        s.candidateRadius = profile.getInt(ID, "candidate_radius", 256);
        s.resumeSettleTicks = profile.getInt(ID, "resume_settle_ticks", ResumeHoldGate.DEFAULT_SETTLE_TICKS);
        s.resumeMaxHoldTicks = 20 * profile.getInt(ID, "resume_max_wait_seconds",
                ResumeHoldGate.DEFAULT_MAX_HOLD_TICKS / 20);
        s.batchErrands = profile.getBool(ID, "batch_errands", true);
        s.depositAfterGather = profile.getBool(ID, "deposit_after_gather", false);
        s.minToolDurability = profile.getInt(ID, "min_tool_durability", ToolDurabilityGuard.DEFAULT_MIN_DURABILITY);
        s.protectEnchantedTools = profile.getBool(ID, "protect_enchanted_tools", true);
        try {
            s.miningStyle = MiningStyle.valueOf(profile.getString(ID, "mining_style", MiningStyle.LIKE_A_PLAYER.name()));
        } catch (IllegalArgumentException e) {
            s.miningStyle = MiningStyle.LIKE_A_PLAYER;
        }
        planner.get().setEfficiencyTools(new com.utilitysuite.jobs.planner.EfficiencyTools.Settings(
                profile.getBool(ID, "efficiency_tools", true), profile.getInt(ID, "shovel_after", 24),
                profile.getInt(ID, "axe_after", 12)));
        try {
            defaultCost = CostProfile.valueOf(profile.getString(ID, "default_cost", CostProfile.FASTEST.name()));
        } catch (IllegalArgumentException e) {
            defaultCost = CostProfile.FASTEST;
        }
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Goal Engine";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice("default_cost", "Default cost profile", CostProfile.FASTEST, "",
                        "Used when a command has no cost= modifier"),
                SettingSpec.choice("mining_style", "Mining style", MiningStyle.LIKE_A_PLAYER, "",
                        "LIKE_A_PLAYER: mine only ore you can see and strip-mine at a good depth for it, like a"
                                + " player. KNOWN_ORES: head straight for ore the suite knows of, even behind stone"
                                + " (faster, less legit)."),
                SettingSpec.bool("batch_errands", "Batch shared errands", true, "",
                        "Fetch and mine for queued jobs on the same trip"),
                SettingSpec.bool("deposit_after_gather", "Deposit after gathering", false, "",
                        "Put gathered items into a known container near the start point"),
                SettingSpec.bool("efficiency_tools", "Right tool first (pro habit)", true, "Tools",
                        "Before digging a lot of dirt, sand or gravel, get a shovel; before felling a lot of logs, an axe"
                                + " (yours, from storage, or crafted). Not on the Cheapest profile."),
                SettingSpec.integer("shovel_after", "Shovel from (blocks)", 24, 4, 512, "Tools",
                        "Shovel material in one job that makes a shovel worth getting.").asAdvanced(),
                SettingSpec.integer("axe_after", "Axe from (logs)", 12, 2, 512, "Tools",
                        "Logs in one job that make an axe worth getting.").asAdvanced(),
                SettingSpec.integer("min_tool_durability", "Swap tool at (uses left)", 5, 0, 100, "Tools",
                        "Swap a pickaxe or shovel for a backup when it has this many uses left; with no backup the job"
                                + " pauses. 0 turns it off."),
                SettingSpec.bool("protect_enchanted_tools", "Never break enchanted tools", true, "Tools",
                        "Enchanted tools are swapped out (or the job pauses) instead of being used up."),
                SettingSpec.integer("stuck_seconds", "Stuck timeout (s)", 15, 5, 120, "Replanning",
                        "Seconds without progress before trying an alternative").asAdvanced(),
                SettingSpec.integer("max_replans", "Replans per step", 3, 1, 10, "Replanning",
                        "After this many replans the job pauses with a diagnostic").asAdvanced(),
                SettingSpec.integer("candidate_radius", "Prediction search radius", 256, 32, 1024, "Replanning",
                        "Radius for simulated ore candidates").asAdvanced(),
                SettingSpec.integer("resume_settle_ticks", "Resume after escape: settle (ticks)",
                        ResumeHoldGate.DEFAULT_SETTLE_TICKS, 0, 200, "Replanning",
                        "After a reflex escape, explosions and lava must stay clear this long before the job goes"
                                + " back").asAdvanced(),
                SettingSpec.integer("resume_max_wait_seconds", "Resume after escape: longest wait (s)",
                        ResumeHoldGate.DEFAULT_MAX_HOLD_TICKS / 20, 2, 120, "Replanning",
                        "If the hazard is still there after this long, the job pauses with a notice instead of"
                                + " walking back in").asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(com.utilitysuite.core.api.Services.modState().profile());
    }

    private static GoalRequest withCost(GoalRequest r, CostProfile c) {
        return switch (r) {
            case GoalRequest.Gather g -> new GoalRequest.Gather(g.itemId(), g.count(), c, g.dataSource());
            case GoalRequest.Craft g -> new GoalRequest.Craft(g.itemId(), g.count(), c, g.dataSource());
            case GoalRequest.Build b -> new GoalRequest.Build(b.schematicPath(), b.anchor(), b.gatherOnly(), c,
                    b.dataSource());
            case GoalRequest.GatherMany m -> new GoalRequest.GatherMany(m.items(), m.reason(), c, m.dataSource());
            case GoalRequest.Travel t -> new GoalRequest.Travel(t.x(), t.y(), t.z(), t.dimension(), c, t.dataSource());
            case GoalRequest.Custom x -> new GoalRequest.Custom(x.text(), c, x.dataSource());
        };
    }
}
