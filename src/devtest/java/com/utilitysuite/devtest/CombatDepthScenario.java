package com.utilitysuite.devtest;

import com.utilitysuite.combat.CombatBootstrap;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.FightStage;
import com.utilitysuite.combat.assistant.stages.GapJumpStage;
import com.utilitysuite.combat.assistant.stages.MaceDiveStage;
import com.utilitysuite.combat.assistant.stages.PotionStage;
import com.utilitysuite.combat.tactics.memory.HabitRecorder;
import com.utilitysuite.combat.tactics.memory.OpponentMemory;
import com.utilitysuite.combat.tactics.memory.OpponentMemoryStore;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.input.RotationLimits;
import com.utilitysuite.travel.TravelBootstrap;
import com.utilitysuite.travel.bridge.BridgeAssist;
import com.utilitysuite.travel.movement.MovementAssist;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.skeleton.AbstractSkeleton;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Scenario: the combat, boss and safety features that so far only had unit tests, exercised in the dev world. One
 * check per behaviour: combat potions (drink and a smoothed splash at the feet), the wind-charge mace dive, gap jumps in
 * fights, the post-pop pressure window, projectile owner detection and opponent-habit persistence, Lag Guard over a
 * {@code /tick freeze}, the boss helper's crystal arc / wither skull / dragon-breath overlays, crystal and anchor
 * exposure, the fall-clutch fallback chain, and the elytra and context HUDs. Every section cleans up after itself
 * (modules off, entities killed, effects and inventory cleared, the server unfrozen). Select it alone with
 * {@code -PplaytestOnly=CombatDepth}.
 */
final class CombatDepthScenario {

    private static final String CA = CombatAssistant.ID;
    private static final String[] MODULES = {CA, "boss_helper", "lag_guard", BridgeAssist.ID, "crystal_exposure",
            "elytra_hud", "context_hud", MovementAssist.ID};

    /** Arrows that joined the client level: owner id of the last one (-1 none synced), count, spawn velocity. */
    private static volatile int arrowOwner = -2;
    private static volatile int arrows;
    private static volatile double[] arrowV = new double[3];
    /** Entity id of the last arrow that joined (its velocity is read a tick later: it arrives after the join). */
    private static volatile int arrowId = -1;
    private static boolean listening;

