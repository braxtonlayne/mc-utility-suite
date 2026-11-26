package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.combat.tactics.CombatVoxels;
import com.utilitysuite.combat.tactics.ComboKeeper;
import com.utilitysuite.combat.tactics.FeintPlanner;
import com.utilitysuite.combat.tactics.PotionPlanner;
import com.utilitysuite.combat.tactics.GearRead;
import com.utilitysuite.combat.tactics.HitPlanner;
import com.utilitysuite.combat.tactics.HitTradeSolver;
import com.utilitysuite.combat.tactics.OpponentModel;
import com.utilitysuite.combat.tactics.PearlSource;
import com.utilitysuite.combat.tactics.memory.HabitRecorder;
import com.utilitysuite.combat.tactics.memory.RecalledHabits;
import com.utilitysuite.combat.tactics.ReadState;
import com.utilitysuite.combat.tactics.ShieldDuel;
import com.utilitysuite.combat.tactics.TacticUtility;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IBodySimProvider;
import com.utilitysuite.core.api.IRouteRunner;
import com.utilitysuite.core.api.ISharedControl;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualFacts;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SharedGrant;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.api.SuiteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * Combat assistance, reactive by default (an accessibility aid for players with motor limitations). One master toggle:
 * the module's enabled flag arms it. While armed it stays {@link CombatState#DORMANT} during normal play and only
 * engages on a confirmed trigger:
 * <ol>
 *   <li><b>Offensive:</b> the user attacks an entity by hand and the server confirms the hit (a damage event for that
 *       entity caused by the local player within {@value #ATTACK_CONFIRM_MS} ms).</li>
 *   <li><b>Defensive:</b> the user takes damage of an entity-attack type with a resolvable, living cause entity. Fall,
 *       fire, lava, drowning and other environmental types never engage; neither does unattributable damage.</li>
 *   <li><b>Ally defense:</b> a roster player marked {@code defend} within range takes confirmed entity damage.</li>
 *   <li><b>Proactive</b> (engagement mode PROACTIVE, single-player or allow-listed servers only): a few times a second
 *       while dormant it scans for a valid target in the engage range ({@link TargetSelector}: filter, priority, line of
 *       sight) and engages it; when a target dies, escapes or hides it moves on to the next one. It pauses after the
 *       user takes over (movement, cancel key, Stop-All) and, in takeover, never scans while the user is moving.</li>
 * </ol>
 * It never targets a roster player with {@code neverTarget} (if one hits the user it stays dormant and logs).
 * It disengages when the target dies or leaves the disengage radius, after losing line of sight for the configured
 * time, on the cancel key, on any manual movement key, on Stop-All and when disarmed.
 * <p>
 * Control: one COMBAT lease over MOVEMENT + CAMERA + WORLD_INTERACT + HOTBAR (preempting GOAL/TRAVEL, which the arbiter
 * resumes afterwards). Mouse-look or attack/use clicks by the user end the lease; the assistant then waits for the
 * override pause and takes over again. All key, camera, hotbar and container actions go through the lease.
 * <p>
 * <b>Co-pilot</b> ({@link CombatSettings.ControlMode#CO_PILOT}; takeover stays the default): the same triggers and
 * the same reads of the fight, but no fight lease. The user keeps WASD, the mouse and the hotbar; a movement key never
 * ends the fight. The assistant only adds, through a {@link SharedGrant} held on the ticks it writes
 * ({@link CoPilotPlanner}): a low-authority yaw co-steer toward the {@link AimSolver} aim point, W held back as a
 * spacing nudge, sprint withheld for the reset after a hit, and, with "Swing for me", an ATTACK tap under a short
 * WORLD_INTERACT lease at the swing moment, only while the user's own crosshair is on the target (a user click wins
 * through the core's override). No stages, routes, sustain, hotbar swaps or pursuit run in co-pilot; USE stays the
 * user's and the standalone shield reflex's ({@link #stageOwns} is false without the fight lease). Holding the core's
 * hand-over key turns a co-pilot fight into a takeover until it comes up. Co-pilot stands aside while another
 * module's lease holds MOVEMENT (a job walks: the user is not driving).
 * <p>
 * Pure Java: the game is reached only through {@link CombatWorld}. {@link #onTick()} allocates nothing in steady state
 * (a new {@link RotationTarget} only when the aim point moved by more than {@value #AIM_RETARGET_DEG} degrees, or
 * {@value #AIM_TRACK_RETARGET_DEG} degrees for the tracked target aim).
 */
public final class CombatAssistant implements IModule, IConfigurable, LeaseListener {

    public static final String ID = "combat_assistant";
    static final long ATTACK_CONFIRM_MS = 750;
    static final long RECENT_OPPONENT_MS = 10_000;
    static final int MAX_CRIT_WAIT = 12;
    static final int TOTEM_RETRY_TICKS = 20;
    static final int DISENGAGE_MAX_TICKS = 10;
    static final int MAX_EAT_TICKS = 45;
    static final float AIM_RETARGET_DEG = 0.5f;
    /**
     * Target aim is a tracking stream (the smoother follows it velocity-continuously and estimates the target's angular
     * rate from successive samples), so it is re-sent at a much finer dead band: skipped samples would read as stops.
     */
    static final float AIM_TRACK_RETARGET_DEG = 0.05f;
    /** Vanilla SWAP button for the offhand slot ({@code Inventory.SLOT_OFFHAND}). */
    static final int OFFHAND_BUTTON = 40;

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/combat");
    static final ControlChannel[] LEASE_CHANNELS = {ControlChannel.MOVEMENT, ControlChannel.CAMERA,
            ControlChannel.WORLD_INTERACT, ControlChannel.HOTBAR};

    private final CombatWorld world;
    private final AllyRoster roster;
    private final IActionArbiter arbiter;
    private final IInputSmoother input;
    private final IContainerClicker clicker;
    private final LongSupplier clock;
    private final CombatSettings settings = new CombatSettings();
    private final CombatSnapshot snap = new CombatSnapshot();
    private final LeaseRequest leaseRequest;
    private final SpacingController spacing = new SpacingController();
    private final StrafePlanner strafe = new StrafePlanner();
    private final ShieldBreakSequencer shield = new ShieldBreakSequencer();
    private final AerialPlanner wind = new AerialPlanner();
    private final TacticsPlanner tactics = new TacticsPlanner();
    private final RetreatChase chase = new RetreatChase();
    private final OpponentModel opponents = new OpponentModel();
    /** C6: this fight's pearl / ranged / mace habits of the target (players, "Opponent memory" on). */
    private final HabitRecorder habits = new HabitRecorder();
    /** C6: the engaged target's remembered habits (cleared when unknown or the setting is off). */
    private final RecalledHabits recalled = new RecalledHabits();
    /** A remembered pearl-escaper is near its usual pearl-out health (read by the pearl countdown line). */
    private boolean opponentPearlLikely;
    /** Damage this player dealt, per target (same ring structure, used for the own-DPS estimate). */
    private final OpponentModel dealt = new OpponentModel();
    private final TacticUtility utility = new TacticUtility();
    /** Combo keeper, strafe-phase read, arena read, feints, rank dial, pearl intercept, the read (one facade). */
    private final ProFeelLayer feel = new ProFeelLayer(ID);
    /** Cross-session opponent memory (off by default; no store in tests). */
    private final OpponentRecall recall;
    private final double[] aimOffset = new double[2];
    /** Sprint reset of the last swing: the setting, or the combo keeper's choice. */
    private CombatSettings.SprintReset resetKind = CombatSettings.SprintReset.W_RELEASE;
    private TacticUtility.Tactic lastPositional = TacticUtility.Tactic.FIGHT;
    private FeintPlanner.Move lastFeintNoted = FeintPlanner.Move.NONE;

    // hit-trade solver (built lazily once a body-sim provider is registered; null in plain unit tests)
    /** Distance beyond spacing max inside which the trade solver drives the keys instead of the band. */
    static final double TRADE_RANGE_EXTRA = 2.5;
    /**
     * Value of a hit taken vs. one landed for the solver comes from the gear read ({@link GearRead#takenWeight}: 1.2
     * at even gear, up to 3.0 against a stronger kit; kiting multiplies it by 1.5).
     */
    private final GearRead gear = new GearRead();
    /** Even-gear weight, still used for the combo keeper's trade input (its plan scores reach, not trades). */
    static final double BASE_TAKEN_WEIGHT = GearRead.EVEN_BIAS;
    /** Kind of the next hit; the swing, the hop and the sprint key follow it. */
    private final HitPlanner hits = new HitPlanner();
    /** This tick's hit-planner overrides applied in {@link #move}: forward up (crit, sweep), sprint up, sprint down. */
    private boolean hitRelease;
    private boolean hitDropSprint;
    private boolean hitHoldSprint;
    /** The target's hit event arrived; the next health drop is its damage (expires after a few ticks). */
    private int targetHitPending;
    static final int TARGET_HIT_PENDING_TICKS = 5;
    /** Duel mode reset: back off and eat a golden apple once this far from the opponent. */
    static final double RESET_EAT_DISTANCE = 6.0;
    private TradeDriver trade;
    private boolean tradeFixed;
    private boolean targetIsPlayer;
    private int tradeTicks;
    /** The target's eye to our box last tick, and which target it was for (the reach sample of its next hit). */
    private double prevOppReach = Double.NaN;
    private int prevOppReachId = -1;
    /** Ticks the retreat chase drove the keys (diagnostics, tests). */
    private int chaseTicks;

    // route runner hand-over (pursuit and flee)
    /** Pursue along a route beyond spacing max + this; hand back to melee inside spacing max + the exit value. */
    static final double PURSUIT_ENTER_EXTRA = 6.0;
    static final double PURSUIT_EXIT_EXTRA = 5.0;
    static final double FLEE_DISTANCE = 24.0;
    /** Closing speed (blocks per tick) above which a retreat turns into a run. */
    static final double CLOSING_SPEED = 0.05;
    /** Ticks before the runner is tried again after it refused or failed. */
    static final int ROUTE_RETRY_TICKS = 20;
    /** Ticks without line of sight after which the target is pursued along a route (short flickers are ignored). */
    static final int LOS_PURSUIT_TICKS = 10;
    static final int ROUTE_NONE = 0;
    static final int ROUTE_PURSUE = 1;
    static final int ROUTE_FLEE = 2;
    /** Run to where the target's ender pearl lands ({@link PearlIntercept}). */
    static final int ROUTE_INTERCEPT = 3;
    /** The stalemate breaker's own route ({@link StalemateBreaker}: to the target's level, a meeting spot, a new angle). */
    static final int ROUTE_STALEMATE = 4;
    /**
     * LOS-lost pursuit only while the target is this far beyond spacing max horizontally: the runner's stop range is
     * horizontal, so a target straight above or below "arrives" at once and the chase would flap without moving (the
     * stalemate breaker routes to its level instead).
     */
    static final double LOS_PURSUIT_MIN_EXTRA = 0.5;
    private final StalemateBreaker stalemate = new StalemateBreaker();
    private CombatVoxels arenaVoxels;
    private int stalemateNoteSerial;
    /** The breaker's moves: our lease handed to the route runner (one instance, no per-tick allocation). */
    private final StalemateBreaker.Runner stalemateRunner = new StalemateBreaker.Runner() {
        @Override
        public boolean follow(long[] cells, int count) {
            IRouteRunner r = Services.routeRunnerOrNull();
            if (r == null || lease == null || !lease.isActive()) {
                return false;
            }
            boolean handOver = routeMode != ROUTE_STALEMATE;
            if (handOver) {
                if (routeMode != ROUTE_NONE) {
                    r.stop(lease);
                } else {
                    move(SpacingController.HOLD, StrafePlanner.NONE, false);
                }
                routeMode = ROUTE_NONE;
                clearKeyCache();
            }
            if (!r.follow(lease, cells, count, true)) {
                return false;
            }
            if (handOver) {
                routeMode = ROUTE_STALEMATE;
                routeRuns++;
            }
            return true;
        }

        @Override
        public IRouteRunner.State state() {
            IRouteRunner r = Services.routeRunnerOrNull();
            return routeMode == ROUTE_STALEMATE && r != null ? r.state() : IRouteRunner.State.IDLE;
        }

        @Override
        public void stop() {
            if (routeMode == ROUTE_STALEMATE) {
                leaveRoute();
            }
        }
    };
    private int routeMode = ROUTE_NONE;
    private int routeRetry;
    private int routeRuns;
    private TacticUtility.Tactic broadTactic = TacticUtility.Tactic.FIGHT;
    private long tacticClock;
    /** Tactic clock of the target's last totem pop (post-pop pressure). */
    private long lastOpponentPopAt = Long.MIN_VALUE / 2;
    private int lastTargetHealthId = -1;
    private float lastTargetHealth;
    /** Assumed DPS floors before any hit was seen (a sword at full charge is ~7). */
    static final double MIN_OWN_DPS = 4.0;
    static final double MIN_OPP_DPS = 1.5;
    /** Extra blocks added to both ends of the spacing band while kiting. */
    static final double KITE_EXTRA_SPACING = 0.5;
    private TacticsPlanner.Tactic lastTactic = TacticsPlanner.Tactic.NONE;
    private int tacticSwings;
    private final float[] aim = new float[2];
    private final boolean[] keyDown = new boolean[SimKey.all().length];

    // fight stages (FightStage): per-slot arrays, filled at start-up; one-tick requests cleared every drive
    private static final FightStage[] NO_STAGES = new FightStage[0];
    private final FightStage[][] stages = {NO_STAGES, NO_STAGES, NO_STAGES, NO_STAGES};
    private FightStage[] allStages = NO_STAGES;
    private List<SettingSpec> specsWithStages;
    private final StageContext stageCtx = new StageContext();
    private boolean stageVetoSwing;
    private boolean stageVetoJump;
    private HitPlanner.Policy stagePolicy;
    private boolean stageSideForced;
    private int stageSide;
    private boolean secondValid;
    private double secondX;
    private double secondZ;
    private final FlankGuard flank = new FlankGuard();
    private double stageSpacing;
    private boolean stagePress;
    private int stageTarget = -1;
    // hit records the stages read (FightContext): fight-clock ticks, hits taken since our last landed one
    private long hitTakenAt = -1L;
    private long hitLandedAt = -1L;
    private int takenUnanswered;
    private int lastAttacker = -1;
    private long lastAttackerAt = -1L;

    private boolean enabled;
    private CombatState state = CombatState.DORMANT;
    private int targetId = -1;
    private ControlLease lease;
    private boolean waitingForResume;
    private boolean lastMovePause;
    private DisengageReason lastDisengage;
    private int disengageTicks;

    // trigger bookkeeping
    private int pendingAttackTarget = -1;
    private long pendingAttackMillis;
    private int recentOpponent = -1;
    private long recentOpponentMillis;

    // per-engagement state
    private int weaponSlot = -1;
    private int losLostTicks;
    private float lastHealth = -1f;
    private float lastHitDamage;
    private boolean eating;
    private int eatTicks;
    private boolean retreating;
    private int totemCooldown;
    private int pulledInvSlot = -1;
    private int pulledHotbarSlot = -1;

    // offhand totem keeper (runs while armed, engaged or not)
    static final int KEEPER_MAX_TICKS = 20;
    private final LeaseRequest keeperRequest;
    private ControlLease keeperLease;
    private int keeperTicks;
    private int keeperClock;
    private int totemKeepCooldown;
    private int totemRefills;
    private int jumpResets;
    private boolean jumpedThisCycle;
    private boolean wasAirborne;
    /** Hit-confirmed sprint reset and the post-crit landing strafe. */
    private final HitFollowUp followUp = new HitFollowUp();
    private int critWait;
    private int sprintResetTicks;
    private boolean sprintResetFirst;
    private boolean restoreWeaponNextTick;
    private float lastAimYaw = Float.NaN;
    private float lastAimPitch = Float.NaN;
    private boolean lastAimTracking;

    // proactive engagement
    public static final int SCAN_INTERVAL_TICKS = 5;
    static final int PAUSE_AFTER_USER_TICKS = 60;
    public static final int PAUSE_AFTER_CANCEL_TICKS = 200;
    static final int PAUSE_AFTER_LOST_SIGHT_TICKS = 40;
    private final TargetCandidates candidates = new TargetCandidates();
    private final TargetSelector.AllyTest allyTest;
    private int scanClock;
    private int proactivePause;
    // hand-over key (core): sampled each client tick by handOverTick
    private boolean handOverHeld;
    private boolean handOverEngaged;
    private int handOverScan;
    private int handOverPause;
    private boolean proactiveBlockedNoted;
    private int proactiveEngagements;

    // food objective
    private boolean foodNeed;
    private int foodRefreshScans;
    private boolean foodEngagement;
    private boolean collecting;
    private int collectTicks;
    private boolean sawDrop;
    private int foodKills;
    private int foodPickups;
    private final double[] dropOffset = new double[3];

    // diagnostics
    private int engagements;
    private int swings;
    private int ignoredAttributions;
    private String lastNote = "";

    // co-pilot (ControlMode.CO_PILOT): shared grant, short swing lease, no fight lease
    /** Module id of the toggle-sprint holder (travel.mobility.MoveToggles), read by id only. */
    static final String MOVE_TOGGLES_ID = "move_toggles";
    /** Ticks the swing lease is kept after a tap (pressed and released before it goes). */
    static final int SWING_LEASE_TICKS = 3;
    /** Ticks a toggle sprint seen before the grant is kept filled in for while the grant suspends it. */
    static final int SPRINT_FILL_TICKS = 40;
    private final CoPilotPlanner copilotPlan = new CoPilotPlanner();
    private final LeaseRequest swingRequest;
    /** The style of the running engagement: true = co-pilot. */
    private boolean copilot;
    private SharedGrant grant;
    private double lastSteerAdded;
    private int sprintFillTicks;
    private ControlLease swingLease;
    private int swingRelease;
    private int copilotSwings;
    private int copilotGrantTicks;

    public CombatAssistant(CombatWorld world, AllyRoster roster, IActionArbiter arbiter, IInputSmoother input,
                           IContainerClicker clicker, LongSupplier clock) {
        this.world = world;
        this.recall = new OpponentRecall(world);
        this.roster = roster;
        this.arbiter = arbiter;
        this.input = input;
        this.clicker = clicker;
        this.clock = clock;
        this.leaseRequest = new LeaseRequest(ID, ControlPriority.COMBAT,
                EnumSet.of(LEASE_CHANNELS[0], LEASE_CHANNELS[1], LEASE_CHANNELS[2], LEASE_CHANNELS[3]), 0, this);
        this.keeperRequest = new LeaseRequest(ID, ControlPriority.EMERGENCY, EnumSet.of(ControlChannel.HOTBAR),
                KEEPER_MAX_TICKS, this);
        this.swingRequest = new LeaseRequest(ID, ControlPriority.COMBAT, EnumSet.of(ControlChannel.WORLD_INTERACT), 0,
                this);
        this.allyTest = id -> roster.isNeverTarget(world.uuidOf(id));
    }

    // =================================================================================================================
    // Triggers (called from the client event bridge; client thread)
    // =================================================================================================================

    /** An entity popped a totem (entity event 35, via the target HUD's pop detection). Opens the pressure window. */
    public void onTotemPop(int entityId) {
        if (state == CombatState.ENGAGED && entityId == targetId) {
            lastOpponentPopAt = tacticClock;
            FightStage[] all = allStages;
            for (int i = 0; i < all.length; i++) {
                all[i].onTargetPop(tacticClock); // e.g. the combat potions' post-pop pressure rule
            }
        }
    }

    /** A thrown projectile spawns this close (blocks) to the target's eye when the owner is not synced. */
    static final double PROJECTILE_SPAWN_RADIUS = 2.5;
    /** Eye height of a standing player. */
    static final double PLAYER_EYE = 1.62;

    /**
     * A projectile joined the client level (entity join event, client thread): an ender pearl, arrow, trident or wind
     * charge ({@code HabitRecorder.PROJ_*}) with its owner's entity id (-1 when the client has none) and its spawn
     * position and velocity. While engaged with a player and "Opponent memory" on, the target's own projectiles (by
     * owner, else by a spawn at its eye) feed this fight's habit record. Allocation free.
     */
    public void onProjectileSpawn(int kind, int ownerId, double x, double y, double z, double vx, double vy,
                                  double vz) {
        if (state != CombatState.ENGAGED || !settings.opponentMemory || !targetIsPlayer || targetId < 0) {
            return;
        }
        CombatSnapshot s = snap;
        if (ownerId >= 0) {
            if (ownerId != targetId) {
                return;
            }
        } else {
            double dx = x - s.tx, dy = y - (s.ty + PLAYER_EYE), dz = z - s.tz;
            if (dx * dx + dy * dy + dz * dz > PROJECTILE_SPAWN_RADIUS * PROJECTILE_SPAWN_RADIUS) {
                return;
            }
        }
        habits.onProjectile(kind, vx, vy, vz, s.targetHealth, s.tx, s.tz, s.px, s.pz);
    }

    /**
     * A remembered pearl-escaper (C6) is at or near the health it usually pearls out at. Read every client tick by the
     * pearl countdown line; false unless engaged with "Opponent memory" on.
     */
    public boolean opponentPearlLikely() {
        return state == CombatState.ENGAGED && opponentPearlLikely;
    }

    /** This fight's habit record (diagnostics, tests). */
    public HabitRecorder habitRecord() {
        return habits;
    }

    /** The engaged target's remembered habits (diagnostics, tests). */
    public RecalledHabits recalledHabits() {
        return recalled;
    }

    /** Post-pop pressure: the opponent popped recently and health is above the critical threshold. */
    private boolean popPressureHolds(CombatSnapshot s) {
        return settings.popPressure && PotionPlanner.pressureHoldsRetreat(
                tacticClock - lastOpponentPopAt < settings.pressureTicks, s.effectiveHealth(), settings.pressureCritical);
    }

    /** The local player started a manual attack on an entity ({@code AttackEntityEvent}). Confirmed by the damage event. */
    public void onLocalAttack(int targetEntityId) {
        pendingAttackTarget = targetEntityId;
        pendingAttackMillis = clock.getAsLong();
    }

    /**
     * A damage event from the server ({@code ClientboundDamageEventPacket}).
     *
     * @param causeId  the responsible entity (shooter, attacker) or -1
     * @param directId the direct entity (projectile, attacker) or -1
     */
    public void onDamageEvent(int victimId, String damageTypeId, int causeId, int directId) {
        int local = world.localPlayerId();
        if (local < 0) {
            return;
        }
        DamageClassifier.Kind kind = DamageClassifier.kind(damageTypeId);
        if (victimId == local && state == CombatState.ENGAGED && holdsLease()
                && SustainExtras.shouldJumpReset(settings.jumpReset, world.extrasAllowedHere(), true,
                kind == DamageClassifier.Kind.ENTITY_ATTACK, snap.onGround, eating)
                && input.scheduleKeyTap(lease, SimKey.JUMP, 1, 1)) {
            jumpResets++; // ordinary jump tap under the MOVEMENT channel of the combat lease
        }
        if (victimId == local) {
            onLocalDamaged(local, kind, damageTypeId, causeId);
        } else if ((causeId == local || directId == local) && victimId == pendingAttackTarget
                && clock.getAsLong() - pendingAttackMillis <= ATTACK_CONFIRM_MS) {
            onOwnHitConfirmed(victimId);
        } else if (causeId != local) {
            onOtherDamaged(local, victimId, kind, damageTypeId, causeId);
        }
    }

    private void onLocalDamaged(int local, DamageClassifier.Kind kind, String typeId, int causeId) {
        if (kind == DamageClassifier.Kind.ENVIRONMENTAL) {
            return; // fall, fire, lava, drowning...: never a reason to engage
        }
        if (kind != DamageClassifier.Kind.ENTITY_ATTACK || causeId < 0 || causeId == local
                || !world.isLivingAlive(causeId)) {
            ignore("Ambiguous attribution for damage to you (" + typeId + ", cause " + causeId + "); staying dormant");
            return;
        }
        if (roster.isNeverTarget(world.uuidOf(causeId))) {
            ignore("Ally " + world.describe(causeId) + " hit you (" + typeId + "); never targeted, staying dormant");
            return;
        }
        rememberOpponent(causeId);
        lastAttacker = causeId; // read by stages: proof the entity is already hostile to the player
        lastAttackerAt = tacticClock;
        if (state == CombatState.ENGAGED && causeId == targetId) {
            feel.noteOpponentHit(tacticClock, lastHitDamage); // rank dial: its hit landed
            targetHitPending = TARGET_HIT_PENDING_TICKS; // the gear read takes the matching health drop
        }
        if (enabled && state != CombatState.ENGAGED) {
            engage(causeId, "defensive: " + typeId);
        } else if (handOverHeld && state != CombatState.ENGAGED) {
            // module off, hand-over key held: the one attacking you is the threat (reactive, allowed everywhere)
            engage(causeId, "hand-over key, defensive: " + typeId);
            handOverEngaged = true;
        }
    }

    private void onOwnHitConfirmed(int victimId) {
        pendingAttackTarget = -1;
        if (!world.isLivingAlive(victimId)) {
            return;
        }
        if (roster.isNeverTarget(world.uuidOf(victimId))) {
            ignore("You hit ally " + world.describe(victimId) + "; never targeted, staying dormant");
            return;
        }
        rememberOpponent(victimId);
        if (state == CombatState.ENGAGED && victimId == targetId) {
            feel.noteOwnHit(tacticClock); // rank dial: our hit landed
        }
        if (enabled) {
            engage(victimId, "offensive: confirmed hit");
        }
    }

    private void onOtherDamaged(int local, int victimId, DamageClassifier.Kind kind, String typeId, int causeId) {
        UUID victimUuid = world.uuidOf(victimId);
        if (victimUuid == null || !roster.isDefended(victimUuid) || !world.isPlayer(victimId)) {
            return;
        }
        if (kind == DamageClassifier.Kind.ENVIRONMENTAL) {
            return;
        }
        if (world.distanceSqToLocal(victimId) > settings.allyDefenseRange * settings.allyDefenseRange) {
            return;
        }
        if (kind != DamageClassifier.Kind.ENTITY_ATTACK || causeId < 0 || causeId == victimId
                || !world.isLivingAlive(causeId)) {
            ignore("Ambiguous attribution for damage to ally " + world.describe(victimId) + " (" + typeId
                    + "); not guessing an attacker");
            return;
        }
        if (roster.isNeverTarget(world.uuidOf(causeId))) {
            ignore("Ally " + world.describe(causeId) + " hit ally " + world.describe(victimId) + "; staying dormant");
            return;
        }
        if (enabled && state != CombatState.ENGAGED) {
            engage(causeId, "ally defense: " + world.describe(victimId));
        }
    }

    /** The retreat/cancel key. */
    public void onCancelKey() {
        if (state != CombatState.DORMANT) {
            disengageNow(DisengageReason.CANCEL_KEY);
        }
    }

    /**
     * Explicit "the user moved" signal. The assistant also detects movement presses itself from the arbiter's
     * MOVEMENT override state; this entry point exists for callers that see the press first.
     */
    public void onManualMovementKey() {
        if (state != CombatState.DORMANT) {
            disengageNow(DisengageReason.MANUAL_MOVEMENT);
        }
    }

    // =================================================================================================================
    // State machine
    // =================================================================================================================

    private void engage(int entityId, String why) {
        if (state == CombatState.ENGAGED && targetId == entityId) {
            return;
        }
        if (arbiter.isAutomationStopped()) {
            // Stop-All holds until Resume: a hit taken or landed meanwhile (defensive, offensive, ally defense,
            // hand-over) must not re-arm the fight behind the user's back (playtest 19-combatextra, 2026-11-25).
            return;
        }
        boolean fresh = state == CombatState.DORMANT;
        leaveRoute(); // a target switch ends any pursuit or flight from the old one
        stalemate.reset(null); // a new opponent: no stall yet, no escalation memory (the route was left above)
        routeRetry = 0;
        targetId = entityId;
        targetIsPlayer = world.isPlayer(entityId);
        if (trade != null) {
            trade.resetAll();
        }
        foodEngagement = false;
        collecting = false;
        losLostTicks = 0;
        shield.reset();
        tactics.reset();
        utility.reset();
        broadTactic = TacticUtility.Tactic.FIGHT;
        lastTactic = TacticsPlanner.Tactic.NONE;
        lastPositional = TacticUtility.Tactic.FIGHT;
        lastFeintNoted = FeintPlanner.Move.NONE;
        feel.engage(entityId, targetIsPlayer); // a target switch ends the combo plan, feints and any pearl run
        habits.reset();
        opponentPearlLikely = false;
        recalled.clear();
        if (settings.opponentMemory && targetIsPlayer) {
            double prior = recall.onEngage(entityId, true, recalled);
            if (!Double.isNaN(prior)) {
                feel.seedSkill(entityId, prior);
            }
        }
        stageTarget = -1;
        takenUnanswered = 0; // a new opponent owes us nothing yet
        for (FightStage st : allStages) {
            st.onEngage(stageCtx);
        }
        hits.reset(); // the first hit on a new target is an opener
        gear.reset();
        targetHitPending = 0;
        wind.reset();
        spacing.reset();
        copilotPlan.reset(); // a new opponent: no co-steer window, nudge or swing timing carried over
        state = CombatState.ENGAGED;
        if (fresh) {
            copilot = wantCoPilot();
            weaponSlot = -1;
            lastHealth = -1f;
            lastHitDamage = 0f;
            retreating = false;
            eating = false;
            waitingForResume = false;
            critWait = 0;
            jumpedThisCycle = false;
            lastMovePause = arbiter.isManualOverrideActive(ControlChannel.MOVEMENT);
            engagements++;
        }
        lastNote = "Engaged " + world.describe(entityId) + " (" + why + (copilot ? ", co-pilot" : "") + ")";
        LOG.info("Combat assistant: {}", lastNote);
        diagnostic(lastNote);
    }

    @Override
    public void onTick() {
        if (totemCooldown > 0) {
            totemCooldown--;
        }
        tickTotemKeeper(enabled, settings.keepTotem);
        if (state == CombatState.DORMANT) {
            if (enabled && settings.engageMode == TargetSelector.Mode.PROACTIVE) {
                tickProactive();
            }
            return;
        }
        tickFight();
    }

    /** One tick of a running engagement (ENGAGED or DISENGAGING). */
    private void tickFight() {
        if (state == CombatState.DISENGAGING) {
            if (!holdsLease() || clicker.pending() == 0 || ++disengageTicks > DISENGAGE_MAX_TICKS) {
                finishDisengage();
            }
            return;
        }
        tickEngaged();
    }

    private void tickEngaged() {
        boolean co = wantCoPilot();
        if (co != copilot) {
            switchStyle(co);
        }
        if (!copilot) {
            // A movement pause that starts while engaged (we were not holding the lease, e.g. after mouse look) is a
            // new manual movement press. A pause already running at engagement (the user was walking) is not.
            // In co-pilot the user's movement keys are the plan, never a reason to stop.
            boolean movePause = arbiter.isManualOverrideActive(ControlChannel.MOVEMENT);
            if (movePause && !lastMovePause) {
                lastMovePause = true;
                disengageNow(DisengageReason.MANUAL_MOVEMENT);
                return;
            }
            lastMovePause = movePause;
        }
        if (collecting) {
            tickCollect();
            return;
        }
        if (foodEngagement && ++scanClock >= SCAN_INTERVAL_TICKS) {
            scanClock = 0; // a hostile always comes before food
            int hostile = pickProactiveTarget();
            if (hostile >= 0) {
                proactiveEngagements++;
                engage(hostile, "proactive: hostile while hunting food");
            }
        }
        CombatSnapshot s = snap;
        s.wantTerrain = settings.holdGap || settings.edgeKnock;
        s.wantMovementModel = settings.exactWorld;
        syncExactWorld();
        if (!world.sample(targetId, s)) {
            if (foodEngagement) {
                startCollect();
                return;
            }
            if (retarget()) {
                return;
            }
            disengage(DisengageReason.TARGET_LOST);
            return;
        }
        if (roster.isNeverTarget(world.uuidOf(targetId))) {
            disengage(DisengageReason.TARGET_IS_ALLY);
            return;
        }
        // pearl intercept first: a target that teleports to its pearl must not trip the disengage radius
        feel.pearlTick(settings.pearlIntercept && settings.routePursuit && Services.routeRunnerOrNull() != null,
                tacticClock, s, settings.spacingMax, settings.disengageRadius);
        double r = Math.max(settings.disengageRadius, feel.pearl().radiusNeeded(s.px, s.py, s.pz));
        // duel mode: the target is alive and loaded (the sample succeeded), so range and sight never end the fight
        boolean duel = settings.duelMode;
        if (!duel && s.distanceSq() > r * r) {
            if (retarget()) {
                return;
            }
            disengage(DisengageReason.OUT_OF_RANGE);
            return;
        }
        if (s.lineOfSight) {
            losLostTicks = 0;
        } else if (++losLostTicks > settings.losTimeoutTicks && !duel) {
            if (retarget()) {
                return;
            }
            disengage(DisengageReason.LINE_OF_SIGHT_LOST);
            return;
        }
        float eff = s.effectiveHealth();
        tacticClock++;
        if (lastHealth >= 0f && eff < lastHealth - 0.01f) {
            lastHitDamage = lastHealth - eff;
            opponents.recordHit(targetId, tacticClock, lastHitDamage);
            if (targetHitPending > 0) {
                gear.noteTaken(lastHitDamage);
                if (prevOppReachId == targetId) {
                    // its eye to our box on the tick before the hit showed, before the knockback moved us
                    opponents.noteHitDistance(targetId, tacticClock, prevOppReach);
                }
                targetHitPending = 0;
                hitTakenAt = tacticClock; // the target's hit (stages: combo escape)
                takenUnanswered++;
            }
        }
        if (targetHitPending > 0) {
            targetHitPending--;
        }
        lastHealth = eff;
        prevOppReach = Math.sqrt(HitTradeSolver.reachSq(s.tx, s.ty, s.tz, s.px, s.py, s.pz));
        prevOppReachId = targetId;
        observeOpponent(s);
        // stalemate bookkeeping (cheap): eating, backing off or a pearl run are deliberate, never a stall
        boolean busy = eating || retreating || broadTactic == TacticUtility.Tactic.FLEE
                || broadTactic == TacticUtility.Tactic.RETREAT_HEAL || feel.pearl().active();
        stalemate.observe(s, busy);
        if (busy && stalemate.active()) {
            stalemate.abort(stalemateRunner);
        }
        gear.update(eff, s.targetHealth, GearRead.afterArmor(s.ownAttackDamage, s.targetArmor, s.targetToughness),
                GearRead.afterArmor(s.targetAttackDamage, s.ownArmor, s.ownToughness));
        feel.observe(s, settings, opponents, tacticClock,
                keyDown[SimKey.LEFT.ordinal()] ? 1 : (keyDown[SimKey.RIGHT.ordinal()] ? -1 : 0), tradeAllowJump(s));
        if (copilot) {
            coPilotDrive(s);
            return;
        }
        if (!holdsLease() && !tryAcquire()) {
            return;
        }
        if (weaponSlot < 0) {
            // a pro draws their best weapon when the fight starts (once per engagement, not per tick)
            weaponSlot = settings.autoWeapon ? world.bestMeleeSlot(s.selectedSlot) : s.selectedSlot;
            if (weaponSlot != s.selectedSlot) {
                selectWeapon();
            }
        }
        drive(s);
        if (stageTarget >= 0) {
            int next = stageTarget;
            stageTarget = -1;
            // seam fix: a requested target is never a roster ally (the next tick would end the whole fight on it)
            if (next != targetId && state == CombatState.ENGAGED && world.isLivingAlive(next)
                    && !roster.isNeverTarget(world.uuidOf(next))) {
                engage(next, "threat");
            }
        }
    }

    private boolean holdsLease() {
        return lease != null && lease.isActive();
    }

    private boolean tryAcquire() {
        if (waitingForResume || arbiter.isAutomationStopped()) {
            return false;
        }
        for (ControlChannel c : LEASE_CHANNELS) {
            if (arbiter.isManualOverrideActive(c)) {
                return false; // the user is acting; take over once the override pause ends
            }
        }
        Optional<ControlLease> granted = arbiter.request(leaseRequest);
        if (granted.isEmpty()) {
            return false;
        }
        lease = granted.get();
        clearKeyCache();
        return true;
    }

    private void drive(CombatSnapshot s) {
        hitRelease = false; // the hit planner's key overrides are set again by step 5 when it runs this tick
        hitDropSprint = false;
        hitHoldSprint = false;
        stageVetoSwing = false;
        stageVetoJump = false;
        stagePolicy = null;
        stageSideForced = false;
        secondValid = false;
        stageSpacing = 0.0;
        stagePress = false;
        if (settings.opponentMemory && targetIsPlayer) {
            // C6: a remembered mace diver airborne above us with the mace: keep extra distance so the dive lands short
            stageSpacing = recalled.diveSpacing(s.targetHeld, s.targetOnGround, s.ty - s.py);
        }
        if (routeRetry > 0) {
            routeRetry--;
        }
        if (restoreWeaponNextTick) {
            restoreWeaponNextTick = false;
            selectWeapon();
        }

        // ---- 0. stages ahead of the emergency sustain (healing windows) -----------------------------------------
        if (!eating && runStages(FightStage.Slot.SUSTAIN, s)) {
            return;
        }

        // ---- 1. emergency sustain ------------------------------------------------------------------------------
        if (settings.sustain) {
            maybePullConsumable(s);
            SustainPlanner.Action a = SustainPlanner.decide(s, settings.lowHealth, lastHitDamage, settings.totems,
                    settings.gapples, popPressureHolds(s));
            if (a == SustainPlanner.Action.EQUIP_TOTEM && totemCooldown == 0 && !s.screenOpen
                    && clicker.queueClick(lease, SustainPlanner.menuSlotOf(s.totemInvSlot), OFFHAND_BUTTON,
                    IContainerClicker.Click.SWAP)) {
                totemCooldown = TOTEM_RETRY_TICKS;
            }
            if (eating) {
                if (continueEating(s)) {
                    aimAtTarget(s);
                    retreatMove(s);
                    return;
                }
            } else if (a == SustainPlanner.Action.EAT && !s.screenOpen) {
                leaveRoute(); // eat while backing off, facing the opponent
                startEating(s);
                aimAtTarget(s);
                retreatMove(s);
                return;
            }
            float eff = s.effectiveHealth();
            if (retreating && eff > settings.lowHealth + SustainPlanner.RECOVER_MARGIN) {
                retreating = false;
            }
            if (a == SustainPlanner.Action.RETREAT
                    || (a == SustainPlanner.Action.EQUIP_TOTEM && eff <= settings.lowHealth)) {
                retreating = true;
            }
            // duel mode: the opponent never lets go, so backing off is only worth it while it heals us more than
            // it heals them; otherwise low health changes nothing and the fight goes on (arena finding 2026-11-22:
            // at 8 HP with no food the client held S in a corner, full charge, without a swing, until the round ended)
            boolean duel = settings.duelMode;
            if (retreating && duel && !duelResetPays(s)) {
                retreating = false;
            }
            // a retreating opponent is no threat to back away from (eating above still runs)
            if (retreating && !oppRetreating()) {
                if (retreatRun(s, duel)) {
                    return; // the opponent keeps closing (duel mode: always): turn and run along a route
                }
                if (!duel) {
                    aimAtTarget(s);
                    retreatMove(s);
                    return;
                }
                // duel mode without a route to run: back-pedalling from a sprinting opponent only takes hits
            }
        } else if (eating) {
            stopEating();
        }

        // ---- 1b. broad tactic by utility (FLEE backs off; KITE widens the spacing band) ------------------------
        broadTactic = settings.tacticUtility ? chooseTactic(s) : TacticUtility.Tactic.FIGHT;
        if (broadTactic == TacticUtility.Tactic.FLEE) {
            if (retreatRun(s, true)) {
                return; // a person turns and runs
            }
            aimAtTarget(s);
            retreatMove(s);
            return;
        }
        if (settings.duelMode && broadTactic == TacticUtility.Tactic.RETREAT_HEAL) {
            // duel mode never flees: reset instead (make distance, heal, come back once FIGHT wins again)
            if (settings.sustain && !eating && s.gappleSlot >= 0 && !s.gappleOnCooldown && !s.screenOpen
                    && s.horizontalDistance() >= RESET_EAT_DISTANCE && s.effectiveHealth() < s.maxHealth) {
                leaveRoute();
                startEating(s);
                aimAtTarget(s);
                retreatMove(s);
                return;
            }
            if (retreatRun(s, true)) {
                return;
            }
            aimAtTarget(s);
            retreatMove(s);
            return;
        }

        // ---- 1c. pearl intercept: run to where the target's pearl lands (a third route mode) ---------------------
        if (feel.pearl().active() && runRoute(s, ROUTE_INTERCEPT)) {
            return;
        }

        // ---- 1c'. stalemate breaker: nobody can or will close in (a ravine, a ledge, water between) ---------------
        int act = stalemate.drive(s, arenaVoxels, stalemateRunner, settings.breakStalemates,
                (int) Math.round(settings.stallSeconds * 20.0));
        noteStalemate();
        if (act == StalemateBreaker.ACT_ROUTE && routeMode == ROUTE_STALEMATE) {
            return; // the runner walks the breaker's route (look where you move)
        }
        if (act == StalemateBreaker.ACT_HOLD) {
            leaveRoute();
            aimAtTarget(s); // waiting where it can reach us, or at the new angle: face it, stand still
            move(SpacingController.HOLD, StrafePlanner.NONE, false);
            return;
        }
        if (routeMode == ROUTE_STALEMATE) {
            leaveRoute();
        }

        // ---- 1d. pursuit along a route (far away or out of sight), melee code inside the range -------------------
        double dist = s.horizontalDistance();
        boolean chase = (losLostTicks > LOS_PURSUIT_TICKS && dist > settings.spacingMax + LOS_PURSUIT_MIN_EXTRA)
                || dist > settings.spacingMax
                + (routeMode == ROUTE_PURSUE ? PURSUIT_EXIT_EXTRA : PURSUIT_ENTER_EXTRA);
        if (chase && runRoute(s, ROUTE_PURSUE)) {
            return;
        }
        leaveRoute();

        // ---- 1e. stages ahead of the aim (threat answers, combo escape) ------------------------------------------
        if (runStages(FightStage.Slot.PRE_AIM, s)) {
            return;
        }

        // ---- 2. wind-charge launch (owns camera + hotbar while active) ------------------------------------------
        if (settings.windCharge || wind.active()) {
            AerialPlanner.Action w = wind.step(s, settings.windCharge && settings.maceSwap);
            if (w == AerialPlanner.Action.AIM_DOWN) {
                aimAt(s.yaw, 90f, 1.5f);
                move(SpacingController.HOLD, StrafePlanner.NONE, false);
                return;
            } else if (w == AerialPlanner.Action.THROW) {
                input.selectHotbarSlot(lease, s.windChargeSlot);
                input.scheduleKeyTap(lease, SimKey.USE, 1, 1);
                input.scheduleKeyTap(lease, SimKey.JUMP, 1, 2);
                jumpedThisCycle = true;
                return;
            } else if (w == AerialPlanner.Action.RESTORE) {
                selectWeapon();
            }
        }

        // ---- 3. aim --------------------------------------------------------------------------------------------
        aimAtTarget(s);

        // ---- 4. shield break -----------------------------------------------------------------------------------
        if (settings.shieldBreak || shield.active()) {
            ShieldBreakSequencer.Action a = shield.step(s, settings.shieldBreak);
            if (a == ShieldBreakSequencer.Action.SELECT_AXE) {
                input.selectHotbarSlot(lease, s.axeSlot);
            } else if (a == ShieldBreakSequencer.Action.SWING) {
                tapAttack();
            } else if (a == ShieldBreakSequencer.Action.SELECT_WEAPON) {
                selectWeapon();
            }
            if (shield.active() || a != ShieldBreakSequencer.Action.NONE) {
                movement(s, false); // no feints during a shield break
                return;
            }
        }

        // ---- 4b. stages ahead of the swing (shield duel, hit vetoes) ---------------------------------------------
        if (runStages(FightStage.Slot.PRE_SWING, s)) {
            return;
        }

        // ---- 5. swing (cooldown + crit sync, optional mace) ----------------------------------------------------
        if (s.onGround && wasAirborne) {
            jumpedThisCycle = false;
        }
        followUp.onGround(wasAirborne, s.onGround, s.onGround && wasAirborne && followUp.critPending()
                ? input.nextGaussianTicks(3.5, 0.5, HitFollowUp.LANDING_TICKS_MIN, HitFollowUp.LANDING_TICKS_MAX) : 0);
        wasAirborne = !s.onGround;
        TacticsPlanner.Tactic tac = s.wantTerrain ? tactics.plan(s, settings.holdGap, settings.edgeKnock)
                : TacticsPlanner.Tactic.NONE;
        noteTactic(tac);
        if (tactics.swingNow() && s.wantTerrain) {
            tacticSwings++;
            tapAttack(); // target mid-jump over a drop: hit now, the knockback drops it
            move(tac == TacticsPlanner.Tactic.NONE ? SpacingController.HOLD : tactics.forward(), StrafePlanner.NONE,
                    false);
            return;
        }
        // the hit planner picks the kind of the next hit (sprint knockback, crit, sweep, plain); the swing, the crit
        // hop and the sprint/forward keys all follow that one decision. No hop at the lip of a gap, under a low roof,
        // with a drop behind, or while the opponent's charged hit is in reach.
        HitPlanner hp = hits;
        hp.policy = stagePolicy != null ? stagePolicy : settings.hitChoice;
        hp.critTiming = settings.critSync;
        hp.approachCrit = settings.approachCrit;
        hp.targetIsPlayer = targetIsPlayer;
        hp.comboLive = feel.comboDrives(settings);
        hp.holdGap = tac == TacticsPlanner.Tactic.HOLD_GAP;
        hp.oppCharged = settings.cooldownRead
                && ProFeelLayer.oppReadyIn(settings, opponents, targetId, tacticClock) == 0
                && !oppRetreating();
        hp.gearRatio = gear.ratio();
        hp.healGate = settings.critNeedsHealer;
        hp.oppOutHeals = HitPlanner.outHeals(opponents.healRate(targetId, tacticClock),
                GearRead.afterArmor(s.ownAttackDamage, s.targetArmor, s.targetToughness), trade != null
                        ? trade.ownCooldown() : 20.0 / Math.max(0.1, s.ownAttackSpeed));
        hp.sprintPending = keyDown[SimKey.FORWARD.ordinal()] && keyDown[SimKey.SPRINT.ordinal()] && !s.sprinting;
        hp.decide(s);
        if (hp.jump() && !stageVetoJump) {
            input.scheduleKeyTap(lease, SimKey.JUMP, 1, 2);
            jumpedThisCycle = true;
        }
        hitRelease = hp.releaseForward();
        hitDropSprint = hp.dropSprint();
        hitHoldSprint = hp.holdSprint();
        if (hp.swing() && !stageVetoSwing) {
            if (AerialPlanner.shouldSwapToMace(s, settings.maceSwap)) {
                input.selectHotbarSlot(lease, s.maceSlot);
                restoreWeaponNextTick = true;
            }
            tapAttack();
        } else if (SwingGate.ready(s.attackStrength)) {
            critWait++;
        }

        // ---- 6. spacing, strafe, sprint reset -------------------------------------------------------------------
        if (runStages(FightStage.Slot.MOVEMENT, s)) {
            return;
        }
        if (tac != TacticsPlanner.Tactic.NONE) {
            move(tactics.forward(), tactics.side(), tactics.sprint());
        } else if (!positionalMove(s)) {
            movement(s, true);
        }
    }

    /**
     * HIGH_GROUND / CEILING_DENY from the arena read: walk to (or hold) the spot while aim and swings carry on.
     * Hazards veto; a step-up jump is a 1-tick tap through the smoother. Notes only on a change of tactic.
     *
     * @return false when no positional tactic drives this tick (the caller runs the normal movement)
     */
    private boolean positionalMove(CombatSnapshot s) {
        TacticUtility.Tactic bt = broadTactic;
        // a stage widening the band (a fuse back-off) outranks the high ground: the spot is no use inside a blast
        boolean positional = stageSpacing <= 0.0 && settings.arenaRead
                && (bt == TacticUtility.Tactic.HIGH_GROUND || bt == TacticUtility.Tactic.CEILING_DENY)
                && feel.planPositional(s, bt);
        if (!positional) {
            if (lastPositional != TacticUtility.Tactic.FIGHT) {
                lastPositional = TacticUtility.Tactic.FIGHT;
                feel.resetPositional();
            }
            return false;
        }
        if (bt != lastPositional) {
            lastPositional = bt;
            lastNote = (bt == TacticUtility.Tactic.HIGH_GROUND ? "Taking the high ground over "
                    : "Backing under a low ceiling against ") + world.describe(targetId);
            diagnostic(lastNote);
        }
        int fwd = feel.positionalForward();
        int side = feel.positionalSide();
        if ((fwd > 0 && s.hazardFront) || (fwd < 0 && s.hazardBack)) {
            fwd = SpacingController.HOLD;
        }
        if ((side == StrafePlanner.LEFT && s.hazardLeft) || (side == StrafePlanner.RIGHT && s.hazardRight)) {
            side = StrafePlanner.NONE;
        }
        if (feel.positionalJump() && s.onGround) {
            input.scheduleKeyTap(lease, SimKey.JUMP, 1, 1);
        }
        move(fwd, side, fwd > 0 && feel.positionalSprint());
        return true;
    }

    private void tapAttack() {
        input.scheduleKeyTap(lease, SimKey.ATTACK, 1, 1);
        swings++;
        critWait = 0;
        jumpedThisCycle = false;
        hits.onSwing(); // every swing (planned, shield break, gap ambush) starts the hit cycle over
        // the combo keeper plans the follow-up of a hit sent in reach (its reset replaces the fixed one)
        followUp.noteSwing(snap.onGround);
        boolean planned = feel.onSwingSent(snap, settings, tacticClock);
        if (settings.sprintReset != CombatSettings.SprintReset.OFF) {
            if (planned) {
                int kind = feel.comboResetKind();
                resetKind = kind == ComboKeeper.RESET_S_TAP ? CombatSettings.SprintReset.S_TAP
                        : CombatSettings.SprintReset.W_RELEASE;
                sprintResetTicks = kind == ComboKeeper.RESET_NONE ? 0 : Math.max(1, feel.comboResetTicks());
                sprintResetFirst = true;
            } else if (snap.inReach()) {
                // the fixed reset waits for the hit to register (see observeOpponent): a miss keeps the pressure
                followUp.armReset(tacticClock);
            }
        }
    }

    /** allowJump of the trade solver and the combo keeper this tick (crit sync owns hops; none at a gap or low roof). */
    private boolean tradeAllowJump(CombatSnapshot s) {
        return lastTactic != TacticsPlanner.Tactic.HOLD_GAP
                && !(settings.critSync && (critWait > 0 || jumpedThisCycle || !s.onGround || hits.ownsHop()))
                && s.headroom > ProFeelLayer.LOW_HEADROOM;
    }

    /** Feeds the opponent model and the damage-dealt model from this tick's snapshot. Allocation free. */
    private void observeOpponent(CombatSnapshot s) {
        boolean landed = targetId == lastTargetHealthId && s.targetHealth < lastTargetHealth - 0.01f;
        boolean hurtRose = followUp.hurtRose(targetId, s.targetHurtTime);
        followUp.expire(tacticClock);
        if ((landed || hurtRose) && settings.sprintReset != CombatSettings.SprintReset.OFF
                && followUp.confirmed(tacticClock) && !feel.comboDrives(settings)) {
            resetKind = settings.sprintReset;
            sprintResetTicks = input.nextGaussianTicks(1.5, 0.5, 1, 2);
            sprintResetFirst = true;
        }
        if (landed) {
            // before the observation: the knockback's lift on this tick is a reaction, not a hop of its own
            opponents.noteOwnHit(targetId, tacticClock);
        }
        opponents.observe(targetId, tacticClock, s.tx, s.tz, s.tvx, s.tvz, s.px, s.pz, s.targetOnGround,
                s.targetBlocking);
        if (settings.opponentMemory && targetIsPlayer) {
            habits.tick(tacticClock, s.targetOnGround, s.ty, s.targetHeld, s.targetUsingItem, s.targetSwingStarted);
            opponentPearlLikely = recalled.pearlLikely(s.targetHealth);
        } else {
            opponentPearlLikely = false;
        }
        opponents.noteHealth(targetId, tacticClock, s.targetHealth);
        if (s.targetSwingStarted) {
            opponents.noteSwing(targetId, tacticClock, s.targetAttackSpeed); // cooldown reading
        }
        opponents.observeRetreat(targetId, tacticClock, s.targetYaw, s.tx, s.tz, s.tvx, s.tvz, s.px, s.pz);
        if (landed) {
            dealt.recordHit(targetId, tacticClock, lastTargetHealth - s.targetHealth);
            gear.noteDealt(lastTargetHealth - s.targetHealth);
            feel.noteOwnHit(tacticClock); // rank dial: a swing in reach landed
            hitLandedAt = tacticClock;
            takenUnanswered = 0;
        }
        lastTargetHealthId = targetId;
        lastTargetHealth = s.targetHealth;
    }

    /**
     * Food level of vanilla's fast natural regeneration (1 HP per half second while saturation lasts). The slow one
     * (food 18+, 1 HP per 4 seconds) is no reason for a duel reset: the opponent closes the distance sooner.
     */
    private static final int FAST_REGEN_FOOD = 20;

    private static boolean fastRegen(CombatSnapshot s) {
        return s.foodLevel >= FAST_REGEN_FOOD && s.saturation > 0f;
    }

    private TacticUtility.Tactic chooseTactic(CombatSnapshot s) {
        TacticUtility u = utility;
        u.ownHealth = s.effectiveHealth();
        u.ownMaxHealth = s.maxHealth > 0f ? s.maxHealth : 20f;
        u.oppHealth = s.targetHealth;
        u.ownDps = Math.max(MIN_OWN_DPS, dealt.dps(targetId, tacticClock));
        u.oppDps = Math.max(MIN_OPP_DPS, opponents.dps(targetId, tacticClock));
        u.healingItems = s.gappleSlot >= 0 || s.sustainInvSlot >= 0 ? 1 : 0;
        u.distance = s.horizontalDistance();
        boolean tempo = settings.keepTempo;
        // keep the tempo: hops that answer our hits (knockback lift, combo-break hops) are no crit pressure
        u.oppHopRate = tempo ? opponents.pressureHopRate(targetId) : opponents.hopRate(targetId);
        u.outRanged = tempo && settings.learnThreat && targetIsPlayer && opponents.reachLearned(targetId)
                && opponents.learnedReach(targetId, TradeDriver.DEFAULT_REACH) < s.reach - 0.05;
        u.oppRetreating = oppRetreating();
        u.highGround = feel.highGround(); // 0 while the arena read is off or idle
        u.ceilingDeny = feel.ceilingDeny();
        u.gearRatio = gear.ratio(); // the gear read leads the observed DPS, which lags by seconds
        u.duelMode = settings.duelMode;
        // the opponent regenerates too: a reset on natural regeneration counts only while it gives us more back
        u.canRegenerate = regenResetPays(s);
        return u.choose();
    }

    /** Our sprint speed on flat ground (blocks per tick) at the base movement speed 0.1. */
    static final double OWN_SPRINT_SPEED = 0.28;

    /**
     * A reset on natural regeneration gains health: fast regeneration, we miss clearly more than the opponent, and
     * (setting "Keep the tempo") the opponent cannot keep pace with our run.
     */
    private boolean regenResetPays(CombatSnapshot s) {
        if (!fastRegen(s) || !TacticUtility.regenResetPays(missingHealth(s), targetMissingHealth(s))) {
            return false;
        }
        double ownSprint = OWN_SPRINT_SPEED * (s.selfSpeed > 0.0 ? s.selfSpeed / 0.1 : 1.0);
        return !settings.keepTempo || !TacticUtility.oppKeepsPace(opponents.chaseSpeed(targetId), ownSprint);
    }

    /** The target is read as retreating and the "Chase a retreating opponent" setting is on. */
    private boolean oppRetreating() {
        // players only: a mob wandering off is not a fleeing duellist, and must not switch off the low-health back-off
        return settings.chaseRetreat && targetIsPlayer && opponents.retreating(targetId);
    }

    private static final double PLAYER_MAX_HEALTH = 20.0;

    private static double missingHealth(CombatSnapshot s) {
        return Math.max(0.0, (s.maxHealth > 0f ? s.maxHealth : 20f) - s.effectiveHealth());
    }

    /** The target's missing health; its maximum is taken as a player's 20 (duel mode is about players). */
    private static double targetMissingHealth(CombatSnapshot s) {
        return Math.max(0.0, PLAYER_MAX_HEALTH - s.targetHealth);
    }

    /**
     * Duel mode: stepping out of the fight heals us more than it heals the opponent. True with a healing item to
     * use, or with hunger high enough to regenerate while we miss clearly more health than they do.
     */
    private boolean duelResetPays(CombatSnapshot s) {
        if ((settings.gapples && s.gappleSlot >= 0) || s.sustainInvSlot >= 0) {
            return true;
        }
        return regenResetPays(s);
    }

    /** Broad tactic of the last engaged tick (FIGHT while the setting is off). */
    public TacticUtility.Tactic broadTactic() {
        return broadTactic;
    }

    /** Opponent habits (strafe bias, crit-hop cadence, shield use, damage dealt) of recent targets. */
    public OpponentModel opponents() {
        return opponents;
    }

    /**
     * Spacing, strafe and sprint reset. Precedence: combo keeper, then the trade solver (inside spacing max + 2.5),
     * then the band; feints (players only) on top of the solver or band; the hazard veto over all of them; the
     * sprint-reset taps last.
     *
     * @param allowFeint false while a shield break runs
     */
    private void movement(CombatSnapshot s, boolean allowFeint) {
        double d = s.horizontalDistance();
        boolean kiting = broadTactic == TacticUtility.Tactic.KITE;
        int fwd;
        int side = StrafePlanner.NONE;
        boolean sprint;
        boolean planned = false; // the solver or the keeper chose the step (the read's "stepping in")
        // a stage that widens the band (a blast, a reach to stay out of) takes the step from the solver and the keeper
        boolean widened = stageSpacing > 0.0;
        TradeDriver t = !widened && settings.hitTrade && d <= settings.spacingMax + TRADE_RANGE_EXTRA
                ? tradeDriver() : null;
        if (t != null) {
            t.useReads(settings.learnThreat, settings.chaseRetreat);
        }
        if (!widened && !eating && oppRetreating() && !s.inReach() && chase.plan(s)) {
            // ---- retreat chase: it faces away and runs; close the gap from the moment it leaves reach ----
            if (t != null) {
                t.reset();
            }
            spacing.reset();
            feel.endFeint();
            chaseTicks++;
            int cf = chase.forward();
            int cs = chase.side();
            if ((cs == StrafePlanner.LEFT && s.hazardLeft) || (cs == StrafePlanner.RIGHT && s.hazardRight)) {
                cs = StrafePlanner.NONE;
            }
            if (cf > 0 && s.hazardFront) {
                cf = SpacingController.HOLD;
            }
            if (chase.jump() && cf > 0 && s.onGround && tradeAllowJump(s)) {
                input.scheduleKeyTap(lease, SimKey.JUMP, 1, 1); // sprint-jump: faster than its run
            }
            sprintResetTicks = 0; // no w-tap while chasing: the run is worth more than the next hit's knockback
            move(cf, cs, cf > 0);
            return;
        }
        // crit sync owns the hops while it waits for or rides one; no solver hops at the lip of a gap or a low roof
        boolean allowJump = tradeAllowJump(s);
        int withDir = feel.withDir();
        double withStr = feel.withStrength();
        if (secondValid && !eating && flank.plan(s.px, s.pz, s.tx, s.tz, secondX, secondZ)
                && flank.strength() > StrafePlanner.WITH_THRESHOLD) {
            // a second attacker in reach: circle so the target stays between us and it (soft, over the move-with read)
            withDir = flank.side();
            withStr = flank.strength();
        }
        if (!widened && feel.comboDrives(settings)) {
            // ---- combo keeper: steer for the next hit on the predicted knockback arc ----
            if (t != null) {
                t.reset();
            }
            spacing.reset();
            planned = true;
            fwd = feel.comboForward();
            side = feel.comboSide();
            sprint = feel.comboSprint();
        } else if (t != null && t.decide(s, opponents, targetId, tacticClock, targetIsPlayer, settings.cooldownRead,
                settings.hitTradeHorizon, gear.takenWeight(kiting, settings.tradeCaution), allowJump)) {
            tradeTicks++;
            planned = true;
            spacing.reset(); // the band restarts from HOLD when the solver hands back
            fwd = t.forward();
            side = ProFeelLayer.sideOf(t.strafe());
            if (withDir != StrafePlanner.NONE && side != withDir) {
                // strafe with the opponent only on a tie: never over an answer that scores better
                int with = ProFeelLayer.solverStrafe(withDir);
                if (t.scoreOf(HitTradeSolver.combo(fwd, with)) >= t.scoreOf(HitTradeSolver.combo(fwd, t.strafe()))) {
                    side = withDir;
                }
            }
            sprint = t.sprint();
            if (t.jump() && allowJump && s.onGround && sprintResetTicks == 0) {
                input.scheduleKeyTap(lease, SimKey.JUMP, 1, 1);
            }
        } else {
            if (t != null) {
                t.reset();
            }
            double kite = (kiting ? KITE_EXTRA_SPACING : 0.0) + stageSpacing;
            fwd = spacing.update(d, settings.spacingMin + kite, settings.spacingMax + kite, s.hazardFront,
                    s.hazardBack);
            if (settings.strafe && d <= settings.spacingMax + 1.0) {
                side = strafe.update(s.hazardLeft, s.hazardRight, input.nextGaussianTicks(30, 8, 15, 50),
                        settings.tacticUtility ? opponents.strafeBias(targetId) : 0.0, withDir, withStr);
            }
            sprint = fwd == SpacingController.FORWARD && d > settings.spacingMax + 0.5;
        }
        if (stageSideForced) {
            side = stageSide;
        }
        if (!feel.comboDrives(settings) && allowFeint && !stagePress && !stageSideForced && !widened && !eating
                && routeMode == ROUTE_NONE) {
            // ---- feints (players only, never mobs) on top of the solver or band ----
            FeintPlanner.Move m = feel.planFeint(s, settings, opponents, tacticClock, side, d);
            if (m != FeintPlanner.Move.NONE) {
                fwd = feel.feintForward(fwd);
                side = feel.feintSide(side);
                sprint = feel.feintSprint(sprint);
            }
            if (m != lastFeintNoted) {
                lastFeintNoted = m;
                if (m != FeintPlanner.Move.NONE) {
                    lastNote = switch (m) {
                        case BREAK_PATTERN -> "Feint: breaking the strafe pattern";
                        case BAIT_RETREAT -> "Feint: backing off to draw a sprint-in";
                        default -> "Feint: stepping in on the bait";
                    }; // constants: no allocation, only on a change
                }
            }
        } else {
            feel.endFeint();
        }
        // never crowd: inside 1.6 blocks the bearing swings faster than the camera may turn (the retreat chase above
        // returns before this; it never gets that close to a runner anyway)
        fwd = SpacingController.uncrowd(fwd, d, s.hazardBack);
        // the hazard flags veto a step whatever the keeper, the solver or a feint says
        if ((fwd > 0 && s.hazardFront) || (fwd < 0 && s.hazardBack)) {
            fwd = SpacingController.HOLD;
        }
        if ((side == StrafePlanner.LEFT && s.hazardLeft) || (side == StrafePlanner.RIGHT && s.hazardRight)) {
            side = StrafePlanner.NONE;
        }
        if (followUp.landingActive()) {
            // soft bias for the first ground ticks after a crit hop: lean to the alternating side, only where no
            // strafe was chosen, nothing forces a side and the side is safe
            int ls = followUp.landingSide();
            if (side == StrafePlanner.NONE && !stageSideForced && !widened && !feel.comboDrives(settings)
                    && !((ls == StrafePlanner.LEFT && s.hazardLeft) || (ls == StrafePlanner.RIGHT && s.hazardRight))) {
                side = ls;
            }
            followUp.spendLanding();
        }
        sprint = sprint && fwd > 0;
        if (sprintResetTicks > 0) {
            sprintResetTicks--;
            if (resetKind == CombatSettings.SprintReset.S_TAP && sprintResetFirst) {
                fwd = s.hazardBack ? SpacingController.HOLD : SpacingController.BACK;
            } else if (fwd == SpacingController.FORWARD) {
                fwd = SpacingController.HOLD;
            }
            sprintResetFirst = false;
            sprint = false;
        }
        move(fwd, side, sprint);
        feel.afterMovement(fwd, planned, ProFeelLayer.oppReadyIn(settings, opponents, targetId, tacticClock),
                tacticClock);
    }

    /**
     * The trade driver, built once a body-sim provider is registered (the solver's scratch bodies allocate, so only
     * then and only once). Null while no provider exists: the band runs.
     */
    private TradeDriver tradeDriver() {
        TradeDriver t = trade;
        if (t == null && !tradeFixed) {
            IBodySimProvider sims = Services.bodySimsOrNull();
            if (sims != null) {
                t = TradeDriver.of(new HitTradeSolver(sims));
                trade = t;
            }
        }
        return t;
    }

    /**
     * Replaces the solve step (tests script the solver answers; HitTradeSolver is final). Null goes back to the
     * production path. Client thread only.
     */
    public void useTradeDriver(TradeDriver driver) {
        trade = driver;
        tradeFixed = driver != null;
    }

    /** Blocks for the arena read and the pearl landing (the bootstrap passes the live level); null = both idle. */
    public void useArenaVoxels(CombatVoxels voxels) {
        feel.useVoxels(voxels);
        arenaVoxels = voxels; // the stalemate breaker's walking search reads the same blocks
    }

    /** Ender pearls in flight by thrower (the bootstrap passes the entity-event watch). */
    public void usePearlSource(PearlSource source) {
        feel.usePearlSource(source);
    }

    /** Where the read of the target is published each engaged tick (for "The Read" overlay); null = not published. */
    public void useReadState(ReadState read) {
        feel.useReadState(read);
    }

    /** Attaches the opponent memory file and where its one-line hints go (the coach / notices). */
    public void useOpponentMemory(com.utilitysuite.combat.tactics.memory.OpponentMemoryStore store,
                                  java.util.function.Consumer<String> hintSink) {
        recall.useStore(store, hintSink);
    }

    /** The pro-feel units (tests script them through its seams). */
    public ProFeelLayer proFeel() {
        return feel;
    }

    /** Ticks the trade solver drove the movement keys (diagnostics, tests). */
    public int tradeTicks() {
        return tradeTicks;
    }

    /** Ticks the retreat chase drove the keys (diagnostics, tests). */
    public int chaseTicks() {
        return chaseTicks;
    }

    /** Route runs started for pursuit or flight (diagnostics, tests). */
    public int routeRuns() {
        return routeRuns;
    }

    /** True while the route runner drives the keys for this engagement. */
    public boolean routeActive() {
        return routeMode != ROUTE_NONE;
    }

    /**
     * Retreat by running away along a route (turn and run, look where you move) when asked to flee, when the opponent
     * keeps closing, or while a flight already runs. Leaves any other run otherwise.
     *
     * @return true when the runner moves us this tick (no aim, no keys of ours)
     */
    private boolean retreatRun(CombatSnapshot s, boolean flee) {
        if ((flee || routeMode == ROUTE_FLEE || opponentClosing(s)) && runRoute(s, ROUTE_FLEE)) {
            return true;
        }
        leaveRoute();
        return false;
    }

    private static boolean opponentClosing(CombatSnapshot s) {
        double bx = s.px - s.tx;
        double bz = s.pz - s.tz;
        double len = Math.sqrt(bx * bx + bz * bz);
        return len > 1.0e-6 && (s.tvx * bx + s.tvz * bz) / len > CLOSING_SPEED;
    }

    /**
     * Hands the keys and camera to the route runner (same lease) for pursuit or flight. Releases our keys and clears
     * the key and aim caches on the hand-over. Falls back (false) when the setting is off, no runner is registered,
     * the runner refused, failed or arrived; a refusal or failure waits {@value #ROUTE_RETRY_TICKS} ticks before the
     * next try so the keys do not flip between the runner and the melee code.
     */
    private boolean runRoute(CombatSnapshot s, int mode) {
        if (!settings.routePursuit || routeRetry > 0) {
            return false;
        }
        IRouteRunner r = Services.routeRunnerOrNull();
        if (r == null) {
            return false;
        }
        if (routeMode != mode) {
            if (routeMode != ROUTE_NONE) {
                r.stop(lease);
            } else {
                move(SpacingController.HOLD, StrafePlanner.NONE, false);
            }
            clearKeyCache();
            routeMode = mode;
            routeRuns++;
            lastNote = mode == ROUTE_INTERCEPT
                    ? "Running to where " + world.describe(targetId) + "'s ender pearl lands"
                    : (mode == ROUTE_PURSUE ? "Chasing " : "Running from ") + world.describe(targetId)
                    + " along a route"; // only on the hand-over, never per tick
        }
        PearlIntercept pi = feel.pearl();
        boolean ok = mode == ROUTE_INTERCEPT
                ? r.pursue(lease, pi.landX(), pi.landY(), pi.landZ(), PearlIntercept.STOP_RANGE, true)
                : mode == ROUTE_PURSUE
                ? r.pursue(lease, s.tx, s.ty, s.tz, settings.spacingMax, true)
                : r.flee(lease, s.tx, s.ty, s.tz, FLEE_DISTANCE, true);
        IRouteRunner.State st = ok ? r.state() : IRouteRunner.State.FAILED;
        if (mode == ROUTE_INTERCEPT && st == IRouteRunner.State.ARRIVED) {
            pi.reset(); // at the landing: normal pursuit or melee takes over at once (no retry wait)
            leaveRoute();
            return false;
        }
        if (!ok || st == IRouteRunner.State.FAILED || st == IRouteRunner.State.ARRIVED
                || st == IRouteRunner.State.IDLE) {
            leaveRoute();
            routeRetry = ROUTE_RETRY_TICKS;
            return false;
        }
        return true;
    }

    /** Takes the keys back from the route runner (stops it, clears our caches). No-op when no run is active. */
    private void leaveRoute() {
        if (routeMode == ROUTE_NONE) {
            return;
        }
        routeMode = ROUTE_NONE;
        IRouteRunner r = Services.routeRunnerOrNull();
        if (r != null && lease != null) {
            r.stop(lease);
        }
        clearKeyCache();
    }

    private void retreatMove(CombatSnapshot s) {
        move(s.hazardBack ? SpacingController.HOLD : SpacingController.BACK, StrafePlanner.NONE, false);
    }

    private void move(int forward, int side, boolean sprint) {
        // the hit planner's overrides for this tick: hold sprint into a knockback hit, drop it (and forward) for a crit
        // or a sweep. Sprint only ever goes down with forward up, so a crit swing tick never sees sprint.
        if (hitHoldSprint && forward > 0) {
            sprint = true;
        }
        if (hitRelease) {
            if (forward > 0) {
                forward = SpacingController.HOLD;
            }
            sprint = false;
        }
        if (hitDropSprint) {
            sprint = false;
        }
        key(SimKey.FORWARD, forward > 0);
        key(SimKey.BACK, forward < 0);
        key(SimKey.LEFT, side == StrafePlanner.LEFT);
        key(SimKey.RIGHT, side == StrafePlanner.RIGHT);
        key(SimKey.SPRINT, sprint);
    }

    private void key(SimKey k, boolean down) {
        int i = k.ordinal();
        if (keyDown[i] != down && input.setKeyHeld(lease, k, down)) {
            keyDown[i] = down;
        }
    }

    private void clearKeyCache() {
        java.util.Arrays.fill(keyDown, false);
        lastAimYaw = Float.NaN;
        lastAimPitch = Float.NaN;
    }

    private void aimAtTarget(CombatSnapshot s) {
        if (settings.velocityLead && settings.anticipatoryAim) {
            // lead by where the learned strafe phase puts the target (the plain velocity lead without a pattern)
            double lead = settings.leadTicks;
            feel.aimOffset(s, tacticClock, lead, aimOffset);
            AimSolver.solve(s, aimOffset[0], s.tvy * lead, aimOffset[1], aim);
        } else {
            AimSolver.solve(s, settings.velocityLead ? settings.leadTicks : 0.0, aim);
        }
        // a moving opponent: tracked with continuous angular velocity, not a chain of start-stop turns
        aimAt(aim[0], aim[1], settings.aimSpeed, true);
    }

    private void aimAt(float yaw, float pitch, float speed) {
        aimAt(yaw, pitch, speed, false);
    }

    private void aimAt(float yaw, float pitch, float speed, boolean tracking) {
        float deadBand = tracking ? AIM_TRACK_RETARGET_DEG : AIM_RETARGET_DEG;
        if (Float.isNaN(lastAimYaw) || tracking != lastAimTracking
                || Math.abs(AimSolver.yawDelta(yaw, lastAimYaw)) > deadBand
                || Math.abs(pitch - lastAimPitch) > deadBand) {
            if (input.setRotationTarget(lease, new RotationTarget(yaw, pitch, RotationMode.VISIBLE, speed, tracking))) {
                lastAimYaw = yaw;
                lastAimPitch = pitch;
                lastAimTracking = tracking;
            }
        }
    }

    private void selectWeapon() {
        if (weaponSlot >= 0 && holdsLease()) {
            input.selectHotbarSlot(lease, weaponSlot);
        }
    }

    private void startEating(CombatSnapshot s) {
        shield.reset();
        wind.reset();
        input.selectHotbarSlot(lease, s.gappleSlot);
        key(SimKey.USE, true);
        eating = true;
        eatTicks = 0;
    }

    /** @return false once eating has finished (and the weapon is back) */
    private boolean continueEating(CombatSnapshot s) {
        eatTicks++;
        if ((eatTicks > 3 && !s.usingItem) || eatTicks > MAX_EAT_TICKS) {
            stopEating();
            return false;
        }
        return true;
    }

    private void stopEating() {
        key(SimKey.USE, false);
        eating = false;
        eatTicks = 0;
        selectWeapon();
        swapBackPulled();
    }

    /** Pulls the best main-inventory consumable into the hotbar (SWAP click) when the hotbar has none. */
    private void maybePullConsumable(CombatSnapshot s) {
        if (eating || pulledInvSlot >= 0 || !settings.gapples || s.effectiveHealth() > settings.lowHealth) {
            return;
        }
        if (!SustainExtras.shouldPull(settings.wholeInventory, world.extrasAllowedHere(), s.gappleSlot,
                s.sustainInvSlot, clicker.pending() > 0, s.screenOpen)) {
            return;
        }
        int hotbar = SustainExtras.hotbarSlotForPull(s.emptyHotbarSlot, weaponSlot);
        if (clicker.queueClick(lease, SustainPlanner.menuSlotOf(s.sustainInvSlot), hotbar,
                IContainerClicker.Click.SWAP)) {
            pulledInvSlot = s.sustainInvSlot;
            pulledHotbarSlot = hotbar;
        }
    }

    /** Returns a pulled item to its inventory slot (the same SWAP again), when the setting asks for it. */
    private void swapBackPulled() {
        if (pulledInvSlot < 0) {
            return;
        }
        if (settings.swapBack && holdsLease() && !snap.screenOpen) {
            clicker.queueClick(lease, SustainPlanner.menuSlotOf(pulledInvSlot), pulledHotbarSlot,
                    IContainerClicker.Click.SWAP);
        }
        pulledInvSlot = -1;
        pulledHotbarSlot = -1;
    }

    /** Every few ticks while armed: refill an empty offhand with a totem under a short EMERGENCY hotbar lease. */
    /**
     * Autopilot path (client tick): while this module is off, the autopilot may still keep a totem in the offhand.
     * Cheap early-out; no allocation.
     */
    public void autopilotTick() {
        if (enabled) {
            return; // onTick already runs the keeper with this module's own setting
        }
        com.utilitysuite.core.api.IAutopilot ap = Services.autopilotOrNull();
        boolean allowed = ap != null && ap.allows(com.utilitysuite.core.api.IAutopilot.Reflex.TOTEM);
        if (!allowed && keeperLease == null) {
            return;
        }
        tickTotemKeeper(allowed, allowed);
    }

    private void tickTotemKeeper(boolean armed, boolean keep) {
        if (totemKeepCooldown > 0) {
            totemKeepCooldown--;
        }
        if (keeperLease != null) {
            if (!keeperLease.isActive() || clicker.pending() == 0 || ++keeperTicks > KEEPER_MAX_TICKS) {
                releaseKeeper();
            }
            return;
        }
        if (!armed || !keep || ++keeperClock < SustainExtras.TOTEM_CHECK_INTERVAL) {
            return;
        }
        keeperClock = 0;
        if (offhandManagerActive()) {
            return; // the Offhand Manager owns the offhand: never click at the same time
        }
        int slot = world.totemRefillSlot();
        if (slot < 0 || !SustainExtras.shouldReplenishTotem(armed, keep, world.extrasAllowedHere(),
                false, slot, false, arbiter.isManualOverrideActive(ControlChannel.HOTBAR), totemKeepCooldown)) {
            return;
        }
        ControlLease l;
        if (holdsLease()) {
            l = lease;
        } else {
            if (arbiter.isAutomationStopped()) {
                return;
            }
            Optional<ControlLease> granted = arbiter.request(keeperRequest);
            if (granted.isEmpty()) {
                return;
            }
            keeperLease = granted.get();
            keeperTicks = 0;
            l = keeperLease;
        }
        if (clicker.queueClick(l, SustainPlanner.menuSlotOf(slot), OFFHAND_BUTTON, IContainerClicker.Click.SWAP)) {
            totemKeepCooldown = SustainExtras.TOTEM_REFILL_COOLDOWN;
            totemRefills++;
        } else if (keeperLease != null) {
            releaseKeeper();
        }
    }

    /** Module id of the player package's offhand manager; while it is on it keeps the offhand instead of this keeper. */
    static final String OFFHAND_MANAGER_ID = "offhand_manager";
    private static final int OFFHAND_LOOKUP_RETRY = 1200;
    private com.utilitysuite.core.api.IModule offhandManager;
    private int offhandLookupWait;
    private com.utilitysuite.core.api.IModuleRegistry moduleRegistry;

    /** Registry used to find the Offhand Manager (set by the bootstrap; tests leave it unset). */
    public void bindModuleRegistry(com.utilitysuite.core.api.IModuleRegistry registry) {
        this.moduleRegistry = registry;
        this.offhandManager = null;
        this.offhandLookupWait = 0;
    }

    /** Cached registry lookup (an Optional only every {@value #OFFHAND_LOOKUP_RETRY} keeper checks until found). */
    private boolean offhandManagerActive() {
        com.utilitysuite.core.api.IModule m = offhandManager;
        if (m == null) {
            if (offhandLookupWait > 0) {
                offhandLookupWait--;
                return false;
            }
            com.utilitysuite.core.api.IModuleRegistry reg = moduleRegistry;
            if (reg == null) {
                return false;
            }
            offhandLookupWait = OFFHAND_LOOKUP_RETRY;
            m = reg.byId(OFFHAND_MANAGER_ID).orElse(null);
            if (m == null) {
                return false;
            }
            offhandManager = m;
        }
        return m.isEnabled();
    }

    private void releaseKeeper() {
        ControlLease l = keeperLease;
        keeperLease = null;
        if (l != null && l.isActive()) {
            arbiter.release(l);
        }
    }

    /** Logs a tactic change (strings only on change, never per tick). */
    /** Status line for the stalemate breaker: only when its note serial changed (never per tick). */
    private void noteStalemate() {
        StalemateBreaker b = stalemate;
        if (b.noteSerial() == stalemateNoteSerial) {
            return;
        }
        stalemateNoteSerial = b.noteSerial();
        if (!b.noteFresh()) {
            return; // the same option re-planned after a knock off the route
        }
        String who = world.describe(targetId);
        double dy = b.noteDy();
        lastNote = switch (b.notePhase()) {
            case SEARCH -> "Stalemate with " + who + ": looking for a way to it";
            case APPROACH -> b.noteFall()
                    ? "Stalemate: dropping down to " + who + " (a fall the health can take; no stairs found)"
                    : dy > StalemateDetector.JUMP_RISE ? "Stalemate: routing up to " + who + "'s level"
                    : dy < -StalemateDetector.SAFE_DROP ? "Stalemate: routing down to " + who + "'s level"
                    : "Stalemate: taking the fight to " + who + " along a route";
            case MEET -> "Stalemate: no way to " + who + "; waiting on open ground it can reach";
            case BAIT -> "Stalemate: repositioning to a new angle on " + who;
            case HOLD_OFF -> "Stalemate: no route, meeting ground or new angle toward " + who + "; holding";
            default -> lastNote;
        };
        diagnostic(lastNote);
    }

    /** The stalemate breaker (diagnostics, tests). */
    public StalemateBreaker stalemate() {
        return stalemate;
    }

    private void noteTactic(TacticsPlanner.Tactic tac) {
        if (tac == lastTactic) {
            return;
        }
        lastTactic = tac;
        if (tac != TacticsPlanner.Tactic.NONE) {
            lastNote = switch (tac) {
                case HOLD_GAP -> "Holding the gap: waiting at the edge for " + world.describe(targetId) + " to jump";
                case EDGE_KNOCK -> "Edge knock: circling to knock " + world.describe(targetId) + " off the edge";
                case EDGE_GUARD -> "Edge guard: circling so a hit from " + world.describe(targetId)
                        + " cannot knock us off the edge";
                case KNOCK_VECTOR -> "Knock vector: lining up to knock " + world.describe(targetId)
                        + " toward the hazard";
                default -> lastNote;
            };
            diagnostic(lastNote);
        }
    }

    // ---- co-pilot -----------------------------------------------------------------------------------------------------

    /** The style a fight should run in now: co-pilot by the setting, takeover while the hand-over key is held. */
    private boolean wantCoPilot() {
        return settings.controlMode == CombatSettings.ControlMode.CO_PILOT && !handOverHeld;
    }

    /**
     * Switches a running engagement between takeover and co-pilot (the setting changed, or the hand-over key went
     * down or up). Takeover to co-pilot hands every key, the camera and the hotbar back (lease released, our keys up);
     * co-pilot to takeover ends the grant and the swing lease, and the next tick requests the fight lease as usual.
     */
    private void switchStyle(boolean co) {
        if (co) {
            leaveRoute();
            stalemate.reset(null);
            if (holdsLease()) {
                if (eating) {
                    key(SimKey.USE, false);
                }
                move(SpacingController.HOLD, StrafePlanner.NONE, false);
                input.clearRotation(lease);
                ControlLease l = lease;
                lease = null;
                arbiter.release(l);
            }
            swapBackPulled();
            eating = false;
            retreating = false;
            shield.reset();
            wind.reset();
            sprintResetTicks = 0;
            waitingForResume = false;
            clearKeyCache();
            for (FightStage st : allStages) {
                st.reset();
            }
            copilotPlan.reset();
        } else {
            endCoPilot();
            // a walk already running is not a fresh movement press for the takeover's "the user moved" check
            lastMovePause = arbiter.isManualOverrideActive(ControlChannel.MOVEMENT);
        }
        copilot = co;
        lastNote = co ? "Co-pilot: you drive, the assistant assists" : "Takeover: the assistant drives the fight";
        diagnostic(lastNote);
    }

    /**
     * One co-pilot tick: the planner's writes go to the shared grant (taken only on ticks with writes, so MOVEMENT is
     * free for other automation otherwise), the swing to a short WORLD_INTERACT lease. Allocation free except the
     * swing lease's request (one per swing, not per tick).
     */
    private void coPilotDrive(CombatSnapshot s) {
        if (foreignMovementLease()) {
            // a job (or a reflex) walks the player: the user is not driving, there is nothing to co-pilot
            endGrant();
            tickSwingLease();
            return;
        }
        ISharedControl shared = Services.sharedControl();
        ManualFacts mf = shared.manual();
        SharedGrant g = grant != null && grant.isActive() ? grant : null;
        if (g != null) {
            double added = g.coSteerAddedDeg();
            copilotPlan.noteSteerAdded(added - lastSteerAdded);
            lastSteerAdded = added;
        } else if (sprintFillTicks > 0) {
            sprintFillTicks--;
        }
        if (arbiter.isHeldBy(ControlChannel.MOVEMENT, MOVE_TOGGLES_ID)) {
            sprintFillTicks = SPRINT_FILL_TICKS; // toggle sprint is on: the grant suspends its lease, so fill it in
        }
        AimSolver.solve(s, 0.0, aim);
        CoPilotPlanner cp = copilotPlan;
        cp.steerOn = settings.copilotSteer;
        cp.spacingOn = settings.copilotSpacing;
        cp.swingOn = settings.copilotSwing;
        cp.sprintReset = settings.sprintReset;
        cp.critSync = settings.critSync;
        cp.tick = mf.tick();
        cp.yaw = s.yaw;
        cp.aimYaw = aim[0];
        cp.distance = s.horizontalDistance();
        cp.spacingMin = settings.spacingMin;
        cp.spacingMax = settings.spacingMax;
        cp.lineOfSight = s.lineOfSight;
        cp.inReach = s.inReach();
        cp.crosshairOnTarget = s.crosshairOnTarget;
        cp.attackStrength = s.attackStrength;
        cp.usingItem = s.usingItem;
        cp.screenOpen = s.screenOpen;
        cp.onGround = s.onGround;
        cp.verticalSpeed = s.pvy;
        cp.critPossible = !s.sprinting && !s.inWater && !s.climbing;
        cp.fallingForCrit = s.fallingForCrit();
        cp.forwardHeld = mf.heldTicks(SimKey.FORWARD) > 0;
        cp.forwardPressTick = mf.lastPressTick(SimKey.FORWARD);
        cp.oppCharged = settings.cooldownRead
                && ProFeelLayer.oppReadyIn(settings, opponents, targetId, tacticClock) == 0 && !oppRetreating();
        cp.shieldFacing = s.targetBlocking && (s.axeSlot < 0 || s.axeSlot != s.selectedSlot)
                && ShieldDuel.frontal(s.targetYaw, s.tx, s.tz, s.px, s.pz);
        cp.manualClick = arbiter.isManualOverrideActive(ControlChannel.WORLD_INTERACT);
        cp.opposingTotal = g != null ? g.opposingYawDeg() : 0.0;
        cp.jitter = input.nextGaussianTicks(1.0, 0.6, 0, 2);
        cp.decide();
        if (cp.hasWrites()) {
            g = coPilotGrant(g, shared);
            if (g != null) {
                copilotGrantTicks++;
                if (cp.forward() != SharedGrant.KEEP) {
                    g.axes(cp.forward(), SharedGrant.KEEP);
                }
                if (cp.withholdSprint()) {
                    g.withholdSprint();
                } else if (sprintFillTicks > 0 && cp.forwardHeld) {
                    g.startSprint(); // fill only: vanilla's own conditions, as the suspended toggle would have
                }
                if (cp.steer()) {
                    // the allowance is per window; the core's cap is per grant, so it is the spent part plus this
                    g.coSteer(cp.steerYaw(), CoPilotPlanner.STEER_DEG_PER_SEC, g.coSteerAddedDeg() + cp.allowance(),
                            cp.authority());
                }
            }
        } else {
            endGrant();
        }
        if (cp.swing()) {
            coPilotSwing();
        }
        tickSwingLease();
    }

    /** The live grant, or a new one (null when refused: a screen, Stop-All, another module's grant or lease). */
    private SharedGrant coPilotGrant(SharedGrant live, ISharedControl shared) {
        if (live != null) {
            return live;
        }
        SharedGrant g = shared.begin(ID, ControlPriority.COMBAT, true);
        grant = g;
        lastSteerAdded = 0.0;
        if (g != null) {
            copilotPlan.grantRestarted();
        }
        return g;
    }

    /** Another module's lease holds MOVEMENT (toggle sprint excepted). */
    private boolean foreignMovementLease() {
        return arbiter.holderOrNull(ControlChannel.MOVEMENT) != null
                && !arbiter.isHeldBy(ControlChannel.MOVEMENT, MOVE_TOGGLES_ID);
    }

    /** The swing: an ATTACK tap under a short WORLD_INTERACT lease (refused while the user's own click has it). */
    private void coPilotSwing() {
        ControlLease l = swingLease;
        if (l == null || !l.isActive()) {
            swingLease = null;
            if (arbiter.isAutomationStopped()) {
                return;
            }
            Optional<ControlLease> granted = arbiter.request(swingRequest);
            if (granted.isEmpty()) {
                return;
            }
            l = granted.get();
            swingLease = l;
        }
        if (input.scheduleKeyTap(l, SimKey.ATTACK, 1, 1)) {
            swings++;
            copilotSwings++;
        }
        swingRelease = SWING_LEASE_TICKS;
    }

    private void tickSwingLease() {
        if (swingLease != null && --swingRelease <= 0) {
            releaseSwingLease();
        }
    }

    private void releaseSwingLease() {
        ControlLease l = swingLease;
        swingLease = null;
        if (l != null && l.isActive()) {
            arbiter.release(l);
        }
    }

    private void endGrant() {
        SharedGrant g = grant;
        grant = null;
        lastSteerAdded = 0.0;
        if (g != null) {
            g.end();
        }
    }

    /** Ends everything co-pilot holds (grant, swing lease). Idempotent. */
    private void endCoPilot() {
        endGrant();
        releaseSwingLease();
        sprintFillTicks = 0;
        copilotPlan.reset();
    }

    /** True while the running engagement is a co-pilot one (diagnostics, tests). */
    public boolean coPilotActive() {
        return copilot && state != CombatState.DORMANT;
    }

    /** The co-pilot's planner (diagnostics, tests). */
    public CoPilotPlanner coPilotPlanner() {
        return copilotPlan;
    }

    /** Swings the co-pilot sent, and ticks it wrote to the shared grant (diagnostics, tests). */
    public int coPilotSwings() {
        return copilotSwings;
    }

    public int coPilotGrantTicks() {
        return copilotGrantTicks;
    }

    // ---- hand-over key --------------------------------------------------------------------------------------------

    /**
     * The core's hand-over key ({@link com.utilitysuite.core.api.IAutopilot#handOverHeld}), once per client tick,
     * whether this module is on or off. While held and dormant it engages the threat in front of you: every
     * {@value #SCAN_INTERVAL_TICKS} ticks the entity under the crosshair when the target filter accepts it (a
     * proactive pick: single-player and allow-listed servers only), else whoever attacked you last (reactive: allowed
     * everywhere). The fight itself is the ordinary engagement: same lease, same smoothed aim, same reach and
     * line-of-sight rules. A fresh movement press still ends it, and the scan then waits out the usual pause. When
     * the key comes up (or Stop-All revokes it), an engagement that started under it ends on this tick with
     * {@link DisengageReason#HAND_OVER_RELEASED}; one the module started itself is left alone. Allocation free
     * except the scan's own entity query (a few times a second, only while held and dormant).
     */
    public void handOverTick() {
        com.utilitysuite.core.api.IAutopilot ap = Services.autopilotOrNull();
        boolean held = ap != null && ap.handOverHeld();
        if (!held) {
            if (handOverHeld) {
                handOverHeld = false;
                handOverScan = 0;
                handOverPause = 0;
                if (handOverEngaged) {
                    handOverEngaged = false;
                    if (state != CombatState.DORMANT) {
                        disengageNow(DisengageReason.HAND_OVER_RELEASED);
                    }
                }
            }
            return;
        }
        handOverHeld = true;
        if (state == CombatState.DORMANT) {
            if (handOverEngaged) {
                handOverEngaged = false; // it ended by itself (target down, the user moved): honour the usual pause
                handOverPause = proactivePause;
            }
            if (handOverPause > 0) {
                handOverPause--;
                return;
            }
            if (--handOverScan > 0) {
                return;
            }
            handOverScan = SCAN_INTERVAL_TICKS;
            int id = pickHandOverTarget();
            if (id < 0) {
                return;
            }
            engage(id, "hand-over key");
            handOverEngaged = true;
        }
        if (!enabled && state != CombatState.DORMANT) {
            tickFight(); // the module registry does not tick a module that is off
        }
    }

    /** The threat in front: the crosshair entity the target filter accepts, else the last attacker. -1 for none. */
    private int pickHandOverTarget() {
        if (arbiter.isAutomationStopped() || world.localPlayerId() < 0) {
            return -1;
        }
        int c = world.crosshairEntityId();
        if (c >= 0 && world.extrasAllowedHere() && world.isLivingAlive(c)
                && !roster.isNeverTarget(world.uuidOf(c))) {
            world.scanTargets(settings.engageRange, false, candidates);
            for (int i = 0; i < candidates.size; i++) {
                if (candidates.ids[i] == c) {
                    if (TargetSelector.accepts(settings.targets, candidates.flags[i])) {
                        return c;
                    }
                    break;
                }
            }
        }
        int a = lastAttacker;
        if (a >= 0 && world.isLivingAlive(a) && !roster.isNeverTarget(world.uuidOf(a))
                && world.distanceSqToLocal(a) <= settings.engageRange * settings.engageRange) {
            return a;
        }
        return -1;
    }

    /** True while the current engagement was started by the hand-over key (diagnostics, tests). */
    public boolean handOverEngaged() {
        return handOverEngaged;
    }

    // ---- proactive engagement -------------------------------------------------------------------------------------

    /**
     * Dormant + proactive: every {@value #SCAN_INTERVAL_TICKS} ticks look for a target and engage it. Skipped while the
     * user is moving (manual input wins), after Stop-All, during the pause that follows a user takeover, and outside
     * single-player / allow-listed servers.
     */
    private void tickProactive() {
        if (proactivePause > 0) {
            proactivePause--;
            return;
        }
        if (++scanClock < SCAN_INTERVAL_TICKS) {
            return;
        }
        scanClock = 0;
        // co-pilot: the user always drives, so walking is no reason to hold back a pick (the fight only adds to it)
        boolean coPilot = settings.controlMode == CombatSettings.ControlMode.CO_PILOT;
        if (arbiter.isAutomationStopped() || (!coPilot && arbiter.isManualOverrideActive(ControlChannel.MOVEMENT))
                || world.localPlayerId() < 0) {
            return;
        }
        if (!world.extrasAllowedHere()) {
            if (!proactiveBlockedNoted) {
                proactiveBlockedNoted = true;
                diagnostic("Proactive engagement is limited to single-player and allow-listed servers; reacting only");
            }
            return;
        }
        proactiveBlockedNoted = false;
        int id = pickProactiveTarget();
        if (id >= 0) {
            proactiveEngagements++;
            engage(id, "proactive");
        } else if (settings.foodObjective && !coPilot && foodNeeded()) { // a hunt walks the player: takeover only
            int food = TargetSelector.pickFood(candidates, settings.foodRange, settings.engageRequiresLos);
            if (food >= 0) {
                engage(food, "food objective: no food on you or in nearby chests");
                foodEngagement = true;
            }
        }
    }

    // ---- food objective --------------------------------------------------------------------------------------------

    /** Cached need, re-evaluated every {@link FoodObjective#REFRESH_TICKS} ticks (counted in scans) or after a pickup. */
    private boolean foodNeeded() {
        if (--foodRefreshScans <= 0) {
            foodRefreshScans = FoodObjective.REFRESH_TICKS / SCAN_INTERVAL_TICKS;
            boolean was = foodNeed;
            foodNeed = FoodObjective.needed(world.inventoryFood(),
                    world.storedFoodNearby(FoodObjective.STORAGE_RADIUS));
            if (foodNeed && !was) {
                diagnostic("No food on you or in nearby chests: food animals nearby are now a passive objective");
            }
        }
        return foodNeed;
    }

    /** The food animal died: keep the lease and walk over its drops. */
    private void startCollect() {
        foodKills++;
        collecting = true;
        collectTicks = 0;
        sawDrop = false;
        targetId = -1;
        tactics.reset();
        lastNote = "Collecting food drops";
    }

    private void tickCollect() {
        if (!holdsLease() && !tryAcquire()) {
            return;
        }
        boolean drop = world.nearestFoodDrop(FoodObjective.COLLECT_RADIUS, dropOffset);
        if (!drop || ++collectTicks > FoodObjective.COLLECT_MAX_TICKS) {
            if (sawDrop && !drop) {
                foodPickups++;
            }
            foodRefreshScans = 0; // re-check the need on the next scan: one meal may be enough
            disengage(DisengageReason.TARGET_LOST);
            return;
        }
        sawDrop = true;
        double dx = dropOffset[0];
        double dz = dropOffset[2];
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dropOffset[1] - 1.62, Math.max(horiz, 0.1)));
        aimAt(yaw, pitch, settings.aimSpeed);
        move(horiz > 0.35 ? SpacingController.FORWARD : SpacingController.HOLD, StrafePlanner.NONE, false);
    }

    /** Proactive mode only: replace a dead, escaped or hidden target with the next one instead of standing down. */
    private boolean retarget() {
        if (settings.engageMode != TargetSelector.Mode.PROACTIVE || !world.extrasAllowedHere()) {
            return false;
        }
        int id = pickProactiveTarget();
        if (id < 0 || id == targetId) {
            return false;
        }
        proactiveEngagements++;
        engage(id, "proactive: next target");
        return true;
    }

    private int pickProactiveTarget() {
        double radius = settings.foodObjective ? Math.max(settings.engageRange, settings.foodRange) : settings.engageRange;
        world.scanTargets(radius, settings.engageRequiresLos, candidates);
        return TargetSelector.pick(candidates, settings.targets, settings.priority, settings.engageRange,
                settings.engageRequiresLos, recentOpponentId(), allyTest);
    }

    // ---- disengage ----------------------------------------------------------------------------------------------

    /** Leaves ENGAGED: keys up, weapon slot back; releases the lease now unless our container clicks are in flight. */
    private void disengage(DisengageReason reason) {
        if (state == CombatState.DORMANT) {
            return;
        }
        lastDisengage = reason;
        if (settings.opponentMemory && targetIsPlayer && targetId >= 0) {
            recall.onFightEnd(targetId, true, opponents, habits, feel.skillOf(targetId),
                    feel.skillSamples(targetId), clock.getAsLong());
        }
        habits.reset();
        recalled.clear();
        opponentPearlLikely = false;
        collecting = false;
        foodEngagement = false;
        proactivePause = switch (reason) {
            case CANCEL_KEY, STOP_ALL, DISARMED -> PAUSE_AFTER_CANCEL_TICKS;
            case MANUAL_MOVEMENT, LEASE_ENDED -> PAUSE_AFTER_USER_TICKS;
            case LINE_OF_SIGHT_LOST, OUT_OF_RANGE, TARGET_IS_ALLY -> PAUSE_AFTER_LOST_SIGHT_TICKS;
            default -> 0;
        };
        targetId = -1;
        shield.reset();
        wind.reset();
        spacing.reset();
        strafe.reset();
        retreating = false;
        restoreWeaponNextTick = false;
        sprintResetTicks = 0;
        followUp.reset();
        leaveRoute(); // stop any pursuit or flight before our own keys go up
        endCoPilot(); // co-pilot: the grant and the swing lease end, the keys were always the user's
        feel.disengage(snap); // combo plan, feints, pearl run, arena read, the read and any rank-dial request end
        lastPositional = TacticUtility.Tactic.FIGHT;
        lastFeintNoted = FeintPlanner.Move.NONE;
        if (trade != null) {
            trade.reset();
        }
        if (holdsLease()) {
            if (eating) {
                key(SimKey.USE, false);
            }
            move(SpacingController.HOLD, StrafePlanner.NONE, false);
            input.clearRotation(lease);
            selectWeapon();
        }
        swapBackPulled();
        eating = false;
        LOG.info("Combat assistant: disengaged ({})", reason);
        diagnostic("Disengaged: " + reason);
        if (holdsLease() && clicker.pending() > 0) {
            state = CombatState.DISENGAGING;
            disengageTicks = 0;
        } else {
            finishDisengage();
        }
    }

    /** Disengage and release the lease immediately (user override, disarm, Stop-All). */
    private void disengageNow(DisengageReason reason) {
        disengage(reason);
        if (state != CombatState.DORMANT) {
            finishDisengage();
        }
    }

    private void finishDisengage() {
        ControlLease l = lease;
        lease = null;
        if (l != null && l.isActive()) {
            arbiter.release(l);
        }
        clearKeyCache();
        waitingForResume = false;
        weaponSlot = -1;
        targetId = -1;
        state = CombatState.DORMANT;
        stageTarget = -1;
        takenUnanswered = 0;
        lastAttacker = -1;
        lastAttackerAt = -1L;
        stalemate.reset(null); // the lease is gone; the disengage paths stopped any route already
        for (FightStage st : allStages) {
            st.reset();
        }
    }

    // =================================================================================================================
    // Lease callbacks
    // =================================================================================================================

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == swingLease) {
            swingLease = null; // the user clicked, or a higher priority took WORLD_INTERACT: the next swing asks again
            return;
        }
        if (l != lease) {
            return;
        }
        if (routeMode != ROUTE_NONE) {
            routeMode = ROUTE_NONE;
            IRouteRunner r = Services.routeRunnerOrNull();
            if (r != null) {
                r.stop(l); // the lease ended: the runner stops with it (a no-op if it already did)
            }
        }
        lease = null;
        clearKeyCache(); // the core released every key and the rotation of the ended lease
        eating = false;
        switch (reason) {
            case STOP_ALL -> disengage(DisengageReason.STOP_ALL);
            case MANUAL_OVERRIDE -> {
                // While we held MOVEMENT no movement pause could be running (requests are refused during one), so an
                // active movement pause now means the user just pressed a movement key: hand control back for good.
                if (arbiter.isManualOverrideActive(ControlChannel.MOVEMENT)) {
                    lastMovePause = true;
                    disengage(DisengageReason.MANUAL_MOVEMENT);
                } else if (state == CombatState.DISENGAGING) {
                    finishDisengage();
                }
                // Otherwise mouse look or an attack/use click: yield, take over again after the override pause.
            }
            case PREEMPTED -> {
                waitingForResume = true;
                if (state == CombatState.DISENGAGING) {
                    finishDisengage();
                }
            }
            default -> disengage(DisengageReason.LEASE_ENDED);
        }
    }

    @Override
    public void onResumable(ControlLease formerLease) {
        waitingForResume = false; // re-request on the next tick (never from inside a callback)
    }

    // =================================================================================================================
    // IModule / IConfigurable
    // =================================================================================================================

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.COMBAT;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    /** The master toggle (armed / disarmed). Disarming during an engagement releases everything at once. */
    @Override
    public void setEnabled(boolean enabled) {
        if (!enabled && state != CombatState.DORMANT) {
            disengageNow(DisengageReason.DISARMED);
        }
        if (!enabled) {
            releaseKeeper();
            feel.clearSkillRequest();
        }
        this.enabled = enabled;
    }

    @Override
    public void onStopAll() {
        pendingAttackTarget = -1;
        keeperLease = null; // Stop-All revoked it already
        totemKeepCooldown = 100; // do not grab the hotbar again right after Stop-All
        feel.clearSkillRequest();
        if (state != CombatState.DORMANT) {
            disengageNow(DisengageReason.STOP_ALL);
        }
    }

    @Override
    public void onWorldUnload() {
        lease = null; // the core ended it already (and the shared grant and the swing lease with it)
        grant = null;
        swingLease = null;
        sprintFillTicks = 0;
        copilotPlan.reset();
        routeMode = ROUTE_NONE;
        keeperLease = null;
        pulledInvSlot = -1;
        pulledHotbarSlot = -1;
        if (state != CombatState.DORMANT) {
            lastDisengage = DisengageReason.WORLD_UNLOAD;
        }
        finishDisengage();
        eating = false;
        retreating = false;
        shield.reset();
        wind.reset();
        pendingAttackTarget = -1;
        recentOpponent = -1;
        feel.worldUnload(snap);
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        settings.read(profile, ID);
        for (FightStage st : allStages) {
            st.read(profile, ID);
        }
        syncExactWorld();
    }

    /**
     * Hands the "Exact world shapes" setting to the shared body sims (combat bodies and pursuit read the same switch).
     * Called on settings reads and each engaged tick (a field compare; the provider may register after the profile).
     */
    private void syncExactWorld() {
        IBodySimProvider sims = Services.bodySimsOrNull();
        if (sims != null && sims.exactShapes() != settings.exactWorld) {
            sims.useExactShapes(settings.exactWorld);
        }
    }

    @Override
    public String displayName() {
        return "Combat Assistant";
    }

    @Override
    public List<SettingSpec> settings() {
        if (allStages.length == 0) {
            return CombatSettings.specs();
        }
        if (specsWithStages == null) {
            java.util.ArrayList<SettingSpec> all = new java.util.ArrayList<>(CombatSettings.specs());
            for (FightStage st : allStages) {
                st.specs(all);
            }
            specsWithStages = List.copyOf(all);
        }
        return specsWithStages;
    }

    @Override
    public void onSettingChanged(String key) {
        ProfileConfig profile = Services.modState().profile();
        settings.read(profile, ID);
        for (FightStage st : allStages) {
            st.read(profile, ID);
        }
        syncExactWorld();
    }

    // =================================================================================================================
    // Fight stages
    // =================================================================================================================

    /** Registers a stage (start-up, client thread). Stages of one slot run in registration order. */
    public void addStage(FightStage stage) {
        int i = stage.slot().ordinal();
        FightStage[] old = stages[i];
        FightStage[] grown = java.util.Arrays.copyOf(old, old.length + 1);
        grown[old.length] = stage;
        stages[i] = grown;
        allStages = java.util.Arrays.copyOf(allStages, allStages.length + 1);
        allStages[allStages.length - 1] = stage;
        specsWithStages = null;
    }

    /**
     * The assistant holds its lease and one of its stages runs {@code key} for this fight ({@link FightStage#ownsKey}).
     * Asked by the combat reflexes every tick: no allocation.
     */
    public boolean stageOwns(SimKey key) {
        if (!holdsLease()) {
            return false;
        }
        FightStage[] all = allStages;
        for (int i = 0; i < all.length; i++) {
            if (all[i].ownsKey(key)) {
                return true;
            }
        }
        return false;
    }

    /** The context stages act through (tests drive a stage against the real assistant with it). */
    public FightContext stageContext() {
        return stageCtx;
    }

    private boolean runStages(FightStage.Slot slot, CombatSnapshot s) {
        FightStage[] list = stages[slot.ordinal()];
        for (int i = 0; i < list.length; i++) {
            if (list[i].step(s, stageCtx)) {
                return true;
            }
        }
        return false;
    }

    private final class StageContext implements FightContext {
        @Override
        public CombatSettings settings() {
            return settings;
        }

        @Override
        public CombatWorld world() {
            return world;
        }

        @Override
        public OpponentModel opponents() {
            return opponents;
        }

        @Override
        public int targetId() {
            return targetId;
        }

        @Override
        public boolean targetIsPlayer() {
            return targetIsPlayer;
        }

        @Override
        public long clock() {
            return tacticClock;
        }

        @Override
        public RecalledHabits recalledHabits() {
            return settings.opponentMemory && targetIsPlayer ? recalled : RecalledHabits.NONE;
        }

        @Override
        public boolean eating() {
            return eating;
        }

        @Override
        public int weaponSlot() {
            return weaponSlot;
        }

        @Override
        public float lastHitDamage() {
            return lastHitDamage;
        }

        @Override
        public void aimAtTarget(CombatSnapshot s) {
            if (holdsLease()) {
                CombatAssistant.this.aimAtTarget(s);
            }
        }

        @Override
        public void aimAt(float yaw, float pitch, float speed) {
            if (holdsLease()) {
                CombatAssistant.this.aimAt(yaw, pitch, speed);
            }
        }

        @Override
        public void move(int forward, int side, boolean sprint) {
            if (holdsLease()) {
                CombatAssistant.this.move(forward, side, sprint);
            }
        }

        @Override
        public void retreatMove(CombatSnapshot s) {
            if (holdsLease()) {
                CombatAssistant.this.retreatMove(s);
            }
        }

        @Override
        public boolean retreatRoute(CombatSnapshot s) {
            return holdsLease() && retreatRun(s, true);
        }

        @Override
        public void tapAttack() {
            if (holdsLease()) {
                CombatAssistant.this.tapAttack();
            }
        }

        @Override
        public void hold(SimKey key, boolean down) {
            if (holdsLease()) {
                key(key, down);
            }
        }

        @Override
        public void tap(SimKey key, int holdTicks, int delayTicks) {
            if (holdsLease()) {
                input.scheduleKeyTap(lease, key, holdTicks, delayTicks);
            }
        }

        @Override
        public void selectHotbar(int slot) {
            if (holdsLease() && slot >= 0 && slot < 9) {
                input.selectHotbarSlot(lease, slot);
            }
        }

        @Override
        public void selectWeapon() {
            CombatAssistant.this.selectWeapon();
        }

        @Override
        public void startEating(CombatSnapshot s, int hotbarSlot) {
            if (!holdsLease() || eating || hotbarSlot < 0 || hotbarSlot >= 9 || s.screenOpen) {
                return;
            }
            leaveRoute();
            shield.reset();
            wind.reset();
            input.selectHotbarSlot(lease, hotbarSlot);
            key(SimKey.USE, true);
            eating = true;
            eatTicks = 0;
        }

        @Override
        public void vetoSwing() {
            stageVetoSwing = true;
        }

        @Override
        public void vetoJump() {
            stageVetoJump = true;
        }

        @Override
        public void hitPolicy(HitPlanner.Policy policy) {
            stagePolicy = policy;
        }

        @Override
        public boolean secondThreatValid() {
            return secondValid;
        }

        @Override
        public double secondThreatX() {
            return secondX;
        }

        @Override
        public double secondThreatZ() {
            return secondZ;
        }

        @Override
        public void secondThreat(double x, double z) {
            secondValid = true;
            secondX = x;
            secondZ = z;
        }

        @Override
        public void forceSide(int side) {
            stageSideForced = true;
            stageSide = side;
        }

        @Override
        public void widenSpacing(double blocks) {
            stageSpacing = Math.max(stageSpacing, blocks);
        }

        @Override
        public void pressAdvantage() {
            stagePress = true;
        }

        @Override
        public void requestTarget(int entityId) {
            stageTarget = entityId;
        }

        @Override
        public long lastHitTakenAt() {
            return hitTakenAt;
        }

        @Override
        public long lastHitLandedAt() {
            return hitLandedAt;
        }

        @Override
        public int hitsTakenUnanswered() {
            return takenUnanswered;
        }

        @Override
        public int lastAttackerId() {
            return lastAttacker;
        }

        @Override
        public long lastAttackerAt() {
            return lastAttackerAt;
        }
    }

    /** The hit planner of this fight (diagnostics, tests): the planned kind of the next hit. */
    public HitPlanner hitPlanner() {
        return hits;
    }

    /** This tick's fight snapshot (diagnostics only: read, never written, and only on the client thread). */
    public CombatSnapshot snapshotForDiagnostics() {
        return snap;
    }

    /** {@link GearRead#ratio()} of this fight (diagnostics, tests). */
    public double gearRatio() {
        return gear.ratio();
    }

    /** The gear read (diagnostics). */
    public com.utilitysuite.combat.tactics.GearRead gearRead() {
        return gear;
    }

    /** Hit policy the hit planner used on its last decision (a stage's one-tick override included; tests). */
    public HitPlanner.Policy lastHitPolicy() {
        return hits.policy;
    }

    // =================================================================================================================
    // Accessors (coach, UI, tests)
    // =================================================================================================================

    public CombatState state() {
        return state;
    }

    /**
     * {@code ENGAGED} or {@code DISENGAGING} (the state's constant name); null while dormant. Not gated on the toggle:
     * the autopilot's hand-over can engage it while it is off.
     */
    @Override
    public String phase() {
        CombatState s = state;
        return s != CombatState.DORMANT ? s.name() : null;
    }

    /** Current target entity id, or -1. */
    public int targetId() {
        return targetId;
    }

    /** The entity that last hit the user or that the user last hit (confirmed), if recent and still alive; else -1. */
    public int recentOpponentId() {
        if (recentOpponent >= 0 && clock.getAsLong() - recentOpponentMillis <= RECENT_OPPONENT_MS
                && world.isLivingAlive(recentOpponent)) {
            return recentOpponent;
        }
        return -1;
    }

    public CombatSettings config() {
        return settings;
    }

    /** The active lease, or null. */
    public ControlLease lease() {
        return holdsLease() ? lease : null;
    }

    public DisengageReason lastDisengageReason() {
        return lastDisengage;
    }

    public float lastHitDamage() {
        return lastHitDamage;
    }

    /** Totems moved to the offhand by the keeper. */
    public int totemRefills() {
        return totemRefills;
    }

    public int jumpResets() {
        return jumpResets;
    }

    /** Food animals killed for the food objective, and drop pickups completed. */
    public int foodKills() {
        return foodKills;
    }

    public int foodPickups() {
        return foodPickups;
    }

    /** True while the current engagement is the food objective (hunt or pickup). */
    public boolean onFoodObjective() {
        return foodEngagement || collecting;
    }

    /** The terrain tactic of the last engaged tick. */
    public TacticsPlanner.Tactic tactic() {
        return lastTactic;
    }

    /** Swings forced by a tactic (target airborne over a drop). */
    public int tacticSwings() {
        return tacticSwings;
    }

    /** Engagements the proactive mode started (including retargets). */
    public int proactiveEngagements() {
        return proactiveEngagements;
    }

    public int engagements() {
        return engagements;
    }

    public int swings() {
        return swings;
    }

    /** Triggers that were deliberately ignored (ambiguous attribution or an ally attacker). */
    public int ignoredAttributions() {
        return ignoredAttributions;
    }

    public String lastNote() {
        return lastNote;
    }

    // ---- internals ----------------------------------------------------------------------------------------------

    private void rememberOpponent(int id) {
        recentOpponent = id;
        recentOpponentMillis = clock.getAsLong();
    }

    private void ignore(String message) {
        ignoredAttributions++;
        lastNote = message;
        LOG.warn("Combat assistant: {}", message);
        diagnostic(message);
    }

    private static void diagnostic(String message) {
        ISuiteEventBus bus = Services.eventsOrNull();
        if (bus != null) {
            bus.post(new SuiteEvent.Diagnostic(System.currentTimeMillis(), ID, message));
        }
    }
}
