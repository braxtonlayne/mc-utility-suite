package com.utilitysuite.tools.rules;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEditsTest {

    /** Context with a configurable main-hand item; everything else idle. */
    static final class HandContext extends RuleEngineTest.FakeContext {
        int left = -1;
        int max;

        @Override
        public int mainHandDurability() {
            return left;
        }

        @Override
        public int mainHandMaxDurability() {
            return max;
        }
    }

    private static RuleEdits.Result build(Rule.ConditionType ct, Rule.Comparison cmp, String value, String condArg,
                                          String unit, Rule.ActionType at, String actionArg, String cooldown) {
        return RuleEdits.build("rX", true, ct, cmp, value, condArg, unit, at, actionArg, true, cooldown);
    }

    // ---- editing ----------------------------------------------------------------------------------------------------

    @Test
    void editKeepsIdEnabledAndPositionAndRoundTripsThroughProfileJson() {
        List<Rule> rules = new ArrayList<>();
        rules.add(RuleEngineTest.depositRule("r1", 200));
        rules.add(RuleEngineTest.depositRule("r2", 200).withEnabled(false));
        rules.add(RuleEngineTest.depositRule("r3", 200));

        RuleEdits.Result edited = build(Rule.ConditionType.HEALTH, Rule.Comparison.BELOW, "8", "", "",
                Rule.ActionType.SUBMIT_GOAL, "gather 16 bread", "40");
        assertTrue(edited.ok(), edited.error());
        // the editor builds with the original id/enabled, but replace must keep them even if handed different ones
        List<Rule> next = RuleEdits.replace(rules, "r2", edited.rule());
        assertEquals(3, next.size());
        Rule r2 = next.get(1);
        assertEquals("r2", r2.id());
        assertFalse(r2.enabled());
        assertEquals("If health < 8, run \"gather 16 bread\"", r2.describe());
        assertEquals(40, r2.cooldownTicks());
        assertEquals("r1", next.get(0).id());
        assertEquals("r3", next.get(2).id());
        assertEquals(rules.get(0), next.get(0));

        List<Rule> back = RuleCodec.fromJson(JsonParser.parseString(RuleCodec.toJson(next).toString()));
        assertEquals(next, back);
    }

    @Test
    void replaceUnknownIdReturnsNull() {
        List<Rule> rules = List.of(RuleEngineTest.depositRule("r1", 0));
        assertNull(RuleEdits.replace(rules, "nope", RuleEngineTest.depositRule("x", 0)));
    }

    @Test
    void validationRejectsBadInput() {
        assertFalse(build(Rule.ConditionType.HEALTH, Rule.Comparison.BELOW, "abc", "", "",
                Rule.ActionType.PAUSE_JOB, "", "").ok());
        assertFalse(build(Rule.ConditionType.HEALTH, Rule.Comparison.BELOW, "25", "", "",
                Rule.ActionType.PAUSE_JOB, "", "").ok());
        assertFalse(build(Rule.ConditionType.INVENTORY_FULL_PCT, Rule.Comparison.AT_LEAST, "-1", "", "",
                Rule.ActionType.PAUSE_JOB, "", "").ok());
        assertFalse(build(Rule.ConditionType.TIME_OF_DAY, Rule.Comparison.AT_LEAST, "24000", "", "",
                Rule.ActionType.PAUSE_JOB, "", "").ok());
        assertFalse(build(Rule.ConditionType.TOOL_DURABILITY, Rule.Comparison.BELOW, "150", "",
                Rule.ConditionType.UNIT_PERCENT, Rule.ActionType.PAUSE_JOB, "", "").ok());
        assertTrue(build(Rule.ConditionType.TOOL_DURABILITY, Rule.Comparison.BELOW, "150", "",
                Rule.ConditionType.UNIT_POINTS, Rule.ActionType.PAUSE_JOB, "", "").ok());
        RuleEdits.Result emptyGoal = build(Rule.ConditionType.HEALTH, Rule.Comparison.BELOW, "8", "", "",
                Rule.ActionType.SUBMIT_GOAL, "   ", "");
        assertFalse(emptyGoal.ok());
        assertEquals("Enter the goal text", emptyGoal.error());
        assertFalse(build(Rule.ConditionType.ITEM_COUNT, Rule.Comparison.AT_LEAST, "3", " ", "",
                Rule.ActionType.PAUSE_JOB, "", "").ok());
        assertFalse(build(Rule.ConditionType.HEALTH, Rule.Comparison.BELOW, "8", "", "",
                Rule.ActionType.PAUSE_JOB, "", "1.5").ok());
        assertFalse(build(Rule.ConditionType.HEALTH, Rule.Comparison.BELOW, "8", "", "",
                Rule.ActionType.PAUSE_JOB, "", "-5").ok());
        RuleEdits.Result ok = build(Rule.ConditionType.HEALTH, Rule.Comparison.BELOW, "7,5", "", "",
                Rule.ActionType.PAUSE_JOB, "ignored", "");
        assertTrue(ok.ok());
        assertEquals(7.5, ok.rule().condition().value());
        assertEquals("", ok.rule().action().arg());
        assertEquals(RuleEdits.DEFAULT_COOLDOWN, ok.rule().cooldownTicks());
    }

    // ---- presets ----------------------------------------------------------------------------------------------------

    @Test
    void presetDuplicateCheckIgnoresIdAndEnabled() {
        List<Rule> rules = new ArrayList<>();
        for (RuleEdits.Preset preset : RuleEdits.PRESETS) {
            assertFalse(RuleEdits.hasEquivalent(rules, preset));
        }
        RuleEdits.Preset deposit = RuleEdits.PRESETS.get(0);
        rules.add(deposit.rule("r7").withEnabled(false));
        assertTrue(RuleEdits.hasEquivalent(rules, deposit));
        assertFalse(RuleEdits.hasEquivalent(rules, RuleEdits.PRESETS.get(1)));
        // waypoint name compares case-insensitively; a different threshold is a different rule
        Rule lower = new Rule("r8", true, deposit.condition(),
                new Rule.Action(Rule.ActionType.DEPOSIT_AT, "base", true), deposit.cooldownTicks());
        assertTrue(RuleEdits.hasEquivalent(List.of(lower), deposit));
        Rule other = new Rule("r9", true,
                new Rule.Condition(Rule.ConditionType.INVENTORY_FULL_PCT, Rule.Comparison.AT_LEAST, 80, ""),
                deposit.action(), deposit.cooldownTicks());
        assertFalse(RuleEdits.hasEquivalent(List.of(other), deposit));
    }

    @Test
    void presetsDescribeThemselves() {
        assertEquals("If inventory >= 90% full, deposit at Base and resume",
                RuleEdits.PRESETS.get(0).rule("a").describe());
        assertEquals("If main-hand durability < 10, pause the job", RuleEdits.PRESETS.get(1).rule("b").describe());
        assertEquals("If health < 12, pause the job", RuleEdits.PRESETS.get(2).rule("c").describe());
    }

    // ---- durability condition ---------------------------------------------------------------------------------------

    @Test
    void durabilityConditionPointsAndPercent() {
        HandContext ctx = new HandContext();
        Rule.Condition points = new Rule.Condition(Rule.ConditionType.TOOL_DURABILITY, Rule.Comparison.BELOW, 10,
                Rule.ConditionType.UNIT_POINTS);
        Rule.Condition pct = new Rule.Condition(Rule.ConditionType.TOOL_DURABILITY, Rule.Comparison.BELOW, 10,
                Rule.ConditionType.UNIT_PERCENT);

        // empty hand / undamageable item: never true
        assertFalse(RuleEngine.evaluate(points, ctx));
        assertFalse(RuleEngine.evaluate(pct, ctx));

        ctx.max = 1561; // diamond pickaxe
        ctx.left = 9;
        assertTrue(RuleEngine.evaluate(points, ctx));
        assertTrue(RuleEngine.evaluate(pct, ctx));
        ctx.left = 10;
        assertFalse(RuleEngine.evaluate(points, ctx)); // strictly below
        ctx.left = 150; // 9.6%
        assertFalse(RuleEngine.evaluate(points, ctx));
        assertTrue(RuleEngine.evaluate(pct, ctx));
        ctx.left = 157; // 10.06%
        assertFalse(RuleEngine.evaluate(pct, ctx));

        assertEquals("main-hand durability < 10%", pct.describe());
        Rule r = new Rule("r1", true, pct, new Rule.Action(Rule.ActionType.PAUSE_JOB, "", false), 0);
        assertEquals(List.of(r), RuleCodec.fromJson(JsonParser.parseString(RuleCodec.toJson(List.of(r)).toString())));
    }

    @Test
    void durabilityRuleFiresOnceThroughEngine() {
        HandContext ctx = new HandContext();
        ctx.max = 250;
        ctx.left = 100;
        RuleEngine engine = new RuleEngine();
        engine.setInterval(1);
        engine.setRules(List.of(RuleEdits.PRESETS.get(1).rule("r1")));
        List<Rule> fired = new ArrayList<>();
        engine.tick(ctx, fired::add);
        assertTrue(fired.isEmpty());
        ctx.left = 5;
        engine.tick(ctx, fired::add);
        engine.tick(ctx, fired::add);
        assertEquals(1, fired.size());
    }
}
