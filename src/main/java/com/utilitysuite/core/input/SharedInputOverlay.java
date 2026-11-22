package com.utilitysuite.core.input;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.ISharedControl;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.ManualFacts;
import com.utilitysuite.core.api.SharedGrant;
import com.utilitysuite.core.arbiter.ActionArbiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;

import java.util.function.BooleanSupplier;

/**
 * Control of the MOVEMENT keys shared with the user ({@link ISharedControl}): one {@link SharedGrant} at a time, a
 * single reused instance (no allocation per {@link #begin} or per write). The grant's writes are collected during a
 * tick and applied once, in {@link #onMovementInput} (MovementInputUpdateEvent at {@code EventPriority.LOW}, after the
 * module listeners that only read the user's input at NORMAL), then they lapse: a write made in tick {@code t}
 * applies to the movement input of tick {@code t} (when written from an earlier listener of the same event) or
 * {@code t + 1}, never later.
 *
 * <h2>Writes</h2>
 * <ul>
 *   <li>{@code axes}: forward/strafe set together with {@code up/down/left/right} and the impulses; vanilla's sneak
 *       multiplier (already applied to the user's impulse) is applied to the written one too.</li>
 *   <li>{@code jump}: ON sets, OFF withholds the user's jump for that tick.</li>
 *   <li>{@code sneak(true)} sets the sneak key; {@code sneak(false)} and KEEP never lift a sneak the user holds.</li>
 *   <li>{@code startSprint}: fill only, under vanilla's own start conditions ({@link #sprintAllowed}, mirrored from
 *       {@code LocalPlayer.aiStep} / {@code canStartSprinting} of 1.21.1); vanilla's stop rules run right after the
 *       event in the same {@code aiStep}. The only {@code setSprinting} call of shared control.</li>
 *   <li>{@code withholdSprint}: for that tick a running sprint ends ({@code setSprinting(false)} in the event) and
 *       no sprint starts later in the same {@code aiStep}: {@link #isSprintStartBlocked()} is read by
 *       {@code mixin.input.LocalPlayerSprintWithholdMixin}, which skips every {@code setSprinting(true)} of
 *       {@code LocalPlayer.aiStep} (the sprint-key start, the double-tap-forward start) while it is set. Wins over
 *       {@code startSprint} in the same tick; the flag lapses at the next tick start, on {@code end()}, Stop-All and
 *       a screen opening.</li>
 *   <li>{@code coSteer}: forwarded to {@link YawCoSteer} unless the grant was begun without {@code wantsYaw} or the
 *       legit level was LEGIT at {@link #begin} (the core refuses; the module should not ask either).</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * {@link #begin} is refused (null) when a screen is open, another module's grant is live, automation is stopped or a
 * lease at or above the priority holds MOVEMENT ({@link ActionArbiter#beginShared}). The grant ends on
 * {@link SharedGrant#end}, Stop-All, world unload, a lease that wins MOVEMENT (arbiter callback) and a screen opening
 * ({@link #onScreenOpening} and the per-tick check). Hardware key presses never end it; modules read them through
 * {@link #manual()}. Client thread only.
 */
public final class SharedInputOverlay implements ISharedControl {

    /** Vanilla's {@code hasEnoughImpulseToStartSprinting} threshold (on land). */
    public static final float SPRINT_IMPULSE = 0.8f;
    /** Vanilla's {@code hasEnoughFoodToStartSprinting}: food level strictly above this. */
    public static final int SPRINT_FOOD = 6;

    private final ActionArbiter arbiter;
    private final ManualFactsTracker facts;
    private final YawCoSteer steer;
    private final BooleanSupplier screenOpen;
    private final BooleanSupplier legitLevel;
    private final Grant grant = new Grant();
    /** Scratch copy of the movement input the writes are applied to (reused). */
    private final MoveFields fields = new MoveFields();
    /** The overlay the sprint-withhold mixin reads (the registered one; null before the core starts). */
    private static SharedInputOverlay active;
    /** Set while this tick's movement input carries a sprint withhold: no sprint may start in this aiStep. */
    private boolean sprintBlocked;

