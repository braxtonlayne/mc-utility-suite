package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.CandidateQuery;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IDangerModel;
import com.utilitysuite.core.api.IInventoryView;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.IWorldKnowledgeService;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.StorageEntry;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.api.WorldObservation;
import com.utilitysuite.jobs.planner.DependencyPlanner;
import com.utilitysuite.jobs.planner.DisplayNames;
import com.utilitysuite.jobs.planner.WoodTypes;
import com.utilitysuite.jobs.planner.ErrandBatcher;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.planner.Plan;
import com.utilitysuite.jobs.planner.PlanContext;
import com.utilitysuite.jobs.planner.PlanStep;
import com.utilitysuite.jobs.planner.StepKind;
import com.utilitysuite.jobs.planner.StepRole;
import com.utilitysuite.jobs.planner.StripRoutePlanner;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import com.utilitysuite.jobs.planner.PositionPredicate;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Runs queued goal jobs step by step against {@link MiningDriver} and {@link WorldActions}, with live replanning:
 * <ul>
 *   <li>yield-based mining (inventory delta of the item, so Fortune/Silk Touch change the remaining work);</li>
 *   <li>data-source handling per job: OBSERVED_ONLY scans loaded chunks, SIMULATED_ONLY walks predicted candidates,
 *       HYBRID walks candidates and falls back to scanning, recording verified misses;</li>
 *   <li>branch repair: a broken pickaxe inserts only the tool branch, an empty supply chest replaces only that fetch
 *       branch (container marked stale and excluded), failed crafts re-resolve only that item;</li>
 *   <li>stuck detection (no progress for {@code stuckTicks}) and path failures trigger alternatives;</li>
 *   <li>at most {@code maxReplans} replans per step, then the job pauses with a diagnostic instead of looping.</li>
 * </ul>
 * Holds a GOAL lease on MOVEMENT, CAMERA and WORLD_INTERACT (plus HOTBAR for container steps) while a job runs, pauses
 * the driver when preempted and resumes from the checkpoint (step index + yield baseline) on {@code onResumable}.
 * Client thread only; planning runs on {@code planExecutor} and is applied on the next tick.
 */
public final class JobExecutor implements LeaseListener {

    public static final String MODULE_ID = "goal_engine";
    /** A stuck or failed Return/Deposit this close to the start (horizontally) counts as back. */
    static final int RETURN_NEAR_XZ = 8;

    /** Collaborators. Suppliers may return null for optional services. */
    public record Deps(IActionArbiter arbiter, Supplier<ISuiteEventBus> events, Supplier<IInventoryView> inventory,
                       Supplier<MiningDriver> driver, WorldActions actions,
                       Supplier<IWorldKnowledgeService> knowledge, Supplier<IStorageIndex> storage,
                       BiConsumer<String, Long> markStorageStale, Supplier<DependencyPlanner> planner,
                       PositionPredicate isProtected, LongSupplier ticks, LongSupplier millis,
                       Executor planExecutor) {
    }

    /** Tunables (read from the profile by {@link GoalEngine}). */
    public static final class Settings {
        public int stuckTicks = 300;
        public int maxReplans = 3;
        public int candidateRadius = 256;
        public int maxCandidates = 16;
        public boolean batchErrands = true;
        public boolean depositAfterGather = false;
        /** LIKE_A_PLAYER: no predicted or hidden ore targets; the backend mines only what it can see. */
        public MiningStyle miningStyle = MiningStyle.LIKE_A_PLAYER;
        /** After an emergency escape: ticks every explosion/lava hazard must stay clear before the job resumes. */
        public int resumeSettleTicks = ResumeHoldGate.DEFAULT_SETTLE_TICKS;
        /** After an emergency escape: longest wait for a live hazard before the job pauses with a notice. */
        public int resumeMaxHoldTicks = ResumeHoldGate.DEFAULT_MAX_HOLD_TICKS;
    }

    enum Phase { START, PLANNING, MINE_CANDIDATE, MINE_SCAN, STRIP, STRIP_ORE, WAIT_DRIVER, WAIT_ACTION, BUILD_WAIT }

    /** Strip mine (LIKE_A_PLAYER): tunnel length per route, blocks dug per segment, how far ore is looked up. */
    static final int STRIP_LENGTH = 32;
    static final int STRIP_SEGMENT = 4;
    static final int ROUTE_RADIUS = STRIP_LENGTH + 8;
    static final int ROUTE_MAX_ORES = 4096;
    /** Routes tried per mine step before falling back to the backend's own legit exploring. */
    static final int MAX_ROUTES = 6;
    /** Longest stop to mine what a segment uncovered (ticks). */
    static final int STRIP_ORE_TICKS = 20 * 45;
    /** Lowest strip level (keeps the tunnel off the bedrock layers). */
    static final int STRIP_MIN_Y = -59;

    static final class Job {
        final long id;
        final GoalRequest request;
        JobStatus status = JobStatus.QUEUED;
        String reason = "";
        /** Execution plan (mutable: branch repairs insert/replace steps). */
        final List<PlanStep> steps = new ArrayList<>();
        /** Provisional plan for queued jobs ("Show Plan", errand batching). */
        volatile Plan provisional = Plan.EMPTY;
        volatile Plan pendingPlan;
        int index;
        Phase phase = Phase.START;
        long origin = Long.MIN_VALUE;
        int totalReplans;
        final IdentityHashMap<PlanStep, Integer> replans = new IdentityHashMap<>();
        final IdentityHashMap<PlanStep, Integer> baselines = new IdentityHashMap<>();
        final Set<Long> excludedContainers = new HashSet<>();
        /** Paused by the user, Stop-All or an exhausted budget: no automatic resume. */
        boolean holdPaused;
        boolean preempted;
        boolean resumeRequested;
        List<WorldObservation> candidates = List.of();
        int candidateIdx;
        /** LIKE_A_PLAYER strip mine: the route, how far it is dug, and ore the tunnel has uncovered. */
        StripRoutePlanner.Route route;
        int routeT;
        int segFrom;
        int dugCells;
        int routesTried;
        final LongArrayList exposed = new LongArrayList();
        long oreStartTick;
        long lastProgressTick;
        int lastProgress;
        long bestDistSq = Long.MAX_VALUE;
        JobSnapshot lastPosted;

        Job(long id, GoalRequest request) {
            this.id = id;
            this.request = request;
        }

        PlanStep current() {
            return index < steps.size() ? steps.get(index) : null;
        }
    }

    private static final Set<ControlChannel> MOVE_CHANNELS =
            Set.copyOf(EnumSet.of(ControlChannel.MOVEMENT, ControlChannel.CAMERA, ControlChannel.WORLD_INTERACT));
    private static final Set<ControlChannel> ALL_CHANNELS = Set.copyOf(EnumSet.allOf(ControlChannel.class));

    private final Deps deps;
    private final Settings settings;
    private final StripRoutePlanner routePlanner = new StripRoutePlanner();
    private final List<Job> jobs = new ArrayList<>();
    private Job active;
    private ControlLease lease;
    private long nextLeaseAttempt;
    /** A next step farther than this from the player is not searched for hazards while a resume is held. */
    static final int STEP_WATCH_RANGE = 32;
    /** Ticks between searches around the next step while a resume is held. */
    static final int STEP_PROBE_INTERVAL = 5;

    /**
     * Holds a resume after an EMERGENCY preemption (creeper back-off, fall clutch) until no explosion or lava hazard
     * is live near the player or the next step, and pauses the job when one outlasts the maximum wait.
     */
    private final ResumeHoldGate resumeHold = new ResumeHoldGate();
    /** Hazards found around the next step by the last search, and when to search again. */
    private int stepHazards;
    private long nextStepProbe;
    private long nextJobId = 1;
    private BuildHandler buildHandler;

    public JobExecutor(Deps deps, Settings settings) {
        this.deps = deps;
        this.settings = settings;
    }

