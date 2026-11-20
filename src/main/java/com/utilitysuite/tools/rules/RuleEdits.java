package com.utilitysuite.tools.rules;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure helpers behind the rules UI: validating the builder/editor fields into a {@link Rule}, replacing a rule in
 * place, and the starter presets with their duplicate check. No Minecraft classes, so it is unit-testable.
 */
public final class RuleEdits {

    public static final int DEFAULT_COOLDOWN = 200;
    /** One in-game hour of ticks is plenty for "don't fire again for a while"; anything above is a typo. */
    public static final int MAX_COOLDOWN = 72_000;

    private RuleEdits() {
    }

    /** Either a built rule or the message to toast. */
    public record Result(Rule rule, String error) {
        public boolean ok() {
            return rule != null;
        }

        static Result fail(String error) {
            return new Result(null, error);
        }
    }

    /**
     * Validates raw field text and builds a rule.
     *
     * @param durabilityUnit {@link Rule.ConditionType#UNIT_PERCENT} or {@link Rule.ConditionType#UNIT_POINTS}; only
     *                       used for TOOL_DURABILITY
     */
    public static Result build(String id, boolean enabled, Rule.ConditionType ct, Rule.Comparison cmp,
                               String valueText, String condArgText, String durabilityUnit,
                               Rule.ActionType at, String actionArgText, boolean flag, String cooldownText) {
        double value = 0;
        if (ct == Rule.ConditionType.JOB_STATUS) {
            if (cmp != Rule.Comparison.NOT_EQUALS) {
                cmp = Rule.Comparison.EQUALS;
            }
        } else {
            String v = valueText == null ? "" : valueText.trim().replace(',', '.');
            try {
                value = Double.parseDouble(v);
            } catch (NumberFormatException e) {
                return Result.fail("Enter a number for the condition");
            }
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                return Result.fail("Enter a number for the condition");
            }
            boolean pct = ct == Rule.ConditionType.TOOL_DURABILITY
                    && Rule.ConditionType.UNIT_PERCENT.equals(durabilityUnit);
            double max = maxValue(ct, pct);
            if (value < 0 || value > max) {
                return Result.fail(ct.label() + " must be between 0 and " + Rule.fmt(max));
            }
        }
        String condArg = condArgText == null ? "" : condArgText.trim();
        if (ct.usesText() && condArg.isEmpty()) {
            return Result.fail(ct == Rule.ConditionType.ITEM_COUNT ? "Enter an item id" : "Enter a job status");
        }
        if (ct == Rule.ConditionType.TOOL_DURABILITY) {
            condArg = Rule.ConditionType.UNIT_PERCENT.equals(durabilityUnit) ? Rule.ConditionType.UNIT_PERCENT
                    : Rule.ConditionType.UNIT_POINTS;
        } else if (!ct.usesText()) {
            condArg = "";
        }
        String actionArg = actionArgText == null ? "" : actionArgText.trim();
        if (at.usesText() && actionArg.isEmpty()) {
            return Result.fail(switch (at) {
                case SUBMIT_GOAL -> "Enter the goal text";
                case DEPOSIT_AT -> "Enter a waypoint name";
                default -> "Enter a module id";
            });
        }
        int cooldown = DEFAULT_COOLDOWN;
        String cd = cooldownText == null ? "" : cooldownText.trim();
        if (!cd.isEmpty()) {
            try {
                cooldown = Integer.parseInt(cd);
            } catch (NumberFormatException e) {
                return Result.fail("Cooldown must be whole ticks");
            }
            if (cooldown < 0 || cooldown > MAX_COOLDOWN) {
                return Result.fail("Cooldown must be between 0 and " + MAX_COOLDOWN + " ticks");
            }
        }
        return new Result(new Rule(id, enabled, new Rule.Condition(ct, cmp, value, condArg),
                new Rule.Action(at, at.usesText() ? actionArg : "", flag), cooldown), null);
    }

    /** Largest sensible threshold for a condition type. */
    public static double maxValue(Rule.ConditionType ct, boolean durabilityPercent) {
        return switch (ct) {
            case INVENTORY_FULL_PCT -> 100;
            case HEALTH, HUNGER -> 20;
            case TIME_OF_DAY -> 23_999;
            case ITEM_COUNT -> 41 * 64 * 64; // every slot full of 64-stacks, generously
            case TOOL_DURABILITY -> durabilityPercent ? 100 : 100_000;
            case JOB_STATUS -> 0;
        };
    }

    /**
     * Copy of {@code rules} with the rule whose id is {@code id} given {@code edited}'s content (its id, enabled flag
     * and position are kept), or null when no rule has that id.
     */
    public static List<Rule> replace(List<Rule> rules, String id, Rule edited) {
        List<Rule> out = new ArrayList<>(rules.size());
        boolean found = false;
        for (Rule r : rules) {
            if (!found && r.id().equals(id)) {
                out.add(r.withContentOf(edited));
                found = true;
            } else {
                out.add(r);
            }
        }
        return found ? out : null;
    }

    // ---- starter presets --------------------------------------------------------------------------------------------

    /** A one-click starter rule. {@link #rule(String)} stamps it with a fresh id. */
    public record Preset(String label, Rule.Condition condition, Rule.Action action, int cooldownTicks) {
        public Rule rule(String id) {
            return new Rule(id, true, condition, action, cooldownTicks);
        }
    }

    public static final String BASE_WAYPOINT = "Base";

    public static final List<Preset> PRESETS = List.of(
            new Preset("Full inv → deposit at Base",
                    new Rule.Condition(Rule.ConditionType.INVENTORY_FULL_PCT, Rule.Comparison.AT_LEAST, 90, ""),
                    new Rule.Action(Rule.ActionType.DEPOSIT_AT, BASE_WAYPOINT, true), DEFAULT_COOLDOWN),
            new Preset("Tool < 10 dur → pause",
                    new Rule.Condition(Rule.ConditionType.TOOL_DURABILITY, Rule.Comparison.BELOW, 10,
                            Rule.ConditionType.UNIT_POINTS),
                    new Rule.Action(Rule.ActionType.PAUSE_JOB, "", false), DEFAULT_COOLDOWN),
            new Preset("Health < 6 hearts → pause",
                    new Rule.Condition(Rule.ConditionType.HEALTH, Rule.Comparison.BELOW, 12, ""),
                    new Rule.Action(Rule.ActionType.PAUSE_JOB, "", false), DEFAULT_COOLDOWN));

    /** True when {@code rules} already holds a rule doing exactly what the preset does (id/enabled ignored). */
    public static boolean hasEquivalent(List<Rule> rules, Preset preset) {
        Rule probe = preset.rule("_probe");
        for (Rule r : rules) {
            if (r.sameContent(probe)) {
                return true;
            }
        }
        return false;
    }
}
