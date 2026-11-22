package com.utilitysuite.travel.movement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The replica against numbers derived from the vanilla 1.21.1 formulas (Mojang mappings, NeoForge 21.1.265 sources),
 * not from the replica. {@link Ref} is a minimal open-floor integrator written line by line from:
 * <ul>
 *   <li>{@code LivingEntity.aiStep}: noJumpDelay countdown, the 0.003 velocity floor, the jump
 *       ({@code jumping && onGround && noJumpDelay == 0}, then noJumpDelay = 10; not jumping resets it to 0),
 *       {@code xxa/zza *= 0.98F};</li>
 *   <li>{@code LivingEntity.jumpFromGround}: vy = {@code (double) getJumpPower()} = 0.42F, and when sprinting
 *       {@code (double) -Mth.sin(yRot * (float) (PI / 180)) * 0.2} / {@code Mth.cos(..) * 0.2};</li>
 *   <li>{@code LivingEntity.travel}: f2 = 0.6F (block friction), f3 = onGround ? f2 * 0.91F : 0.91F, then
 *       {@code handleRelativeFrictionAndCalculateMovement} (moveRelative, move), vy = (vy - 0.08) * 0.98F,
 *       horizontal * f3;</li>
 *   <li>{@code LivingEntity.getFrictionInfluencedSpeed}: onGround ? getSpeed() * (0.21600002F / f2^3) :
 *       {@code Player.getFlyingSpeed} (0.025999999F sprinting, else 0.02F); {@code Player.getSpeed} =
 *       (float) MOVEMENT_SPEED attribute: base 0.1F, sprint modifier 0.3F ADD_MULTIPLIED_TOTAL;</li>
 *   <li>{@code Entity.getInputVector}: lengthSqr < 1e-7 is no input, longer than 1 is normalised (divide by the
 *       length), scaled by the float speed, rotated by the {@code Mth} table sin/cos of the float yaw.</li>
 * </ul>
 * The floor is the only collision (no walls): a downward move is clipped at the floor top, vy is zeroed
 * ({@code Block.updateEntityAfterFallOn}) and onGround is set ({@code Entity.move}).
 * <p>
 * Literal cross-checks: the standing-jump apex 1.252203340253729 and the 12-tick airtime, the first walking tick
 * 0.98F * 0.1F, steady walk 0.98 * 0.1 / (1 - 0.546) and sprint 0.98 * 0.13 / (1 - 0.546) per tick.
 */
class VanillaParityTest {

    private static final double FLOOR = 1.0;
    private static final double TIGHT = 1e-12;
    /** The cube path keeps the pre-shape double arithmetic (bit-identical pins); it is within this of vanilla. */
    private static final double CUBE = 1e-5;

    // ---- the vanilla reference -----------------------------------------------------------------------------------

    private static final float[] SIN = new float[65536];

    static {
        for (int i = 0; i < SIN.length; i++) {
            SIN[i] = (float) Math.sin((double) i * Math.PI * 2.0 / 65536.0);
        }
    }

    private static float sin(float v) {
        return SIN[(int) (v * 10430.378F) & 65535];
    }

    private static float cos(float v) {
        return SIN[(int) (v * 10430.378F + 16384.0F) & 65535];
    }

    /** Vanilla player on an endless flat floor (feet at {@link #FLOOR}), open air above. */
    static final class Ref {
        double x = 0.5, y = FLOOR, z = 0.5;
        double vx, vy = -0.08 * (double) 0.98F, vz;
        boolean onGround = true;
        int noJumpDelay;

