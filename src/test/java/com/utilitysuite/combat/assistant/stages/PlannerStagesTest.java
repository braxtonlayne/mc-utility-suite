package com.utilitysuite.combat.assistant.stages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.tactics.MaceDivePlanner;
import com.utilitysuite.combat.tactics.PotionPlanner;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.travel.movement.MovementPhysics;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The C2/C3/C5 planner stages (potions, mace launch, gap jumps) against a recording fight context. */
class PlannerStagesTest {

    final CombatSettings cs = new CombatSettings();
    final List<String> log = new ArrayList<>();
    int selected = -1;
    float aimPitch = Float.NaN;
    int eatSlot = -1;
    long clock;

    final FightContext ctx = (FightContext) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {FightContext.class}, (proxy, m, args) -> {
                switch (m.getName()) {
                    case "settings":
                        return cs;
                    case "clock":
                        return clock;
                    case "selectHotbar":
                        selected = (Integer) args[0];
                        log.add("select " + args[0]);
                        return null;
                    case "selectWeapon":
                        log.add("weapon");
                        return null;
                    case "aimAt":
                        aimPitch = (Float) args[1];
                        return null;
                    case "tap":
                        log.add("tap " + args[0]);
                        return null;
                    case "tapAttack":
                        log.add("attack");
                        return null;
                    case "startEating":
                        eatSlot = (Integer) args[1];
                        log.add("eat " + args[1]);
                        return null;
                    case "move":
                        log.add("move " + args[0] + " " + args[2]);
                        return null;
                    default:
                        Class<?> r = m.getReturnType();
                        if (r == boolean.class) {
                            return false;
                        }
                        if (r == int.class) {
                            return 0;
                        }
                        if (r == long.class) {
                            return 0L;
                        }
                        if (r == float.class) {
                            return 0f;
                        }
                        if (r == double.class) {
                            return 0.0;
                        }
                        return null;
                }
            });

    static final class Potions implements HealFacts {
        int drinkHeal = -1, drinkRegen = -1, splashHeal = -1, splashRegen = -1;

        @Override public void refresh() { }
        @Override public int hunger() { return 20; }
        @Override public float saturation() { return 5f; }
        @Override public int bestFoodHotbarSlot() { return -1; }
        @Override public int bestFoodNutrition() { return 0; }
        @Override public float bestFoodSaturation() { return 0f; }
        @Override public int bestFoodEatTicks() { return 32; }
        @Override public int splashHealingSlot() { return splashHeal; }
        @Override public int goldenAppleCount() { return 0; }
        @Override public int drinkHealingSlot() { return drinkHeal; }
        @Override public int drinkRegenSlot() { return drinkRegen; }
        @Override public int splashRegenSlot() { return splashRegen; }
    }

    @BeforeEach
    void defaults() {
        cs.totems = false;
        cs.gapples = false;
    }

    /** Player at the origin, target 8 blocks south standing still, full health. */
    static CombatSnapshot snap() {
        CombatSnapshot s = new CombatSnapshot();
        s.px = 0.5;
        s.py = 64;
        s.pz = 0.5;
        s.eyeY = 65.62;
        s.health = 20f;
        s.maxHealth = 20f;
        s.onGround = true;
        s.reach = 3.0;
        s.tx = 0.5;
        s.ty = 64;
        s.tz = 8.5;
        box(s);
        return s;
    }

    static void box(CombatSnapshot s) {
        s.minX = s.tx - 0.3;
        s.maxX = s.tx + 0.3;
        s.minZ = s.tz - 0.3;
        s.maxZ = s.tz + 0.3;
        s.minY = s.ty;
        s.maxY = s.ty + 1.8;
    }

    // ---- potions ---------------------------------------------------------------------------------------------------

    @Test
    void potionsOffByDefault() {
        assertFalse(new CombatSettings().combatPotions);
        CombatSnapshot s = snap();
        s.health = 4f;
        Potions f = new Potions();
        f.drinkHeal = 2;
        assertFalse(new PotionStage(f).step(s, ctx));
        assertTrue(log.isEmpty());
    }

    @Test
    void drinksAHealingPotionThroughTheSustainLayer() {
        cs.combatPotions = true;
        cs.sustain = true;
        Potions f = new Potions();
        f.drinkHeal = 2;
        PotionStage st = new PotionStage(f);
        CombatSnapshot s = snap();
        s.health = 6f;
        assertFalse(st.step(s, ctx));
        assertEquals(2, eatSlot);
        assertEquals(1, st.drinks());
    }

    @Test
    void splashLooksDownSmoothedThenThrowsWithThePotionInHand() {
        cs.combatPotions = true;
        Potions f = new Potions();
        f.splashHeal = 4;
        PotionStage st = new PotionStage(f);
        CombatSnapshot s = snap();
        s.health = 6f;
        s.pitch = 0f;
        assertTrue(st.step(s, ctx));
        assertEquals(PotionPlanner.FEET_PITCH, aimPitch, 1e-3);
        assertEquals(4, selected);
        assertFalse(log.contains("tap USE"));
        // the camera eased down, the potion is in hand
        s.pitch = 85f;
        s.selectedSlot = 4;
        clock++;
        assertTrue(st.step(s, ctx));
        assertTrue(log.contains("tap USE"));
        assertEquals(1, st.throwsMade());
        // weapon back after the throw tick
        log.clear();
        st.step(s, ctx);
        st.step(s, ctx);
        assertTrue(log.contains("weapon"));
    }

    @Test
    void noDrinkWithoutSustainButSplashStillWorks() {
        cs.combatPotions = true;
        cs.sustain = false;
        Potions f = new Potions();
        f.drinkHeal = 2;
        PotionStage st = new PotionStage(f);
        CombatSnapshot s = snap();
        s.health = 6f;
        assertFalse(st.step(s, ctx));
        assertEquals(-1, eatSlot);
    }

    // ---- mace launch ----------------------------------------------------------------------------------------------

    @Test
    void maceLaunchAimsJumpsThrowsThenSwingsWithTheMaceHeld() {
        cs.maceDive = true;
        cs.windCharge = false;
        cs.maceTier = MaceDivePlanner.Tier.SMASH;
        MaceDiveStage st = new MaceDiveStage();
        CombatSnapshot s = snap();
        s.tz = 1.5; // adjacent
        box(s);
        s.maceSlot = 1;
        s.windChargeSlot = 5;
        s.selectedSlot = 0;
        s.pitch = 0f;
        assertTrue(st.step(s, ctx));
        assertEquals(MaceDiveStage.Phase.AIM, st.phase());
        assertEquals(MaceDivePlanner.LAUNCH_PITCH, aimPitch, 1e-3);
        assertEquals(5, selected);
        assertFalse(log.contains("tap JUMP"));
        // camera down, charge in hand: jump + throw together
        s.pitch = 86f;
        s.selectedSlot = 5;
        assertTrue(st.step(s, ctx));
        assertTrue(log.contains("tap JUMP"));
        assertTrue(log.contains("tap USE"));
        assertEquals(MaceDiveStage.Phase.AIR, st.phase());
        // airborne: the mace is selected, never swung on the tick it arrives
        s.onGround = false;
        st.step(s, ctx);
        assertEquals(1, selected);
        s.selectedSlot = 1;
        int swingAt = st.plannedSwingTick();
        assertTrue(swingAt > 0);
        int ticks = 1;
        while (st.phase() == MaceDiveStage.Phase.AIR && ticks < 80) {
            s.fallDistance = ticks > 8 ? 3f : 0f;
            st.step(s, ctx);
            ticks++;
        }
        assertEquals(1, st.swings());
        assertEquals(MaceDiveStage.Phase.AFTER, st.phase());
        // the mace stays for the hit tick, then the weapon comes back
        log.clear();
        st.step(s, ctx);
        assertTrue(log.contains("weapon"));
        assertEquals(MaceDiveStage.Phase.IDLE, st.phase());
    }

    @Test
    void maceLaunchStepsAsideForTheBuiltInWindLaunchAndWhenFar() {
        cs.maceDive = true;
        cs.windCharge = true;
        MaceDiveStage st = new MaceDiveStage();
        CombatSnapshot s = snap();
        s.maceSlot = 1;
        s.windChargeSlot = 5;
        assertFalse(st.step(s, ctx));
        cs.windCharge = false;
        assertFalse(st.step(s, ctx)); // 8 blocks away: no vertical launch
        assertEquals(MaceDiveStage.Phase.IDLE, st.phase());
    }

    // ---- gap jumps ------------------------------------------------------------------------------------------------

    static GapJumpFacts floors(boolean gap) {
        MovementPhysics.Voxels w = (x, y, z) -> x >= -3 && x <= 3 && y == 19
                && ((z >= -30 && z <= 0) || (z >= (gap ? 4 : 1) && z <= 30));
        return new GapJumpFacts() {
            @Override
            public MovementPhysics.Voxels voxels() {
                return w;
            }

            @Override
            public boolean safeWalk() {
                return true;
            }
        };
    }

    static CombatSnapshot atEdge() {
        CombatSnapshot s = snap();
        s.px = 0.5;
        s.py = 20;
        s.pz = 1.31;
        s.pvz = 0.153;
        s.tz = 8.5;
        s.ty = 20;
        box(s);
        return s;
    }

    @Test
    void gapJumpTakesAnApprovedJumpTowardTheTarget() {
        cs.fightGapJumps = true;
        GapJumpStage st = new GapJumpStage(floors(true));
        assertTrue(st.step(atEdge(), ctx), st.lastReason());
        assertTrue(log.contains("tap JUMP"));
        assertTrue(log.contains("move 1 true"));
        assertEquals(1, st.jumps());
    }

    @Test
    void gapJumpOffByDefaultAndNothingOnFlatGround() {
        assertFalse(new CombatSettings().fightGapJumps);
        assertFalse(new GapJumpStage(floors(true)).step(atEdge(), ctx));
        cs.fightGapJumps = true;
        GapJumpStage st = new GapJumpStage(floors(false));
        assertFalse(st.step(atEdge(), ctx));
        assertFalse(log.contains("tap JUMP"));
        assertEquals("validator", st.lastReason());
    }

    @Test
    void gapJumpUsesSimKeyJump() {
        assertEquals("JUMP", SimKey.JUMP.name());
    }
}