    public Settings settings() {
        return settings;
    }

    public void setBuildHandler(BuildHandler handler) {
        this.buildHandler = handler;
    }

    // ---- queue API --------------------------------------------------------------------------------------------------

    public long submit(GoalRequest request) {
        Job j = new Job(nextJobId++, request);
        if (request instanceof GoalRequest.Build && buildHandler == null) {
            j.reason = "Waiting for schematic toolkit";
        }
        jobs.add(j);
        refreshProvisionalPlans();
        markChanged(j);
        return j.id;
    }

    public Optional<JobSnapshot> activeJob() {
        return active == null ? Optional.empty() : Optional.of(snapshot(active));
    }

    public List<JobSnapshot> queuedJobs() {
        List<JobSnapshot> out = new ArrayList<>();
        for (Job j : jobs) {
            if (j != active && !j.status.isTerminal()) {
                out.add(snapshot(j));
            }
        }
        return out;
    }

    public List<String> planSteps(long jobId) {
        Job j = find(jobId);
        if (j == null) {
            return List.of();
        }
        List<PlanStep> src = j.steps.isEmpty() ? j.provisional.steps() : j.steps;
        List<String> out = new ArrayList<>(src.size());
        for (int i = 0; i < src.size(); i++) {
            String mark = j.steps.isEmpty() ? "  " : i < j.index ? "✓ " : i == j.index ? "> " : "  ";
            out.add(mark + src.get(i).milestone() + " — " + src.get(i).describe());
        }
        return out;
    }

    /** Current execution steps of a job (tests, diagnostics). */
    public List<PlanStep> steps(long jobId) {
        Job j = find(jobId);
        return j == null ? List.of() : List.copyOf(j.steps);
    }

    public int stepIndex(long jobId) {
        Job j = find(jobId);
        return j == null ? -1 : j.index;
    }

    public Optional<JobSnapshot> snapshot(long jobId) {
        Job j = find(jobId);
        return j == null ? Optional.empty() : Optional.of(snapshot(j));
    }

    public void pause(long jobId) {
        Job j = find(jobId);
        if (j == null || j.status.isTerminal()) {
            return;
        }
        j.holdPaused = true;
        if (j == active) {
            driver().pause();
            deps.actions().abort();
            restartPhaseIfAction(j);
            releaseLease();
        }
        setStatus(j, JobStatus.PAUSED, "Paused by user");
    }

    public void resume(long jobId) {
        Job j = find(jobId);
        if (j == null || j.status != JobStatus.PAUSED) {
            return;
        }
        j.holdPaused = false;
        resumeHold.disarm(); // the user's own resume wins over the hazard hold
        PlanStep cur = j.current();
        if (cur != null && j.replans.getOrDefault(cur, 0) >= settings.maxReplans) {
            j.replans.put(cur, 0);
        }
        nextLeaseAttempt = 0;
        if (j.steps.isEmpty()) {
            setStatus(j, JobStatus.QUEUED, "Resumed; waiting for its turn");
            return;
        }
        // stays PAUSED until it is active again and the lease is granted; then it continues from its checkpoint
        j.resumeRequested = true;
        setStatus(j, JobStatus.PAUSED, "Resuming from step " + (j.index + 1));
    }

    /** Moves a job behind every other queued job (death recovery runs its errands first). */
    public void requeueAtEnd(long jobId) {
        Job j = find(jobId);
        if (j == null || j == active) {
            return;
        }
        jobs.remove(j);
        jobs.add(j);
    }

    public void cancel(long jobId) {
        Job j = find(jobId);
        if (j == null || j.status.isTerminal()) {
            return;
        }
        if (j == active) {
            driver().cancel();
            deps.actions().abort();
            if (j.request instanceof GoalRequest.Build && buildHandler != null) {
                buildHandler.cancelBuild(j.id);
            }
            releaseLease();
            active = null;
        }
        setStatus(j, JobStatus.CANCELLED, "Cancelled by user");
        jobs.remove(j);
        refreshProvisionalPlans();
    }

    /** Stop-All / world unload: pause everything, drop the lease. */
    public void haltAll(String why) {
        if (active != null) {
            driver().cancel();
            deps.actions().abort();
            active.holdPaused = true;
            restartPhaseIfAction(active);
            setStatus(active, JobStatus.PAUSED, why);
        }
        resumeHold.disarm();
        releaseLease();
    }

    /** World left: jobs are world-scoped. */
    public void clear() {
        haltAll("World unloaded");
        jobs.clear();
        active = null;
    }

    // ---- tick -------------------------------------------------------------------------------------------------------

    public void tick() {
        if (active == null) {
            active = pickNext();
            if (active == null) {
                return;
            }
        }
        Job j = active;
        if (j.status.isTerminal()) {
            active = null;
            return;
        }
        if (j.status == JobStatus.PAUSED) {
            if (j.holdPaused) {
                // held (user, Stop-All, diagnostic): step aside so other queued jobs can run; resume() re-queues it
                releaseLease();
                active = null;
                return;
            }
            if (!j.resumeRequested) {
                return;
            }
            if (resumeHold.pending() && !hazardHoldAllowsResume(j)) {
                return;
            }
            if (needsLease(j.current()) && !ensureLease(channelsFor(j.current()))) {
                return;
            }
            j.resumeRequested = false;
            j.preempted = false;
            if (j.phase == Phase.MINE_CANDIDATE || j.phase == Phase.MINE_SCAN || j.phase == Phase.WAIT_DRIVER
                    || j.phase == Phase.STRIP || j.phase == Phase.STRIP_ORE) {
                driver().resume();
            }
            j.lastProgressTick = now();
            setStatus(j, JobStatus.RUNNING, "Resumed from checkpoint at step " + (j.index + 1));
        }
        if (j.phase == Phase.PLANNING) {
            Plan p = j.pendingPlan;
            if (p == null) {
                return;
            }
            j.pendingPlan = null;
            applyInitialPlan(j, p);
            return;
        }
        if (j.status == JobStatus.QUEUED) {
            startPlanning(j);
            return;
        }
        PlanStep step = j.current();
        if (step == null) {
            complete(j);
            return;
        }
        if (needsLease(step) && !ensureLease(channelsFor(step))) {
            return;
        }
        if (j.status == JobStatus.REPLANNING) {
            setStatus(j, JobStatus.RUNNING, j.reason);
        }
        switch (step.kind()) {
            case GATHER, MINE -> tickMine(j, step);
            case FETCH -> tickFetch(j, step);
            case CRAFT, SMELT -> tickCraft(j, step);
            case TRAVEL, RETURN -> tickTravel(j, step);
            case BUILD -> tickBuild(j, step);
        }
    }

    private Job pickNext() {
        for (Job j : jobs) {
            if (j.status.isTerminal() || j.holdPaused) {
                continue;
            }
            if (j.request instanceof GoalRequest.Build && buildHandler == null) {
                continue;
            }
            return j;
        }
        return null;
    }

    // ---- planning ---------------------------------------------------------------------------------------------------

    private void startPlanning(Job j) {
        j.phase = Phase.PLANNING;
        j.origin = deps.actions().playerPos();
        setStatus(j, JobStatus.PLANNING, "Planning from current inventory and known storage");
        if (j.request instanceof GoalRequest.Travel t) {
            String here = deps.actions().dimension();
            if (t.dimension() != null && !t.dimension().isEmpty() && !t.dimension().equals(here)) {
                // Coordinates are only meaningful in their own dimension; portal routing is a later milestone.
                fail(j, "Travel target is in " + GoalParser.shortDim(t.dimension()) + " but you are in "
                        + GoalParser.shortDim(here) + "; cross-dimension routing is not supported yet");
                return;
            }
            j.pendingPlan = travelPlan(t);
            return;
        }
        PlanContext ctx = context(j, Map.of(), true);
        List<DependencyPlanner.Target> targets = targetsFor(j.request);
        DependencyPlanner planner = deps.planner().get();
        deps.planExecutor().execute(() -> j.pendingPlan = planner.plan(targets, ctx));
    }

