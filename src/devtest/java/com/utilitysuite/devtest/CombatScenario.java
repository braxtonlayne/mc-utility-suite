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
import net.minecraft.world.entity.monster.Zombie;

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
                + " {ArmorItems:[{},{},{},{id:\"minecraft:leather_helmet\",count:1}],PersistenceRequired:1b}");
        s.waitFor("zombie visible", 60, () -> nearestZombie() != null);
        s.waitUpTo("zombie close", 60, () -> nearestZombie().distanceTo(H.p()) < 2.8);
        s.run("user's first hit", () -> {
            target[0] = nearestZombie();
            H.p().lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    target[0].getEyePosition());
            H.mc().gameMode.attack(H.p(), target[0]);
            H.p().swing(InteractionHand.MAIN_HAND);
            s.note("hit zombie #" + target[0].getId() + " at distance " + Harness.fmt("%.2f",
                    target[0].distanceTo(H.p())));
        });
        s.waitFor("ENGAGED", 40, () -> ca.state() == CombatState.ENGAGED);
        s.run("engaged", () -> s.check("first hit -> ENGAGED with a lease", ca.state() == CombatState.ENGAGED
                && "combat_assistant".equals(holder()), "state=" + ca.state() + " movement holder=" + holder()
                + " target=" + ca.targetId()));
        s.step("fight", t -> {
            if (!coachDrew[0] && t % 5 == 0) {
                CountingGraphics g = CountingGraphics.render(new FightCoachHud(CombatBootstrap.coach()));
                coachDrew[0] = g.calls() > 0;
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
        s.always("disarm", () -> {
            H.enable(CombatAssistant.ID, false);
            H.enable("fight_coach", false);
            Harness.command("kill @e[type=minecraft:zombie]");
            Harness.command("effect give @s minecraft:instant_health 1 5 true");
        });
        return s;
    }
}