    /**
     * @param screenOpen true while a screen is open (read on begin and per tick)
     * @param legitLevel true while the legit level is LEGIT (read once per begin)
     */
    public SharedInputOverlay(ActionArbiter arbiter, ManualFactsTracker facts, YawCoSteer steer,
                              BooleanSupplier screenOpen, BooleanSupplier legitLevel) {
        this.arbiter = arbiter;
        this.facts = facts;
        this.steer = steer;
        this.screenOpen = screenOpen;
        this.legitLevel = legitLevel;
        active = this;
    }

    /**
     * True while the current tick's movement input carries a sprint withhold: {@code LocalPlayer.aiStep} must not start
     * a sprint (read by {@code LocalPlayerSprintWithholdMixin}, client thread). A field read when idle.
     */
    public static boolean isSprintStartBlocked() {
        SharedInputOverlay o = active;
        return o != null && o.sprintBlocked;
    }

    /** This overlay's sprint-start block for the current tick (tests, diagnostics). */
    public boolean sprintStartBlocked() {
        return sprintBlocked;
    }

    // ---- ISharedControl ---------------------------------------------------------------------------------------------

    @Override
    public SharedGrant begin(String moduleId, ControlPriority priority, boolean wantsYaw) {
        if (moduleId == null || priority == null || screenOpen.getAsBoolean()) {
            return null;
        }
        if (grant.active && !grant.moduleId.equals(moduleId)) {
            return null;
        }
        if (!arbiter.beginShared(moduleId, priority, grant)) {
            return null;
        }
        grant.start(moduleId, wantsYaw, wantsYaw && !legitLevel.getAsBoolean());
        return grant;
    }

    @Override
    public ManualFacts manual() {
        return facts;
    }

    /** The grant instance (live or not), for diagnostics and tests. */
    public SharedGrant grant() {
        return grant;
    }

    /** Why the last grant ended (null while live or before the first). */
    public LeaseEndReason lastEndReason() {
        return grant.endReason;
    }

    /** Module id of the live grant, or null. */
    public String activeModuleId() {
        return grant.active ? grant.moduleId : null;
    }

    // ---- per tick ---------------------------------------------------------------------------------------------------

    /** ClientTickEvent.Pre (after {@link ManualFactsTracker#beginTick}): screen check, co-steer lapse. */
    public void beginTick(boolean screenIsOpen) {
        sprintBlocked = false; // a withhold covers one tick only
        if (screenIsOpen) {
            onScreenOpening();
        }
        steer.beginTick();
    }

    /** A screen opened: the grant ends at once (PREEMPTED by "screen"). */
    public void onScreenOpening() {
        if (grant.active) {
            arbiter.endShared(grant);
            grant.finish(LeaseEndReason.PREEMPTED);
        }
    }

    /** MovementInputUpdateEvent (EventPriority.LOW): applies this tick's writes to the player's input. */
    public void onMovementInput(MovementInputUpdateEvent event) {
        sprintBlocked = false;
        Grant g = grant;
        if (g.active && g.hasWrites()) {
            GameSide.apply(this, event);
        }
    }

    /**
     * The Minecraft-facing half of {@link #onMovementInput}, kept in its own class so the overlay itself loads in
     * engine-free unit tests.
     */
    private static final class GameSide {

        private GameSide() {
        }

