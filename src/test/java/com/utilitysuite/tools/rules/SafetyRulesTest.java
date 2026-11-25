package com.utilitysuite.tools.rules;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The safety triggers (stranger, armour, totem), the job gate, the new actions and the one-click safety templates. */
class SafetyRulesTest {

    static class Ctx extends RuleEngineTest.FakeContext {
        double stranger = Double.POSITIVE_INFINITY;
        double armor = -1;
        double totemAge = Double.POSITIVE_INFINITY;

        @Override
        public double strangerDistance() {
            return stranger;
        }

        @Override
        public double lowestArmorDurabilityPct() {
            return armor;
        }

        @Override
        public double secondsSinceTotemPop() {
            return totemAge;
        }
    }

    private static RuleEdits.Preset template(Rule.ActionType action) {
        for (RuleEdits.Preset p : RuleEdits.SAFETY_TEMPLATES) {
            if (p.action().type() == action) {
                return p;
            }
        }
        throw new AssertionError("no template for " + action);
    }

    private static int run(RuleEngine e, RuleContext c, List<Rule> fired, int ticks) {
        int n = 0;
        for (int i = 0; i < ticks; i++) {
            n += e.tick(c, fired::add);
        }
        return n;
    }

    @Test
    void strangerDistanceNeverTrueWithNobodyAround() {
        Ctx c = new Ctx();
        Rule.Condition near = new Rule.Condition(Rule.ConditionType.STRANGER_DISTANCE, Rule.Comparison.AT_MOST, 48, "");
        assertFalse(RuleEngine.evaluate(near, c));
        c.stranger = 48;
        assertTrue(RuleEngine.evaluate(near, c));
        c.stranger = 48.5;
        assertFalse(RuleEngine.evaluate(near, c));
    }

    @Test
    void armourWithNothingWornNeverFires() {
        Ctx c = new Ctx();
        Rule.Condition low = new Rule.Condition(Rule.ConditionType.ARMOR_DURABILITY, Rule.Comparison.BELOW, 12, "");
        assertFalse(RuleEngine.evaluate(low, c), "-1 (no armour) must not read as 'below 12%'");
        c.armor = 11.9;
        assertTrue(RuleEngine.evaluate(low, c));
        c.armor = 12;
        assertFalse(RuleEngine.evaluate(low, c));
    }

    @Test
    void totemAgeNeverTrueBeforeAPop() {
        Ctx c = new Ctx();
        Rule.Condition recent = new Rule.Condition(Rule.ConditionType.TOTEM_POP_AGE, Rule.Comparison.AT_MOST, 5, "");
        Rule.Condition old = new Rule.Condition(Rule.ConditionType.TOTEM_POP_AGE, Rule.Comparison.ABOVE, 5, "");
        assertFalse(RuleEngine.evaluate(recent, c));
        assertFalse(RuleEngine.evaluate(old, c), "no pop yet is not 'a pop long ago'");
        c.totemAge = 0.05;
        assertTrue(RuleEngine.evaluate(recent, c));
    }

    @Test
    void jobGateOnlyCountsWhileAJobWorks() {
        assertTrue(RuleEngine.jobActive("RUNNING"));
        assertTrue(RuleEngine.jobActive("PLANNING"));
        assertTrue(RuleEngine.jobActive("REPLANNING"));
        assertFalse(RuleEngine.jobActive("PAUSED"));
        assertFalse(RuleEngine.jobActive("NONE"));
        assertFalse(RuleEngine.jobActive("COMPLETED"));

        Ctx c = new Ctx();
        c.stranger = 20;
        RuleEngine e = new RuleEngine();
        e.setInterval(1);
        e.setRules(List.of(template(Rule.ActionType.STOP_ALL).rule("r1")));
        List<Rule> fired = new ArrayList<>();
        assertEquals(0, run(e, c, fired, 5), "a stranger nearby without a job is not a trigger");
        c.job = "RUNNING";
        assertEquals(1, run(e, c, fired, 5), "fires once on the rising edge when the job starts");
        assertEquals(Rule.ActionType.STOP_ALL, fired.get(0).action().type());
    }

    @Test
    void strangerTemplateMatchesTheSpec() {
        Rule r = template(Rule.ActionType.STOP_ALL).rule("x");
        assertEquals(Rule.ConditionType.STRANGER_DISTANCE, r.condition().type());
        assertEquals(48, r.condition().value());
        assertTrue(r.whileJob());
        assertEquals("If a non-ally player is <= 48 m away while a job runs, Stop-All", r.describe());
    }

    @Test
    void armourTemplateStopsTheJobBelow12Percent() {
        Rule r = template(Rule.ActionType.STOP_JOB).rule("x");
        Ctx c = new Ctx();
        c.job = "RUNNING";
        c.armor = 30;
        RuleEngine e = new RuleEngine();
        e.setInterval(1);
        e.setRules(List.of(r));
        List<Rule> fired = new ArrayList<>();
        assertEquals(0, run(e, c, fired, 3));
        c.armor = 11;
        assertEquals(1, run(e, c, fired, 3));
    }

    @Test
    void totemTemplateFiresOncePerPopAndNotOnLaterEvaluations() {
        Rule r = template(Rule.ActionType.QUICK_PEARL).rule("x");
        assertFalse(r.whileJob(), "a pearl escape is wanted in any fight, not only during jobs");
        Ctx c = new Ctx();
        RuleEngine e = new RuleEngine();
        e.setInterval(20);
        e.setRules(List.of(r));
        List<Rule> fired = new ArrayList<>();
        assertEquals(0, run(e, c, fired, 40));
        c.totemAge = 0;
        e.evaluateNextTick();
        assertEquals(1, e.tick(c, fired::add), "the pop is evaluated on the very next tick, not at the interval");
        c.totemAge = 2;
        assertEquals(0, run(e, c, fired, 40), "still inside the window: no second firing");
        c.totemAge = 30;
        run(e, c, fired, 40);
        assertEquals(1, fired.size());
    }

