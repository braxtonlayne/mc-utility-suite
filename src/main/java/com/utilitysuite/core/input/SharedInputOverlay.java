package com.utilitysuite.core.input;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.ISharedControl;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.ManualFacts;
import com.utilitysuite.core.api.SharedGrant;
import com.utilitysuite.core.arbiter.ActionArbiter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.effect.MobEffects;
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
 *   <li>{@code axes}: forward/strafe set together with {@code up/down/left/right} and the impulses. In 26.3 the
 *       move vector is raw (normalized, length at most 1) at the event and vanilla applies the sneak / item-use
 *       multipliers afterwards ({@code LocalPlayer.modifyInput}), so the written vector gets them too: the game side
 *       passes {@code slow = 1} and normalizes a diagonal write like vanilla's own keys.</li>
 *   <li>{@code jump}: ON sets, OFF withholds the user's jump for that tick.</li>
 *   <li>{@code sneak(true)} sets the sneak key; {@code sneak(false)} and KEEP never lift a sneak the user holds.</li>
 *   <li>{@code startSprint}: fill only, under vanilla's own start conditions ({@link #sprintAllowed}, mirrored from
 *       {@code LocalPlayer.aiStep} / {@code canStartSprinting} of 26.3, {@link #sprintStartAllowed}); vanilla's stop rules run right after the
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
 *
 * <h2>Assist grant</h2>
 * {@link #beginAssist} hands out a second reused instance that rides along with another module's MOVEMENT lease
 * ({@link ActionArbiter#beginAssist}) instead of holding MOVEMENT: axes, jump and sneak only (sprint and co-steer
 * calls are ignored), applied in the same pass right after the grant's writes, with the same one-tick lapse and sneak
 * multiplier. It ends with the lease it rides on (so the user's own movement keys end it with the job's lease), on
 * Stop-All, world unload and a screen opening. The two never write in the same tick: a lease on MOVEMENT and the
 * shared grant exclude each other in the arbiter.
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
    private final Grant grant = new Grant(false);
    /** The assist grant ({@link #beginAssist}): axes, jump and sneak next to another module's MOVEMENT lease. */
    private final Grant assist = new Grant(true);
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
    public SharedGrant beginAssist(String moduleId) {
        if (moduleId == null || screenOpen.getAsBoolean()) {
            return null;
        }
        Grant a = assist;
        if (a.active && !a.moduleId.equals(moduleId)) {
            return null;
        }
        if (!arbiter.beginAssist(moduleId, a)) {
            return null;
        }
        if (!a.active) {
            // a live grant stays as it is (pending writes included): the arbiter ends it with the lease it rides on
            a.start(moduleId, false, false);
        }
        return a;
    }

    /** The assist grant instance (live or not), for diagnostics and tests. */
    public SharedGrant assistGrant() {
        return assist;
    }

    /** Why the last assist grant ended (null while live or before the first). */
    public LeaseEndReason lastAssistEndReason() {
        return assist.endReason;
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

    /** A screen opened: the grant and the assist grant end at once (PREEMPTED by "screen"). */
    public void onScreenOpening() {
        if (grant.active) {
            arbiter.endShared(grant);
            grant.finish(LeaseEndReason.PREEMPTED);
        }
        if (assist.active) {
            arbiter.endAssist(assist);
            assist.finish(LeaseEndReason.PREEMPTED);
        }
    }

    /** MovementInputUpdateEvent (EventPriority.LOW): applies this tick's writes to the player's input. */
    public void onMovementInput(MovementInputUpdateEvent event) {
        sprintBlocked = false;
        Grant g = grant;
        Grant a = assist;
        if ((g.active && g.hasWrites()) || (a.active && a.hasWrites())) {
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
            if (mc.gui.screen() != null) {
                o.onScreenOpening();
                return;
            }
            ClientInput in = event.getInput();
            net.minecraft.world.entity.player.Input keys = in.keyPresses;
            MoveFields f = o.fields;
            f.forwardImpulse = MoveInputs.forward(in);
            f.leftImpulse = MoveInputs.left(in);
            f.up = keys.forward();
            f.down = keys.backward();
            f.left = keys.left();
            f.right = keys.right();
            f.jumping = keys.jump();
            f.shiftKeyDown = keys.shift();
            // 26.3: vanilla scales the move vector for sneaking after this event (modifyInput), so no multiplier here
            boolean sprint = o.apply(f, 1.0f);
            if (o.sprintBlocked && p.isSprinting()) {
                // withhold: a running sprint ends as if the sprint key were up and forward released for a tick; the
                // mixin keeps aiStep from starting one again later in this tick
                p.setSprinting(false);
            }
            MoveInputs.setMove(in, f.forwardImpulse, f.leftImpulse);
            MoveInputs.setKeys(in, f.up, f.down, f.left, f.right, f.jumping, f.shiftKeyDown);
            if (sprint && sprintStartAllowed(p.isSprinting(), MoveInputs.forward(in), p.isUnderWater(),
                    p.isInShallowWater(), p.getAbilities().flying,
                    p.getFoodData().hasEnoughFood() || p.mayFly(), p.isPassenger(), vehicleCanSprint(p),
                    slowDueToUsingItem(p), p.hasEffect(MobEffects.BLINDNESS), p.isFallFlying(), p.isMovingSlowly(),
                    p.horizontalCollision && !p.minorHorizontalCollision)) {
                // fill only: vanilla's stop rules (forward released, hunger, a wall) run right after this event
                p.setSprinting(true);
            }
        }

        private static boolean vehicleCanSprint(LocalPlayer p) {
            net.minecraft.world.entity.Entity v = p.getVehicle();
            return v != null && v.canSprint() && v.isLocalInstanceAuthoritative();
        }

        /** 26.3 {@code LocalPlayer.isSlowDueToUsingItem} (private there). */
        private static boolean slowDueToUsingItem(LocalPlayer p) {
            return p.isUsingItem() && !p.getUseItem().getOrDefault(net.minecraft.core.component.DataComponents.USE_EFFECTS,
                    net.minecraft.world.item.component.UseEffects.DEFAULT).canSprint();
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
        sprintBlocked = false;
        boolean sprint = applyGrant(grant, f, slow);
        applyGrant(assist, f, slow); // axes, jump and sneak only: it never carries a sprint write
        return sprint;
    }

    private boolean applyGrant(Grant g, MoveFields f, float slow) {
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
            if (g.noSprint) {
                sprintBlocked = true;
            }
        }
        g.clearWrites();
        return sprint;
    }

    /**
     * 26.3 vanilla's conditions for starting a sprint ({@code LocalPlayer.canStartSprinting} /
     * {@code isSprintingPossible}), plus its stop rule for a wall so a started sprint is not undone in the same tick:
     * not already sprinting; any forward impulse; not blind; riding only a vehicle that can sprint and is controlled
     * locally, else food above 6 or able to fly; not in shallow water unless flying; not slowed by the item in use;
     * not gliding and not sneaking/crawling unless under water; not against a wall (non-minor horizontal collision).
     */
    public static boolean sprintStartAllowed(boolean sprinting, float forwardImpulse, boolean underWater,
                                             boolean shallowWater, boolean flying, boolean foodOk, boolean passenger,
                                             boolean vehicleOk, boolean slowUsingItem, boolean blind,
                                             boolean fallFlying, boolean movingSlowly, boolean againstWall) {
        if (sprinting || forwardImpulse <= 1.0e-5f || blind || slowUsingItem || againstWall) {
            return false;
        }
        boolean possible = (passenger ? vehicleOk : foodOk) && (flying || !shallowWater);
        return possible && (!fallFlying || underWater) && (!movingSlowly || underWater);
    }

    /**
     * Retained for the engine-free tests: vanilla's start conditions as they were in 1.21.1 (the game side uses
     * {@link #sprintStartAllowed}). Vanilla's own conditions for starting a sprint (1.21.1 {@code LocalPlayer.aiStep} sprint-key branch and
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
        /** The assist grant: axes, jump and sneak only; sprint and yaw calls are ignored. */
        final boolean assistOnly;
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

        Grant(boolean assistOnly) {
            this.assistOnly = assistOnly;
        }

        void start(String id, boolean wantsYaw, boolean yawAllowed) {
            this.active = true;
            this.moduleId = id;
            this.wantsYaw = wantsYaw;
            this.yawAllowed = yawAllowed;
            this.endReason = null;
            clearWrites();
            if (!assistOnly) {
                steer.reset();
            }
        }

        void finish(LeaseEndReason reason) {
            active = false;
            endReason = reason;
            clearWrites();
            if (!assistOnly) {
                sprintBlocked = false;
                steer.reset();
            }
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
            if (!active || assistOnly) {
                return;
            }
            stamp();
            this.sprint = true;
        }

        @Override
        public void withholdSprint() {
            if (!active || assistOnly) {
                return;
            }
            stamp();
            this.noSprint = true;
        }

        @Override
        public boolean canWithholdSprint() {
            return !assistOnly;
        }

        @Override
        public void coSteer(float idealYawDeg, double maxDegPerSec, double maxTotalDeg, double authority) {
            if (active && yawAllowed) {
                steer.request(idealYawDeg, maxDegPerSec, maxTotalDeg, authority);
            }
        }

        @Override
        public double opposingYawDeg() {
            return active && !assistOnly ? steer.opposingDeg() : 0.0;
        }

        @Override
        public double coSteerAddedDeg() {
            return active && !assistOnly ? steer.totalAddedDeg() : 0.0;
        }

        @Override
        public void end() {
            if (active) {
                if (assistOnly) {
                    arbiter.endAssist(this);
                } else {
                    arbiter.endShared(this);
                }
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