        static void apply(SharedInputOverlay o, MovementInputUpdateEvent event) {
            Minecraft mc = Minecraft.getInstance();
            LocalPlayer p = mc.player;
            if (event.getEntity() != p || p == null) {
                return;
            }
            if (mc.screen != null) {
                o.onScreenOpening();
                return;
            }
            Input in = event.getInput();
            MoveFields f = o.fields;
            f.forwardImpulse = in.forwardImpulse;
            f.leftImpulse = in.leftImpulse;
            f.up = in.up;
            f.down = in.down;
            f.left = in.left;
            f.right = in.right;
            f.jumping = in.jumping;
            f.shiftKeyDown = in.shiftKeyDown;
            float slow = p.isMovingSlowly() ? (float) p.getAttributeValue(Attributes.SNEAKING_SPEED) : 1.0f;
            boolean sprint = o.apply(f, slow);
            if (o.sprintBlocked && p.isSprinting()) {
                // withhold: a running sprint ends as if the sprint key were up and forward released for a tick; the
                // mixin keeps aiStep from starting one again later in this tick
                p.setSprinting(false);
            }
            in.forwardImpulse = f.forwardImpulse;
            in.leftImpulse = f.leftImpulse;
            in.up = f.up;
            in.down = f.down;
            in.left = f.left;
            in.right = f.right;
            in.jumping = f.jumping;
            in.shiftKeyDown = f.shiftKeyDown;
            if (sprint && sprintAllowed(p.isSprinting(), in.forwardImpulse, p.isUnderWater(), p.isInWater(),
                    p.canStartSwimming(), p.getFoodData().getFoodLevel(), p.isPassenger(), p.mayFly(),
                    p.isUsingItem(), p.hasEffect(MobEffects.BLINDNESS), !p.isPassenger() || vehicleCanSprint(p),
                    p.isFallFlying(), p.horizontalCollision && !p.minorHorizontalCollision)) {
                // fill only: vanilla's stop rules (forward released, hunger, a wall) run right after this event
                p.setSprinting(true);
            }
        }

        private static boolean vehicleCanSprint(LocalPlayer p) {
            net.minecraft.world.entity.Entity v = p.getVehicle();
            return v != null && v.canSprint() && v.isControlledByLocalInstance();
        }
    }

    /**
     * Engine-free core of {@link #onMovementInput}: applies the pending writes of the live grant to {@code f} and clears
     * them. Writes older than the previous tick are dropped. Returns whether a sprint start was asked for (never with a
     * sprint withhold in the same tick, which sets {@link #sprintStartBlocked()} instead).
     *
     * @param slow vanilla's impulse multiplier while moving slowly (sneaking speed), else 1
     */
    public boolean apply(MoveFields f, float slow) {
        Grant g = grant;
        sprintBlocked = false;
        if (!g.active) {
            return false;
        }
        boolean fresh = g.writeTick >= facts.tick() - 1L;
        boolean sprint = false;
        if (fresh) {
            if (g.forward != SharedGrant.KEEP) {
                f.forwardImpulse = g.forward * slow;
                f.up = g.forward > 0;
                f.down = g.forward < 0;
            }
            if (g.strafe != SharedGrant.KEEP) {
                f.leftImpulse = g.strafe * slow;
                f.left = g.strafe > 0;
                f.right = g.strafe < 0;
            }
            if (g.jump == SharedGrant.ON) {
                f.jumping = true;
            } else if (g.jump == SharedGrant.OFF) {
                f.jumping = false;
            }
            if (g.sneak) {
                f.shiftKeyDown = true;
            }
            sprint = g.sprint && !g.noSprint;
            sprintBlocked = g.noSprint;
        }
        g.clearWrites();
        return sprint;
    }

    /**
     * Vanilla's own conditions for starting a sprint (1.21.1 {@code LocalPlayer.aiStep} sprint-key branch and
     * {@code canStartSprinting}), plus its stop rule for a wall, so a started sprint is not undone in the same tick:
     * not already sprinting; forward impulse at least 0.8 on land (any forward impulse under water); not in water
     * unless under water or able to start swimming; food above 6 (or riding, or able to fly); not using an item; not
     * blind; riding only a vehicle that can sprint and is controlled locally; not gliding; not against a wall
     * (horizontal collision that is not minor). Modded fluid types are treated as land.
     */
    public static boolean sprintAllowed(boolean sprinting, float forwardImpulse, boolean underWater, boolean inWater,
                                        boolean canStartSwimming, int foodLevel, boolean passenger, boolean mayFly,
                                        boolean usingItem, boolean blind, boolean vehicleOk, boolean fallFlying,
                                        boolean againstWall) {
        if (sprinting || usingItem || blind || fallFlying || againstWall || !vehicleOk) {
            return false;
        }
        boolean impulse = underWater ? forwardImpulse > 1.0e-5f : forwardImpulse >= SPRINT_IMPULSE;
        boolean food = passenger || foodLevel > SPRINT_FOOD || mayFly;
        boolean fluidOk = !inWater || underWater || canStartSwimming;
        return impulse && food && fluidOk;
    }