    private void applyInitialPlan(Job j, Plan p) {
        j.steps.clear();
        j.steps.addAll(p.steps());
        if (j.request instanceof GoalRequest.Build b) {
            // Gather-only too: the toolkit diffs the schematic and submits the GatherMany itself (see SchematicBuildBridge),
            // since missingMaterials() is unknown until the schematic is loaded.
            j.steps.add(new PlanStep(StepKind.BUILD, "", 1, 0, null, List.of(), 0, b.anchor(),
                    deps.actions().dimension(), StepRole.TARGET, b.label(), null));
        }
        j.index = 0;
        j.phase = Phase.START;
        if (j.request instanceof GoalRequest.Custom) {
            fail(j, "Custom goals are not supported by the planner");
            return;
        }
        if (j.steps.isEmpty()) {
            complete(j);
            return;
        }
        String warn = p.warnings().isEmpty() ? "" : " (" + String.join("; ", p.warnings()) + ")";
        setStatus(j, JobStatus.RUNNING, "Plan ready: " + j.steps.size() + " steps" + warn);
    }

    public List<DependencyPlanner.Target> targetsFor(GoalRequest request) {
        List<DependencyPlanner.Target> t = new ArrayList<>();
        switch (request) {
            case GoalRequest.Gather g -> t.add(new DependencyPlanner.Target(g.itemId(), g.count(),
                    DependencyPlanner.TargetMode.GATHER));
            case GoalRequest.Craft c -> t.add(new DependencyPlanner.Target(c.itemId(), c.count(),
                    DependencyPlanner.TargetMode.CRAFT));
            case GoalRequest.GatherMany m -> m.items().forEach((k, v) -> t.add(new DependencyPlanner.Target(k, v,
                    DependencyPlanner.TargetMode.GATHER)));
            case GoalRequest.Build b -> {
                if (buildHandler != null) {
                    buildHandler.missingMaterials(b).forEach((k, v) -> t.add(new DependencyPlanner.Target(k, v,
                            DependencyPlanner.TargetMode.GATHER)));
                }
            }
            default -> {
            }
        }
        return t;
    }

    private Plan travelPlan(GoalRequest.Travel t) {
        PlanStep s = new PlanStep(StepKind.TRAVEL, "", 1, 0, null, List.of(), 0, PackedPos.pack(t.x(), t.y(), t.z()),
                t.dimension(), StepRole.TARGET, "Travel to " + t.x() + " " + t.y() + " " + t.z(), null);
        return new Plan(List.of(s), Map.of(), List.of(), Map.of());
    }

    private PlanContext context(Job j, Map<String, Integer> credit, boolean includeReturn) {
        return context(j.request, j.excludedContainers, credit, includeReturn);
    }

    /** Captures planning inputs for a request (client thread). Also used for dry-run previews. */
    public PlanContext context(GoalRequest request, Set<Long> excluded, Map<String, Integer> credit,
                               boolean includeReturn) {
        IInventoryView inv = deps.inventory().get();
        boolean ret = includeReturn && !(request instanceof GoalRequest.Craft);
        return PlanContext.capture(inv, deps.storage().get(), deps.actions().dimension(), deps.actions().playerPos(),
                request.cost(), credit, deps.actions().nearbyStations(), excluded, ret);
    }

    /** Provisional plans of queued jobs (job id, plan), in queue order. */
    public List<ErrandBatcher.JobPlan> provisionalPlans() {
        List<ErrandBatcher.JobPlan> out = new ArrayList<>();
        for (Job j : jobs) {
            if (!j.status.isTerminal()) {
                out.add(new ErrandBatcher.JobPlan(j.id, j.steps.isEmpty() ? j.provisional
                        : new Plan(j.steps.subList(j.index, j.steps.size()), Map.of(), List.of(), Map.of())));
            }
        }
        return out;
    }

    /** Provisional plans for queued jobs, each crediting the surplus of the jobs ahead of it. */
    private void refreshProvisionalPlans() {
        // Inputs are captured here on the client thread; the chained planning runs on the plan executor.
        List<Job> order = new ArrayList<>();
        List<PlanContext> contexts = new ArrayList<>();
        List<List<DependencyPlanner.Target>> targets = new ArrayList<>();
        for (Job j : jobs) {
            if (j.status.isTerminal() || j.request instanceof GoalRequest.Travel) {
                continue;
            }
            if (j == active && !j.steps.isEmpty()) {
                continue;
            }
            order.add(j);
            contexts.add(context(j, Map.of(), true));
            targets.add(targetsFor(j.request));
        }
        if (order.isEmpty()) {
            return;
        }
        DependencyPlanner planner = deps.planner().get();
        deps.planExecutor().execute(() -> {
            Map<String, Integer> credit = new HashMap<>();
            for (int i = 0; i < order.size(); i++) {
                Plan p = planner.plan(targets.get(i), contexts.get(i).withCredit(credit));
                order.get(i).provisional = p;
                p.surplus().forEach((k, v) -> credit.merge(k, v, Integer::sum));
            }
        });
    }

    // ---- steps ------------------------------------------------------------------------------------------------------

    private void tickMine(Job j, PlanStep step) {
        IInventoryView inv = deps.inventory().get();
        MiningDriver d = driver();
        if (j.phase == Phase.START) {
            if (!d.isAvailable()) {
                pauseDiagnostic(j, "Baritone is not installed; '" + step.label() + "' needs it");
                return;
            }
            int base = j.baselines.computeIfAbsent(step, s -> stepCount(inv, s));
            int progress = stepCount(inv, step) - base;
            if (progress >= step.count()) {
                completeStep(j);
                return;
            }
            j.lastProgress = progress;
            j.lastProgressTick = now();
            DataSourceMode mode = modeFor(j);
            if (settings.miningStyle == MiningStyle.LIKE_A_PLAYER) {
                // a player does not walk to ore behind solid stone; what the suite knows only picks where to dig
                j.routesTried = 0;
                if (!startStrip(j, step)) {
                    startScan(j, step, progress);
                }
                return;
            }
            if (mode == DataSourceMode.OBSERVED_ONLY) {
                startScan(j, step, progress);
                return;
            }
            j.candidates = queryCandidates(step, mode, j.request.cost());
            j.candidateIdx = 0;
            if (j.candidates.isEmpty()) {
                if (mode == DataSourceMode.HYBRID) {
                    setReason(j, "No predicted " + step.label() + " nearby; scanning loaded chunks");
                    startScan(j, step, progress);
                } else {
                    pauseDiagnostic(j, "No simulated candidates for " + step.itemId() + " within "
                            + settings.candidateRadius + " blocks");
                }
                return;
            }
            approachCandidate(j);
            return;
        }
        int progress = stepCount(inv, step) - j.baselines.getOrDefault(step, 0);
        if (progress >= step.count()) {
            d.cancel();
            completeStep(j);
            return;
        }
        if (progress != j.lastProgress) {
            j.lastProgress = progress;
            j.lastProgressTick = now();
            markChanged(j);
        }
        if (step.requiredTier() > 0 && inv.bestPickaxeTier() < step.requiredTier()) {
            repairTool(j, step);
            return;
        }
        MiningDriver.Status st = d.status();
        if (j.phase == Phase.STRIP || j.phase == Phase.STRIP_ORE) {
            tickStrip(j, step, progress, st);
            if (j.phase == Phase.STRIP || j.phase == Phase.STRIP_ORE) {
                stuckCheck(j, step, progress);
            }
            return;
        }
        if (j.phase == Phase.MINE_CANDIDATE) {
            long c = j.candidates.get(j.candidateIdx).packedPos();
            String dim = j.candidates.get(j.candidateIdx).dimension();
            String actual = deps.actions().blockAtIfLoaded(dim, c);
            if (actual != null) {
                if (step.blockIds().contains(actual)) {
                    IWorldKnowledgeService k = deps.knowledge().get();
                    if (k != null) {
                        k.recordObserved(dim, c, actual);
                    }
                    setReason(j, "Prediction confirmed: " + actual + " at " + PackedPos.format(c));
                    startScan(j, step, progress);
                } else {
                    verifiedMiss(j, step, dim, c, actual);
                }
                return;
            }
        }
        if (st == MiningDriver.Status.FAILED) {
            if (!replan(j, step, "Pathing failed: " + d.failureReason())) {
                return;
            }
            alternative(j, step, progress);
            return;
        }
        if (j.phase == Phase.MINE_SCAN && (st == MiningDriver.Status.IDLE || st == MiningDriver.Status.ARRIVED)) {
            d.mineByYield(step.itemId(), step.count() - progress, step.blockIds());
        } else if (j.phase == Phase.MINE_CANDIDATE && st == MiningDriver.Status.ARRIVED) {
            // arrived but the chunk still is not readable: treat the area as observed territory
            startScan(j, step, progress);
            return;
        }
        stuckCheck(j, step, progress);
    }

