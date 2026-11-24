package com.utilitysuite.combat.tactics;

/**
 * Shield duel rules, engine free: when a raised shield blocks a hit (geometry and raise delay), which way round its
 * open side is, and the block-and-answer loop with our own shield. Static rules are pure; one instance holds the
 * block-and-answer state of one fight. Allocation free.
 * <p>
 * Vanilla 1.21.1 rules this follows (Mojang mappings, NeoForge 21.1.265 sources):
 * <ul>
 *   <li>{@code LivingEntity.isDamageSourceBlocked}: a hit is blocked when the holder {@code isBlocking()} and
 *   {@code sourcePos.vectorTo(holderPos)} (horizontal, normalised) dotted with the holder's view vector
 *   ({@code calculateViewVector(0, getYHeadRot())}) is below zero, i.e. the attacker stands strictly within 90 degrees
 *   of the holder's view direction. At exactly 90 degrees (dot 0) the hit lands.</li>
 *   <li>{@code LivingEntity.isBlocking}: the shield only counts once
 *   {@code getUseDuration - useItemRemaining >= 5}, so a hit inside the first 5 ticks of a raise lands.</li>
 *   <li>{@code Player.blockUsingShield} -> {@code disableShield}: a hit from an entity whose main hand
 *   {@code canDisableShield} (an axe) puts the shield on a 100-tick cooldown and stops its use.</li>
 *   <li>{@code Minecraft.handleKeybinds}: while the local player {@code isUsingItem()}, attack clicks are consumed
 *   without an attack, so a swing needs the shield lowered on an earlier tick ({@link #LOWER_GAP}).</li>
 * </ul>
 * Yaw convention: Minecraft's (0 = +Z, 90 = -X), view vector {@code (-sin yaw, cos yaw)}. Side keys: {@link #LEFT}
 * and {@link #RIGHT} equal {@code StrafePlanner.LEFT} / {@code RIGHT}; with the player facing the target, the left
 * key moves along {@code (fz, -fx)} for a facing {@code f} (vanilla {@code Entity.getInputVector}).
 */
public final class ShieldDuel {

    /** Ticks a raised shield needs before it blocks ({@code LivingEntity.isBlocking}). */
    public static final int RAISE_DELAY = 5;
    /** Ticks an axe hit disables a shield ({@code Player.disableShield}). */
    public static final int DISABLE_TICKS = 100;
    /** Reaction margin added to the raise lead (ticks; ping and a person's reaction). */
    public static final int REACTION_MARGIN = 2;
    /** Ticks from a swing decision to the hit (the attack tap is pressed on the next tick). */
    public static final int HIT_LEAD = 1;
    /** Ticks after lowering our shield before a swing is allowed (attack clicks are eaten while using an item). */
    public static final int LOWER_GAP = 1;
    /** Legit front cone half-angle: our shield only goes up against an opponent inside it (as the reflex does). */
    public static final double FRONT_CONE_DEG = 70.0;
    /** Extra distance (blocks) past the opponent's reach that still counts as "in reach soon" (a sprint-in step). */
    public static final double REACH_MARGIN = 0.8;
    /** Longest continuous raise without a hit on it (an opponent that never swings); then a rest. */
    public static final int MAX_HOLD = 40;
    /** Ticks the shield stays down after a {@link #MAX_HOLD} raise ran out. */
    public static final int REST_TICKS = 10;

    public static final int LEFT = -1;
    public static final int NONE = 0;
    public static final int RIGHT = 1;

    // ---- static rules ---------------------------------------------------------------------------------------------

    /**
     * Signed angle in degrees (-180..180) of the attacker around the holder, measured from the holder's view
     * direction; positive is counter-clockwise in the (x, z) plane ({@code atan2(z, x)} sense). 0 when the two stand
     * on the same spot.
     */
    public static double signedOffAxisDeg(float holderYaw, double hx, double hz, double ax, double az) {
        double yr = Math.toRadians(holderYaw);
        double vx = -Math.sin(yr);
        double vz = Math.cos(yr);
        double ux = ax - hx;
        double uz = az - hz;
        if (ux * ux + uz * uz < 1.0e-12) {
            return 0.0;
        }
        return Math.toDegrees(Math.atan2(vx * uz - vz * ux, vx * ux + vz * uz));
    }

