package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.world.blast.BlastMath;

/**
 * Engine-free state machine for the ancient-debris blasting cycle (Nether only, opt-in). The in-game executor fills
 * one reused {@link Observation} per tick, calls {@link #step}, and carries out the returned phase (dig the 1x2
 * tunnel, dig a side pocket, place the charge, walk back, place the blast-shield block, trigger, wait, plug lava with
 * Lava Plug, mine revealed debris with Vein Work). It sets {@link Observation#stepDone} once the current phase's
 * action finished. Allocation free.
 * <p>
 * Charge order: a bed (TNT-free, explodes with power 5 in the Nether when used) when {@code preferBed}, else TNT with an
 * igniter. A bed has to be used from within reach, so the sequence is charge, retreat, shield, trigger. TNT gets lit
 * first and then the player retreats behind the shield during the 4 s fuse.
 * <p>
 * Safety first: any phase aborts when the player leaves the Nether, health drops below {@code minHealth}, or flowing
 * lava is next to the feet. Before the charge is placed the planned stand distance must keep the blast below
 * {@code maxBlastDamage} assuming {@code plannedExposure} (the shield's expected cover); right before the trigger the
 * measured exposure at the actual stand spot is checked again ({@link BlastMath#entityDamage}, armour ignored).
 */
public final class DebrisBlastCycle {

    public enum Phase { IDLE, TUNNEL, POCKET, PLACE_CHARGE, RETREAT, PLACE_SHIELD, TRIGGER, SETTLE, PLUG_LAVA,
        MINE_DEBRIS, ABORTED }

    public enum Charge { NONE, BED, TNT }

    public enum Abort { NONE, NOT_NETHER, WRONG_LEVEL, LOW_HEALTH, LAVA_AT_FEET, NO_SHIELD_BLOCK, NO_CHARGE,
        UNSAFE_DISTANCE }

    /** Settings (pro defaults). */
    public static final class Config {
        /** Tunnel Y level (debris peaks at Y 15). */
        public int targetY = 15;
        public int yTolerance = 3;
        /** Side pockets every N tunnel blocks. */
        public int pocketEvery = 6;
        public boolean preferBed = true;
        /** Abort below this health (half hearts). */
        public float minHealth = 12f;
        /** Highest blast damage allowed at the stand spot (half hearts, before armour). */
        public float maxBlastDamage = 4f;
        /** Expected fraction of rays the shield (a full 1x2 block wall) lets through, for the plan check. */
        public double plannedExposure = 0.05;
        /** Farthest the player may stand from the charge (bed reach is about 4.5). */
        public double maxStandDistance = 4.5;
        /** TNT standing distance (the player can walk farther during the fuse). */
        public double tntStandDistance = 7.0;
        /** Ticks to wait after the blast before inspecting the pocket. */
        public int settleTicks = 30;
        /** 0 peaceful .. 3 hard. */
        public int difficulty = 3;
    }

    /** Per-tick facts from the executor; reuse one instance. */
    public static final class Observation {
        public boolean nether;
        public int feetY;
        public float health;
        public boolean hasBed;
        public boolean hasTnt;
        public boolean hasIgniter;
        public boolean hasShieldBlock;
        public boolean lavaNearFeet;
        /** Tunnel blocks dug since the last pocket. */
        public int blocksSincePocket;
        /** The current phase's action has finished. */
        public boolean stepDone;
        /** Distance from the player to the charge right now. */
        public double distanceToCharge;
        /** Measured fraction of explosion rays reaching the player (vanilla seen-percent). */
        public double exposure = 1.0;
        public int exposedLava;
        public int debrisVisible;
    }

    private final Config config;
    private Phase phase = Phase.IDLE;
    private Abort abort = Abort.NONE;
    private Charge charge = Charge.NONE;
    private int settle;
    private int cycles;

    public DebrisBlastCycle(Config config) {
        this.config = config;
    }

    public Phase phase() { return phase; }
    public Abort abortReason() { return abort; }
    public Charge charge() { return charge; }
    /** Completed blasts. */
    public int cycles() { return cycles; }

    public float chargePower() {
        return charge == Charge.TNT ? BlastMath.POWER_TNT : BlastMath.POWER_BED;
    }

    /** Distance the executor should stand from the charge for the current charge type. */
    public double standDistance() {
        return charge == Charge.TNT ? config.tntStandDistance : config.maxStandDistance;
    }

    /** Distance needed so the blast stays under the configured damage with the planned shield cover. */
    public double requiredDistance(Charge c) {
        float power = c == Charge.TNT ? BlastMath.POWER_TNT : BlastMath.POWER_BED;
        return BlastMath.safeDistance(power, config.maxBlastDamage, config.plannedExposure, config.difficulty);
    }

