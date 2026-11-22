package com.utilitysuite.combat.tactics;

import com.utilitysuite.core.api.BodySim;
import com.utilitysuite.travel.movement.MovementPhysics;
import com.utilitysuite.travel.movement.PhysicsBodySim;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the vanilla melee knockback numbers the hit-trade solver relies on. */
class KnockbackSimTest {

    static final int FLOOR_Y = 64;
    static final MovementPhysics.Voxels FLAT = (x, y, z) -> y < FLOOR_Y;

    static BodySim standing(double x, double z) {
        return new PhysicsBodySim(FLAT).set(x, FLOOR_Y, z, 0, 0, 0, true);
    }

    @Test
    void plainHitOnStandingBody() {
        BodySim victim = standing(0.5, 0.5);
        // attacker to the north (-Z): toward source = (0, -1)
        victim.knockback(0.4, 0.0, -2.5);
        assertEquals(0.0, victim.vx(), 1e-12);
        assertEquals(0.4, victim.vz(), 1e-12);
        assertEquals(0.4, Math.hypot(victim.vx(), victim.vz()), 1e-12);
        assertEquals(0.4, victim.vy(), 1e-12);
    }

    @Test
    void plainHitDiagonalIsNormalised() {
        BodySim victim = standing(0.5, 0.5);
        victim.knockback(0.4, 3.0, 4.0);
        assertEquals(-0.4 * 0.6, victim.vx(), 1e-12);
        assertEquals(-0.4 * 0.8, victim.vz(), 1e-12);
    }

    @Test
    void sprintHitAddsHalfAlongAttackerFacingAfterHalving() {
        BodySim victim = standing(0.5, 0.5);
        // attacker north of the victim facing +Z (yaw 0): sin = 0, -cos = -1
        victim.knockback(0.4, 0.0, -1.0);
        double yaw = Math.toRadians(0.0);
        victim.knockback(0.5, Math.sin(yaw), -Math.cos(yaw));
        assertEquals(0.0, victim.vx(), 1e-12);
        assertEquals(0.4 * 0.5 + 0.5, victim.vz(), 1e-12);
        assertEquals(0.4, victim.vy(), 1e-12); // min(0.4, 0.2 + 0.5)
    }

    @Test
    void sprintHitFacingEastPushesEastward() {
        BodySim victim = standing(0.5, 0.5);
        double yaw = Math.toRadians(-90.0); // yaw -90 faces +X
        victim.knockback(0.5, Math.sin(yaw), -Math.cos(yaw));
        assertEquals(0.5, victim.vx(), 1e-12);
        assertEquals(0.0, victim.vz(), 1e-12);
    }

    @Test
    void airborneVictimKeepsVerticalVelocity() {
        BodySim victim = new PhysicsBodySim(FLAT).set(0.5, FLOOR_Y + 2, 0.5, 0, -0.2, 0, false);
        victim.knockback(0.4, 1.0, 0.0);
        assertEquals(-0.2, victim.vy(), 1e-12);
        assertEquals(-0.4, victim.vx(), 1e-12);
    }

    @Test
    void knockedBackBodyTravelsPlausibleDistance() {
        BodySim victim = standing(0.5, 0.5);
        victim.knockback(0.4, 0.0, -1.0);
        for (int i = 0; i < 10; i++) {
            victim.tick(0, 0, 0f, false, false);
        }
        double plain = victim.z() - 0.5;
        assertTrue(plain > 0.6 && plain < 2.0, "plain hit moved " + plain);
        for (int i = 0; i < 4 && !victim.onGround(); i++) {
            victim.tick(0, 0, 0f, false, false);
        }
        assertTrue(victim.onGround(), "the 0.4 hop lands again within 14 ticks");

        BodySim sprintHit = standing(0.5, 0.5);
        sprintHit.knockback(0.4, 0.0, -1.0);
        sprintHit.knockback(0.5, 0.0, -1.0);
        for (int i = 0; i < 10; i++) {
            sprintHit.tick(0, 0, 0f, false, false);
        }
        double far = sprintHit.z() - 0.5;
        assertTrue(far > plain + 0.5 && far < 4.0, "sprint hit moved " + far + " vs plain " + plain);
    }

    @Test
    void knockbackWithZeroDirectionKeepsVelocity() {
        BodySim victim = new PhysicsBodySim(FLAT).set(0.5, FLOOR_Y, 0.5, 0.1, 0, 0, true);
        victim.knockback(0.4, 0.0, 0.0);
        assertEquals(0.1, victim.vx(), 1e-12);
    }
}