    /** Unsigned angle 0..180 between the holder's view and the direction holder -> attacker. */
    public static double offAxisDeg(float holderYaw, double hx, double hz, double ax, double az) {
        return Math.abs(signedOffAxisDeg(holderYaw, hx, hz, ax, az));
    }

    /**
     * The attacker stands in the holder's blocked half-space ({@code isDamageSourceBlocked}: dot of the horizontal
     * direction attacker -> holder with the view vector below zero; at exactly 90 degrees the hit lands).
     */
    public static boolean frontal(float holderYaw, double hx, double hz, double ax, double az) {
        double yr = Math.toRadians(holderYaw);
        double vx = -Math.sin(yr);
        double vz = Math.cos(yr);
        double ux = hx - ax; // attacker -> holder, as vanilla's sourcePos.vectorTo(position)
        double uz = hz - az;
        double len = Math.sqrt(ux * ux + uz * uz);
        if (len < 1.0e-9) {
            return false; // vanilla normalises a zero vector to zero: dot 0, not blocked
        }
        return (ux * vx + uz * vz) / len < -1.0e-9;
    }

    /**
     * The shield will block a hit sent now: it has been raised {@code useTicks} ticks (0 = not raised) and the hit
     * arrives {@code leadTicks} later; it blocks once raised {@link #RAISE_DELAY} ticks.
     */
    public static boolean blocksHit(int useTicks, int leadTicks) {
        return useTicks > 0 && useTicks + Math.max(0, leadTicks) >= RAISE_DELAY;
    }

    /**
     * Side key that takes us round the holder to its open side the shorter way: the side that grows our angle off
     * its view. A flagged hazard on that side picks the other one; both flagged gives {@link #NONE}. Exactly ahead
     * (no shorter way) takes {@code preferred} when it is free.
     */
    public static int openSide(float holderYaw, double hx, double hz, double px, double pz, boolean hazardLeft,
                               boolean hazardRight, int preferred) {
        double phi = signedOffAxisDeg(holderYaw, hx, hz, px, pz);
        int want;
        if (phi > 1.0e-6) {
            want = LEFT; // the left key turns us counter-clockwise round the holder: the angle grows
        } else if (phi < -1.0e-6) {
            want = RIGHT;
        } else {
            want = preferred == RIGHT ? RIGHT : LEFT;
        }
        if (want == LEFT && hazardLeft) {
            want = hazardRight ? NONE : RIGHT;
        } else if (want == RIGHT && hazardRight) {
            want = hazardLeft ? NONE : LEFT;
        }
        return want;
    }

    // ---- circling probe: does the holder turn with us? ------------------------------------------------------------

    /** Circling ticks after which a holder that never let us off its axis counts as turning with us. */
    public static final int TRACK_PROBE_TICKS = 10;
    /** Largest angle off its view (degrees) we may reach in the probe and still count it as turning with us. */
    public static final double TRACK_DEG = 20.0;

    private int circleTicks;
    private double circleMaxOff;
    private boolean tracks;

    /**
     * One tick of circling its raised shield, at {@code offAxisDeg} off its view. After {@value #TRACK_PROBE_TICKS}
     * ticks without ever getting {@value #TRACK_DEG} degrees round, it turns as fast as we circle: circling gains
     * nothing against it for the rest of the fight.
     *
     * @return {@link #tracksUs()}
     */
    public boolean noteCircling(double offAxisDeg) {
        circleTicks++;
        circleMaxOff = Math.max(circleMaxOff, offAxisDeg);
        if (circleTicks >= TRACK_PROBE_TICKS && circleMaxOff < TRACK_DEG) {
            tracks = true;
        }
        return tracks;
    }

