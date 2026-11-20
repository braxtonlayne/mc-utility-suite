package com.utilitysuite.tools.rules;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Evaluates local rules at most every {@code interval} ticks. A rule fires on the rising edge of its condition (false →
 * true; a condition already true when the rule is loaded fires once) and not again until the condition was false in
 * between and its cooldown elapsed. Pure Java; the evaluation pass itself allocates nothing (the context decides
 * what its reads cost).
 */
public final class RuleEngine {

    private Rule[] rules = new Rule[0];
    private boolean[] wasTrue = new boolean[0];
    private long[] lastFired = new long[0];
    private int interval = 20;
    private long tick;

    /** Replaces the rule set. Edge state is kept for rules whose id survives. */
    public void setRules(List<Rule> list) {
        Rule[] next = list.toArray(new Rule[0]);
        boolean[] nextTrue = new boolean[next.length];
        long[] nextFired = new long[next.length];
        for (int i = 0; i < next.length; i++) {
            nextFired[i] = Long.MIN_VALUE / 2;
            for (int j = 0; j < rules.length; j++) {
                if (rules[j].id().equals(next[i].id())) {
                    nextTrue[i] = wasTrue[j];
                    nextFired[i] = lastFired[j];
                }
            }
        }
        rules = next;
        wasTrue = nextTrue;
        lastFired = nextFired;
    }

    public void setInterval(int ticks) {
        this.interval = Math.max(1, ticks);
    }

    public int interval() {
        return interval;
    }

    /**
     * Advances one tick; on evaluation ticks checks every enabled rule and hands firing rules to {@code fire}.
     *
     * @return number of rules fired this tick
     */
    public int tick(RuleContext ctx, Consumer<Rule> fire) {
        tick++;
        if (tick % interval != 0) {
            return 0;
        }
        int fired = 0;
        Rule[] rs = rules;
        for (int i = 0; i < rs.length; i++) {
            Rule r = rs[i];
            if (!r.enabled()) {
                wasTrue[i] = false;
                continue;
            }
            boolean now = evaluate(r.condition(), ctx);
            if (now && !wasTrue[i] && tick - lastFired[i] >= r.cooldownTicks()) {
                lastFired[i] = tick;
                fired++;
                fire.accept(r);
            }
            wasTrue[i] = now;
        }
        return fired;
    }

    public static boolean evaluate(Rule.Condition c, RuleContext ctx) {
        return switch (c.type()) {
            case INVENTORY_FULL_PCT -> c.comparison().test(ctx.inventoryFullPct(), c.value());
            case HEALTH -> c.comparison().test(ctx.health(), c.value());
            case HUNGER -> c.comparison().test(ctx.hunger(), c.value());
            case TIME_OF_DAY -> c.comparison().test(ctx.timeOfDay(), c.value());
            case ITEM_COUNT -> !c.arg().isEmpty() && c.comparison().test(ctx.itemCount(c.arg()), c.value());
            case TOOL_DURABILITY -> {
                int max = ctx.mainHandMaxDurability();
                int left = ctx.mainHandDurability();
                if (max <= 0 || left < 0) {
                    yield false;
                }
                double actual = c.durabilityPercent() ? left * 100.0 / max : left;
                yield c.comparison().test(actual, c.value());
            }
            case JOB_STATUS -> {
                boolean same = ctx.jobStatus().equalsIgnoreCase(c.arg().toUpperCase(Locale.ROOT));
                yield c.comparison() == Rule.Comparison.NOT_EQUALS ? !same : same;
            }
        };
    }
}