    private void stuckCheck(Job j, PlanStep step, int progress) {
        if (now() - j.lastProgressTick > settings.stuckTicks) {
            if (!replan(j, step, "No progress for " + settings.stuckTicks / 20 + " s on '" + step.label() + "'")) {
                return;
            }
            j.lastProgressTick = now();
            driver().cancel();
            alternative(j, step, progress);
        }
    }

    // ---- strip mining (LIKE_A_PLAYER) ---------------------------------------------------------------------------

    /**
     * Picks a strip mine that happens to run past the most known ore ({@link StripRoutePlanner}) and heads for its
     * start under the player's feet. False when there is no ore band for the block, no knowledge, or no route touches
     * any ore. The knowledge only picks the route; the job mines just what the tunnel uncovers.
     */
    private boolean startStrip(Job j, PlanStep step) {
        int[] band = MiningStyle.band(step.blockIds());
        IWorldKnowledgeService k = deps.knowledge().get();
        if (band == null || k == null || j.routesTried >= MAX_ROUTES) {
            return false;
        }
        String dim = deps.actions().dimension();
        long me = deps.actions().playerPos();
        int minY = Math.max(band[0], STRIP_MIN_Y);
        int maxY = band[1];
        // the query radius is spherical: centre it in the band so every level of every tunnel is inside
        int half = (maxY - minY) / 2 + 2;
        int radius = (int) Math.ceil(Math.sqrt((double) ROUTE_RADIUS * ROUTE_RADIUS + (double) half * half));
        long centre = PackedPos.pack(PackedPos.x(me), minY + (maxY - minY) / 2, PackedPos.z(me));
        List<WorldObservation> obs = k.findCandidates(new CandidateQuery(Set.copyOf(step.blockIds()), dim, centre,
                radius, ROUTE_MAX_ORES, DataSourceMode.HYBRID));
        long[] ores = new long[obs.size()];
        int n = 0;
        for (WorldObservation o : obs) {
            if (!k.isVerifiedMiss(o.dimension(), o.packedPos())
                    && !deps.isProtected().test(o.dimension(), o.packedPos())) {
                ores[n++] = o.packedPos();
            }
        }
        // known fluid pockets push the tunnel away (lava weighs above water); once per route, not per tick
        routePlanner.setFluids(fluidCells(k, dim, centre, radius, "minecraft:lava"),
                fluidCells(k, dim, centre, radius, "minecraft:water"));
        StripRoutePlanner.Route r = routePlanner.best(java.util.Arrays.copyOf(ores, n), PackedPos.x(me),
                PackedPos.y(me), PackedPos.z(me), minY, maxY, STRIP_LENGTH);
        j.routesTried++;
        if (r == null) {
            return false;
        }
        j.route = r;
        j.routeT = 0;
        j.segFrom = 0;
        j.exposed.clear();
        j.bestDistSq = Long.MAX_VALUE;
        j.phase = Phase.STRIP;
        driver().cancel();
        driver().gotoCoord(r.cell(0), 0); // dig down (or up) to the tunnel level
        setReason(j, "Strip mining for " + step.label() + " at y " + r.y() + ", heading " + heading(r));
        markChanged(j);
        return true;
    }

    /** Packed positions of known cells of one fluid around the route area (empty when the knowledge has none). */
    private static long[] fluidCells(IWorldKnowledgeService k, String dim, long centre, int radius, String fluidId) {
        List<WorldObservation> obs = k.findCandidates(new CandidateQuery(Set.of(fluidId), dim, centre, radius,
                ROUTE_MAX_ORES, DataSourceMode.HYBRID));
        long[] out = new long[obs.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = obs.get(i).packedPos();
        }
        return out;
    }

    private static String heading(StripRoutePlanner.Route r) {
        return r.dx() > 0 ? "east" : r.dx() < 0 ? "west" : r.dz() > 0 ? "south" : "north";
    }

    private void tickStrip(Job j, PlanStep step, int progress, MiningDriver.Status st) {
        MiningDriver d = driver();
        if (st == MiningDriver.Status.FAILED) {
            if (!replan(j, step, "Strip mine: " + d.failureReason())) {
                return;
            }
            alternative(j, step, progress);
            return;
        }
        if (j.phase == Phase.STRIP_ORE) {
            pruneExposed(j, step);
            if (j.exposed.isEmpty() || now() - j.oreStartTick > STRIP_ORE_TICKS) {
                d.cancel();
                j.phase = Phase.STRIP;
                nextSegment(j, step, progress);
            } else if (st == MiningDriver.Status.IDLE || st == MiningDriver.Status.ARRIVED) {
                d.mineByYield(step.itemId(), step.count() - progress, step.blockIds());
            }
            return;
        }
        if (st == MiningDriver.Status.RUNNING) {
            // digging down to the level (or along a segment) counts as progress while the gap keeps closing
            long d2 = PackedPos.distSq(deps.actions().playerPos(), j.route.cell(j.routeT));
            if (d2 < j.bestDistSq) {
                j.bestDistSq = d2;
                j.lastProgressTick = now();
            }
            // Baritone digs within reach without stepping: every block opened in the segment is progress too
            if (j.routeT > 0 && (now() % 10) == 0) {
                int dug = dugCells(j);
                if (dug > j.dugCells) {
                    j.dugCells = dug;
                    j.lastProgressTick = now();
                }
            }
            return;
        }
        // at the tunnel start, or a segment is dug: mine what it uncovered, as a player would
        collectExposed(j, step);
        if (!j.exposed.isEmpty()) {
            j.phase = Phase.STRIP_ORE;
            j.oreStartTick = now();
            d.setMiningStyle(MiningStyle.LIKE_A_PLAYER, step.blockIds());
            d.mineByYield(step.itemId(), step.count() - progress, step.blockIds());
            setReason(j, "Mining " + j.exposed.size() + " uncovered " + step.label());
            return;
        }
        nextSegment(j, step, progress);
    }

    private void nextSegment(Job j, PlanStep step, int progress) {
        StripRoutePlanner.Route r = j.route;
        if (j.routeT >= r.length()) {
            if (!startStrip(j, step)) {
                setReason(j, "No more ore along a strip mine nearby; exploring");
                startScan(j, step, progress);
            }
            return;
        }
        int from = j.routeT + 1;
        int to = Math.min(r.length(), j.routeT + STRIP_SEGMENT);
        long end = r.cell(to);
        j.segFrom = from;
        j.routeT = to;
        j.lastProgressTick = now(); // a dug segment is progress, even before any ore drops
        j.bestDistSq = Long.MAX_VALUE;
        j.dugCells = 0;
        setReason(j, "Strip mining for " + step.label() + " at y " + r.y() + ", heading " + heading(r) + ", block "
                + to + "/" + r.length());
        driver().clearArea(r.cell(from), PackedPos.pack(PackedPos.x(end), r.y() + 1, PackedPos.z(end)));
    }