    @Test
    void evaluateNextTickIsOneShot() {
        Ctx c = new Ctx();
        c.health = 4;
        RuleEngine e = new RuleEngine();
        e.setInterval(100);
        e.setRules(List.of(new Rule("h", true,
                new Rule.Condition(Rule.ConditionType.HEALTH, Rule.Comparison.BELOW, 6, ""),
                new Rule.Action(Rule.ActionType.ALERT, "low", false), 0)));
        List<Rule> fired = new ArrayList<>();
        e.evaluateNextTick();
        assertEquals(1, e.tick(c, fired::add));
        c.health = 20;
        e.evaluateNextTick();
        assertEquals(0, e.tick(c, fired::add)); // forced again: the condition is seen false (edge resets)
        c.health = 4;
        assertEquals(0, run(e, c, fired, 97), "back to the interval after the forced ticks");
        assertEquals(1, e.tick(c, fired::add), "tick 100 is the next regular evaluation");
    }

    @Test
    void templatesAreDuplicateCheckedAndDistinctFromStarters() {
        List<Rule> rules = new ArrayList<>();
        for (RuleEdits.Preset p : RuleEdits.SAFETY_TEMPLATES) {
            assertFalse(RuleEdits.hasEquivalent(rules, p));
            rules.add(p.rule("r" + rules.size()));
            assertTrue(RuleEdits.hasEquivalent(rules, p));
        }
        for (RuleEdits.Preset p : RuleEdits.PRESETS) {
            assertFalse(RuleEdits.hasEquivalent(rules, p));
        }
        // the job gate is part of the content: the same rule without it is not a duplicate
        Rule gated = RuleEdits.SAFETY_TEMPLATES.get(0).rule("g");
        Rule ungated = new Rule("u", true, gated.condition(), gated.action(), gated.cooldownTicks(), false);
        assertFalse(gated.sameContent(ungated));
    }

    @Test
    void codecRoundTripsTheJobGateAndNewTypes() {
        List<Rule> in = new ArrayList<>();
        for (RuleEdits.Preset p : RuleEdits.SAFETY_TEMPLATES) {
            in.add(p.rule("r" + in.size()));
        }
        in.add(new Rule("r9", false, new Rule.Condition(Rule.ConditionType.HUNGER, Rule.Comparison.BELOW, 6, ""),
                new Rule.Action(Rule.ActionType.ALERT, "Eat something", false), 400));
        List<Rule> out = RuleCodec.fromJson(RuleCodec.toJson(in));
        assertEquals(in, out);
    }

    @Test
    void oldRulesWithoutTheGateFieldStillLoad() {
        JsonObject o = new JsonObject();
        o.addProperty("id", "r1");
        o.addProperty("if", "HEALTH");
        o.addProperty("cmp", "BELOW");
        o.addProperty("value", 6);
        o.addProperty("then", "PAUSE_JOB");
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(o);
        List<Rule> out = RuleCodec.fromJson(arr);
        assertEquals(1, out.size());
        assertFalse(out.get(0).whileJob());
        assertFalse(RuleCodec.toJson(out).get(0).getAsJsonObject().has("whileJob"), "default stays compact");
    }

    @Test
    void builderValidatesTheNewFields() {
        RuleEdits.Result ok = RuleEdits.build("r1", true, Rule.ConditionType.STRANGER_DISTANCE,
                Rule.Comparison.AT_MOST, "48", "", Rule.ConditionType.UNIT_POINTS, Rule.ActionType.STOP_ALL, "",
                false, "", true);
        assertTrue(ok.ok(), ok.error());
        assertTrue(ok.rule().whileJob());
        assertEquals(RuleEdits.DEFAULT_COOLDOWN, ok.rule().cooldownTicks());

        RuleEdits.Result far = RuleEdits.build("r1", true, Rule.ConditionType.STRANGER_DISTANCE,
                Rule.Comparison.AT_MOST, "9000", "", "", Rule.ActionType.STOP_ALL, "", false, "", false);
        assertFalse(far.ok());
        RuleEdits.Result armour = RuleEdits.build("r1", true, Rule.ConditionType.ARMOR_DURABILITY,
                Rule.Comparison.BELOW, "101", "", "", Rule.ActionType.STOP_JOB, "", false, "", false);
        assertFalse(armour.ok());
        RuleEdits.Result alert = RuleEdits.build("r1", true, Rule.ConditionType.HEALTH, Rule.Comparison.BELOW,
                "6", "", "", Rule.ActionType.ALERT, "  ", false, "", false);
        assertEquals("Enter the alert text", alert.error());
        RuleEdits.Result pearl = RuleEdits.build("r1", true, Rule.ConditionType.TOTEM_POP_AGE,
                Rule.Comparison.AT_MOST, "5", "", "", Rule.ActionType.QUICK_PEARL, "ignored", false, "", false);
        assertTrue(pearl.ok());
        assertEquals("", pearl.rule().action().arg(), "actions without text drop stray arg text");
    }

    @Test
    void urgentActionsAreTheSafetyOnes() {
        assertTrue(Rule.ActionType.STOP_ALL.urgent());
        assertTrue(Rule.ActionType.STOP_JOB.urgent());
        assertTrue(Rule.ActionType.QUICK_PEARL.urgent());
        assertTrue(Rule.ActionType.ALERT.urgent());
        assertFalse(Rule.ActionType.DEPOSIT_AT.urgent());
        assertFalse(Rule.ActionType.PAUSE_JOB.urgent());
    }
}