    private CombatDepthScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("9b-combat-depth", "Combat depth: potions, mace dive, gap jumps, pops, habits,"
                + " lag guard, boss overlays, exposure, clutch chain, elytra/context HUD");
        CombatAssistant ca = CombatBootstrap.assistant();
        listen();
        Arena.ensure(s);
        reset(s);
        potions(s, ca);
        totemPop(s, ca);
        maceDive(s, ca);
        gapJump(s, ca);
        habits(s, ca);
        lagGuard(s);
        boss(s);
        exposure(s);
        clutchChain(s);
        huds(s);
        s.always("disarm", () -> {
            Harness.command("tick unfreeze");
            if (TravelBootstrap.bridge().isRunning()) {
                TravelBootstrap.bridge().toggle();
            }
            for (String id : MODULES) {
                H.enable(id, false);
            }
            defaults();
            Harness.command("tp @e[type=minecraft:ender_dragon] " + Arena.cx + " -300 " + Arena.cz);
            Harness.command("tp @e[type=minecraft:wither] " + Arena.cx + " -300 " + Arena.cz);
            Harness.command("kill @e[type=!minecraft:player]");
            Harness.command("effect clear @s");
            Harness.command("clear @s");
            Harness.command("gamerule fall_damage false");
            Harness.command(Arena.fill(-12, 0, 2, 12, 0, 3, "stone"));
            Harness.command(Arena.fill(-13, -6, 1, 13, -5, 4, "air"));
            Harness.command(Arena.fill(-12, 1, -12, 12, 9, 12, "air"));
            Harness.command(Arena.fill(13, 0, -6, 40, 0, 0, "air"));
            Harness.command("effect give @s minecraft:instant_health 1 5 true");
        });
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        return s;
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static void listen() {
        if (listening) {
            return;
        }
        listening = true;
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, e -> {
            if (e.getLevel().isClientSide() && e.getEntity() instanceof AbstractArrow a) {
                Entity o = a.getOwner();
                var v = a.getDeltaMovement();
                arrowV = new double[] {v.x, v.y, v.z};
                arrowOwner = o == null ? -1 : o.getId();
                arrowId = a.getId();
                arrows++;
            }
        });
    }

    /** Combat Assistant settings this scenario touches, back to their defaults. */
    private static void defaults() {
        H.setting(CA, "engage_mode", "REACTIVE");
        H.setting(CA, "combat_potions", false);
        H.setting(CA, "pop_pressure", false);
        H.setting(CA, "mace_dive", false);
        H.setting(CA, "fight_gap_jumps", false);
        H.setting(CA, "tactic_hold_gap", true);
        H.setting(CA, "opponent_memory", false);
        H.setting(MovementAssist.ID, "clutch_fallback_chain", false);
    }

    /** Kill everything, clear inventory and effects, full health, back to the centre. */
    private static void reset(Scenario s) {
        s.cmd("tick unfreeze");
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("effect clear @s");
        s.cmd("clear @s");
        s.cmd("effect give @s minecraft:instant_health 1 5 true");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
    }

    private static String zombie(double dx, double dz, boolean ai, String extra) {
        return "summon minecraft:zombie " + (Arena.cx + dx) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + dz)
                + " {PersistenceRequired:1b,NoAI:" + (ai ? "0b" : "1b") + ",Health:500f,"
                + "attributes:[{id:\"minecraft:max_health\",base:500d}],"
                + "ArmorItems:[{},{},{},{id:\"minecraft:leather_helmet\",count:1}]" + extra + "}";
    }

    /** A private field of {@code o} (or a superclass), or null. */
    static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // try the superclass
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static <T> T stage(Class<T> c) {
        FightStage[] all = (FightStage[]) field(CombatBootstrap.assistant(), "allStages");
        for (FightStage st : all) {
            if (c.isInstance(st)) {
                return c.cast(st);
            }
        }
        throw new IllegalStateException("no stage " + c.getSimpleName());
    }

    private static long clock(CombatAssistant ca) {
        return (Long) field(ca, "tacticClock");
    }

    private static boolean on(String id) {
        IModule m = Services.modules().byId(id).orElse(null);
        return m != null && m.isEnabled();
    }

    /** The user's first hit on the nearest zombie (once it is within {@code within} blocks), then ENGAGED. */
    private static void engage(Scenario s, CombatAssistant ca, String tag, double within) {
        s.waitUpTo(tag + ": zombie visible", 60, () -> CombatScenario.nearestZombie() != null);
        s.waitUpTo(tag + ": zombie close", 20 * 8, () -> {
            Zombie z = CombatScenario.nearestZombie();
            return z != null && z.distanceTo(H.p()) < within;
        });
        s.run(tag + ": first hit", () -> {
            Zombie z = CombatScenario.nearestZombie();
            if (z == null) {
                return;
            }
            H.p().lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, z.getEyePosition());
            H.mc().gameMode.attack(H.p(), z);
            H.p().swing(InteractionHand.MAIN_HAND, H.p().getMainHandItem().getAttackAnimation(), false);
        });
        s.waitUpTo(tag + ": engaged", 40, () -> ca.state() == CombatState.ENGAGED);
    }

    private static void disengage(Scenario s, CombatAssistant ca) {
        s.cmd("kill @e[type=minecraft:zombie]");
        s.waitUpTo("dormant", 100, () -> ca.state() == CombatState.DORMANT);
    }

    // ---- 1. combat potions ------------------------------------------------------------------------------------------

    private static void potions(Scenario s, CombatAssistant ca) {
        PotionStage ps = stage(PotionStage.class);
        int[] before = new int[2];
        float[] hp = new float[3];
        boolean[] sawDrinking = new boolean[1];
        s.cmd("give @s minecraft:diamond_sword");
        s.cmd("give @s minecraft:potion[minecraft:potion_contents={potion:\"minecraft:strong_healing\"}]");
        s.run("potions on", () -> {
            defaults();
            H.setting(CA, "combat_potions", true);
            H.setting(CA, "sustain", true);
            // above the emergency "low health" (8): a drinkable healing potion also counts as the emergency sustain's
            // consumable, which would drink it first below 8; at 10 only the potion stage acts
            H.setting(CA, "potion_heal_health", 12.0);
            H.enable(CA, true);
            before[0] = ps.drinks();
        });
        s.cmd(() -> zombie(0.5, 2.8, false, ""));
        engage(s, ca, "potion", 3.5);
        s.run("hurt", () -> {
            hp[0] = H.p().getHealth();
            Harness.command("damage @s 10 minecraft:generic");
        });
        s.waitTicks(2);
        s.run("hurt done", () -> hp[1] = H.p().getHealth());
        s.waitUpTo("drink the healing potion", 20 * 6, () -> {
            var p = H.p();
            sawDrinking[0] |= p.isUsingItem() && p.getUseItem().is(H.item("minecraft:potion"));
            hp[2] = Math.max(hp[2], p.getHealth());
            return ps.drinks() > before[0] && H.count("minecraft:potion") == 0 && !p.isUsingItem()
                    && p.getInventory().getSelectedSlot() == 0;
        });
        s.waitTicks(4);
        s.run("drink result", () -> {
            var p = H.p();
            boolean weapon = p.getMainHandItem().is(H.item("minecraft:diamond_sword"));
            s.check("potions: drinks a hotbar healing potion when hurt in a fight, weapon back in hand (by the potion"
                            + " stage, or by the emergency sustain if a hit took health under low_health first)",
                    sawDrinking[0] && H.count("minecraft:potion") == 0 && hp[2] > hp[1] + 3 && weapon,
                    Harness.fmt("drinks %d -> %d, potion left %d, saw drinking %s, health %.1f -> %.1f -> max %.1f,"
                                    + " held %s (slot %d), state %s", before[0], ps.drinks(),
                            H.count("minecraft:potion"), sawDrinking[0], hp[0], hp[1], hp[2], p.getMainHandItem(),
                            p.getInventory().getSelectedSlot(), ca.state()));
        });
        // splash variant: only a splash potion in the hotbar
        float[] look = {Float.NaN, 0f, -90f}; // last pitch, largest per-tick change, highest pitch
        s.cmd("clear @s minecraft:glass_bottle");
        s.cmd("give @s minecraft:splash_potion[minecraft:potion_contents={potion:\"minecraft:strong_healing\"}]");
        s.waitTicks(30); // the planner's cooldown after the drink
        s.run("hurt again", () -> {
            before[1] = ps.throwsMade();
            look[0] = H.p().getXRot();
            hp[1] = H.p().getHealth();
            Harness.command("damage @s " + Math.max(1, (int) Math.ceil(hp[1] - 10)) + " minecraft:generic");
        });
        s.waitUpTo("splash at the feet", 20 * 6, () -> {
            float pitch = H.p().getXRot();
            look[1] = Math.max(look[1], Math.abs(pitch - look[0]));
            look[2] = Math.max(look[2], pitch);
            look[0] = pitch;
            return ps.throwsMade() > before[1] && H.p().getInventory().getSelectedSlot() == 0;
        });
        s.waitTicks(20);
        s.run("splash result", () -> {
            var p = H.p();
            double cap = RotationLimits.MAX_DEG_PER_TICK;
            s.check("potions: splash thrown at the feet after a smooth look down (no snap), weapon back",
                    ps.throwsMade() > before[1] && H.count("minecraft:splash_potion") == 0 && look[2] >= 80f
                            && look[1] <= cap * 1.1 + 0.5 && p.getMainHandItem().is(H.item("minecraft:diamond_sword")),
                    Harness.fmt("throws %d -> %d, splash left %d, pitch max %.1f, largest per-tick pitch change"
                                    + " %.1f (cap %.1f), health %.1f -> %.1f, held %s", before[1], ps.throwsMade(),
                            H.count("minecraft:splash_potion"), look[2], look[1], cap, hp[1], p.getHealth(),
                            p.getMainHandItem()));
        });
        disengage(s, ca);
    }

    // ---- 4. totem-pop pressure ------------------------------------------------------------------------------------

    private static void totemPop(Scenario s, CombatAssistant ca) {
        PotionStage ps = stage(PotionStage.class);
        long[] popAt = new long[2];
        boolean[] pressure = new boolean[1];
        int[] zid = {-1};
        reset(s);
        s.cmd("give @s minecraft:diamond_sword");
        s.run("pressure on", () -> {
            H.setting(CA, "combat_potions", true);
            H.setting(CA, "pop_pressure", true);
            H.enable(CA, true);
        });
        s.cmd(() -> zombie(0.5, 2.8, false, ",HandItems:[{},{id:\"minecraft:totem_of_undying\",count:1}]"));
        engage(s, ca, "pop", 3.5);
        s.run("lethal hit", () -> {
            popAt[0] = (Long) field(ca, "lastOpponentPopAt");
            Zombie z = CombatScenario.nearestZombie();
            zid[0] = z == null ? -1 : z.getId();
            Harness.command("damage @e[type=minecraft:zombie,limit=1,sort=nearest] 1000 minecraft:generic");
        });
        s.waitUpTo("totem pops", 40, () -> {
            popAt[1] = (Long) field(ca, "lastOpponentPopAt");
            if (popAt[1] != popAt[0]) {
                pressure[0] = ps.planner().pressureActive(clock(ca));
                return true;
            }
            return false;
        });
        s.run("pop result", () -> {
            int pops = zid[0] < 0 ? -1 : CombatBootstrap.targetHud().pops(zid[0]);
            s.check("totem pop: the target's pop opens the post-pop pressure window (PotionStage.onOpponentPop)",
                    popAt[1] != popAt[0] && pressure[0],
                    "pop clock " + (popAt[0] < -1_000_000 ? "none" : popAt[0]) + " -> " + popAt[1] + ", tactic clock "
                            + clock(ca) + ", pressure window open " + pressure[0] + ", target HUD pops " + pops
                            + ", state " + ca.state());
        });
        disengage(s, ca);
    }

    // ---- 2. mace dive -------------------------------------------------------------------------------------------------

    private static void maceDive(Scenario s, CombatAssistant ca) {
        MaceDiveStage md = stage(MaceDiveStage.class);
        int[] before = new int[3]; // launches, swings, wind charges
        double[] maxRise = new double[1];
        int[] prevSlot = {-1};
        int[] slotBeforeSwing = {-1};
        float[] fallAtSwing = {-1f};
        float[] zhp = {-1f, -1f, 9999f}; // last, before swing, min after
        boolean[] swung = new boolean[1];
        int[] after = new int[1];
        int[] trace = new int[2];
        StringBuilder log = new StringBuilder();
        reset(s);
        s.cmd("give @s minecraft:diamond_sword");
        s.cmd("give @s minecraft:mace");
        s.cmd("give @s minecraft:wind_charge 16");
        s.cmd("effect give @s minecraft:resistance 300 4 true");
        s.run("mace dive on", () -> {
            H.setting(CA, "combat_potions", false);
            H.setting(CA, "pop_pressure", false);
            H.setting(CA, "mace_dive", true);
            H.setting(CA, "wind_charge_launch", false);
            H.setting(CA, "mace_swap", false);
            H.enable(CA, true);
            before[0] = md.launches();
            before[1] = md.swings();
            before[2] = H.count("minecraft:wind_charge");
        });
        s.cmd(() -> zombie(0.5, 4.5, true, ""));
        engage(s, ca, "mace", 2.6);
        s.waitUpTo("launch, dive and smash", 20 * 20, () -> {
            var p = H.p();
            maxRise[0] = Math.max(maxRise[0], p.getY() - (Arena.FLOOR + 1));
            Zombie z = CombatScenario.nearestZombie();
            float zh = z == null ? -1f : z.getHealth();
            if (!swung[0] && md.swings() > before[1]) {
                swung[0] = true;
                slotBeforeSwing[0] = prevSlot[0];
                fallAtSwing[0] = (float) p.fallDistance;
                zhp[1] = zhp[0];
            }
            if (swung[0]) {
                zhp[2] = Math.min(zhp[2], zh);
                after[0]++;
            }
            zhp[0] = zh;
            prevSlot[0] = p.getInventory().getSelectedSlot();
            if (md.launches() > before[0] && trace[1] < 45) {
                trace[1]++;
                double gap = z == null ? -1 : Math.sqrt(Math.pow(Math.max(0, Math.abs(z.getX() - p.getX()) - 0.3), 2)
                        + Math.pow(Math.max(0, Math.abs(z.getZ() - p.getZ()) - 0.3), 2));
                double eyeToBox = z == null ? -1 : Math.sqrt(gap * gap + Math.pow(Math.max(0, Math.max(
                        z.getY() - p.getEyeY(), p.getEyeY() - (z.getY() + z.getBbHeight()))), 2));
                log.append(Harness.fmt("%s:%.1f/%.1f/%.1f/s%d ", md.phase().name(), p.getY() - Arena.FLOOR
                        - 1, p.fallDistance, eyeToBox, p.getInventory().getSelectedSlot()));
            }
            return swung[0] && after[0] > 10;
        });
        s.run("mace trace", () -> s.note("mace dive trace (phase/rise/fall/eye-to-box/slot): " + log));
        Shots.add(s, "combat-mace-dive");
        s.run("mace result", () -> {
            int maceSlot = -1;
            for (int i = 0; i < 9; i++) {
                if (H.p().getInventory().getItem(i).is(H.item("minecraft:mace"))) {
                    maceSlot = i;
                }
            }
            int used = before[2] - H.count("minecraft:wind_charge");
            float dmg = zhp[1] - zhp[2];
            s.check("mace dive: wind charge thrown at the feet launches the player",
                    md.launches() > before[0] && used > 0 && maxRise[0] > 2.0,
                    Harness.fmt("launches %d -> %d, wind charges used %d, highest rise %.2f blocks, phase %s,"
                                    + " state %s, note '%s'", before[0], md.launches(), used, maxRise[0], md.phase(),
                            ca.state(), ca.lastNote()));
            s.check("mace dive: the mace is in hand before the swing and the smash lands from the HEAVY tier",
                    swung[0] && slotBeforeSwing[0] == maceSlot && fallAtSwing[0] >= 3.0f && dmg >= 12f,
                    Harness.fmt("swings %d -> %d, slot before the swing %d (mace %d), fall at swing %.2f (tier 3.05),"
                                    + " zombie health %.1f -> %.1f (-%.1f), planned swing tick %d", before[1],
                            md.swings(), slotBeforeSwing[0], maceSlot, fallAtSwing[0], zhp[1], zhp[2], dmg,
                            md.plannedSwingTick()));
        });
        disengage(s, ca);
    }

    // ---- 3. gap jumps in fights ---------------------------------------------------------------------------------------

    private static void gapJump(Scenario s, CombatAssistant ca) {
        GapJumpStage gs = stage(GapJumpStage.class);
        int[] before = new int[1];
        double[] minY = {Double.MAX_VALUE};
        float[] hp = new float[2];
        String[] reasons = {""};
        reset(s);
        s.cmd("give @s minecraft:diamond_sword");
        // a 2-wide trench across the whole deck (z 2..3), a water basin 6 blocks down so a miss is not lethal
        s.cmd(() -> Arena.fill(-13, -6, 1, 13, -5, 4, "stone"));
        s.cmd(() -> Arena.fill(-12, -5, 2, 12, -5, 3, "water"));
        s.cmd(() -> Arena.fill(-12, 0, 2, 12, 0, 3, "air"));
        s.cmd("gamerule fall_damage true");
        s.cmd(() -> Arena.tp(0.5, -2.5, 0f));
        s.run("gap jumps on", () -> {
            H.setting(CA, "mace_dive", false);
            H.setting(CA, "fight_gap_jumps", true);
            H.setting(CA, "tactic_hold_gap", false);
            H.enable(CA, true);
            before[0] = gs.jumps();
        });
        s.cmd(() -> zombie(0.5, -0.5, false, ""));
        engage(s, ca, "gap", 3.5);
        s.cmd(() -> "tp @e[type=minecraft:zombie] " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " "
                + (Arena.cz + 5.5));
        s.run("gap start", () -> hp[0] = H.p().getHealth());
        s.waitUpTo("jump the gap", 20 * 12, () -> {
            var p = H.p();
            minY[0] = Math.min(minY[0], p.getY());
            String r = gs.lastReason();
            if (!reasons[0].endsWith(r)) {
                reasons[0] += (reasons[0].isEmpty() ? "" : ",") + r;
            }
            return gs.jumps() > before[0] && p.onGround() && p.getZ() > Arena.cz + 4.0;
        });
        s.waitTicks(10);
        Shots.add(s, "combat-gap-jump");
        s.run("gap result", () -> {
            var p = H.p();
            hp[1] = p.getHealth();
            boolean far = p.getZ() > Arena.cz + 4.0 && p.getY() >= Arena.FLOOR + 0.99;
            s.check("gap jumps: an approved jump across a 2-block gap toward the target, no fall damage",
                    gs.jumps() > before[0] && far && minY[0] >= Arena.FLOOR + 0.99 && hp[1] >= hp[0],
                    Harness.fmt("run-ups " + gs.runUps() + ", jumps %d -> %d, player %s, min y %.2f, health %.1f -> %.1f, gate reasons [%s],"
                                    + " state %s, note '%s'", before[0], gs.jumps(), H.posStr(), minY[0] - Arena.FLOOR,
                            hp[0], hp[1], reasons[0], ca.state(), ca.lastNote()));
        });
        s.cmd("gamerule fall_damage false");
        disengage(s, ca);
        s.cmd(() -> Arena.fill(-12, 0, 2, 12, 0, 3, "stone"));
        s.cmd(() -> Arena.fill(-13, -6, 1, 13, -5, 4, "air"));
    }

    // ---- 5. opponent memory: projectile owners and habit persistence ---------------------------------------------------

    private static void habits(Scenario s, CombatAssistant ca) {
        HabitRecorder rec = new HabitRecorder();
        int[] seen = new int[3]; // arrows before, owned by the skeleton, owner mismatches
        int[] skId = {-1};
        long[] t = new long[1];
        int[] lastCount = new int[1];
        int[] pendingArrow = {-1, 0}; // arrow id waiting for its velocity, ticks waited
        double[] shotSpeed = new double[1];
        reset(s);
        s.cmd("effect give @s minecraft:resistance 300 4 true");
        s.run("memory on", () -> {
            H.setting(CA, "fight_gap_jumps", false);
            H.setting(CA, "tactic_hold_gap", true);
            H.setting(CA, "opponent_memory", true);
            H.enable(CA, true);
            seen[0] = arrows;
            lastCount[0] = arrows;
        });
        s.cmd(() -> "summon minecraft:skeleton " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " "
                + (Arena.cz + 9.5) + " {PersistenceRequired:1b,Rotation:[180f,0f],HandItems:[{id:\"minecraft:bow\",count:1},{}],"
                + "ArmorItems:[{},{},{},{id:\"minecraft:leather_helmet\",count:1}]}");
        s.waitUpTo("skeleton shoots", 20 * 14, () -> {
            AbstractSkeleton sk = null;
            for (Entity e : H.mc().level.entitiesForRendering()) {
                if (e instanceof AbstractSkeleton k && k.isAlive()) {
                    sk = k;
                }
            }
            if (sk == null) {
                return false;
            }
            skId[0] = sk.getId();
            var p = H.p();
            rec.tick(t[0]++, sk.onGround(), sk.getY(), HabitRecorder.HELD_BOW, sk.isUsingItem(), false);
            if (pendingArrow[0] >= 0) {
                // the shot's velocity, a tick after the join (as ProjectileHabitFeed reads it)
                Entity a = H.mc().level.getEntity(pendingArrow[0]);
                var v = a == null ? net.minecraft.world.phys.Vec3.ZERO : a.getDeltaMovement();
                if (v.lengthSqr() > 1.0e-8 || ++pendingArrow[1] >= 2) {
                    arrowV = new double[] {v.x, v.y, v.z};
                    shotSpeed[0] = Math.max(shotSpeed[0], v.length());
                    rec.onProjectile(HabitRecorder.PROJ_ARROW, v.x, v.y, v.z, sk.getHealth(), sk.getX(),
                            sk.getZ(), p.getX(), p.getZ());
                    pendingArrow[0] = -1;
                }
            }
            if (arrows != lastCount[0]) {
                lastCount[0] = arrows;
                if (arrowOwner == sk.getId()) {
                    seen[1]++;
                    if (pendingArrow[0] < 0) {
                        pendingArrow[0] = arrowId;
                        pendingArrow[1] = 0;
                    }
                } else {
                    seen[2]++;
                }
            }
            return t[0] > HabitRecorder.MIN_TICKS_FOR_RATES + 20 && seen[1] >= 2;
        });
        s.run("owner result", () -> s.check("opponent memory: projectile owner detection (skeleton arrows carry their"
                        + " shooter's id on the client)", seen[1] >= 1 && seen[2] == 0,
                "arrows joined " + (arrows - seen[0]) + ", owned by the skeleton #" + skId[0] + ": " + seen[1]
                        + ", other owner: " + seen[2] + " (last owner id " + arrowOwner + "), recorder ranged shots "
                        + rec.rangedShots() + Harness.fmt(" (max shot speed %.2f)", shotSpeed[0])
                        + ", assistant's own record (players only) " + ca.habitRecord().rangedShots()));
        s.run("persist habits", () -> {
            Path f = Harness.dir().resolve("opponents-playtest.json");
            Files.deleteIfExists(f);
            String uuid = UUID.nameUUIDFromBytes("utilitysuite-playtest-opponent".getBytes()).toString();
            OpponentMemory m = new OpponentMemory();
            double nan = Double.NaN;
            m.record("playtest", uuid, "PlaytestSkeleton",
                    new OpponentMemory.FightSummary(nan, nan, rec.pearlOutHp(), rec.rangedRate(), rec.maceRate(), nan,
                            nan, nan, false), rec.summary(), System.currentTimeMillis());
            OpponentMemoryStore store = new OpponentMemoryStore(f);
            store.saveDebounced(m);
            store.close(); // flushes off-thread, then stops the writer
            String body = Files.isRegularFile(f) ? Files.readString(f) : "";
            OpponentMemory.Profile back = new OpponentMemoryStore(f).load().lookup("playtest", uuid);
            s.check("opponent memory: the fight's habits persist to the JSON file and load back",
                    body.contains(uuid) && back != null && back.rangedPerMin > 0 && back.fights == 1,
                    f.getFileName() + " " + body.length() + " bytes, loaded back: "
                            + (back == null ? "nothing" : Harness.fmt("fights %d, ranged/min %.1f, draw ticks %.1f,"
                            + " range %.1f", back.fights, back.rangedPerMin, back.drawTicks, back.rangedRange))
                            + "; the assistant itself records only player targets, so a mob fight writes nothing to"
                            + " config/utilitysuite/combat/opponents.json (by design, documented)");
        });
        s.cmd("kill @e[type=minecraft:skeleton]");
        s.cmd("kill @e[type=minecraft:arrow]");
        s.waitUpTo("dormant after skeleton", 100, () -> ca.state() == CombatState.DORMANT);
        s.run("memory off", () -> {
            H.setting(CA, "opponent_memory", false);
            H.enable(CA, false);
        });
    }

    // ---- 6. Lag Guard over /tick freeze (Bridge Assist) ------------------------------------------------------------

    private static int bridgeBlocks() {
        int n = 0;
        for (int dx = 13; dx <= 40; dx++) {
            if (!H.block(Arena.cx + dx, Arena.FLOOR, Arena.cz - 3).isAir()) {
                n++;
            }
        }
        return n;
    }

    private static void lagGuard(Scenario s) {
        int[] blocks = new int[4]; // at freeze, at the end of the frozen hold, after resume
        int[] ticks = {-1, 0, -1}; // detect tick, frozen ticks, recover tick
        boolean[] keys = new boolean[1];
        String[] reason = {""};
        reset(s);
        s.cmd("give @s minecraft:cobblestone 64");
        s.cmd(() -> Arena.tp(11.5, -2.5, -90f));
        s.waitTicks(10);
        s.run("bridge + lag guard on", () -> {
            H.enable("lag_guard", true);
            H.setting(BridgeAssist.ID, "mode", "LINE");
            H.setting(BridgeAssist.ID, "budget", 14);
            H.setting(BridgeAssist.ID, "length", 24);
            H.setting(BridgeAssist.ID, "hotbar_reserve", 0);
            H.enable(BridgeAssist.ID, true);
            TravelBootstrap.bridge().start();
        });
        s.waitUpTo("a few blocks out", 20 * 12, () -> bridgeBlocks() >= 2);
        s.run("freeze", () -> {
            blocks[0] = bridgeBlocks();
            Harness.command("tick freeze");
        });
        s.step("guard trips", t -> {
            if (Services.serverHealth().pauseHighRisk()) {
                ticks[0] = t;
                var g = (com.utilitysuite.tools.packetlab.LagGuard) H.module("lag_guard");
                reason[0] = g.detector().reason() + ", tps " + Harness.fmt("%.1f", g.tps()) + ", since tick "
                        + g.msSinceServerTick() + " ms";
                return true;
            }
            return t >= 20 * 5;
        });
        s.step("hold while frozen", t -> {
            if (t == 0) {
                blocks[1] = bridgeBlocks();
            }
            var o = H.mc().options;
            keys[0] |= o.keyUp.isDown() || o.keyDown.isDown();
            ticks[1] = t;
            return t >= 40 || !Services.serverHealth().pauseHighRisk();
        });
        s.run("hold result", () -> {
            blocks[2] = bridgeBlocks();
            Harness.command("tick unfreeze");
            s.check("lag guard: a /tick freeze is reported as a stall within ~2 s and the bridge holds",
                    ticks[0] >= 0 && ticks[0] <= 50 && blocks[2] == blocks[1] && !keys[0] && ticks[1] >= 40,
                    "tripped after " + ticks[0] + " ticks (" + reason[0] + "), held " + ticks[1] + " ticks; deck blocks"
                            + " at freeze " + blocks[0] + ", at trip " + blocks[1] + ", after the hold " + blocks[2]
                            + ", forward/back pressed while paused " + keys[0] + ", bridge running "
                            + TravelBootstrap.bridge().isRunning());
        });
        s.step("recover", t -> {
            if (!Services.serverHealth().pauseHighRisk()) {
                ticks[2] = t;
                return true;
            }
            return t >= 20 * 10;
        });
        s.waitUpTo("bridge resumes", 20 * 10, () -> bridgeBlocks() > blocks[2]);
        s.run("resume result", () -> {
            blocks[3] = bridgeBlocks();
            s.check("lag guard: healthy again after /tick unfreeze and the bridge resumes",
                    ticks[2] >= 0 && !Services.serverHealth().pauseHighRisk() && blocks[3] > blocks[2],
                    "healthy after " + ticks[2] + " ticks, tps " + Harness.fmt("%.1f", Services.serverHealth().tps())
                            + ", deck blocks " + blocks[2] + " -> " + blocks[3] + ", bridge running "
                            + TravelBootstrap.bridge().isRunning());
            if (TravelBootstrap.bridge().isRunning()) {
                TravelBootstrap.bridge().toggle();
            }
            H.enable(BridgeAssist.ID, false);
            H.enable("lag_guard", false);
        });
        s.cmd(() -> Arena.fill(13, 0, -6, 40, 0, 0, "air"));
    }

    // ---- 7. boss helper overlays ------------------------------------------------------------------------------------

    private static String crystalWord(IModule boss) {
        int slot = ((int[]) field(boss, "markSlot"))[0];
        var marks = Services.visuals().marks();
        return slot >= 0 && marks.occupied(slot) ? marks.stateWord(slot) : "(no mark)";
    }

    private static int tracked(IModule boss, String arrayField) {
        Object[] a = (Object[]) field(boss, arrayField);
        int n = 0;
        for (Object o : a) {
            n += o != null ? 1 : 0;
        }
        return n;
    }

    private static void boss(Scenario s) {
        String[] words = new String[3];
        int[] arc = new int[4];
        reset(s);
        s.cmd("effect give @s minecraft:resistance 300 4 true");
        s.cmd(() -> Arena.tp(0.5, -6.5, 0f));
        s.run("boss helper on", () -> {
            H.setting("boss_helper", "dragon_hints", true);
            H.setting("boss_helper", "wither_hints", true);
            H.setting("boss_helper", "world_overlays", true);
            H.enable("boss_helper", true);
        });
        s.cmd(() -> "summon minecraft:ender_dragon " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 40) + " "
                + (Arena.cz + 30.5) + " {NoAI:1b}");
        // caged crystal: iron bars on the 8 neighbours at its level and the one above, one bar over it
        s.cmd(() -> Arena.fill(-1, 1, 4, 1, 2, 6, "iron_bars"));
        s.cmd(() -> Arena.fill(0, 1, 5, 0, 2, 5, "air"));
        s.cmd(() -> Arena.fill(0, 3, 5, 0, 3, 5, "iron_bars"));
        s.cmd(() -> "summon minecraft:end_crystal " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " "
                + (Arena.cz + 5.5) + " {ShowBottom:0b}");
        s.waitTicks(30);
        IModule boss = H.module("boss_helper");
        s.run("caged", () -> words[0] = crystalWord(boss));
        s.cmd("kill @e[type=minecraft:end_crystal]");
        s.cmd(() -> Arena.fill(-1, 1, 4, 1, 3, 6, "air"));
        s.cmd(() -> Arena.fill(-3, 1, 0, 3, 4, 0, "stone"));
        s.cmd(() -> "summon minecraft:end_crystal " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " "
                + (Arena.cz + 5.5) + " {ShowBottom:0b}");
        s.waitTicks(30);
        s.run("blocked", () -> {
            words[1] = crystalWord(boss);
            arc[0] = (Integer) field(boss, "arcCount");
            arc[1] = (Integer) field(boss, "arcBlockedAt");
        });
        Shots.add(s, "boss-arc-blocked");
        s.cmd(() -> Arena.fill(-3, 1, 0, 3, 4, 0, "air"));
        s.waitTicks(30);
        s.run("clear", () -> {
            words[2] = crystalWord(boss);
            arc[2] = (Integer) field(boss, "arcCount");
            arc[3] = (Integer) field(boss, "arcBlockedAt");
            s.check("boss helper: crystal arc verdicts CAGED (iron bars), BLOCKED (wall), BOW RANGE (clear)",
                    "CAGED".equals(words[0]) && "BLOCKED".equals(words[1]) && arc[1] >= 0
                            && "BOW RANGE".equals(words[2]) && arc[2] >= 2 && arc[3] < 0,
                    "caged '" + words[0] + "', walled '" + words[1] + "' (arc points " + arc[0] + ", blocked at "
                            + arc[1] + "), open '" + words[2] + "' (arc points " + arc[2] + ", blocked at " + arc[3]
                            + "), crystals tracked " + tracked(boss, "crystalRefs") + ", dragon "
                            + (field(boss, "dragon") != null));
        });
        Shots.add(s, "boss-arc-clear");
        s.cmd("kill @e[type=minecraft:end_crystal]");
        s.cmd(() -> "tp @e[type=minecraft:ender_dragon] " + Arena.cx + " -300 " + Arena.cz);
        // wither + an incoming skull
        String[] line = new String[2];
        boolean[] incoming = new boolean[1];
        s.cmd(() -> "summon minecraft:wither " + (Arena.cx + 7.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 6.5)
                + " {NoAI:1b}");
        s.waitTicks(10);
        s.run("wither line", () -> line[0] = com.utilitysuite.combat.boss.BossHelperModule.line());
        s.cmd(() -> "summon minecraft:wither_skull " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 2) + " "
                + (Arena.cz + 5.5) + " {Motion:[0.0,0.0,-0.4],acceleration_power:0.0d}");
        s.waitUpTo("skull incoming", 15, () -> {
            boolean[] in = (boolean[]) field(boss, "skullIncoming");
            for (boolean b : in) {
                incoming[0] |= b;
            }
            String l = com.utilitysuite.combat.boss.BossHelperModule.line();
            if (l != null && l.contains("skull")) {
                line[1] = l;
            }
            return incoming[0] && line[1] != null;
        });
        Shots.add(s, "boss-wither-skull");
        s.run("skull result", () -> s.check("boss helper: wither hint and an incoming skull line computed",
                line[0] != null && line[0].startsWith("Wither") && incoming[0] && line[1] != null,
                "wither line '" + line[0] + "', skull line '" + line[1] + "', skull incoming " + incoming[0]
                        + ", skulls tracked " + tracked(boss, "skulls")));
        s.cmd("kill @e[type=minecraft:wither_skull]");
        s.cmd(() -> "tp @e[type=minecraft:wither] " + Arena.cx + " -300 " + Arena.cz);
        // dragon breath cloud under the player
        s.cmd(() -> "summon minecraft:area_effect_cloud " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " "
                + (Arena.cz - 6.5) + " {Particle:{type:\"minecraft:dragon_breath\"},Radius:3.0f,Duration:600,"
                + "WaitTime:0}");
        s.waitTicks(15);
        Shots.add(s, "boss-breath-ring");
        s.run("breath result", () -> {
            String l = com.utilitysuite.combat.boss.BossHelperModule.line();
            s.check("boss helper: dragon-breath cloud tracked, ring computed and 'inside' flagged",
                    tracked(boss, "clouds") > 0 && "In dragon breath: move out".equals(l),
                    "clouds tracked " + tracked(boss, "clouds") + ", line '" + l + "', module on " + on("boss_helper"));
            H.enable("boss_helper", false);
        });
        s.cmd("kill @e[type=minecraft:area_effect_cloud]");
    }

    // ---- 8a. crystal / anchor exposure (C7) -------------------------------------------------------------------------

    private static void exposure(Scenario s) {
        float[] dmg = new float[3];
        int[] src = new int[3];
        reset(s);
        s.run("exposure on", () -> H.enable("crystal_exposure", true));
        s.cmd(() -> "summon minecraft:end_crystal " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " "
                + (Arena.cz + 4.5) + " {ShowBottom:0b}");
        s.waitTicks(20);
        IModule ex = H.module("crystal_exposure");
        s.run("open", () -> {
            src[0] = (Integer) field(ex, "sources");
            dmg[0] = ((float[]) field(ex, "srcDamage"))[0];
        });
        s.cmd(() -> Arena.fill(-2, 1, 2, 3, 3, 2, "obsidian"));
        s.waitTicks(20);
        Shots.add(s, "exposure-walled");
        s.run("walled", () -> {
            src[1] = (Integer) field(ex, "sources");
            dmg[1] = ((float[]) field(ex, "srcDamage"))[0];
            var cr = (com.utilitysuite.world.blast.CrystalExposureModule) ex;
            s.check("crystal exposure: damage rated for an open crystal, much less behind an obsidian wall",
                    src[0] > 0 && dmg[0] > 4f && dmg[1] < dmg[0] * 0.5f,
                    Harness.fmt("sources %d/%d, open %.1f damage (lethal now %s), walled %.1f damage", src[0], src[1],
                            dmg[0], cr.lethal(), dmg[1]));
        });
        s.cmd("kill @e[type=minecraft:end_crystal]");
        s.cmd(() -> Arena.fill(-2, 1, 2, 3, 3, 2, "air"));
        s.cmd(() -> "setblock " + (Arena.cx + 4) + " " + (Arena.FLOOR + 1) + " " + Arena.cz
                + " minecraft:respawn_anchor[charges=2]");
        s.waitTicks(30);
        s.run("anchor", () -> {
            src[2] = (Integer) field(ex, "sources");
            long[] cells = (long[]) field(ex, "srcCell");
            float[] d = (float[]) field(ex, "srcDamage");
            int anchor = -1;
            for (int i = 0; i < src[2]; i++) {
                if (cells[i] != Long.MIN_VALUE) {
                    anchor = i;
                }
            }
            dmg[2] = anchor >= 0 ? d[anchor] : -1f;
            s.check("crystal exposure: a charged respawn anchor in the overworld is rated", anchor >= 0 && dmg[2] > 0f,
                    Harness.fmt("sources %d, anchor index %d, damage %.1f", src[2], anchor, dmg[2]));
            H.enable("crystal_exposure", false);
        });
        s.cmd(() -> "setblock " + (Arena.cx + 4) + " " + (Arena.FLOOR + 1) + " " + Arena.cz + " minecraft:air");
    }

    // ---- 8b. fall-clutch fallback chain (C8) -----------------------------------------------------------------------

    private static void clutchChain(Scenario s) {
        float[] hp = new float[2];
        int[] before = new int[1];
        int drop = 14;
        reset(s);
        s.cmd("give @s minecraft:twisting_vines 4");
        s.cmd("give @s minecraft:wind_charge 4");
        s.cmd("gamerule fall_damage true");
        s.waitTicks(10);
        s.run("chain on", () -> {
            H.setting(MovementAssist.ID, "water_clutch", true);
            H.setting(MovementAssist.ID, "clutch_blocks", true);
            H.setting(MovementAssist.ID, "clutch_fallback_chain", true);
            H.enable(MovementAssist.ID, true);
            Object clutch = field(TravelBootstrap.movement(), "clutch");
            before[0] = (Integer) field(clutch, "fallbacks");
            hp[0] = H.p().getHealth();
        });
        s.cmd(() -> "tp @s " + (Arena.cx - 9.5) + " " + (Arena.FLOOR + 1 + drop) + " " + (Arena.cz + 9.5) + " 0 0");
        StringBuilder trace = new StringBuilder();
        int[] traced = new int[1];
        s.waitUpTo("fall", 20 * 8, () -> {
            var p = H.p();
            // per tick: height / climbable / client fall / server fall / vine in the landing cell
            if (p.getY() < Arena.FLOOR + 6 && traced[0]++ < 24) {
                var server = H.mc().getSingleplayerServer();
                var sp = server == null ? null : server.getPlayerList().getPlayer(p.getUUID());
                boolean vine = H.mc().level.getBlockState(net.minecraft.core.BlockPos.containing(p.getX(),
                        Arena.FLOOR + 1, p.getZ())).is(net.minecraft.world.level.block.Blocks.TWISTING_VINES);
                trace.append(Harness.fmt("%.2f/%s/%.1f/%.1f/%s ", p.getY() - Arena.FLOOR - 1, p.onClimbable() ? "c" : "-",
                        p.fallDistance, sp == null ? -1f : sp.fallDistance, vine ? "v" : "-"));
            }
            return p.getY() < Arena.FLOOR + drop - 1 && p.onGround() && p.getDeltaMovement().y > -0.1;
        });
        s.waitTicks(30);
        s.run("chain trace", () -> s.note("clutch chain trace (height/climbable/client fall/server fall/vine): "
                + trace));
        s.run("chain result", () -> {
            Object clutch = field(TravelBootstrap.movement(), "clutch");
            int after = (Integer) field(clutch, "fallbacks");
            hp[1] = H.p().getHealth();
            s.check("fall-clutch chain: a " + drop + "-block fall with twisting vines + wind charges (no block or"
                            + " water) uses a fallback and lands unhurt",
                    after > before[0] && hp[1] >= hp[0] && (H.count("minecraft:twisting_vines") < 4
                            || H.count("minecraft:wind_charge") < 4),
                    Harness.fmt("fallbacks %d -> %d, note '%s', health %.1f -> %.1f, y %.2f, vines left %d, wind"
                                    + " charges left %d", before[0], after, TravelBootstrap.movement().clutchNote(),
                            hp[0], hp[1], H.p().getY() - Arena.FLOOR, H.count("minecraft:twisting_vines"),
                            H.count("minecraft:wind_charge")));
            H.setting(MovementAssist.ID, "clutch_fallback_chain", false);
            H.enable(MovementAssist.ID, false);
        });
        s.cmd("gamerule fall_damage false");
        s.cmd(() -> Arena.fill(-11, 1, 8, -8, 3, 11, "air"));
    }

    // ---- 9. elytra HUD and context HUD ------------------------------------------------------------------------------

    private static void huds(Scenario s) {
        boolean[] glide = new boolean[3]; // gliding, wants draw, footprint ring
        String[] seen = {""};
        reset(s);
        s.cmd("item replace entity @s armor.chest with minecraft:elytra");
        s.run("huds on", () -> {
            H.enable("elytra_hud", true);
            H.enable("context_hud", true);
        });
        s.cmd(() -> "tp @s " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 60) + " " + (Arena.cz + 0.5) + " 0 10");
        s.waitTicks(6);
        s.run("start gliding", () -> {
            var p = H.p();
            if (p.tryToStartFallFlying()) {
                p.connection.send(new ServerboundPlayerCommandPacket(p,
                        ServerboundPlayerCommandPacket.Action.START_FALL_FLYING));
            }
        });
        s.waitUpTo("glide", 30, () -> {
            IModule m = H.module("elytra_hud");
            var hud = (com.utilitysuite.core.api.visual.HudElement) m;
            glide[0] |= H.p().isFallFlying();
            glide[1] |= hud.wantsDraw();
            glide[2] |= (Integer) field(m, "ringReady") > 0;
            return glide[0] && glide[1] && glide[2];
        });
        Shots.add(s, "hud-elytra");
        s.run("elytra result", () -> s.check("elytra HUD: draws while gliding (horizon, footprint ring), module on",
                glide[0] && glide[1] && glide[2] && on("elytra_hud"),
                "fall flying " + glide[0] + ", wants draw " + glide[1] + ", footprint headings ready "
                        + field(H.module("elytra_hud"), "ringReady") + ", module on " + on("elytra_hud")));
        s.cmd("item replace entity @s armor.chest with minecraft:air");
        s.cmd(() -> "tp @s " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 22) + " " + (Arena.cz + 0.5) + " 0 0");
        s.waitUpTo("context: falling", 20 * 4, () -> {
            var st = ((com.utilitysuite.ui.holo.HudContextModule) H.module("context_hud")).machine().state();
            if (!seen[0].endsWith(st.name())) {
                seen[0] += (seen[0].isEmpty() ? "" : ">") + st.name();
            }
            return st == com.utilitysuite.core.api.visual.HudContextState.FALLING;
        });
        Shots.add(s, "hud-context-falling");
        s.waitUpTo("landed", 20 * 4, () -> H.p().onGround());
        s.cmd("damage @s 2 minecraft:generic");
        s.waitUpTo("context: combat", 20, () -> {
            var st = ((com.utilitysuite.ui.holo.HudContextModule) H.module("context_hud")).machine().state();
            if (!seen[0].endsWith(st.name())) {
                seen[0] += ">" + st.name();
            }
            return st == com.utilitysuite.core.api.visual.HudContextState.COMBAT;
        });
        Shots.add(s, "hud-context-combat");
        s.run("context result", () -> s.check("context HUD: FALLING in a fall, COMBAT when hurt, module on",
                seen[0].contains("FALLING") && seen[0].contains("COMBAT") && on("context_hud"),
                "states seen " + seen[0] + ", module on " + on("context_hud")));
        s.run("huds off", () -> {
            H.enable("elytra_hud", false);
            H.enable("context_hud", false);
        });
    }
}
