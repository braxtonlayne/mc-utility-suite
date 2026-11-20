package com.utilitysuite.tools.rules;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    static class FakeContext implements RuleContext {
        double full;
        double health = 20;
        double hunger = 20;
        long time;
        String job = "NONE";
        final Map<String, Integer> items = new HashMap<>();

        @Override
        public double inventoryFullPct() {
            return full;
        }

        @Override
        public double health() {
            return health;
        }

        @Override
        public double hunger() {
            return hunger;
        }

        @Override
        public long timeOfDay() {
            return time;
        }

        @Override
        public String jobStatus() {
            return job;
        }

        @Override
        public int itemCount(String itemId) {
            return items.getOrDefault(itemId, 0);
        }
    }

    static Rule depositRule(String id, int cooldown) {
        return new Rule(id, true,
                new Rule.Condition(Rule.ConditionType.INVENTORY_FULL_PCT, Rule.Comparison.AT_LEAST, 90, ""),
                new Rule.Action(Rule.ActionType.DEPOSIT_AT, "Base Chest", true), cooldown);
    }

    @Test
    void specExampleDescribesItself() {
        assertEquals("If inventory >= 90% full, deposit at Base Chest and resume", depositRule("r1", 0).describe());
    }

    @Test
    void conditionsEvaluateEachType() {
        FakeContext c = new FakeContext();
        c.full = 95;
        c.health = 6;
        c.hunger = 4;
        c.time = 13500;
        c.job = "RUNNING";
        c.items.put("minecraft:coal", 3);
        assertTrue(RuleEngine.evaluate(new Rule.Condition(Rule.ConditionType.INVENTORY_FULL_PCT,
                Rule.Comparison.AT_LEAST, 90, ""), c));
        assertTrue(RuleEngine.evaluate(new Rule.Condition(Rule.ConditionType.HEALTH, Rule.Comparison.AT_MOST, 6, ""), c));
        assertFalse(RuleEngine.evaluate(new Rule.Condition(Rule.ConditionType.HUNGER, Rule.Comparison.AT_LEAST, 5, ""), c));
        assertTrue(RuleEngine.evaluate(new Rule.Condition(Rule.ConditionType.TIME_OF_DAY, Rule.Comparison.AT_LEAST,
                13000, ""), c));
        assertTrue(RuleEngine.evaluate(new Rule.Condition(Rule.ConditionType.JOB_STATUS, Rule.Comparison.EQUALS, 0,
                "running"), c));
        assertFalse(RuleEngine.evaluate(new Rule.Condition(Rule.ConditionType.JOB_STATUS, Rule.Comparison.NOT_EQUALS, 0,
                "RUNNING"), c));
        assertTrue(RuleEngine.evaluate(new Rule.Condition(Rule.ConditionType.ITEM_COUNT, Rule.Comparison.AT_MOST, 5,
                "minecraft:coal"), c));
        assertTrue(RuleEngine.evaluate(new Rule.Condition(Rule.ConditionType.ITEM_COUNT, Rule.Comparison.EQUALS, 0,
                "minecraft:diamond"), c));
        assertFalse(RuleEngine.evaluate(new Rule.Condition(Rule.ConditionType.ITEM_COUNT, Rule.Comparison.EQUALS, 0,
                ""), c), "item count without an item never matches");
    }

    @Test
    void firesOnRisingEdgeOnlyAndOnlyOnEvaluationTicks() {
        RuleEngine e = new RuleEngine();
        e.setInterval(5);
        e.setRules(List.of(depositRule("r1", 0)));
        FakeContext c = new FakeContext();
        List<Rule> fired = new ArrayList<>();
        c.full = 95;
        for (int i = 0; i < 4; i++) {
            assertEquals(0, e.tick(c, fired::add), "not an evaluation tick yet");
        }
        assertEquals(1, e.tick(c, fired::add));
        for (int i = 0; i < 50; i++) {
            e.tick(c, fired::add);
        }
        assertEquals(1, fired.size(), "still full: no repeat while the condition stays true");
        c.full = 40;
        for (int i = 0; i < 5; i++) {
            e.tick(c, fired::add);
        }
        c.full = 92;
        for (int i = 0; i < 5; i++) {
            e.tick(c, fired::add);
        }
        assertEquals(2, fired.size(), "re-armed after the condition was false");
    }

    @Test
    void cooldownSuppressesQuickRefiring() {
        RuleEngine e = new RuleEngine();
        e.setInterval(1);
        e.setRules(List.of(depositRule("r1", 100)));
        FakeContext c = new FakeContext();
        List<Rule> fired = new ArrayList<>();
        c.full = 95;
        e.tick(c, fired::add);
        c.full = 10;
        e.tick(c, fired::add);
        c.full = 95;
        e.tick(c, fired::add);
        assertEquals(1, fired.size(), "edge inside the cooldown is ignored");
        for (int i = 0; i < 100; i++) {
            c.full = i % 2 == 0 ? 10 : 95;
            e.tick(c, fired::add);
        }
        assertEquals(2, fired.size(), "fires again once the cooldown elapsed");
    }

    @Test
    void disabledRulesNeverFireAndStateSurvivesReplacingTheList() {
        RuleEngine e = new RuleEngine();
        e.setInterval(1);
        Rule r = depositRule("r1", 0);
        e.setRules(List.of(r.withEnabled(false)));
        FakeContext c = new FakeContext();
        c.full = 99;
        List<Rule> fired = new ArrayList<>();
        e.tick(c, fired::add);
        assertTrue(fired.isEmpty());
        e.setRules(List.of(r));
        e.tick(c, fired::add);
        assertEquals(1, fired.size());
        // Adding another rule keeps r1's edge state: it does not fire again.
        e.setRules(List.of(r, depositRule("r2", 0)));
        e.tick(c, fired::add);
        assertEquals(2, fired.size(), "only the new rule fires");
        assertEquals("r2", fired.get(1).id());
    }

    @Test
    void codecRoundTripsAndPersistsInTheProfile() {
        List<Rule> rules = List.of(depositRule("r1", 200),
                new Rule("r2", false, new Rule.Condition(Rule.ConditionType.JOB_STATUS, Rule.Comparison.EQUALS, 0,
                        "PAUSED"), new Rule.Action(Rule.ActionType.SUBMIT_GOAL, "gather 16 coal", false), 40),
                new Rule("r3", true, new Rule.Condition(Rule.ConditionType.HEALTH, Rule.Comparison.AT_MOST, 6.5, ""),
                        new Rule.Action(Rule.ActionType.TOGGLE_MODULE, "survival_autopilot", false), 0));
        ProfileConfig profile = new ProfileConfig("sp_Test", new JsonObject());
        profile.module("local_rules").add("rules", RuleCodec.toJson(rules));
        // Simulate save + load through text.
        String text = profile.root().toString();
        ProfileConfig loaded = new ProfileConfig("sp_Test", JsonParser.parseString(text).getAsJsonObject());
        List<Rule> back = RuleCodec.fromJson(loaded.module("local_rules").get("rules"));
        assertEquals(rules, back);
    }

    @Test
    void codecSkipsBrokenEntries() {
        JsonArray arr = JsonParser.parseString("[{\"if\":\"NOPE\",\"then\":\"PAUSE_JOB\"}, 5, "
                + "{\"if\":\"HUNGER\",\"cmp\":\"AT_MOST\",\"value\":6,\"then\":\"PAUSE_JOB\"}]").getAsJsonArray();
        List<Rule> rules = RuleCodec.fromJson(arr);
        assertEquals(1, rules.size());
        assertEquals(Rule.ConditionType.HUNGER, rules.get(0).condition().type());
        assertTrue(rules.get(0).enabled());
        assertEquals(200, rules.get(0).cooldownTicks());
        assertTrue(RuleCodec.fromJson(null).isEmpty());
    }
}