    /** Open (non-solid) cells of the segment being dug; unloaded cells count as closed. */
    private int dugCells(Job j) {
        StripRoutePlanner.Route r = j.route;
        String dim = deps.actions().dimension();
        int n = 0;
        for (int t = j.segFrom; t <= j.routeT; t++) {
            long c = r.cell(t);
            for (int h = 0; h < 2; h++) {
                String b = deps.actions().blockAtIfLoaded(dim, PackedPos.pack(PackedPos.x(c), r.y() + h,
                        PackedPos.z(c)));
                if (b != null && b.endsWith("air")) {
                    n++;
                }
            }
        }
        return n;
    }

    /** Ore in the walls, floor and ceiling of the tunnel cells dug since the last look (and at the start). */
    private void collectExposed(Job j, PlanStep step) {
        StripRoutePlanner.Route r = j.route;
        String dim = deps.actions().dimension();
        for (int t = j.segFrom; t <= j.routeT; t++) {
            long c = r.cell(t);
            int x = PackedPos.x(c);
            int z = PackedPos.z(c);
            for (int h = 0; h < 2; h++) {
                int y = r.y() + h;
                addIfOre(j, step, dim, x + 1, y, z);
                addIfOre(j, step, dim, x - 1, y, z);
                addIfOre(j, step, dim, x, y, z + 1);
                addIfOre(j, step, dim, x, y, z - 1);
                addIfOre(j, step, dim, x, h == 0 ? y - 1 : y + 1, z);
            }
        }
        j.segFrom = j.routeT + 1;
    }

    /** Drops mined entries; ore next to a mined one is uncovered now (a vein), so it joins the list. */
    private void pruneExposed(Job j, PlanStep step) {
        String dim = deps.actions().dimension();
        for (int i = j.exposed.size() - 1; i >= 0; i--) {
            long p = j.exposed.getLong(i);
            String b = deps.actions().blockAtIfLoaded(dim, p);
            if (b == null || step.blockIds().contains(b)) {
                continue;
            }
            j.exposed.removeLong(i);
            int x = PackedPos.x(p);
            int y = PackedPos.y(p);
            int z = PackedPos.z(p);
            addIfOre(j, step, dim, x + 1, y, z);
            addIfOre(j, step, dim, x - 1, y, z);
            addIfOre(j, step, dim, x, y + 1, z);
            addIfOre(j, step, dim, x, y - 1, z);
            addIfOre(j, step, dim, x, y, z + 1);
            addIfOre(j, step, dim, x, y, z - 1);
        }
    }

    private void addIfOre(Job j, PlanStep step, String dim, int x, int y, int z) {
        long p = PackedPos.pack(x, y, z);
        if (j.exposed.contains(p) || deps.isProtected().test(dim, p)) {
            return;
        }
        String b = deps.actions().blockAtIfLoaded(dim, p);
        if (b != null && step.blockIds().contains(b)) {
            j.exposed.add(p);
        }
    }

    private void startScan(Job j, PlanStep step, int progress) {
        j.phase = Phase.MINE_SCAN;
        driver().setMiningStyle(settings.miningStyle, step.blockIds());
        driver().mineByYield(step.itemId(), step.count() - progress, step.blockIds());
        markChanged(j);
    }

    private void approachCandidate(Job j) {
        WorldObservation c = j.candidates.get(j.candidateIdx);
        j.phase = Phase.MINE_CANDIDATE;
        driver().gotoCoord(c.packedPos(), 2);
        setReason(j, "Heading to predicted " + c.blockId() + " at " + PackedPos.format(c.packedPos()));
    }

    /** Next-best candidate, or loaded-chunk scanning (HYBRID / OBSERVED), or a fresh candidate query. */
    private void alternative(Job j, PlanStep step, int progress) {
        if (settings.miningStyle == MiningStyle.LIKE_A_PLAYER) {
            if (!startStrip(j, step)) {
                startScan(j, step, progress);
            }
            return;
        }
        DataSourceMode mode = modeFor(j);
        if (j.phase == Phase.MINE_CANDIDATE && j.candidateIdx + 1 < j.candidates.size()) {
            j.candidateIdx++;
            approachCandidate(j);
            return;
        }
        if (mode == DataSourceMode.SIMULATED_ONLY && settings.miningStyle == MiningStyle.KNOWN_ORES) {
            j.candidates = queryCandidates(step, mode, j.request.cost());
            j.candidateIdx = 0;
            if (j.candidates.isEmpty()) {
                pauseDiagnostic(j, "No more simulated candidates for " + step.itemId());
                return;
            }
            approachCandidate(j);
            return;
        }
        startScan(j, step, progress);
    }

    private void verifiedMiss(Job j, PlanStep step, String dim, long pos, String actual) {
        IWorldKnowledgeService k = deps.knowledge().get();
        String expected = step.blockIds().isEmpty() ? step.itemId() : step.blockIds().get(0);
        if (k != null) {
            k.recordVerifiedMiss(dim, pos, actual);
        }
        ISuiteEventBus bus = deps.events().get();
        if (bus != null && k == null) { // with a knowledge service, it posts VerifiedMiss itself (once per miss)
            bus.post(new SuiteEvent.VerifiedMiss(deps.millis().getAsLong(), pos, dim, expected));
        }
        driver().cancel();
        if (!replan(j, step, "Predicted " + expected + " at " + PackedPos.format(pos) + " was " + actual)) {
            return;
        }
        int progress = stepCount(deps.inventory().get(), step) - j.baselines.getOrDefault(step, 0);
        alternative(j, step, progress);
    }

    private void repairTool(Job j, PlanStep step) {
        driver().pause();
        String why = "Pickaxe broke during '" + step.label() + "' (needs tier " + step.requiredTier() + ")";
        if (!replan(j, step, why)) {
            return;
        }
        Plan sub = deps.planner().get().planTool(step.requiredTier(), context(j, Map.of(), false));
        j.steps.addAll(j.index, sub.steps());
        j.phase = Phase.START;
        setReason(j, why + "; repairing: " + String.join(", ", sub.milestones()) + ", then resuming");
    }

    private void tickFetch(Job j, PlanStep step) {
        WorldActions a = deps.actions();
        if (j.phase == Phase.START) {
            Map<String, Integer> items = new LinkedHashMap<>();
            items.put(step.itemId(), step.count());
            if (settings.batchErrands) {
                addBatchedFetches(j, step, items);
            }
            a.beginFetch(step.storagePos(), items, lease);
            j.phase = Phase.WAIT_ACTION;
            markChanged(j);
            return;
        }
        WorldActions.Result r = a.poll();
        if (r == WorldActions.Result.RUNNING) {
            return;
        }
        int got = r == WorldActions.Result.DONE ? a.transferred().getOrDefault(step.itemId(), 0) : 0;
        if (got >= step.count()) {
            completeStep(j);
            return;
        }
        // Supply chest empty or short: mark stale, exclude, and repair only this branch.
        deps.markStorageStale().accept(step.dimension(), step.storagePos());
        j.excludedContainers.add(step.storagePos());
        int short_ = step.count() - got;
        String why = "Supply container at " + PackedPos.format(step.storagePos()) + " had " + got + "/" + step.count()
                + " " + step.itemId() + (r == WorldActions.Result.FAILED ? " (" + a.message() + ")" : "");
        if (!replan(j, step, why)) {
            return;
        }
        Plan sub = deps.planner().get().planAcquire(step.itemId(), short_, context(j, Map.of(), false));
        replaceCurrent(j, sub.steps());
        setReason(j, why + "; new source: " + String.join(", ", sub.milestones()));
    }

