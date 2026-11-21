package com.utilitysuite.core.api;

/**
 * The opt-in autopilot: a layer over the modules that lets chosen reflexes act even when their own module is off
 * (a lethal fall is clutched, a run gets parkour timing). Off by default, toggled from the menu's top bar or its key.
 * Every reflex still goes through the core (leases, smoothed turns), and the user's own input always wins.
 */
public interface IAutopilot {

    /** Reflexes the autopilot may call. Each one is also a setting of its own module. */
    enum Reflex {
        /** A fall that would hurt: look down and clutch with water, powder snow, hay, slime or cobweb. */
        FALL_CLUTCH,
        /** While you run: edge jumps on the last tick and air steering toward a safe landing. */
        PARKOUR,
        /** Mid-jump: place one block when it keeps the run going. Uses blocks from the hotbar. */
        PARKOUR_BLOCKS,
        /** Keep a totem of undying in the offhand: an empty offhand is refilled from the inventory. */
        TOTEM,
        /** Raise the shield against an arrow, a blast or a melee wind-up; lower it to swing. */
        SHIELD,
        /** Step out of a projectile's line with a timed strafe tap (keys only). */
        DODGE,
        /** Back away from a fuse or a blast before it goes off (keys only). */
        THREAT_ESCAPE,
        /** A long fall with an elytra in the inventory: swap it on, and the chestplate back after landing. */
        ELYTRA_SWAP,
        /** Offhand rules beyond the totem (shield against skeletons, totem while gliding). */
        OFFHAND
    }

    /** How far the autopilot may go. */
    enum Reach {
        /** Only in danger (a fall that hurts). */
        DANGER_ONLY,
        /** In danger, and it also helps while you move (parkour timing, blocks). */
        ASSIST
    }

    boolean isOn();

    /** True when the autopilot is on, the reflex is allowed and the reach covers it. Allocation free. */
    boolean allows(Reflex reflex);

    Reach reach();

    // ---- danger model and reflex scheduler (defaults keep fakes and an absent autopilot working) -----------------

    /** What is about to hurt the player. Never null; {@link IDangerModel#NONE} while nothing feeds it. */
    default IDangerModel danger() {
        return IDangerModel.NONE;
    }

    /**
     * The scheduler's answer to "should this reflex act now?". True when the reflex is allowed and no other pending
     * reflex averts more damage per tick of time needed (turn time counted at
     * {@link IInputSmoother#MAX_TURN_DEG_PER_SEC}). A reflex that got a yes holds the turn until it calls
     * {@link #noteDone}. Allocation free.
     *
     * @param ticksNeeded   ticks the reflex needs from now until it has averted the damage (turn + act)
     * @param damageAverted health points it expects to save
     */
    default boolean mayAct(Reflex reflex, int ticksNeeded, float damageAverted) {
        return allows(reflex);
    }

    /** The reflex started acting. {@code reason} must be a constant string (no text built per tick). */
    default void noteActed(Reflex reflex, String reason) {
    }

    /** The reflex saw a danger and did not act (no item, manual input, not legit). Constant string. */
    default void noteDeclined(Reflex reflex, String reason) {
    }

    /** The reflex finished or gave up; the next one may take the turn. */
    default void noteDone(Reflex reflex) {
    }

    /** The reflex acting right now, or null. The HUD shows one status line from it ("Autopilot: clutch"). */
    default Reflex activeReflex() {
        return null;
    }

    /**
     * One EMERGENCY lease shared by reflexes that follow each other (clutch, scoop, face the threat, escape), so the
     * camera retargets on one continuous curve and does not return to the run heading in between. Null when chains
     * are not available: the reflex then requests its own lease as before. The lease ends when the last reflex of the
     * chain calls {@link #noteDone}.
     */
    default ControlLease chainLease(Reflex reflex, String moduleId) {
        return null;
    }
}
