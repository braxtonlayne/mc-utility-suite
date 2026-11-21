package com.utilitysuite.tools.player;

/**
 * Engine-free Auto-Fish state machine. The module reports a bite ({@link #onBite()}) and calls {@link #tick} once per
 * client tick with the current facts; the returned {@link Action} says whether to tap the use key now. Delays come from
 * a {@link Delays} source (the humanizer in game, fixed values in tests). No allocation.
 *
 * <pre>
 *  IDLE --cast--> CASTING --hook in water--> WAITING --bite--> REEL_DELAY --tap--> RECAST_DELAY --tap--> CASTING
 * </pre>
 * Stops (and stays {@link Phase#STOPPED} until {@link #start()}) when the rod is about to break, the inventory is full,
 * the rod is no longer in hand, or the cast never lands.
 */
public final class FishingCycle {

    public enum Phase { IDLE, CASTING, WAITING, REEL_DELAY, RECAST_DELAY, STOPPED }

    public enum Action { NONE, USE, STOP }

    public enum StopReason { NONE, NO_ROD, ROD_LOW, INVENTORY_FULL, CAST_FAILED, MANUAL }

    /** Delay source in ticks. */
    public interface Delays {
        int reelTicks();

        int recastTicks();
    }

    /** Ticks a cast may take to reach water before it counts as failed. */
    public static final int CAST_TIMEOUT = 60;
    /** Ticks a bite stays valid; vanilla gives about 20-40 ticks to reel. */
    public static final int MAX_REEL_TICKS = 18;
    /** Fishing (the hook out without a bite) longer than this re-casts (5 minutes). */
    public static final int WAIT_TIMEOUT = 6000;

    private final Delays delays;
    private Phase phase = Phase.IDLE;
    private StopReason stopReason = StopReason.NONE;
    private int timer;
    private boolean bitePending;
    private int catches;

    public FishingCycle(Delays delays) {
        this.delays = delays;
    }

    public Phase phase() {
        return phase;
    }

    public StopReason stopReason() {
        return stopReason;
    }

    public int catches() {
        return catches;
    }

    /** (Re)starts from IDLE. */
    public void start() {
        phase = Phase.IDLE;
        stopReason = StopReason.NONE;
        timer = 0;
        bitePending = false;
    }

    public void stop(StopReason why) {
        phase = Phase.STOPPED;
        stopReason = why;
        bitePending = false;
    }

    /** A bite was seen on the player's own hook (splash sound or the hook's dip). */
    public void onBite() {
        if (phase == Phase.WAITING) {
            bitePending = true;
        }
    }

    /**
     * @param holdingRod     main hand holds a fishing rod
     * @param hookInWater    the player's own hook exists and is in water
     * @param rodUsesLeft    durability left on the rod ({@code Integer.MAX_VALUE} when unbreakable)
     * @param minRodUses     stop at or below this
     * @param inventoryFull  no free inventory slot
     */
    public Action tick(boolean holdingRod, boolean hookInWater, int rodUsesLeft, int minRodUses,
                       boolean inventoryFull) {
        if (phase == Phase.STOPPED) {
            return Action.NONE;
        }
        if (!holdingRod) {
            stop(StopReason.NO_ROD);
            return Action.STOP;
        }
        switch (phase) {
            case IDLE -> {
                if (rodUsesLeft <= minRodUses) {
                    stop(StopReason.ROD_LOW);
                    return Action.STOP;
                }
                if (inventoryFull) {
                    stop(StopReason.INVENTORY_FULL);
                    return Action.STOP;
                }
                if (hookInWater) { // the user cast already
                    phase = Phase.WAITING;
                    timer = 0;
                    return Action.NONE;
                }
                phase = Phase.CASTING;
                timer = 0;
                return Action.USE;
            }
            case CASTING -> {
                if (hookInWater) {
                    phase = Phase.WAITING;
                    timer = 0;
                    bitePending = false;
                } else if (++timer > CAST_TIMEOUT) {
                    stop(StopReason.CAST_FAILED);
                    return Action.STOP;
                }
                return Action.NONE;
            }
            case WAITING -> {
                if (bitePending) {
                    bitePending = false;
                    phase = Phase.REEL_DELAY;
                    timer = Math.min(Math.max(1, delays.reelTicks()), MAX_REEL_TICKS);
                    return Action.NONE;
                }
                if (!hookInWater || ++timer > WAIT_TIMEOUT) {
                    // the hook vanished (caught on something, user reeled) or waited too long: reel and recast
                    phase = Phase.RECAST_DELAY;
                    timer = Math.max(1, delays.recastTicks());
                    return hookInWater ? Action.USE : Action.NONE;
                }
                return Action.NONE;
            }
            case REEL_DELAY -> {
                if (--timer > 0) {
                    return Action.NONE;
                }
                catches++;
                phase = Phase.RECAST_DELAY;
                timer = Math.max(1, delays.recastTicks());
                return Action.USE;
            }
            case RECAST_DELAY -> {
                if (--timer > 0) {
                    return Action.NONE;
                }
                phase = Phase.IDLE;
                return Action.NONE;
            }
            default -> {
                return Action.NONE;
            }
        }
    }
}
