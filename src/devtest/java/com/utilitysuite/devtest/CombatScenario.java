package com.utilitysuite.devtest;

import com.utilitysuite.combat.CombatBootstrap;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.coach.FightCoachHud;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.Services;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Zombie;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Scenario 9: Combat Assistant engage/disengage, fall damage stays dormant, ally persistence, Fight Coach. */
final class CombatScenario {

    private CombatScenario() {
    }

    static Zombie nearestZombie() {
        Zombie best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : H.mc().level.entitiesForRendering()) {
            if (e instanceof Zombie z && z.isAlive()) {
                double d = z.distanceToSqr(H.p());
                if (d < bd) {
                    bd = d;
                    best = z;
                }
            }
        }
        return best;
    }

    static String holder() {
        return Services.arbiter().holder(ControlChannel.MOVEMENT).map(ControlLease::moduleId).orElse("none");
    }

    static Scenario build() {
        Scenario s = new Scenario("9-combat", "Combat: engage/disengage, fall damage, allies, coach");
        CombatAssistant ca = CombatBootstrap.assistant();
        Zombie[] target = new Zombie[1];
        float[] hp = new float[1];
        boolean[] coachDrew = new boolean[1];
        Arena.ensure(s);
        s.cmd("kill @e[type=minecraft:item]"); // drops left by earlier runs would land in the selected slot
        s.cmd("clear @s");
        s.cmd("give @s minecraft:diamond_sword");
        s.cmd("effect give @s minecraft:resistance 120 4 true");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        s.run("arm", () -> {
            H.enable(CombatAssistant.ID, true);
            H.enable("fight_coach", true);
            s.check("combat assistant armed and DORMANT", ca.isEnabled() && ca.state() == CombatState.DORMANT,
                    "enabled=" + ca.isEnabled() + " state=" + ca.state());
        });
        s.cmd(() -> "summon minecraft:zombie " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 3.5)
                + " {equipment:{head:{id:\"minecraft:leather_helmet\",count:1}},PersistenceRequired:1b}");
        s.waitFor("zombie visible", 60, () -> nearestZombie() != null);
        s.waitUpTo("zombie close", 60, () -> nearestZombie().distanceTo(H.p()) < 2.8);
        s.run("user's first hit", () -> {
            target[0] = nearestZombie();
            H.p().lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    target[0].getEyePosition());
            H.mc().gameMode.attack(H.p(), target[0]);
            H.p().swing(InteractionHand.MAIN_HAND, H.p().getMainHandItem().getAttackAnimation(), false);
            s.note("hit zombie #" + target[0].getId() + " at distance " + Harness.fmt("%.2f",
                    target[0].distanceTo(H.p())));
        });
        s.waitFor("ENGAGED", 40, () -> ca.state() == CombatState.ENGAGED);
        s.run("engaged", () -> s.check("first hit -> ENGAGED with a lease", ca.state() == CombatState.ENGAGED
                && "combat_assistant".equals(holder()), "state=" + ca.state() + " movement holder=" + holder()
                + " target=" + ca.targetId()));
        s.step("fight", t -> {
            if (!coachDrew[0] && t % 5 == 0) {
                // the coach draws through the STEM HUD host (HudElement.draw), not GuiGraphics: run its logic half and
                // ask whether it wants to draw (or, if it still drew directly, count that)
                FightCoachHud hud = new FightCoachHud(CombatBootstrap.coach());
                CountingGraphics g = CountingGraphics.render(hud);
                coachDrew[0] = g.calls() > 0 || hud.wantsDraw();
            }
            if (t == 1) {
                Shots.clearChat();
            }
            if (t == 6) {
                Shots.take(s, "combat-engaged");
            }
            if (!target[0].isAlive() || target[0].isRemoved()) {
                return true;
            }
            if (t > 20 * 40) {
                throw new Scenario.StepTimeout("zombie still alive after 40 s (hp " + target[0].getHealth()
                        + ", state " + ca.state() + ", swings " + ca.swings() + ", note '" + ca.lastNote() + "')");
            }
            return false;
        });
        s.waitUpTo("DORMANT", 100, () -> ca.state() == CombatState.DORMANT);
        s.run("after kill", () -> {
            s.check("zombie dies -> DORMANT, lease released", ca.state() == CombatState.DORMANT
                            && !"combat_assistant".equals(holder()),
                    "state=" + ca.state() + " holder=" + holder() + " swings=" + ca.swings() + " lastDisengage="
                            + ca.lastDisengageReason() + " engagements=" + ca.engagements());
            s.check("Fight Coach HUD renders during the fight", coachDrew[0], "drew=" + coachDrew[0]);
            hp[0] = H.p().getHealth();
        });
        s.cmd("effect clear @s minecraft:resistance");
        s.cmd("tp @s ~ ~10 ~");
        s.waitTicks(60);
        s.run("fall damage", () -> {
            float now = H.p().getHealth();
            s.check("fall damage keeps the assistant DORMANT", ca.state() == CombatState.DORMANT,
                    Harness.fmt("health %.1f -> %.1f, state=%s, note '%s'", hp[0], now, ca.state(), ca.lastNote()));
        });
        s.run("ally add", () -> {
            UUID u = UUID.nameUUIDFromBytes("utilitysuite-playtest-ally".getBytes());
            boolean added = CombatBootstrap.allies().togglePlayer(u, "PlaytestAlly");
            s.check("ally entry added for a fake UUID", added && CombatBootstrap.allies().roster().get(u) != null,
                    "added=" + added + " entries=" + CombatBootstrap.allies().roster().entries().size());
        });
        s.waitTicks(40);
        s.run("ally persisted", () -> {
            Path f = CombatBootstrap.allies().store().fileFor(Scenarios.PROFILE_ID);
            UUID u = UUID.nameUUIDFromBytes("utilitysuite-playtest-ally".getBytes());
            String body = Files.isRegularFile(f) ? Files.readString(f) : "";
            s.check("ally roster persisted to disk", body.contains(u.toString()), f + (body.isEmpty() ? " missing"
                    : " (" + body.length() + " bytes)"));
            CombatBootstrap.allies().togglePlayer(u, "PlaytestAlly");
        });
        proactive(s, ca);
        gapAmbush(s, ca);
        foodObjective(s, ca);
        autopilotReflexes(s, ca);
        s.always("disarm", () -> {
            com.utilitysuite.core.CoreBootstrap.setAutopilot(false);
            H.setting(CombatAssistant.ID, "engage_mode", "REACTIVE");
            Harness.command(Arena.fill(-6, 0, 4, -5, 0, 10, "stone"));
            Harness.command("kill @e[type=minecraft:cow]");
            H.enable(CombatAssistant.ID, false);
            H.enable("fight_coach", false);
            Harness.command("kill @e[type=minecraft:zombie]");
            Harness.command("effect give @s minecraft:instant_health 1 5 true");
        });
        return s;
    }

    /**
     * Autopilot lane: Combat Assistant off, the autopilot on with its defaults. A skeleton in front must get the
     * offhand shield raised, and a lit creeper next to the player must make it back off. Whatever fires here can only
     * have come through the autopilot. The creeper has explosion radius 0 so the arena survives.
     */
    private static void autopilotReflexes(Scenario s, CombatAssistant ca) {
        var rx = CombatBootstrap.reflexes();
        int[] before = new int[3];
        double[] dist = new double[2];
        boolean[] sawRaised = new boolean[1];
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("effect give @s minecraft:instant_health 1 5 true");
        s.cmd("effect give @s minecraft:resistance 120 4 true");
        s.cmd("clear @s");
        s.cmd("item replace entity @s weapon.offhand with minecraft:shield");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        s.run("autopilot only", () -> {
            H.enable(CombatAssistant.ID, false);
            H.enable(com.utilitysuite.combat.reflex.CombatReflexes.ID, true);
            com.utilitysuite.core.CoreBootstrap.setAutopilot(true);
            before[0] = rx.shieldRaises();
            before[1] = rx.escapes();
            before[2] = rx.dodges();
            s.check("autopilot lane armed: assistant off, autopilot on, shield in the offhand",
                    !ca.isEnabled() && com.utilitysuite.core.CoreBootstrap.autopilot().isOn()
                            && H.p().getOffhandItem().is(net.minecraft.world.item.Items.SHIELD),
                    "assistant=" + ca.isEnabled() + " offhand=" + H.p().getOffhandItem());
        });
        s.cmd(() -> "summon minecraft:skeleton " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 9.5)
                + " {PersistenceRequired:1b,Rotation:[180f,0f]}");
        s.waitUpTo("shield raised against the skeleton", 20 * 12, () -> {
            sawRaised[0] |= H.p().isBlocking() || H.p().isUsingItem();
            return rx.shieldRaises() > before[0] && sawRaised[0];
        });
        Shots.add(s, "autopilot-shield");
        s.run("shield result", () -> s.check("autopilot raises the offhand shield against a skeleton (assistant off)",
                rx.shieldRaises() > before[0],
                "raises " + before[0] + " -> " + rx.shieldRaises() + ", dodges " + before[2] + " -> " + rx.dodges()
                        + ", saw using item=" + sawRaised[0] + ", hp=" + Harness.fmt("%.1f", H.p().getHealth())));
        s.cmd("kill @e[type=minecraft:skeleton]");
        s.cmd("kill @e[type=minecraft:arrow]");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        s.waitTicks(20);
        s.cmd(() -> "summon minecraft:creeper " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 2.5)
                + " {NoAI:1b,PersistenceRequired:1b,ignited:1b,Fuse:50,ExplosionRadius:0b}");
        s.run("creeper placed", () -> dist[0] = H.p().position().distanceTo(
                new net.minecraft.world.phys.Vec3(Arena.cx + 0.5, Arena.FLOOR + 1, Arena.cz + 2.5)));
        s.waitUpTo("back off the lit creeper", 45, () -> rx.escapes() > before[1]);
        s.waitTicks(30);
        s.run("escape result", () -> {
            dist[1] = H.p().position().distanceTo(
                    new net.minecraft.world.phys.Vec3(Arena.cx + 0.5, Arena.FLOOR + 1, Arena.cz + 2.5));
            s.check("autopilot backs off a lit creeper (assistant off)",
                    rx.escapes() > before[1] && dist[1] > dist[0] + 1.5,
                    Harness.fmt("escapes %d -> %d, distance %.2f -> %.2f", before[1], rx.escapes(), dist[0],
                            dist[1]));
        });
        s.cmd("kill @e[type=minecraft:creeper]");
        s.run("autopilot off", () -> com.utilitysuite.core.CoreBootstrap.setAutopilot(false));
    }

    private static String idleZombie(double dx, double dy, double dz, String extra) {
        return "summon minecraft:zombie " + (Arena.cx + dx) + " " + (Arena.FLOOR + dy) + " " + (Arena.cz + dz)
                + " {NoAI:1b,PersistenceRequired:1b,equipment:{head:{id:\"minecraft:leather_helmet\",count:1}}"
                + extra + "}";
    }

    /** Proactive mode engages an idle zombie that never attacked and was never hit. */
    private static void proactive(Scenario s, CombatAssistant ca) {
        int[] before = new int[1];
        s.cmd("kill @e[type=minecraft:zombie]");
        s.cmd("effect give @s minecraft:instant_health 1 5 true");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        s.run("proactive on", () -> {
            H.setting(CombatAssistant.ID, "engage_mode", "PROACTIVE");
            H.setting(CombatAssistant.ID, "tactic_hold_gap", true);
            before[0] = ca.proactiveEngagements();
        });
        s.cmd(() -> idleZombie(0.5, 1, 5.5, ""));
        s.waitUpTo("proactive engage", 60, () -> ca.state() == CombatState.ENGAGED);
        s.run("proactive engaged", () -> s.check("proactive mode engages an idle hostile on its own",
                ca.state() == CombatState.ENGAGED && ca.proactiveEngagements() > before[0],
                "state=" + ca.state() + " proactive engagements " + before[0] + " -> " + ca.proactiveEngagements()
                        + ", note '" + ca.lastNote() + "'"));
        Shots.add(s, "combat-proactive");
        s.waitUpTo("idle zombie dies", 20 * 30, () -> nearestZombie() == null);
        s.waitUpTo("DORMANT after proactive kill", 100, () -> ca.state() == CombatState.DORMANT);
    }

    /**
     * Gap ambush: a 2-wide ravine (the sky platform, so the drop is ~150 blocks) between the player and an idle zombie.
     * The assistant must walk up to the edge and wait there without falling; then a zombie hanging over the pit in
     * reach (the "chaser mid-jump") must get hit at once.
     */
    private static void gapAmbush(Scenario s, CombatAssistant ca) {
        int[] swingsBefore = new int[1];
        boolean[] sawHold = new boolean[1];
        double[] minY = {Double.MAX_VALUE};
        s.cmd(() -> Arena.fill(-6, 0, 4, -5, 0, 10, "air"));
        s.cmd(() -> Arena.tp(-9.5, 7.5, -90f));
        s.waitTicks(10);
        s.cmd(() -> idleZombie(-2.5, 1, 7.5, ""));
        s.waitUpTo("engage across the gap", 60, () -> ca.state() == CombatState.ENGAGED);
        s.waitUpTo("hold at the edge", 20 * 6, () -> {
            sawHold[0] |= ca.tactic() == com.utilitysuite.combat.assistant.TacticsPlanner.Tactic.HOLD_GAP;
            minY[0] = Math.min(minY[0], H.p().getY());
            return sawHold[0] && H.p().getDeltaMovement().horizontalDistanceSqr() < 1.0e-4;
        });
        s.waitTicks(20);
        s.run("gap hold result", () -> {
            minY[0] = Math.min(minY[0], H.p().getY());
            double edgeGap = (Arena.cx - 6) - H.p().getX(); // distance from the player's centre to the near edge
            s.check("gap ambush: holds at the near edge instead of chasing into the ravine",
                    sawHold[0] && minY[0] >= Arena.FLOOR + 0.99 && edgeGap > 0.3 && edgeGap < 1.8,
                    Harness.fmt("tactic=%s seen=%s, player %s, %.2f blocks short of the edge, min y %.2f, note '%s'",
                            ca.tactic(), sawHold[0], H.posStr(), edgeGap, minY[0], ca.lastNote()));
            swingsBefore[0] = ca.tacticSwings();
        });
        Shots.add(s, "combat-gap-hold");
        // the chaser "jumps": a zombie hangs over the pit right in front of the player; the first one is removed
        s.cmd(() -> idleZombie(-5.0, 1.4, 7.5, ",NoGravity:1b,Tags:[\"jumper\"]"));
        s.cmd("kill @e[type=minecraft:zombie,tag=!jumper]");
        s.waitUpTo("hit the jumper over the pit", 20 * 5, () -> ca.tacticSwings() > swingsBefore[0]);
        s.run("mid-jump hit", () -> s.check("gap ambush: hits a target that is over the drop",
                ca.tacticSwings() > swingsBefore[0], "tactic swings " + swingsBefore[0] + " -> " + ca.tacticSwings()
                        + ", target " + ca.targetId() + ", state " + ca.state() + ", note '" + ca.lastNote() + "'"));
        Shots.add(s, "combat-gap-hit");
        s.cmd("kill @e[type=minecraft:zombie]");
        s.waitUpTo("DORMANT after gap", 100, () -> ca.state() == CombatState.DORMANT);
        s.cmd(() -> Arena.fill(-6, 0, 4, -5, 0, 10, "stone"));
    }

    /** No food anywhere: the assistant hunts a nearby cow and picks up the beef. */
    private static void foodObjective(Scenario s, CombatAssistant ca) {
        int[] killsBefore = new int[1];
        s.cmd("clear @s");
        s.cmd("give @s minecraft:diamond_sword");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        s.run("food objective on", () -> {
            H.setting(CombatAssistant.ID, "food_objective", true);
            killsBefore[0] = ca.foodKills();
        });
        s.cmd(() -> "summon minecraft:cow " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 6.5)
                + " {NoAI:1b}");
        s.waitUpTo("hunt the cow", 20 * 10, () -> ca.onFoodObjective());
        Shots.add(s, "combat-food-hunt");
        s.waitUpTo("cow killed and beef picked up", 20 * 30,
                () -> ca.foodKills() > killsBefore[0] && ca.state() == CombatState.DORMANT);
        s.run("food result", () -> {
            int beef = H.count("minecraft:beef");
            s.check("food objective: hunts a cow when there is no food and picks up the beef",
                    ca.foodKills() > killsBefore[0] && beef > 0,
                    "food kills " + killsBefore[0] + " -> " + ca.foodKills() + ", pickups " + ca.foodPickups()
                            + ", beef in inventory " + beef + ", state " + ca.state() + ", note '" + ca.lastNote()
                            + "'");
        });
        Shots.add(s, "combat-food-done");
    }
}