    private void addBatchedFetches(Job j, PlanStep step, Map<String, Integer> items) {
        for (Job q : jobs) {
            if (q == j || q.status.isTerminal() || !q.steps.isEmpty()) {
                continue;
            }
            for (PlanStep s : q.provisional.steps()) {
                if (s.kind() == StepKind.FETCH && s.storagePos() == step.storagePos()
                        && s.dimension().equals(step.dimension())) {
                    items.merge(s.itemId(), s.count(), Integer::sum);
                }
            }
        }
    }

    /**
     * Items a GATHER/MINE step has collected so far. A wood step that may fell several log types counts all of them, so
     * a birch or jungle spawn satisfies "gather oak logs for planks". Allocation-free (runs every tick).
     */
    static int stepCount(IInventoryView inv, PlanStep step) {
        List<String> blocks = step.blockIds();
        if (blocks.size() > 1 && WoodTypes.isLog(step.itemId())) {
            int c = 0;
            for (int i = 0, n = blocks.size(); i < n; i++) {
                if (WoodTypes.isLog(blocks.get(i))) {
                    c += inv.count(blocks.get(i));
                }
            }
            return c;
        }
        return inv.count(step.itemId());
    }

    /**
     * A planks craft planned from one wood (oak) when the player holds other logs: returns replacement steps that craft
     * the planks of the logs on hand (planned wood first), or null when no substitution is needed or possible. Any
     * planks satisfy the vanilla planks tag used by the later crafts (table, sticks, wooden tools).
     */
    static List<PlanStep> planksForLogsOnHand(IInventoryView inv, PlanStep step) {
        if (step.kind() != StepKind.CRAFT || step.crafts() <= 0) {
            return null;
        }
        String plannedLog = WoodTypes.logFor(step.itemId());
        if (plannedLog == null || inv.count(plannedLog) >= step.crafts()) {
            return null;
        }
        int perCraft = Math.max(1, (step.count() + step.crafts() - 1) / step.crafts());
        int left = step.crafts();
        List<PlanStep> out = new ArrayList<>(2);
        int planned = Math.min(left, inv.count(plannedLog));
        if (planned > 0) {
            out.add(planksStep(step, step.itemId(), planned, perCraft));
            left -= planned;
        }
        for (String log : WoodTypes.LOGS) {
            if (left <= 0) {
                break;
            }
            if (log.equals(plannedLog)) {
                continue;
            }
            int use = Math.min(left, inv.count(log));
            if (use > 0) {
                out.add(planksStep(step, WoodTypes.planksFor(log), use, perCraft));
                left -= use;
            }
        }
        return left > 0 || out.isEmpty() ? null : out;
    }

    private static PlanStep planksStep(PlanStep step, String planks, int crafts, int perCraft) {
        // vanilla planks recipes are named after their output ("minecraft:birch_planks")
        String recipe = step.recipeId() == null ? planks
                : step.recipeId().replace(com.utilitysuite.jobs.planner.Items.path(step.itemId()),
                com.utilitysuite.jobs.planner.Items.path(planks));
        return new PlanStep(StepKind.CRAFT, planks, crafts * perCraft, crafts, recipe, step.blockIds(),
                step.requiredTier(), step.storagePos(), step.dimension(), step.role(), step.label(), step.milestone());
    }

    private void tickCraft(Job j, PlanStep step) {
        WorldActions a = deps.actions();
        if (j.phase == Phase.START) {
            List<PlanStep> wood = planksForLogsOnHand(deps.inventory().get(), step);
            if (wood != null) {
                // Same position in the plan, no replan counted: just the planks matching the logs actually mined.
                j.steps.remove(j.index);
                j.steps.addAll(j.index, wood);
                step = wood.get(0);
                setReason(j, "Using " + DisplayNames.of(WoodTypes.logFor(step.itemId())) + " for planks");
            }
            if (step.kind() == StepKind.SMELT) {
                a.beginSmelt(step, lease);
            } else {
                a.beginCraft(step, lease);
            }
            j.phase = Phase.WAIT_ACTION;
            markChanged(j);
            return;
        }
        WorldActions.Result r = a.poll();
        if (r == WorldActions.Result.RUNNING) {
            return;
        }
        if (r == WorldActions.Result.DONE) {
            completeStep(j);
            return;
        }
        String why = (step.kind() == StepKind.SMELT ? "Smelting" : "Crafting") + " '" + step.label() + "' failed: "
                + a.message();
        if (!replan(j, step, why)) {
            return;
        }
        Plan sub = deps.planner().get().planAcquire(step.itemId(), step.count(), context(j, Map.of(), false));
        replaceCurrent(j, sub.steps());
        setReason(j, why + "; re-resolving " + step.itemId());
    }

    private void tickTravel(Job j, PlanStep step) {
        MiningDriver d = driver();
        long target = step.kind() == StepKind.RETURN ? j.origin : step.storagePos();
        if (j.phase == Phase.START) {
            if (!d.isAvailable()) {
                pauseDiagnostic(j, "Baritone is not installed; '" + step.label() + "' needs it");
                return;
            }
            j.bestDistSq = Long.MAX_VALUE;
            j.lastProgressTick = now();
            d.gotoCoord(target, step.kind() == StepKind.RETURN ? 2 : 1);
            j.phase = Phase.WAIT_DRIVER;
            markChanged(j);
            return;
        }
        if (j.phase == Phase.WAIT_ACTION) {
            WorldActions.Result r = deps.actions().poll();
            if (r != WorldActions.Result.RUNNING) {
                completeStep(j);
            }
            return;
        }
        MiningDriver.Status st = d.status();
        if (st == MiningDriver.Status.ARRIVED) {
            if (step.kind() == StepKind.RETURN && settings.depositAfterGather && startDeposit(j)) {
                return;
            }
            completeStep(j);
            return;
        }
        long dist = PackedPos.distSq(deps.actions().playerPos(), target);
        if (dist < j.bestDistSq) {
            j.bestDistSq = dist;
            j.lastProgressTick = now();
        }
        boolean stuck = now() - j.lastProgressTick > settings.stuckTicks;
        if (st == MiningDriver.Status.FAILED || stuck) {
            String why = st == MiningDriver.Status.FAILED ? "Pathing failed: " + d.failureReason()
                    : "No progress toward " + PackedPos.format(target) + " for " + settings.stuckTicks / 20 + " s";
            if (step.kind() == StepKind.RETURN
                    && (horizontalDistSq(deps.actions().playerPos(), target) <= RETURN_NEAR_XZ * RETURN_NEAR_XZ
                    || j.replans.getOrDefault(step, 0) >= settings.maxReplans)) {
                // coming back is a courtesy, the goal is already met: never pause a finished job over it (the start
                // may be somewhere the way back cannot reach, such as a tree top)
                d.cancel();
                setReason(j, why + "; stopped " + (int) Math.sqrt(horizontalDistSq(deps.actions().playerPos(), target))
                        + " blocks from the start");
                if (settings.depositAfterGather && startDeposit(j)) {
                    return;
                }
                completeStep(j);
                return;
            }
            if (!replan(j, step, why)) {
                return;
            }
            d.cancel();
            j.lastProgressTick = now();
            j.bestDistSq = Long.MAX_VALUE;
            d.gotoCoord(target, 3);
        }
    }

    private static long horizontalDistSq(long a, long b) {
        long dx = PackedPos.x(a) - PackedPos.x(b);
        long dz = PackedPos.z(a) - PackedPos.z(b);
        return dx * dx + dz * dz;
    }

    private boolean startDeposit(Job j) {
        IStorageIndex idx = deps.storage().get();
        if (idx == null || !(j.request instanceof GoalRequest.Gather g)) {
            return false;
        }
        List<StorageEntry> near = idx.containersWith(g.itemId(), deps.actions().dimension(), j.origin);
        if (near.isEmpty() || PackedPos.distSq(near.get(0).packedPos(), j.origin) > 32 * 32) {
            return false;
        }
        deps.actions().beginDeposit(near.get(0).packedPos(), Map.of(g.itemId(), g.count()), lease);
        j.phase = Phase.WAIT_ACTION;
        return true;
    }