        void tick(int forward, int strafe, float yaw, boolean sprinting, boolean jumping) {
            if (noJumpDelay > 0) {
                noJumpDelay--;
            }
            if (Math.abs(vx) < 0.003) {
                vx = 0.0;
            }
            if (Math.abs(vy) < 0.003) {
                vy = 0.0;
            }
            if (Math.abs(vz) < 0.003) {
                vz = 0.0;
            }
            if (jumping) {
                if (onGround && noJumpDelay == 0) {
                    vy = (double) 0.42F;
                    if (sprinting) {
                        float f1 = yaw * (float) (Math.PI / 180.0);
                        vx += (double) (-sin(f1)) * 0.2;
                        vz += (double) cos(f1) * 0.2;
                    }
                    noJumpDelay = 10;
                }
            } else {
                noJumpDelay = 0;
            }
            float xxa = (float) strafe;
            float zza = (float) forward;
            xxa *= 0.98F;
            zza *= 0.98F;
            float f2 = 0.6F;
            float f3 = onGround ? f2 * 0.91F : 0.91F;
            float speed = sprinting ? (float) ((double) 0.1F * (1.0 + (double) 0.3F)) : (float) (double) 0.1F;
            float amount = onGround ? speed * (0.21600002F / (f2 * f2 * f2)) : (sprinting ? 0.025999999F : 0.02F);
            double rx = xxa;
            double rz = zza;
            double d0 = rx * rx + rz * rz;
            if (!(d0 < 1.0E-7)) {
                if (d0 > 1.0) {
                    double len = Math.sqrt(d0);
                    rx = rx / len;
                    rz = rz / len;
                }
                rx *= amount;
                rz *= amount;
                float f = sin(yaw * (float) (Math.PI / 180.0));
                float g = cos(yaw * (float) (Math.PI / 180.0));
                vx += rx * (double) g - rz * (double) f;
                vz += rz * (double) g + rx * (double) f;
            }
            double dy = vy;
            if (y + dy < FLOOR) {
                dy = FLOOR - y;
            }
            boolean vertical = dy != vy;
            y += dy;
            x += vx;
            z += vz;
            onGround = vertical && vy < 0.0;
            if (vertical) {
                vy = 0.0;
            }
            vy = (vy - 0.08) * (double) 0.98F;
            vx *= f3;
            vz *= f3;
        }
    }

    // ---- harness ----------------------------------------------------------------------------------------------------

    /** Full path (the in-game path: LevelShapes is shaped): a shaped floor of unit cubes. */
    private static MovementPhysics full() {
        return new MovementPhysics().set(0.5, FLOOR, 0.5, 0, -0.08 * (double) 0.98F, 0, true);
    }

    private static final ShapeGrid SHAPED = new ShapeGrid().fill(-40, 0, -40, 40, 0, 200);

    private static MovementPhysicsTest.Grid cubeGrid() {
        MovementPhysicsTest.Grid g = new MovementPhysicsTest.Grid();
        g.fill(-40, 0, -40, 79, 0, 79);
        return g;
    }

    private static final MovementPhysicsTest.Grid CUBES = cubeGrid();

    /** One scripted tick of input. */
    private record In(int forward, int strafe, float yaw, boolean sprint, boolean jump) {
    }

    /** Steps the reference, the full path and the cube path together; checks every tick. Returns the reference. */
    private static Ref run(In[] script, Ref ref, MovementPhysics fullSim, MovementPhysics cubeSim) {
        for (int t = 0; t < script.length; t++) {
            In in = script[t];
            ref.tick(in.forward, in.strafe, in.yaw, in.sprint, in.jump);
            fullSim.tick(in.forward, in.strafe, in.yaw, in.sprint, in.jump, SHAPED);
            String at = "tick " + (t + 1);
            assertEquals(ref.x, fullSim.x, TIGHT, at + " x");
            assertEquals(ref.y, fullSim.y, TIGHT, at + " y");
            assertEquals(ref.z, fullSim.z, TIGHT, at + " z");
            assertEquals(ref.vx, fullSim.vx, TIGHT, at + " vx");
            assertEquals(ref.vy, fullSim.vy, TIGHT, at + " vy");
            assertEquals(ref.vz, fullSim.vz, TIGHT, at + " vz");
            assertEquals(ref.onGround, fullSim.onGround, at + " onGround");
            if (cubeSim != null) {
                cubeSim.tick(in.forward, in.strafe, in.yaw, in.sprint, in.jump, CUBES);
                assertEquals(ref.x, cubeSim.x, CUBE, at + " cube x");
                assertEquals(ref.y, cubeSim.y, CUBE, at + " cube y");
                assertEquals(ref.z, cubeSim.z, CUBE, at + " cube z");
                assertEquals(ref.onGround, cubeSim.onGround, at + " cube onGround");
            }
        }
        return ref;
    }

    private static In[] repeat(In in, int n) {
        In[] a = new In[n];
        java.util.Arrays.fill(a, in);
        return a;
    }

