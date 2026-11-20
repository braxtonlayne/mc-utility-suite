package com.utilitysuite.combat.assistant;

import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
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
 * Reactive, defensive combat assistance (an accessibility aid for players with motor limitations). One master toggle:
 * the module's enabled flag arms it. While armed it stays {@link CombatState#DORMANT} during normal play and only
 * engages on a confirmed trigger:
 * <ol>
 *   <li><b>Offensive:</b> the user attacks an entity by hand and the server confirms the hit (a damage event for that
 *       entity caused by the local player within {@value #ATTACK_CONFIRM_MS} ms).</li>
 *   <li><b>Defensive:</b> the user takes damage of an entity-attack type with a resolvable, living cause entity. Fall,
 *       fire, lava, drowning and other environmental types never engage; neither does unattributable damage.</li>
 *   <li><b>Ally defense:</b> a roster player marked {@code defend} within range takes confirmed entity damage.</li>
 * </ol>
 * It never targets a roster player with {@code neverTarget} (if one hits the user it stays dormant and logs).
 * It disengages when the target dies or leaves the disengage radius, after losing line of sight for the configured
 * time, on the cancel key, on any manual movement key, on Stop-All and when disarmed.
 * <p>
 * Control: one COMBAT lease over MOVEMENT + CAMERA + WORLD_INTERACT + HOTBAR (preempting GOAL/TRAVEL, which the arbiter
 * resumes afterwards). Mouse-look or attack/use clicks by the user end the lease; the assistant then waits for the
 * override pause and takes over again. All key, camera, hotbar and container actions go through the lease.
 * <p>
 * Pure Java: the game is reached only through {@link CombatWorld}. {@link #onTick()} allocates nothing in steady state
 * (a new {@link RotationTarget} only when the aim point moved by more than {@value #AIM_RETARGET_DEG} degrees).
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
    private final float[] aim = new float[2];
    private final boolean[] keyDown = new boolean[SimKey.all().length];

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
    private boolean jumpedThisCycle;
    private boolean wasAirborne;
    private int critWait;
    private int sprintResetTicks;
    private boolean sprintResetFirst;
    private boolean restoreWeaponNextTick;
    private float lastAimYaw = Float.NaN;
    private float lastAimPitch = Float.NaN;

    // diagnostics
    private int engagements;
    private int swings;
    private int ignoredAttributions;
    private String lastNote = "";

    public CombatAssistant(CombatWorld world, AllyRoster roster, IActionArbiter arbiter, IInputSmoother input,
                           IContainerClicker clicker, LongSupplier clock) {
        this.world = world;
        this.roster = roster;
        this.arbiter = arbiter;
        this.input = input;
        this.clicker = clicker;
        this.clock = clock;
        this.leaseRequest = new LeaseRequest(ID, ControlPriority.COMBAT,
                EnumSet.of(LEASE_CHANNELS[0], LEASE_CHANNELS[1], LEASE_CHANNELS[2], LEASE_CHANNELS[3]), 0, this);
    }

    // =================================================================================================================
    // Triggers (called from the client event bridge; client thread)
    // =================================================================================================================

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
        if (enabled && state != CombatState.ENGAGED) {
            engage(causeId, "defensive: " + typeId);
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
        boolean fresh = state == CombatState.DORMANT;
        targetId = entityId;
        losLostTicks = 0;
        shield.reset();
        wind.reset();
        spacing.reset();
        state = CombatState.ENGAGED;
        if (fresh) {
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
        lastNote = "Engaged " + world.describe(entityId) + " (" + why + ")";
        LOG.info("Combat assistant: {}", lastNote);
        diagnostic(lastNote);
    }

    @Override
    public void onTick() {
        if (totemCooldown > 0) {
            totemCooldown--;
        }
        if (state == CombatState.DORMANT) {
            return;
        }
        if (state == CombatState.DISENGAGING) {
            if (!holdsLease() || clicker.pending() == 0 || ++disengageTicks > DISENGAGE_MAX_TICKS) {
                finishDisengage();
            }
            return;
        }
        tickEngaged();
    }

    private void tickEngaged() {
        // A movement pause that starts while engaged (we were not holding the lease, e.g. after mouse look) is a new
        // manual movement press. A pause already running at engagement (the user was walking) is not.
        boolean movePause = arbiter.isManualOverrideActive(ControlChannel.MOVEMENT);
        if (movePause && !lastMovePause) {
            lastMovePause = true;
            disengageNow(DisengageReason.MANUAL_MOVEMENT);
            return;
        }
        lastMovePause = movePause;
        CombatSnapshot s = snap;
        if (!world.sample(targetId, s)) {
            disengage(DisengageReason.TARGET_LOST);
            return;
        }
        if (roster.isNeverTarget(world.uuidOf(targetId))) {
            disengage(DisengageReason.TARGET_IS_ALLY);
            return;
        }
        double r = settings.disengageRadius;
        if (s.distanceSq() > r * r) {
            disengage(DisengageReason.OUT_OF_RANGE);
            return;
        }
        if (s.lineOfSight) {
            losLostTicks = 0;
        } else if (++losLostTicks > settings.losTimeoutTicks) {
            disengage(DisengageReason.LINE_OF_SIGHT_LOST);
            return;
        }
        float eff = s.effectiveHealth();
        if (lastHealth >= 0f && eff < lastHealth - 0.01f) {
            lastHitDamage = lastHealth - eff;
        }
        lastHealth = eff;
        if (!holdsLease() && !tryAcquire()) {
            return;
        }
        if (weaponSlot < 0) {
            weaponSlot = s.selectedSlot;
        }
        drive(s);
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
        if (restoreWeaponNextTick) {
            restoreWeaponNextTick = false;
            selectWeapon();
        }

        // ---- 1. emergency sustain ------------------------------------------------------------------------------
        if (settings.sustain) {
            SustainPlanner.Action a = SustainPlanner.decide(s, settings.lowHealth, lastHitDamage, settings.totems,
                    settings.gapples);
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
            if (retreating) {
                aimAtTarget(s);
                retreatMove(s);
                return;
            }
        } else if (eating) {
            stopEating();
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
                movement(s);
                return;
            }
        }

        // ---- 5. swing (cooldown + crit sync, optional mace) ----------------------------------------------------
        if (s.onGround && wasAirborne) {
            jumpedThisCycle = false;
        }
        wasAirborne = !s.onGround;
        SwingGate.Action sw = SwingGate.decide(s, settings.critSync, jumpedThisCycle, critWait, MAX_CRIT_WAIT);
        if (sw == SwingGate.Action.JUMP) {
            input.scheduleKeyTap(lease, SimKey.JUMP, 1, 2);
            jumpedThisCycle = true;
        } else if (sw == SwingGate.Action.SWING) {
            if (AerialPlanner.shouldSwapToMace(s, settings.maceSwap)) {
                input.selectHotbarSlot(lease, s.maceSlot);
                restoreWeaponNextTick = true;
            }
            tapAttack();
        }
        if (sw != SwingGate.Action.SWING && SwingGate.ready(s.attackStrength)) {
            critWait++;
        }

        // ---- 6. spacing, strafe, sprint reset -------------------------------------------------------------------
        movement(s);
    }

    private void tapAttack() {
        input.scheduleKeyTap(lease, SimKey.ATTACK, 1, 1);
        swings++;
        critWait = 0;
        jumpedThisCycle = false;
        if (settings.sprintReset != CombatSettings.SprintReset.OFF) {
            sprintResetTicks = input.nextGaussianTicks(1.5, 0.5, 1, 2);
            sprintResetFirst = true;
        }
    }

    private void movement(CombatSnapshot s) {
        double d = s.horizontalDistance();
        int fwd = spacing.update(d, settings.spacingMin, settings.spacingMax, s.hazardFront, s.hazardBack);
        int side = StrafePlanner.NONE;
        if (settings.strafe && d <= settings.spacingMax + 1.0) {
            side = strafe.update(s.hazardLeft, s.hazardRight, input.nextGaussianTicks(30, 8, 15, 50));
        }
        boolean sprint = fwd == SpacingController.FORWARD && d > settings.spacingMax + 0.5;
        if (sprintResetTicks > 0) {
            sprintResetTicks--;
            if (settings.sprintReset == CombatSettings.SprintReset.S_TAP && sprintResetFirst) {
                fwd = s.hazardBack ? SpacingController.HOLD : SpacingController.BACK;
            } else if (fwd == SpacingController.FORWARD) {
                fwd = SpacingController.HOLD;
            }
            sprintResetFirst = false;
            sprint = false;
        }
        move(fwd, side, sprint);
    }

    private void retreatMove(CombatSnapshot s) {
        move(s.hazardBack ? SpacingController.HOLD : SpacingController.BACK, StrafePlanner.NONE, false);
    }

    private void move(int forward, int side, boolean sprint) {
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
        AimSolver.solve(s, settings.velocityLead ? settings.leadTicks : 0.0, aim);
        aimAt(aim[0], aim[1], settings.aimSpeed);
    }

    private void aimAt(float yaw, float pitch, float speed) {
        if (Float.isNaN(lastAimYaw) || Math.abs(AimSolver.yawDelta(yaw, lastAimYaw)) > AIM_RETARGET_DEG
                || Math.abs(pitch - lastAimPitch) > AIM_RETARGET_DEG) {
            if (input.setRotationTarget(lease, new RotationTarget(yaw, pitch, RotationMode.VISIBLE, speed))) {
                lastAimYaw = yaw;
                lastAimPitch = pitch;
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
    }

    // ---- disengage ----------------------------------------------------------------------------------------------

    /** Leaves ENGAGED: keys up, weapon slot back; releases the lease now unless our container clicks are in flight. */
    private void disengage(DisengageReason reason) {
        if (state == CombatState.DORMANT) {
            return;
        }
        lastDisengage = reason;
        targetId = -1;
        shield.reset();
        wind.reset();
        spacing.reset();
        strafe.reset();
        retreating = false;
        restoreWeaponNextTick = false;
        sprintResetTicks = 0;
        if (holdsLease()) {
            if (eating) {
                key(SimKey.USE, false);
            }
            move(SpacingController.HOLD, StrafePlanner.NONE, false);
            input.clearRotation(lease);
            selectWeapon();
        }
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
    }

    // =================================================================================================================
    // Lease callbacks
    // =================================================================================================================

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l != lease) {
            return;
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
        this.enabled = enabled;
    }

    @Override
    public void onStopAll() {
        pendingAttackTarget = -1;
        if (state != CombatState.DORMANT) {
            disengageNow(DisengageReason.STOP_ALL);
        }
    }

    @Override
    public void onWorldUnload() {
        lease = null; // the core ended it already
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
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        settings.read(profile, ID);
    }

    @Override
    public String displayName() {
        return "Combat Assistant";
    }

    @Override
    public List<SettingSpec> settings() {
        return CombatSettings.specs();
    }

    @Override
    public void onSettingChanged(String key) {
        settings.read(Services.modState().profile(), ID);
    }

    // =================================================================================================================
    // Accessors (coach, UI, tests)
    // =================================================================================================================

    public CombatState state() {
        return state;
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
