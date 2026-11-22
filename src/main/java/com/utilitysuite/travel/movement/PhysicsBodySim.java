package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.BodySim;

/**
 * {@link BodySim} over {@link MovementPhysics} and a {@link MovementPhysics.Voxels} grid. Engine-free, so tests of
 * any module can build one over a hand-made grid. Allocation free after construction.
 */
public final class PhysicsBodySim implements BodySim {

    private final MovementPhysics body = new MovementPhysics();
    private final MovementPhysics.Voxels voxels;

    public PhysicsBodySim(MovementPhysics.Voxels voxels) {
        this.voxels = voxels;
    }

    /** The wrapped body, for movement code that already works on {@link MovementPhysics}. */
    public MovementPhysics physics() {
        return body;
    }

    @Override
    public BodySim set(double x, double y, double z, double vx, double vy, double vz, boolean onGround) {
        body.set(x, y, z, vx, vy, vz, onGround);
        body.horizontalCollision = false;
        body.crouching = false;
        body.jumpDelay = 0;
        body.sprintBlocked = false;
        body.sprinting = false;
        return this;
    }

    @Override
    public BodySim copyFrom(BodySim other) {
        if (other instanceof PhysicsBodySim p) {
            body.copyFrom(p.body);
        } else {
            body.set(other.x(), other.y(), other.z(), other.vx(), other.vy(), other.vz(), other.onGround());
            body.fallDistance = other.fallDistance();
            body.horizontalCollision = other.horizontalCollision();
        }
        return this;
    }

    @Override
    public void tick(int forward, int strafe, float yawDeg, boolean sprint, boolean jump) {
        body.tick(forward, strafe, yawDeg, sprint, jump, voxels);
    }

    /**
     * {@link #tick} with the sneak key (edge back-off on the same tick, 0.3 input and the crouched hitbox one tick
     * later). Not part of {@link BodySim}: callers that hold a {@code PhysicsBodySim} use it directly.
     */
    public void tick(int forward, int strafe, float yawDeg, boolean sprint, boolean jump, boolean sneak) {
        body.tick(forward, strafe, yawDeg, sprint, jump, sneak, voxels);
    }

    @Override
    public void knockback(double strength, double towardSourceX, double towardSourceZ) {
        if (strength <= 0.0) {
            return;
        }
        double len = Math.sqrt(towardSourceX * towardSourceX + towardSourceZ * towardSourceZ);
        if (len < 1.0e-5) {
            return; // vanilla picks a random direction here; a prediction keeps the velocity instead
        }
        double kx = towardSourceX / len * strength;
        double kz = towardSourceZ / len * strength;
        body.vx = body.vx * 0.5 - kx;
        body.vz = body.vz * 0.5 - kz;
        if (body.onGround) {
            body.vy = Math.min(0.4, body.vy * 0.5 + strength);
        }
    }

    @Override
    public void scaleHorizontal(double factor) {
        body.vx *= factor;
        body.vz *= factor;
    }

    @Override
    public double x() {
        return body.x;
    }

    @Override
    public double y() {
        return body.y;
    }

    @Override
    public double z() {
        return body.z;
    }

    @Override
    public double vx() {
        return body.vx;
    }

    @Override
    public double vy() {
        return body.vy;
    }

    @Override
    public double vz() {
        return body.vz;
    }

    @Override
    public boolean onGround() {
        return body.onGround;
    }

    @Override
    public double fallDistance() {
        return body.fallDistance;
    }

    @Override
    public boolean horizontalCollision() {
        return body.horizontalCollision;
    }
}