    /** Its shield came down: the next raise starts a new probe (a verdict already reached stays). */
    public void endCircling() {
        circleTicks = 0;
        circleMaxOff = 0.0;
    }

    /** The holder turns with us (see {@link #noteCircling}). */
    public boolean tracksUs() {
        return tracks;
    }

    /** Our own front cone (legit rule): the opponent is within {@code coneDeg} of our view. */
    public static boolean inFrontCone(float ownYaw, double px, double pz, double tx, double tz, double coneDeg) {
        return offAxisDeg(ownYaw, px, pz, tx, tz) <= coneDeg;
    }

    // ---- block and answer -----------------------------------------------------------------------------------------

    // per-tick inputs (filled by the caller; plain fields, no allocation)
    /** The setting is on. */
    public boolean blockEnabled;
    /** A usable shield in the offhand, not on cooldown. */
    public boolean hasShield;
    /** The sustain layer is eating or drinking (it owns USE). */
    public boolean eating;
    /** The opponent's main hand disables shields (an axe) or it hits like a heavy mob: never raise into it. */
    public boolean oppDisables;
    /** The opponent is inside our legit front cone. */
    public boolean oppInCone;
    /** The opponent is in its reach of us, or one sprint step from it ({@link #REACH_MARGIN}). */
    public boolean oppInReach;
    /** The opponent's own shield is up (it cannot swing while it holds it). */
    public boolean oppShieldUp;
    /** Ticks until the opponent's attack is charged ({@code OpponentModel.attackReadyIn}). */
    public int oppReadyIn;
    /**
     * Ticks from the opponent's swing to its damage reaching us ({@code OpponentModel.hitDelay}); the raise lead moves
     * by its difference from {@link #HIT_LEAD}, which is what the static lead assumed. Clamped to 0..4.
     */
    public int oppHitDelay = HIT_LEAD;

    // state
    private boolean raised;
    private int heldTicks;
    private int sinceLower = 1 << 20;
    private int rest;

    /** Clears the fight state (new engagement, stop). */
    public void reset() {
        raised = false;
        heldTicks = 0;
        sinceLower = 1 << 20;
        rest = 0;
        circleTicks = 0;
        circleMaxOff = 0.0;
        tracks = false;
    }

    /**
     * One tick of the block-and-answer loop from the inputs above. Afterwards {@link #raised()} says whether USE
     * should be held, {@link #vetoSwing()} whether the swing must wait, {@link #lowered()} whether this tick lowered.
     *
     * @return true when the shield should be up this tick
     */
    public boolean step() {
        if (rest > 0) {
            rest--;
        }
        boolean allowed = blockEnabled && hasShield && !eating && !oppDisables && oppInCone;
        // their charged hit is due inside the raise lead and they can reach us; a raised shield of theirs is no threat
        boolean threat = allowed && oppInReach && !oppShieldUp
                && oppReadyIn + (Math.max(0, Math.min(4, oppHitDelay)) - HIT_LEAD) <= RAISE_DELAY + REACTION_MARGIN
                && rest == 0;
        if (raised && threat && heldTicks >= MAX_HOLD) {
            threat = false; // they never swing into it: drop it for a rest and play the normal game
            rest = REST_TICKS;
        }
        if (threat) {
            raised = true;
            heldTicks++;
            sinceLower = 1 << 20;
        } else {
            if (raised) {
                sinceLower = 0;
            } else if (sinceLower < (1 << 20)) {
                sinceLower++;
            }
            raised = false;
            heldTicks = 0;
        }
        return raised;
    }

    /** USE should be held this tick. */
    public boolean raised() {
        return raised;
    }

    /** This tick lowered the shield. */
    public boolean lowered() {
        return !raised && sinceLower == 0;
    }

    /** The swing must wait this tick: the shield is up or was lowered less than {@link #LOWER_GAP} ticks ago. */
    public boolean vetoSwing() {
        return raised || sinceLower <= LOWER_GAP;
    }

    /** Ticks the shield has been held up by this loop (0 = down). */
    public int heldTicks() {
        return heldTicks;
    }
}