    private void tickBuild(Job j, PlanStep step) {
        if (buildHandler == null) {
            pauseDiagnostic(j, "Waiting for schematic toolkit");
            return;
        }
        GoalRequest.Build b = (GoalRequest.Build) j.request;
        if (j.phase == Phase.START) {
            releaseLease();
            buildHandler.startBuild(j.id, b);
            j.phase = Phase.BUILD_WAIT;
            setReason(j, "Building " + b.schematicPath());
            return;
        }
        JobStatus st = buildHandler.buildStatus(j.id);
        if (st == JobStatus.COMPLETED) {
            completeStep(j);
        } else if (st == JobStatus.FAILED || st == JobStatus.CANCELLED) {
            fail(j, "Schematic toolkit reported " + st);
        }
    }

    // ---- replanning -------------------------------------------------------------------------------------------------

    /**
     * Counts a replan for the step and logs the reason on the job card.
     *
     * @return false when the budget is exhausted (the job is now paused with a diagnostic)
     */
    private boolean replan(Job j, PlanStep step, String reason) {
        int n = j.replans.getOrDefault(step, 0);
        if (n >= settings.maxReplans) {
            driver().cancel();
            deps.actions().abort();
            j.holdPaused = true;
            j.phase = Phase.START;
            releaseLease();
            setStatus(j, JobStatus.PAUSED, "Paused: replan budget (" + settings.maxReplans + ") exhausted on '"
                    + step.label() + "'. Last problem: " + reason);
            return false;
        }
        j.replans.put(step, n + 1);
        j.totalReplans++;
        setStatus(j, JobStatus.REPLANNING, reason);
        return true;
    }

    private void replaceCurrent(Job j, List<PlanStep> replacement) {
        // Replacement steps inherit the replaced step's replan count: the counter is keyed by step identity, so
        // without this a repair that keeps failing the same way would reset the budget every time and loop forever.
        int used = j.replans.getOrDefault(j.steps.get(j.index), 0);
        if (used > 0) {
            for (PlanStep s : replacement) {
                j.replans.merge(s, used, Math::max);
            }
        }
        j.steps.remove(j.index);
        j.steps.addAll(j.index, replacement);
        j.phase = Phase.START;
        if (j.index >= j.steps.size()) {
            complete(j);
        }
    }

    private void completeStep(Job j) {
        PlanStep done = j.current();
        j.index++;
        j.phase = Phase.START;
        j.candidates = List.of();
        if (done != null && done.kind() == StepKind.MINE && settings.batchErrands) {
            insertBatchedMines(j, done);
        }
        if (j.index >= j.steps.size()) {
            complete(j);
        } else {
            markChanged(j);
        }
    }

    /** After a mining step, mine queued jobs' needs in the same Y-band before leaving it. */
    private void insertBatchedMines(Job j, PlanStep done) {
        int tier = deps.inventory().get().bestPickaxeTier();
        int at = j.index;
        for (Job q : jobs) {
            if (q == j || q.status.isTerminal() || !q.steps.isEmpty()) {
                continue;
            }
            for (PlanStep s : q.provisional.steps()) {
                if (s.kind() == StepKind.MINE && s.requiredTier() <= tier && !s.itemId().equals(done.itemId())
                        && ErrandBatcher.sameBand(done, s, ErrandBatcher.DEFAULT_BAND_HEIGHT)) {
                    j.steps.add(at++, new PlanStep(s.kind(), s.itemId(), s.count(), 0, null, s.blockIds(),
                            s.requiredTier(), s.storagePos(), s.dimension(), StepRole.PRIMARY,
                            "Batched for job #" + q.id + ": " + s.label(), "Batched errands"));
                }
            }
        }
    }

    private void complete(Job j) {
        releaseLease();
        setStatus(j, JobStatus.COMPLETED, "Done");
        jobs.remove(j);
        if (active == j) {
            active = null;
        }
        refreshProvisionalPlans();
    }

    private void fail(Job j, String why) {
        driver().cancel();
        releaseLease();
        setStatus(j, JobStatus.FAILED, why);
        jobs.remove(j);
        if (active == j) {
            active = null;
        }
    }

    private void pauseDiagnostic(Job j, String why) {
        driver().cancel();
        deps.actions().abort();
        j.holdPaused = true;
        j.phase = Phase.START;
        releaseLease();
        setStatus(j, JobStatus.PAUSED, "Paused: " + why);
    }

    private void restartPhaseIfAction(Job j) {
        if (j.phase == Phase.WAIT_ACTION) {
            j.phase = Phase.START;
        }
    }

    // ---- leases -----------------------------------------------------------------------------------------------------

    /**
     * A BUILD step is carried out by the schematic toolkit, which requests its own leases (BuildHandler contract):
     * holding ours there would lock its Build Assist out of WORLD_INTERACT / CAMERA for the whole build.
     */
    private static boolean needsLease(PlanStep step) {
        return step == null || step.kind() != StepKind.BUILD;
    }

    private Set<ControlChannel> channelsFor(PlanStep step) {
        if (step == null) {
            return MOVE_CHANNELS;
        }
        return switch (step.kind()) {
            case CRAFT, SMELT, FETCH, RETURN -> ALL_CHANNELS;
            default -> MOVE_CHANNELS;
        };
    }

    private boolean ensureLease(Set<ControlChannel> channels) {
        if (lease != null && lease.isActive() && lease.channels().containsAll(channels)) {
            return true;
        }
        if (lease != null && !lease.isActive() && active != null && !active.preempted
                && active.status != JobStatus.PAUSED) {
            // ended without a callback we acted on (timeout): treat like a preemption
            onPreempted(lease, LeaseEndReason.TIMED_OUT, "timeout");
            return false;
        }
        long t = now();
        if (t < nextLeaseAttempt) {
            return false;
        }
        Optional<ControlLease> l = deps.arbiter().request(new LeaseRequest(MODULE_ID, ControlPriority.GOAL, channels,
                0, this));
        if (l.isEmpty()) {
            nextLeaseAttempt = t + 20;
            if (active != null) {
                setReason(active, "Waiting for control (higher-priority automation or manual input)");
            }
            return false;
        }
        lease = l.get();
        return true;
    }

    private void releaseLease() {
        ControlLease l = lease;
        lease = null;
        if (l != null && l.isActive()) {
            l.close();
        }
    }

    public ControlLease lease() {
        return lease;
    }

    @Override
    public void onPreempted(ControlLease former, LeaseEndReason reason, String byModuleId) {
        if (former != lease) {
            return;
        }
        lease = null;
        Job j = active;
        driver().pause();
        deps.actions().abort();
        if (j == null) {
            return;
        }
        restartPhaseIfAction(j);
        resumeHold.onPreempted(reason == LeaseEndReason.PREEMPTED && preemptedByEmergency(), hazardsNearPlayer());
        nextStepProbe = 0;
        stepHazards = 0;
        if (reason == LeaseEndReason.STOP_ALL) {
            j.holdPaused = true;
            setStatus(j, JobStatus.PAUSED, "Stopped by Stop-All; resume from the job card");
        } else if (reason == LeaseEndReason.TIMED_OUT) {
            j.resumeRequested = true;
            j.preempted = true;
            setStatus(j, JobStatus.PAUSED, "Lease timed out; re-requesting control");
        } else {
            j.preempted = true;
            String by = LeaseEndReason.MANUAL_OVERRIDE == reason ? "manual input" : byModuleId;
            setStatus(j, JobStatus.PAUSED, "Paused: preempted by " + by + "; will resume from step " + (j.index + 1));
        }
    }

