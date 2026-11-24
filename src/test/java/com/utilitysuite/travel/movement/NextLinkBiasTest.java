package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.travel.movement.MovementPhysicsTest.Grid;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Next-link landing bias (roadmap 2.1): {@link NextLinkBias} ranking, {@link ChainLookahead#findNextLink}, the air
 * brake's choice, chain runs with and without the bias, the brain's wiring and the allocation probes.
 */
class NextLinkBiasTest {

    // ---- ranking -------------------------------------------------------------------------------------------------

    @Test
    void withoutANextLinkTheRankIsTheMargin() {
        NextLinkBias b = new NextLinkBias();
        for (double m = -1.0; m <= 2.0; m += 0.05) {
            assertEquals(m, b.rank(m, 3, 4, 3, 4, 0.2, 0.1), 0.0);
            assertEquals(m, NextLinkBias.rank(null, m, 3, 4, 3, 4, 0.2, 0.1), 0.0);
        }
    }

    @Test
    void belowTheFloorTheRankIsTheMarginAndEveryLandingAtTheFloorOutranksIt() {
        NextLinkBias b = new NextLinkBias();
        b.set(0, 10);
        double worstSafe = Double.MAX_VALUE;
        double bestUnsafe = -Double.MAX_VALUE;
        for (double m = -1.0; m <= 2.0; m += 0.01) {
            for (double x = -6; x <= 6; x += 0.5) {
                for (double v = -0.6; v <= 0.6; v += 0.1) {
                    double r = b.rank(m, x, 0, x, 0, v, -v);
                    if (m >= NextLinkBias.FLOOR) {
                        worstSafe = Math.min(worstSafe, r);
                    } else {
                        assertEquals(m, r, 0.0, "below the floor the margin alone ranks");
                        bestUnsafe = Math.max(bestUnsafe, r);
                    }
                }
            }
        }
        assertTrue(worstSafe > bestUnsafe, "never trades margin below the floor: " + worstSafe + " vs " + bestUnsafe);
    }

    @Test
    void aboveEnoughTheLandingThatFlowsOnWins() {
        NextLinkBias b = new NextLinkBias();
        b.set(0, 10); // next link straight ahead (+z)
        // the middle of a top, at rest, against the far side with speed toward the next link
        double still = b.rank(0.8, 0.5, 5.5, 0.5, 5.5, 0, 0);
        double flowing = b.rank(NextLinkBias.ENOUGH, 0.5, 5.9, 0.5, 5.5, 0, 0.15);
        assertTrue(flowing > still, flowing + " vs " + still);
        // the same speed across the line to the next link counts against it
        double across = b.rank(NextLinkBias.ENOUGH, 0.5, 5.9, 0.5, 5.5, 0.15, 0);
        assertTrue(across < still, across + " vs " + still);
        // between the floor and enough, margin still buys more than a block of distance
        assertTrue(b.rank(NextLinkBias.ENOUGH, 0.5, 5.5, 0.5, 5.5, 0, 0)
                > b.rank(NextLinkBias.ENOUGH - 0.15, 0.5, 6.5, 0.5, 6.5, 0, 0));
    }

    @Test
    void aSharpTurnAtTheLandingGetsNoBias() {
        NextLinkBias b = new NextLinkBias();
        assertTrue(b.offer(0, 9, 0, 0.25, 0, 5), "straight on");
        assertTrue(b.offer(1, 8, 0, 0.25, 0, 5), "18 degrees");
        assertFalse(b.offer(2, 7, 0, 0.25, 0, 5), "45 degrees: the margin order stands");
        assertFalse(b.valid());
        assertFalse(b.offer(0, 9, 0, 0, 0, 5), "no way in");
        assertEquals(90.0, NextLinkBias.turnDeg(1, 0, 0, 0, 0, 3), 1e-9);
    }

    // ---- ChainLookahead.findNextLink -------------------------------------------------------------------------------

    @Test
    void theNextLinkIsTheJumpAfterALinkOrTheEndOfTheJumpABlockSitsOn() {
        Grid g = ChainLookaheadTest.row(2, 9); // one air between single blocks: the path jumps every other one
        ChainLookahead c = new ChainLookahead();
        ChainLookaheadTest.settle(c, ChainLookaheadTest.standing(0.5, 10, 0.5), 0f, g);
        assertTrue(c.ready());
        int on = -1;
        int skipped = -1;
        for (int z = 2; z <= 14; z += 2) {
            boolean isLink = false;
            for (int i = 0; i < c.length(); i++) {
                isLink |= c.linkZ(i) == z;
            }
            if (isLink && on < 0) {
                on = z;
            } else if (!isLink && skipped < 0) {
                skipped = z;
            }
        }
        assertTrue(on > 0, "some block is a link");
        assertTrue(c.findNextLink(0, 9, on));
        assertTrue(c.nextLinkValid());
        assertTrue(c.nextLinkZ() > on && c.nextLinkX() == 0 && c.nextLinkBlockY() == 9, "z " + c.nextLinkZ());
        assertEquals(10.0, c.nextLinkTop(), 1e-9);
        if (skipped > 0) {
            // a block the path jumps over: where the chain goes on from it
            assertTrue(c.findNextLink(0, 9, skipped), "block " + skipped + " on a path jump");
            assertTrue(c.nextLinkZ() > skipped);
        }
        int last = c.length() - 1;
        assertFalse(c.findNextLink(c.linkX(last), c.linkBlockY(last), c.linkZ(last)), "the last link: nothing after");
        assertFalse(c.findNextLink(5, 9, 4), "off the path");
        assertFalse(c.nextLinkValid());
        assertEquals(-1, c.nextLinkIndex());
    }

    // ---- the air brake ---------------------------------------------------------------------------------------------

    private static Grid pillarAndNext() {
        Grid g = new Grid();
        g.fill(0, 19, 5, 0, 19, 5);
        g.fill(0, 19, 8, 0, 19, 8);
        g.fill(-40, -40, -40, 79, -40, 79);
        return g;
    }

    @Test
    void theBrakeKeepsTheFloorAndCarriesSpeedTowardTheNextLink() {
        Grid g = pillarAndNext();
        SupportRegion r = new SupportRegion();
        assertTrue(r.build(g, 0.5, 20.0, 5.5));
        MovementPhysics fast = new MovementPhysics().set(0.5, 21.0, 3.5, 0, 0, 0.35, false);
        FlightController plain = new FlightController();
        assertTrue(plain.plan(fast, 1, 0, 0f, true, true, r, g));
        FlightController biased = new FlightController();
        NextLinkBias nl = new NextLinkBias();
        nl.set(0, 8);
        biased.setNextLinkBias(nl);
        assertTrue(biased.plan(fast, 1, 0, 0f, true, true, r, g));
        assertTrue(biased.bestMargin() >= NextLinkBias.FLOOR, "the floor: " + biased.bestMargin());
        assertTrue(plain.bestMargin() >= biased.bestMargin(), "the plain plan is the deepest stop");
        // fly both plans out: the biased one touches down faster
        double vPlain = flyOut(plain, fast, r, g, null);
        double vBiased = flyOut(biased, fast, r, g, nl);
        assertTrue(vBiased > vPlain + 0.02, "touchdown speed " + vBiased + " vs " + vPlain);
        // without a next link the biased controller is the plain one
        nl.clear();
        assertTrue(plain.plan(fast, 1, 0, 0f, true, true, r, g));
        assertTrue(biased.plan(fast, 1, 0, 0f, true, true, r, g));
        assertEquals(plain.brakeTick(), biased.brakeTick());
        assertEquals(plain.brakeForward(), biased.brakeForward());
        assertEquals(plain.bestMargin(), biased.bestMargin(), 0.0);
    }

    private static double flyOut(FlightController fc, MovementPhysics start, SupportRegion r, Grid g,
                                 NextLinkBias nl) {
        MovementPhysics p = new MovementPhysics().copyFrom(start);
        for (int t = 0; t < 40 && !p.onGround; t++) {
            int f = fc.plan(p, 1, 0, 0f, true, true, r, g) && fc.brakeNow() ? fc.brakeForward() : 1;
            p.tick(f, 0, 0f, f > 0, false, g);
        }
        assertTrue(p.onGround && Math.abs(p.y - 20.0) < 1e-6 && r.margin(p.x, p.z) >= 0.0, "on the top: " + p.z);
        return Math.hypot(p.vx, p.vz);
    }

    // ---- chains ----------------------------------------------------------------------------------------------------

    /** One chain run: per link the touchdown speed, the slowest ground tick and the ground ticks. */
    static final class Run {
        int landed;
        final double[] touchSpeed = new double[8];
        final double[] minSpeed = new double[8];
        final int[] groundTicks = new int[8];
        /** Degrees between the run and the line to the next link on the takeoff tick from a link. */
        final double[] takeoffOff = new double[8];
        /** A ground tick on a middle link slower than 0.05 blocks/tick: a stop. */
        int stops;
        int ticks;
        int links;

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder("landed=" + landed + " stops=" + stops + " ticks=" + ticks);
            for (int k = 1; k <= Math.min(landed, links - 2); k++) {
                sb.append(String.format(" | L%d touch=%.3f min=%.3f ground=%d off=%.1f", k, touchSpeed[k],
                        minSpeed[k], groundTicks[k], takeoffOff[k]));
            }
            return sb.toString();
        }
    }

    /**
     * A user who holds W and sprint, turns the view toward the next link at up to {@code turnRate} degrees a tick on
     * the ground and jumps on the last supported tick. In the air the CARRY air brake ({@link FlightController}, back
     * allowed) plans every tick onto the link being flown to; with {@code bias} it knows the link after that one.
     */
    static Run run(Grid g, int[][] links, boolean bias, float turnRate) {
        FlightController fc = new FlightController();
        NextLinkBias nl = new NextLinkBias();
        fc.setNextLinkBias(nl);
        SupportRegion region = new SupportRegion();
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
        MovementPhysics probe = new MovementPhysics();
        Run r = new Run();
        r.links = links.length;
        int on = 0; // the link the body stands on or last left (0: the run-up)
        float yaw = 0f;
        for (int t = 0; t < 400 && p.y > 15; t++) {
            int to = Math.min(on + 1, links.length - 1);
            int f = 1;
            boolean jump = false;
            if (p.onGround) {
                double dx = links[to][0] + 0.5 - p.x;
                double dz = links[to][2] + 0.5 - p.z;
                float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float d = ((want - yaw) % 360f + 540f) % 360f - 180f;
                yaw += Math.max(-turnRate, Math.min(turnRate, d));
                probe.copyFrom(p).tick(1, 0, yaw, true, false, g);
                jump = !probe.supported(g) && on < links.length - 1;
            } else {
                assertTrue(region.build(g, links[to][0] + 0.5, links[to][1] + 1.0, links[to][2] + 0.5));
                if (bias && to + 1 < links.length) {
                    nl.offer(links[to + 1][0], links[to + 1][2], p.vx, p.vz, links[to][0], links[to][2]);
                } else {
                    nl.clear();
                }
                if (fc.plan(p, 1, 0, yaw, true, true, region, g) && fc.brakeNow()) {
                    f = fc.brakeForward();
                }
            }
            boolean wasGround = p.onGround;
            double bx = p.x;
            double bz = p.z;
            double bvx = p.vx;
            double bvz = p.vz;
            p.tick(f, 0, yaw, f > 0, jump, false, g);
            r.ticks++;
            if (wasGround && !p.onGround && on >= 1) {
                double a = Math.toDegrees(Math.atan2(-(links[on + 1][0] + 0.5 - bx), links[on + 1][2] + 0.5 - bz));
                double v = Math.toDegrees(Math.atan2(-bvx, bvz));
                r.takeoffOff[on] = Math.abs(((a - v) % 360 + 540) % 360 - 180);
            }
            if (p.onGround) {
                int k = on + 1;
                if (k < links.length && Math.abs(p.y - (links[k][1] + 1.0)) < 1e-6
                        && Math.abs(p.x - (links[k][0] + 0.5)) < 0.5 + MovementPhysics.HALF_WIDTH
                        && Math.abs(p.z - (links[k][2] + 0.5)) < 0.5 + MovementPhysics.HALF_WIDTH) {
                    on = k;
                    r.landed = k;
                    r.touchSpeed[k] = Math.hypot(p.vx, p.vz);
                    r.minSpeed[k] = Double.MAX_VALUE;
                    if (k == links.length - 1) {
                        break;
                    }
                }
                if (on >= 1) {
                    r.minSpeed[on] = Math.min(r.minSpeed[on], Math.hypot(p.vx, p.vz));
                    r.groundTicks[on]++;
                }
            }
        }
        for (int k = 1; k <= Math.min(r.landed, links.length - 2); k++) {
            if (r.minSpeed[k] < 0.05) {
                r.stops++;
            }
        }
        return r;
    }

    /** Run-up, link 1 {@code gap1} air out, link 2 {@code gap2} air further and {@code side} across, a platform. */
    static Grid chain(int gap1, int gap2, int side, int[][] linksOut) {
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 1);
        int z1 = 2 + gap1;
        int z2 = z1 + 1 + gap2;
        int z3 = z2 + 3;
        g.fill(0, 19, z1, 0, 19, z1);
        g.fill(side, 19, z2, side, 19, z2);
        g.fill(side - 3, 19, z3, side + 3, 19, z3 + 20);
        g.fill(-40, -40, -40, 79, -40, 79);
        linksOut[0] = new int[] {0, 19, 1};
        linksOut[1] = new int[] {0, 19, z1};
        linksOut[2] = new int[] {side, 19, z2};
        linksOut[3] = new int[] {side, 19, z3};
        return g;
    }

    /**
     * The item's chain: two one-block links and a platform, the second link one block across (an 18-degree turn).
     * The margin order brakes onto the middle of the first link and stops there; with the bias the body keeps moving
     * across it and lands every link in fewer ticks, at every mouse speed.
     */
    @Test
    void aChainWithATurnCarriesOverWithoutAStop() {
        int[][] links = new int[4][];
        Grid g = chain(2, 2, 1, links);
        for (float rate : new float[] {6f, 12f, 30f}) {
            Run off = run(g, links, false, rate);
            Run on = run(g, links, true, rate);
            String what = "rate " + rate + "\n off " + off + "\n on  " + on;
            System.out.println("next-link bias, turn chain: " + what);
            assertEquals(3, off.landed, what);
            assertEquals(3, on.landed, what);
            assertTrue(off.stops >= 1, "the margin order stops on the first link: " + what);
            assertEquals(0, on.stops, what);
            assertTrue(on.touchSpeed[1] > off.touchSpeed[1] + 0.05, what);
            assertTrue(on.ticks < off.ticks, what);
        }
    }

    /**
     * Safety sweep over gaps of one to three air, the second link up to two blocks across, slow and fast mice: the
     * bias never loses a link and never adds a stop, and removes stops overall.
     */
    @Test
    void theSweepNeverLosesALinkOrAddsAStop() {
        int stopsOff = 0;
        int stopsOn = 0;
        int ticksOff = 0;
        int ticksOn = 0;
        int cases = 0;
        for (int gap1 = 1; gap1 <= 3; gap1++) {
            for (int gap2 = 1; gap2 <= 3; gap2++) {
                for (int side = -2; side <= 2; side++) {
                    for (float rate : new float[] {8f, 20f}) {
                        int[][] links = new int[4][];
                        Grid g = chain(gap1, gap2, side, links);
                        Run off = run(g, links, false, rate);
                        Run on = run(g, links, true, rate);
                        String what = "gap " + gap1 + "/" + gap2 + " side " + side + " rate " + rate + "\n off " + off
                                + "\n on  " + on;
                        assertTrue(on.landed >= off.landed, "a link lost: " + what);
                        assertTrue(on.stops <= off.stops, "a stop added: " + what);
                        cases++;
                        stopsOff += off.stops;
                        stopsOn += on.stops;
                        if (on.landed == off.landed && off.landed == 3) {
                            ticksOff += off.ticks;
                            ticksOn += on.ticks;
                        }
                    }
                }
            }
        }
        System.out.printf("next-link bias sweep: %d cases, stops %d -> %d, ticks to the platform %d -> %d%n", cases,
                stopsOff, stopsOn, ticksOff, ticksOn);
        assertTrue(stopsOn < stopsOff, stopsOn + " vs " + stopsOff);
        assertTrue(ticksOn <= ticksOff, ticksOn + " vs " + ticksOff);
    }

    // ---- the brain -------------------------------------------------------------------------------------------------

    /** The brain test's single-block chain: run-up, a pillar at z 5, single blocks at 7..13, a platform from 15. */
    private static Grid singleBlockChain() {
        Grid g = new Grid();
        g.fill(-3, 19, -30, 3, 19, 1);
        g.fill(0, 19, 5, 0, 19, 5);
        for (int z = 7; z <= 13; z += 2) {
            g.fill(0, 19, z, 0, 19, z);
        }
        g.fill(-3, 19, 15, 3, 19, 40);
        g.fill(-40, -40, -40, 79, -40, 79);
        return g;
    }

    /**
     * Runs the brain down the chain, the view six blocks ahead on the course but never past {@code viewEnd} (z), or
     * 40 degrees off it; returns {stalled ground ticks, ticks, ticks with the bias live, reached the platform}.
     */
    private static int[] brainRun(JumpCommit.Mode mode, boolean bias, boolean lookDown, int userF, double viewEnd) {
        Grid g = singleBlockChain();
        MovementAssistBrain b = new MovementAssistBrain();
        ChainLookahead chain = new ChainLookahead();
        b.setChain(chain);
        b.mode = mode;
        b.landingHold = true;
        b.nextLinkBias = bias;
        MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
        int stalled = 0;
        int ticks = 0;
        int biasTicks = 0;
        for (int t = 0; t < 140 && p.y >= 19 && p.z < 15.3; t++) {
            chain.update(p, 0f, g);
            double ahead = Math.min(viewEnd, p.z + 6.0) - p.z;
            b.viewYaw = lookDown ? 0f : 40f;
            b.viewPitch = (float) Math.toDegrees(Math.atan2(p.y + 1.62 - 20.0, Math.max(1.0, ahead)));
            int next = (int) Math.floor(p.z) + 2;
            b.setIntentLook(0.2);
            b.setIntentTarget(0, 19, Math.max(5, Math.min(15, next | 1)), IntentEstimator.SOLE_CONFIDENCE);
            b.decide(p, 0f, true, userF, 0, false, 20f, g);
            biasTicks += b.nextLink().valid() ? 1 : 0;
            int f = b.forward() == MovementAssistBrain.KEEP ? userF : b.forward();
            int s = b.strafe() == MovementAssistBrain.KEEP ? 0 : b.strafe();
            p.tick(f, s, 0f, f > 0, b.jump(), b.sneak(), g);
            ticks++;
            if (p.onGround && Math.abs(p.vz) < 0.05 && p.z > 4) {
                stalled++;
            }
        }
        assertTrue(p.y >= 19, mode + " bias " + bias + ": fell at z " + p.z);
        return new int[] {stalled, ticks, biasTicks, p.z >= 15.3 ? 1 : 0};
    }

    /**
     * The brain hands the next link over while the user goes on down the chain (W, view on the links past the
     * target), and the chain loop is at least as good with it in every mode: it reaches the platform, never stalls
     * more, and never takes longer. Looking off the course, or with W up, the bias is never live.
     */
    @Test
    void theBrainBiasesOnlyWhileGoingOnAndTheChainLoopIsAtLeastAsGood() {
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            int[] off = brainRun(mode, false, true, 1, 40.0);
            int[] on = brainRun(mode, true, true, 1, 40.0);
            String what = mode + ": stalled " + off[0] + " -> " + on[0] + ", ticks " + off[1] + " -> " + on[1]
                    + ", bias ticks " + on[2];
            System.out.println("next-link bias, brain chain: " + what);
            assertEquals(0, off[2], what);
            assertTrue(on[2] > 0, "the bias is live: " + what);
            assertEquals(1, on[3], "reaches the platform: " + what);
            assertTrue(on[0] <= off[0], what);
            assertTrue(on[1] <= off[1], what);
            assertEquals(0, brainRun(mode, true, false, 1, 40.0)[2], mode + ": looking away");
            assertEquals(0, brainRun(mode, true, true, 0, 40.0)[2], mode + ": W up");
        }
    }

    /**
     * The view held on the platform's near edge (the end of the course in view, not the course beyond): the bias is
     * live in the air, but on the last link the landing hold's edge guard does not read "going on" from that view and
     * brakes the faster touchdown to a stop. Still no fall, the platform reached, and never slower.
     */
    @Test
    void aViewOnTheEndOfTheCourseIsNeverWorseThanSlowerToStop() {
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            int[] off = brainRun(mode, false, true, 1, 15.5);
            int[] on = brainRun(mode, true, true, 1, 15.5);
            String what = mode + ": stalled " + off[0] + " -> " + on[0] + ", ticks " + off[1] + " -> " + on[1];
            System.out.println("next-link bias, brain chain, view on the platform edge: " + what);
            assertEquals(1, on[3], "reaches the platform: " + what);
            assertTrue(on[1] <= off[1], what);
        }
    }

    // ---- allocation --------------------------------------------------------------------------------------------

    @Test
    void theBiasedPlannersAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = pillarAndNext();
        SupportRegion r = new SupportRegion();
        assertTrue(r.build(g, 0.5, 20.0, 5.5));
        FlightController fc = new FlightController();
        NextLinkBias nl = new NextLinkBias();
        fc.setNextLinkBias(nl);
        MovementPhysics fast = new MovementPhysics().set(0.5, 21.0, 3.5, 0, 0, 0.35, false);
        ChainLookahead c = new ChainLookahead();
        Grid row = ChainLookaheadTest.row(2, 9);
        ChainLookaheadTest.settle(c, ChainLookaheadTest.standing(0.5, 10, 0.5), 0f, row);
        long bytes = AllocationProbe.measure(200, () -> {
            nl.offer(0, 8, 0, 0.3, 0, 5);
            fc.plan(fast, 1, 0, 0f, true, true, r, g);
            c.findNextLink(0, 9, 6);
            c.findNextLink(0, 9, 4);
        });
        assertEquals(0, bytes, "allocated " + bytes + " bytes");
    }

    @Test
    void decideWithTheBiasLiveAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        Grid g = singleBlockChain();
        for (JumpCommit.Mode mode : JumpCommit.Mode.values()) {
            MovementAssistBrain b = new MovementAssistBrain();
            ChainLookahead chain = new ChainLookahead();
            b.setChain(chain);
            b.mode = mode;
            b.landingHold = true;
            // states along a run down the chain, recorded first
            MovementAssistBrain driver = new MovementAssistBrain();
            ChainLookahead driverChain = new ChainLookahead();
            driver.setChain(driverChain);
            driver.mode = mode;
            driver.landingHold = true;
            MovementPhysics[] states = new MovementPhysics[120];
            MovementPhysics p = new MovementPhysics().set(0.5, 20, -6, 0, -0.0784, 0, true);
            for (int t = 0; t < states.length; t++) {
                states[t] = new MovementPhysics().copyFrom(p);
                driverChain.update(p, 0f, g);
                driver.viewYaw = 0f;
                driver.viewPitch = (float) Math.toDegrees(Math.atan2(p.y + 1.62 - 20.0, 6.0));
                driver.setIntentTarget(0, 19, Math.max(5, Math.min(15, ((int) Math.floor(p.z) + 2) | 1)), 0.9);
                driver.decide(p, 0f, true, 1, 0, false, 20f, g);
                int f = driver.forward() == MovementAssistBrain.KEEP ? 1 : driver.forward();
                p.tick(f, 0, 0f, f > 0, driver.jump(), driver.sneak(), g);
            }
            // a settled chain from the run-up, as in the client: the module updates it every tick
            ChainLookaheadTest.settle(chain, states[30], 0f, g);
            int[] i = {0};
            int[] live = {0};
            long bytes = AllocationProbe.measure(240, () -> {
                MovementPhysics s = states[30 + i[0]++ % 40];
                b.viewYaw = 0f;
                b.viewPitch = (float) Math.toDegrees(Math.atan2(s.y + 1.62 - 20.0, 6.0));
                b.setIntentTarget(0, 19, 5, 0.9);
                b.decide(s, 0f, true, 1, 0, false, 20f, g);
                live[0] += b.nextLink().valid() ? 1 : 0;
            });
            assertTrue(live[0] > 0, mode + ": the bias was live in the probe");
            assertTrue(bytes < 1_024, mode + ": decide allocated " + bytes + " bytes");
        }
    }
}