    // ---- the grant --------------------------------------------------------------------------------------------------

    private final class Grant implements SharedGrant, ActionArbiter.SharedHolder {
        boolean active;
        String moduleId;
        boolean wantsYaw;
        boolean yawAllowed;
        LeaseEndReason endReason;

        long writeTick = Long.MIN_VALUE;
        int forward = KEEP;
        int strafe = KEEP;
        int jump = KEEP;
        boolean sneak;
        boolean sprint;
        boolean noSprint;

        void start(String id, boolean wantsYaw, boolean yawAllowed) {
            this.active = true;
            this.moduleId = id;
            this.wantsYaw = wantsYaw;
            this.yawAllowed = yawAllowed;
            this.endReason = null;
            clearWrites();
            steer.reset();
        }

        void finish(LeaseEndReason reason) {
            active = false;
            endReason = reason;
            sprintBlocked = false;
            clearWrites();
            steer.reset();
        }

        void clearWrites() {
            forward = KEEP;
            strafe = KEEP;
            jump = KEEP;
            sneak = false;
            sprint = false;
            noSprint = false;
        }

        boolean hasWrites() {
            return forward != KEEP || strafe != KEEP || jump != KEEP || sneak || sprint || noSprint;
        }

        private void stamp() {
            writeTick = facts.tick();
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void axes(int forward, int strafe) {
            if (!active) {
                return;
            }
            stamp();
            this.forward = forward == KEEP ? KEEP : Integer.signum(forward);
            this.strafe = strafe == KEEP ? KEEP : Integer.signum(strafe);
        }

        @Override
        public void jump(int state) {
            if (!active) {
                return;
            }
            stamp();
            this.jump = state == ON ? ON : (state == OFF ? OFF : KEEP);
        }

        @Override
        public void sneak(boolean on) {
            if (!active) {
                return;
            }
            stamp();
            this.sneak = on;
        }

        @Override
        public void startSprint() {
            if (!active) {
                return;
            }
            stamp();
            this.sprint = true;
        }

        @Override
        public void withholdSprint() {
            if (!active) {
                return;
            }
            stamp();
            this.noSprint = true;
        }

        @Override
        public boolean canWithholdSprint() {
            return true;
        }

        @Override
        public void coSteer(float idealYawDeg, double maxDegPerSec, double maxTotalDeg, double authority) {
            if (active && yawAllowed) {
                steer.request(idealYawDeg, maxDegPerSec, maxTotalDeg, authority);
            }
        }

        @Override
        public double opposingYawDeg() {
            return active ? steer.opposingDeg() : 0.0;
        }

        @Override
        public void end() {
            if (active) {
                arbiter.endShared(this);
                finish(LeaseEndReason.RELEASED);
            }
        }

        @Override
        public void onSharedEnded(LeaseEndReason reason, String by) {
            if (active) {
                finish(reason);
            }
        }
    }

    // ---- movement input copy ----------------------------------------------------------------------------------------

    /** Mutable copy of the {@code Input} fields the overlay writes (keeps the apply path engine free and testable). */
    public static final class MoveFields {
        public float forwardImpulse;
        public float leftImpulse;
        public boolean up;
        public boolean down;
        public boolean left;
        public boolean right;
        public boolean jumping;
        public boolean shiftKeyDown;
    }
}