    /**
     * True when the owner that just took the job's lease is an EMERGENCY reflex (the creeper back-off). The new holder
     * may not be installed yet when the callback runs, so a live explosion or lava hazard also counts.
     */
    private boolean preemptedByEmergency() {
        Optional<ControlLease> h = deps.arbiter().holder(ControlChannel.MOVEMENT);
        if (h.isPresent() && h.get().priority() == ControlPriority.EMERGENCY) {
            return true;
        }
        return hazardsNearPlayer() != 0;
    }

    /**
     * Explosion and lava hazards live near the player, from the danger model (its sources only report what is about
     * to hurt the player, so a distant fuse or lava pool is never in it).
     */
    private static int hazardsNearPlayer() {
        IDangerModel d = Services.danger();
        if (!d.any()) {
            return 0;
        }
        return (d.has(HazardKind.EXPLOSION) ? ResumeHoldGate.EXPLOSION : 0)
                | (d.has(HazardKind.LAVA) ? ResumeHoldGate.LAVA : 0);
    }

    /** Where the job goes next when it resumes, or {@link PlanStep#NO_POS} when the step has no fixed place. */
    private static long nextStepPos(Job j) {
        PlanStep step = j.current();
        if (step == null) {
            return PlanStep.NO_POS;
        }
        switch (step.kind()) {
            case RETURN:
                return j.origin;
            case FETCH:
            case TRAVEL:
                return step.storagePos();
            case GATHER:
            case MINE:
                return j.phase == Phase.MINE_CANDIDATE && j.candidateIdx < j.candidates.size()
                        ? j.candidates.get(j.candidateIdx).packedPos() : PlanStep.NO_POS;
            default:
                return PlanStep.NO_POS;
        }
    }

    /**
     * One tick of the post-escape hold. Looks around the next step only for the hazard kinds this escape has seen,
     * only when the step is within {@link #STEP_WATCH_RANGE}, and only every {@link #STEP_PROBE_INTERVAL} ticks.
     *
     * @return true when the job may resume now; false while it waits, or after it was paused with a notice
     */
    private boolean hazardHoldAllowsResume(Job j) {
        long t = now();
        int nearPlayer = hazardsNearPlayer();
        int watch = resumeHold.seen() | nearPlayer;
        if (watch == 0) {
            stepHazards = 0;
        } else if (t >= nextStepProbe) {
            nextStepProbe = t + STEP_PROBE_INTERVAL;
            long pos = nextStepPos(j);
            stepHazards = pos != PlanStep.NO_POS
                    && PackedPos.distSq(deps.actions().playerPos(), pos) <= (long) STEP_WATCH_RANGE * STEP_WATCH_RANGE
                    ? deps.actions().hazardsNear(pos, watch) : 0;
        }
        resumeHold.configure(settings.resumeMaxHoldTicks, settings.resumeSettleTicks);
        ResumeHoldGate.Decision d = resumeHold.decide(t, nearPlayer, stepHazards);
        if (d == ResumeHoldGate.Decision.GO) {
            return true;
        }
        if (d == ResumeHoldGate.Decision.HOLD) {
            setReason(j, "Waiting for hazard to clear");
            return false;
        }
        // the hazard outlasted the wait: stay paused (the user resumes from the job card) instead of walking back in
        String what = (resumeHold.blockedBy() & ResumeHoldGate.EXPLOSION) != 0 ? "an explosion hazard" : "lava";
        String why = what + " is still close after " + settings.resumeMaxHoldTicks / 20
                + " s; resume from the job card when it is safe";
        j.preempted = false;
        j.resumeRequested = false;
        pauseDiagnostic(j, why);
        ISuiteEventBus bus = deps.events().get();
        if (bus != null) {
            bus.post(new SuiteEvent.Diagnostic(deps.millis().getAsLong(), MODULE_ID, "Job " + j.id + " paused: " + why));
        }
        return false;
    }

    @Override
    public void onResumable(ControlLease formerLease) {
        Job j = active;
        if (j != null && j.preempted && !j.holdPaused && j.status == JobStatus.PAUSED) {
            j.resumeRequested = true;
            nextLeaseAttempt = 0;
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private DataSourceMode modeFor(Job j) {
        IWorldKnowledgeService k = deps.knowledge().get();
        if (k == null) {
            return DataSourceMode.OBSERVED_ONLY;
        }
        return j.request.dataSource() == null ? k.routingMode() : j.request.dataSource();
    }

    private List<WorldObservation> queryCandidates(PlanStep step, DataSourceMode mode, CostProfile cost) {
        List<WorldObservation> all = queryCandidates(step, mode);
        return cost == CostProfile.SAFEST ? safeCandidates(all) : all;
    }

    /**
     * SAFEST: drops predicted candidates below {@link DependencyPlanner#SAFE_MIN_Y} (lava level) unless nothing else is
     * left, keeping the original order otherwise.
     */
    static List<WorldObservation> safeCandidates(List<WorldObservation> in) {
        List<WorldObservation> out = new ArrayList<>(in.size());
        for (WorldObservation o : in) {
            if (PackedPos.y(o.packedPos()) >= DependencyPlanner.SAFE_MIN_Y) {
                out.add(o);
            }
        }
        return out.isEmpty() ? in : out;
    }

    private List<WorldObservation> queryCandidates(PlanStep step, DataSourceMode mode) {
        IWorldKnowledgeService k = deps.knowledge().get();
        if (k == null || step.blockIds().isEmpty()) {
            return List.of();
        }
        String dim = deps.actions().dimension();
        List<WorldObservation> raw = k.findCandidates(new CandidateQuery(Set.copyOf(step.blockIds()), dim,
                deps.actions().playerPos(), settings.candidateRadius, settings.maxCandidates, mode));
        List<WorldObservation> out = new ArrayList<>(raw.size());
        for (WorldObservation o : raw) {
            if (!k.isVerifiedMiss(o.dimension(), o.packedPos()) && !deps.isProtected().test(o.dimension(),
                    o.packedPos())) {
                out.add(o);
            }
        }
        return out;
    }

    private MiningDriver driver() {
        MiningDriver d = deps.driver().get();
        return d == null ? MiningDriver.NONE : d;
    }

    private long now() {
        return deps.ticks().getAsLong();
    }

    private Job find(long id) {
        for (Job j : jobs) {
            if (j.id == id) {
                return j;
            }
        }
        return null;
    }

    private void setStatus(Job j, JobStatus s, String reason) {
        j.status = s;
        j.reason = reason;
        markChanged(j);
    }

    private void setReason(Job j, String reason) {
        if (!reason.equals(j.reason)) {
            j.reason = reason;
            markChanged(j);
        }
    }

    /** Posts {@link SuiteEvent.JobChanged} when the visible snapshot changed (state changes, not every tick). */
    private void markChanged(Job j) {
        JobSnapshot snap = snapshot(j);
        if (snap.equals(j.lastPosted)) {
            return;
        }
        j.lastPosted = snap;
        ISuiteEventBus bus = deps.events().get();
        if (bus != null) {
            bus.post(new SuiteEvent.JobChanged(deps.millis().getAsLong(), snap));
        }
    }

    private JobSnapshot snapshot(Job j) {
        PlanStep cur = j.current();
        String current;
        int pc = 0;
        int pt = 0;
        if (cur == null) {
            current = j.status == JobStatus.COMPLETED ? "Done" : j.status == JobStatus.QUEUED ? "Queued" : "";
        } else {
            current = cur.label();
            if (cur.kind() == StepKind.MINE || cur.kind() == StepKind.GATHER) {
                pc = Math.max(0, j.lastProgress);
                pt = cur.count();
            } else {
                pt = 1;
            }
        }
        if (j.request instanceof GoalRequest.Build && buildHandler == null && j.steps.isEmpty()) {
            current = "Waiting for schematic toolkit";
        }
        String next = j.index + 1 < j.steps.size() ? j.steps.get(j.index + 1).label() : "";
        return new JobSnapshot(j.id, j.request.label(), j.status, current, pc, pt, j.reason, next, j.totalReplans);
    }
}