    private static In[] concat(In[]... parts) {
        int n = 0;
        for (In[] p : parts) {
            n += p.length;
        }
        In[] out = new In[n];
        int o = 0;
        for (In[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    // ---- tests ------------------------------------------------------------------------------------------------------

    /** LivingEntity.jumpFromGround (0.42F) + LivingEntity.travel (gravity 0.08, drag 0.98F): apex and airtime. */
    @Test
    void standingJumpApexAndAirtime() {
        Ref ref = new Ref();
        MovementPhysics p = full();
        MovementPhysics c = full();
        double apex = 0;
        int landed = -1;
        for (int t = 1; t <= 14 && landed < 0; t++) {
            run(new In[] {new In(0, 0, 0f, false, t == 1)}, ref, p, c);
            apex = Math.max(apex, ref.y - FLOOR);
            if (t > 1 && ref.onGround) {
                landed = t;
            }
        }
        assertEquals(1.252203340253729, apex, 1e-9, "vanilla standing-jump apex");
        assertEquals(12, landed, "airtime: back on the ground at the end of tick 12");
        // first tick: y += 0.42F exactly, vy = (0.42F - 0.08) * 0.98F
        Ref one = new Ref();
        one.tick(0, 0, 0f, false, true);
        assertEquals(FLOOR + (double) 0.42F, one.y, 0.0);
        assertEquals(((double) 0.42F - 0.08) * (double) 0.98F, one.vy, 0.0);
    }

    /** LivingEntity.getFrictionInfluencedSpeed with Player.getSpeed 0.1F: per-tick run-up from rest, yaw 0. */
    @Test
    void walkingRunUpToSteadySpeed() {
        Ref ref = run(repeat(new In(1, 0, 0f, false, false), 60), new Ref(), full(), full());
        Ref first = new Ref();
        first.tick(1, 0, 0f, false, false);
        float a = 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F));
        assertEquals(0.5 + (double) 0.98F * a, first.z, 0.0, "first tick: 0.98F * walk accel");
        double v1 = (double) 0.98F * a;
        Ref second = new Ref();
        second.tick(1, 0, 0f, false, false);
        second.tick(1, 0, 0f, false, false);
        assertEquals(0.5 + v1 + (v1 * (double) (0.6F * 0.91F) + v1), second.z, 1e-15, "second tick");
        double steady = (double) 0.98F * a / (1.0 - (double) (0.6F * 0.91F));
        assertEquals(steady, ref.vz / (double) (0.6F * 0.91F), 1e-9, "steady walk speed ~0.21586 per tick");
        assertEquals(0.21586, steady, 1e-5);
    }

    /** Sprint modifier (Player.getSpeed: (float) 0.1F * 1.3F-ish attribute) run-up from rest. */
    @Test
    void sprintingRunUpToSteadySpeed() {
        Ref ref = run(repeat(new In(1, 0, 0f, true, false), 60), new Ref(), full(), full());
        float s = (float) ((double) 0.1F * (1.0 + (double) 0.3F));
        float a = s * (0.21600002F / (0.6F * 0.6F * 0.6F));
        double steady = (double) 0.98F * a / (1.0 - (double) (0.6F * 0.91F));
        assertEquals(steady, ref.vz / (double) (0.6F * 0.91F), 1e-9, "steady sprint speed per tick");
        assertEquals(0.28062, steady, 1e-5);
    }

    /** Sprint jump from steady sprint: jumpFromGround boost along the yaw, then 12 flight ticks of air control. */
    @Test
    void sprintJumpFirstTwelveFlightTicks() {
        In run = new In(1, 0, 0f, true, false);
        In jump = new In(1, 0, 0f, true, true);
        Ref ref = run(concat(repeat(run, 30), new In[] {jump}, repeat(run, 11)), new Ref(), full(), full());
        assertTrue(ref.onGround, "lands at the end of the 12th tick");
        // the jump tick by hand: v0 (steady, after ground drag) + 0.2 boost + ground sprint accel
        Ref h = new Ref();
        for (int t = 0; t < 30; t++) {
            h.tick(1, 0, 0f, true, false);
        }
        double v0 = h.vz;
        double z0 = h.z;
        h.tick(1, 0, 0f, true, true);
        float s = (float) ((double) 0.1F * (1.0 + (double) 0.3F));
        double a = s * (0.21600002F / (0.6F * 0.6F * 0.6F));
        double move = v0 + (double) SIN[16384] * 0.2 + (double) 0.98F * a;
        assertEquals(z0 + move, h.z, 1e-15, "jump tick travel = v + 0.2 + sprint accel");
        // second flight tick: drag of the ground (f3 from the start-of-tick onGround), then air accel 0.025999999F
        double z1 = h.z;
        h.tick(1, 0, 0f, true, false);
        double move2 = move * (double) (0.6F * 0.91F) + (double) 0.98F * (double) 0.025999999F;
        assertEquals(z1 + move2, h.z, 1e-15, "second flight tick: ground drag then air accel");
    }

    /** Not sprinting on the jump tick: jumpFromGround adds no 0.2 and the ground accel is the walking one. */
    @Test
    void sprintOffOnTheJumpTickGivesNoBoost() {
        In run = new In(1, 0, 0f, true, false);
        Ref ref = run(concat(repeat(run, 30), new In[] {new In(1, 0, 0f, false, true)},
                repeat(new In(1, 0, 0f, false, false), 11)), new Ref(), full(), full());
        Ref boosted = new Ref();
        Ref plain = new Ref();
        for (int t = 0; t < 30; t++) {
            boosted.tick(1, 0, 0f, true, false);
            plain.tick(1, 0, 0f, true, false);
        }
        boosted.tick(1, 0, 0f, true, true);
        plain.tick(1, 0, 0f, false, true);
        float a = 0.1F * (0.21600002F / (0.6F * 0.6F * 0.6F));
        double v0 = boosted.vz;
        assertTrue(v0 > plain.vz + 0.1, "boost only when sprinting (0.2 * 0.546 after the ground drag)");
        Ref h = new Ref();
        for (int t = 0; t < 30; t++) {
            h.tick(1, 0, 0f, true, false);
        }
        double vBefore = h.vz;
        double zBefore = h.z;
        h.tick(1, 0, 0f, false, true);
        assertEquals(zBefore + vBefore + (double) 0.98F * a, h.z, 1e-15, "no boost, walking accel");
        assertTrue(ref.onGround);
    }

    /** W released in the air: no input vector (getInputVector returns zero), only the 0.91F air drag. */
    @Test
    void forwardReleasedInTheAir() {
        In run = new In(1, 0, 0f, true, false);
        Ref ref = run(concat(repeat(run, 30), new In[] {new In(1, 0, 0f, true, true)},
                repeat(run, 3), repeat(new In(0, 0, 0f, false, false), 8)), new Ref(), full(), full());
        assertTrue(ref.onGround);
        Ref h = new Ref();
        for (int t = 0; t < 34; t++) {
            h.tick(1, 0, 0f, true, t == 30);
        }
        double v = h.vz;
        double z = h.z;
        h.tick(0, 0, 0f, false, false);
        assertEquals(z + v, h.z, 1e-15, "the move is the carried velocity");
        assertEquals(v * (double) 0.91F, h.vz, 1e-15, "air drag 0.91F");
        assertFalse(h.onGround);
    }

    /**
     * 45-degree strafe: W + A at yaw 45 (input (0.98F, 0.98F) normalised by Vec3.normalize, Mth table sin/cos of a
     * non-axis yaw) while sprinting; the run then jumps.
     */
    @Test
    void fortyFiveDegreeStrafeRun() {
        In strafe = new In(1, 1, 45f, true, false);
        Ref ref = run(concat(repeat(strafe, 25), new In[] {new In(1, 1, 45f, true, true)}, repeat(strafe, 11)),
                new Ref(), full(), full());
        assertTrue(ref.onGround);
        // first tick by hand: normalised input (1/sqrt2 each), sprint accel, table sin/cos of 45 degrees
        Ref h = new Ref();
        h.tick(1, 1, 45f, true, false);
        float s = (float) ((double) 0.1F * (1.0 + (double) 0.3F));
        double a = s * (0.21600002F / (0.6F * 0.6F * 0.6F));
        double c = (double) 0.98F;
        double len = Math.sqrt(c * c + c * c);
        double ix = c / len * a;
        double iz = c / len * a;
        float rad = 45f * (float) (Math.PI / 180.0);
        double f = sin(rad);
        double g = cos(rad);
        assertEquals(0.5 + ix * g - iz * f, h.x, 1e-15);
        assertEquals(0.5 + iz * g + ix * f, h.z, 1e-15);
        // the normalised input has length 1, not 0.98: the first move is the full accel (the 45-degree strafe gain)
        assertEquals(a, Math.hypot(h.x - 0.5, h.z - 0.5), 1e-8, "table sin/cos are floats");
    }

    /** Mth.sin / Mth.cos of the replica equal the table formula, including a non-axis yaw. */
    @Test
    void tableSinCosMatch() {
        for (float deg = -720f; deg <= 720f; deg += 0.37f) {
            float r = deg * (float) (Math.PI / 180.0);
            assertEquals(sin(r), MovementPhysics.mthSin(r), 0.0f);
            assertEquals(cos(r), MovementPhysics.mthCos(r), 0.0f);
        }
    }
}