    /** True when the planned stand spot is safe for this charge. */
    public boolean planIsSafe(Charge c) {
        double stand = c == Charge.TNT ? config.tntStandDistance : config.maxStandDistance;
        return requiredDistance(c) <= stand;
    }

    public void start(Observation o) {
        abort = Abort.NONE;
        charge = Charge.NONE;
        settle = 0;
        if (!o.nether) {
            fail(Abort.NOT_NETHER);
        } else if (Math.abs(o.feetY - config.targetY) > config.yTolerance) {
            fail(Abort.WRONG_LEVEL);
        } else {
            phase = Phase.TUNNEL;
        }
    }

    public void stop() {
        phase = Phase.IDLE;
        charge = Charge.NONE;
    }

    public Phase step(Observation o) {
        if (phase == Phase.IDLE || phase == Phase.ABORTED) {
            return phase;
        }
        if (!o.nether) {
            return fail(Abort.NOT_NETHER);
        }
        if (o.health < config.minHealth) {
            return fail(Abort.LOW_HEALTH);
        }
        if (o.lavaNearFeet) {
            return fail(Abort.LAVA_AT_FEET);
        }
        boolean done = o.stepDone;
        o.stepDone = false;
        switch (phase) {
            case TUNNEL -> {
                if (o.blocksSincePocket >= config.pocketEvery) {
                    Charge c = pickCharge(o);
                    if (c == Charge.NONE) {
                        return fail(Abort.NO_CHARGE);
                    }
                    if (!o.hasShieldBlock) {
                        return fail(Abort.NO_SHIELD_BLOCK);
                    }
                    if (!planIsSafe(c)) {
                        return fail(Abort.UNSAFE_DISTANCE);
                    }
                    charge = c;
                    phase = Phase.POCKET;
                }
            }
            case POCKET -> {
                if (done) {
                    phase = Phase.PLACE_CHARGE;
                }
            }
            case PLACE_CHARGE -> {
                if (!o.hasShieldBlock) {
                    return fail(Abort.NO_SHIELD_BLOCK);
                }
                if (done) {
                    phase = charge == Charge.TNT ? Phase.TRIGGER : Phase.RETREAT;
                }
            }
            case RETREAT -> {
                if (done) {
                    phase = Phase.PLACE_SHIELD;
                }
            }
            case PLACE_SHIELD -> {
                if (!o.hasShieldBlock) {
                    return fail(Abort.NO_SHIELD_BLOCK);
                }
                if (done) {
                    if (charge == Charge.TNT) {
                        if (!standIsSafe(o)) {
                            return fail(Abort.UNSAFE_DISTANCE);
                        }
                        enterSettle();
                    } else {
                        phase = Phase.TRIGGER;
                    }
                }
            }
            case TRIGGER -> {
                // Bed: last check with the measured cover before using it. TNT is lit before the retreat.
                if (charge == Charge.BED && !standIsSafe(o)) {
                    return fail(Abort.UNSAFE_DISTANCE);
                }
                if (done) {
                    if (charge == Charge.TNT) {
                        phase = Phase.RETREAT;
                    } else {
                        enterSettle();
                    }
                }
            }
            case SETTLE -> {
                if (++settle >= config.settleTicks) {
                    cycles++;
                    charge = Charge.NONE;
                    phase = afterBlast(o);
                }
            }
            case PLUG_LAVA -> {
                if (o.exposedLava == 0) {
                    phase = o.debrisVisible > 0 ? Phase.MINE_DEBRIS : Phase.TUNNEL;
                }
            }
            case MINE_DEBRIS -> {
                if (o.exposedLava > 0) {
                    phase = Phase.PLUG_LAVA;
                } else if (o.debrisVisible == 0) {
                    phase = Phase.TUNNEL;
                }
            }
            default -> {
            }
        }
        return phase;
    }

    private void enterSettle() {
        settle = 0;
        phase = Phase.SETTLE;
    }

    private static Phase afterBlast(Observation o) {
        if (o.exposedLava > 0) {
            return Phase.PLUG_LAVA;
        }
        return o.debrisVisible > 0 ? Phase.MINE_DEBRIS : Phase.TUNNEL;
    }

    private boolean standIsSafe(Observation o) {
        return BlastMath.entityDamage(chargePower(), o.distanceToCharge, o.exposure, config.difficulty)
                < config.maxBlastDamage;
    }

    private Charge pickCharge(Observation o) {
        boolean tnt = o.hasTnt && o.hasIgniter;
        if (config.preferBed && o.hasBed) {
            return Charge.BED;
        }
        if (tnt) {
            return Charge.TNT;
        }
        return o.hasBed ? Charge.BED : Charge.NONE;
    }

    private Phase fail(Abort reason) {
        abort = reason;
        phase = Phase.ABORTED;
        return phase;
    }
}
