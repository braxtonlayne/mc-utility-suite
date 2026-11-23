package com.utilitysuite.core.api;

/**
 * A simulated player body that can be stepped ahead of the game: vanilla walking, sprinting, jumping, gravity, drag,
 * block collision and melee knockback. Engine-free contract so combat planning can predict both fighters without
 * reaching into the movement module. One instance is a mutable scratch body: obtain it once from
 * {@link IBodySimProvider#newBody()}, then {@link #set}/{@link #copyFrom} and {@link #tick} it every game tick.
 * Every method is allocation free. Client thread only.
 */
public interface BodySim {

    /** Walking speed of a plain player: the MOVEMENT_SPEED attribute without effects or the sprint modifier. */
    double BASE_SPEED = 0.1;

    /**
     * Places the body (feet position, velocity in blocks per tick), clears its fall distance and restores the plain
     * movement model ({@link #BASE_SPEED}, no jump boost, not sprinting); {@link #movement} after it seeds a real one.
     */
    BodySim set(double x, double y, double z, double vx, double vy, double vz, boolean onGround);

    /**
     * Seeds the movement model of the body (kept by {@link #copyFrom} and every {@link #tick}, reset by {@link #set}).
     * The defaults (0.1, 0, false) reproduce a body that was only {@link #set}.
     *
     * @param speed     the MOVEMENT_SPEED attribute WITHOUT the sprint modifier (Speed and Slowness included: 0.1 x
     *                  (1 + 0.2 per Speed level) for a plain player); the sprint key adds vanilla's x1.3
     * @param jumpBoost extra jump velocity from Jump Boost ({@code LivingEntity.getJumpBoostPower}: 0.1 per level)
     * @param sprinting the body is sprinting right now (the last tick was a sprinting tick)
     */
    default BodySim movement(double speed, double jumpBoost, boolean sprinting) {
        return this;
    }

    /** Copies the full state of another body from the same provider. */
    BodySim copyFrom(BodySim other);

    /**
     * Advances one game tick.
     *
     * @param forward +1 forward key, -1 back, 0 none
     * @param strafe  +1 left key, -1 right, 0 none
     * @param yawDeg  Minecraft yaw (0 = +Z, 90 = -X)
     * @param sprint  sprinting (only effective while moving forward)
     * @param jump    jump key held
     */
    void tick(int forward, int strafe, float yawDeg, boolean sprint, boolean jump);

    /**
     * Vanilla {@code LivingEntity.knockback}: halves the horizontal velocity and pushes {@code strength} blocks per
     * tick away from the source; on the ground the vertical velocity becomes {@code min(0.4, vy / 2 + strength)}.
     * A plain melee hit is 0.4; a sprint hit adds a second call of 0.5 along the attacker's facing.
     *
     * @param towardSourceX horizontal direction from this body toward the source, x part (any length above 0)
     * @param towardSourceZ the same, z part
     */
    void knockback(double strength, double towardSourceX, double towardSourceZ);

    /** Scales the horizontal velocity (vanilla slows an attacker to 0.6 after a sprint hit). */
    void scaleHorizontal(double factor);

    double x();

    double y();

    double z();

    double vx();

    double vy();

    double vz();

    boolean onGround();

    /** Distance fallen since leaving the ground. */
    double fallDistance();

    /** The last tick bumped into a wall horizontally. */
    boolean horizontalCollision();
}
