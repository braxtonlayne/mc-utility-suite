package com.utilitysuite.travel.movement;

/**
 * Engine-free mirrors of two vanilla 1.21.1 body rules Movement Assist reads every movement tick, from plain values so
 * they are unit-testable and allocate nothing.
 */
final class VanillaBodyRules {

    /** The offset below the feet of {@code Entity.getBlockPosBelowThatAffectsMyMovement} (a float in vanilla). */
    static final float MOVEMENT_BLOCK_OFFSET = 0.500001F;
    /** Forward impulse a sprint start needs on land, and the food level it must exceed. */
    static final float SPRINT_IMPULSE = 0.8f;
    static final int SPRINT_FOOD = 6;

    private VanillaBodyRules() {
    }

    /**
     * Block Y of the block whose friction, speed and jump factors apply to a body with its feet at {@code feetY}
     * ({@code Entity.getBlockPosBelowThatAffectsMyMovement}). With this offset vanilla always takes this Y; X and Z are
     * the supporting block's when the body has one, else the floored position.
     */
    static int movementBlockY(double feetY) {
        return MovementPhysics.floor(feetY - (double) MOVEMENT_BLOCK_OFFSET);
    }

    /**
     * Vanilla's conditions for starting a sprint with the sprint key ({@code LocalPlayer.aiStep} and
     * {@code canStartSprinting}), plus its stop rule for a wall so a started sprint is not undone in the same tick. Being
     * on the ground is not one of them: only the double-tap start needs it. Same rule as the shared input overlay
     * applies to the write itself (a test holds the two together).
     */
    static boolean sprintStart(boolean sprinting, float forwardImpulse, boolean underWater, boolean inWater,
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
}
