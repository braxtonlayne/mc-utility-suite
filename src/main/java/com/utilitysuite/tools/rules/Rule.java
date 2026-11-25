package com.utilitysuite.tools.rules;

import java.util.Locale;
import java.util.Objects;

/**
 * One local rule: when {@link Condition} becomes true, run {@link Action}. Immutable.
 *
 * @param id            stable id (for enable/delete in the UI)
 * @param cooldownTicks minimum ticks between two firings of this rule
 * @param whileJob      the condition only counts while a goal job is active (planning, running or replanning)
 */
public record Rule(String id, boolean enabled, Condition condition, Action action, int cooldownTicks,
                   boolean whileJob) {

    public Rule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(action, "action");
        cooldownTicks = Math.max(0, cooldownTicks);
    }

    /** A rule without the job gate (applies at any time). */
    public Rule(String id, boolean enabled, Condition condition, Action action, int cooldownTicks) {
        this(id, enabled, condition, action, cooldownTicks, false);
    }

    public Rule withEnabled(boolean value) {
        return new Rule(id, value, condition, action, cooldownTicks, whileJob);
    }

    /** Same id and enabled flag, everything else from {@code edited}. */
    public Rule withContentOf(Rule edited) {
        return new Rule(id, enabled, edited.condition(), edited.action(), edited.cooldownTicks(), edited.whileJob());
    }

    /** True when both rules do the same thing (condition, action, cooldown, job gate), ignoring id and enabled. */
    public boolean sameContent(Rule other) {
        return condition.type() == other.condition.type() && condition.comparison() == other.condition.comparison()
                && condition.value() == other.condition.value()
                && condition.arg().equalsIgnoreCase(other.condition.arg())
                && action.type() == other.action.type() && action.arg().equalsIgnoreCase(other.action.arg())
                && action.flag() == other.action.flag() && cooldownTicks == other.cooldownTicks
                && whileJob == other.whileJob;
    }

    /** "If inventory >= 90% full, deposit at Base Chest and resume". */
    public String describe() {
        return "If " + condition.describe() + (whileJob ? " while a job runs" : "") + ", " + action.describe();
    }

    public enum ConditionType {
        /** Share of the 36 inventory slots that hold anything, 0-100. */
        INVENTORY_FULL_PCT("inventory % full"),
        /** Health points, 0-20 (hearts x2). */
        HEALTH("health"),
        /** Food level, 0-20. */
        HUNGER("food level"),
        /** Day time in ticks, 0-23999 (0 = sunrise, 6000 = noon, 13000 = night). */
        TIME_OF_DAY("time of day"),
        /** Active job status ({@code JobStatus} name, or NONE when no job is active). Uses {@link Condition#arg}. */
        JOB_STATUS("job status"),
        /** Count of an item id in the inventory. Uses {@link Condition#arg}. */
        ITEM_COUNT("count of"),
        /**
         * Remaining durability of the main-hand item. {@link Condition#arg} = {@link #UNIT_PERCENT} for percent of
         * max durability, anything else (normally {@link #UNIT_POINTS}) for absolute points. Never true when the
         * main-hand item has no durability.
         */
        TOOL_DURABILITY("main-hand durability"),
        /**
         * Distance in metres to the nearest other player who is not on the ally roster. Never true when no such
         * player is loaded (the distance is then infinite).
         */
        STRANGER_DISTANCE("nearest non-ally (m)"),
        /** Lowest remaining durability, in percent of max, of any worn armour piece. Never true with none worn. */
        ARMOR_DURABILITY("lowest armour durability"),
        /** Seconds since the local player's last totem of undying popped. Never true before the first pop. */
        TOTEM_POP_AGE("seconds since totem pop");

        public static final String UNIT_POINTS = "points";
        public static final String UNIT_PERCENT = "percent";

        private final String label;

        ConditionType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean usesText() {
            return this == JOB_STATUS || this == ITEM_COUNT;
        }
    }

    public enum Comparison {
        AT_LEAST(">="), AT_MOST("<="), BELOW("<"), ABOVE(">"), EQUALS("="), NOT_EQUALS("!=");

        private final String symbol;

        Comparison(String symbol) {
            this.symbol = symbol;
        }

        public String symbol() {
            return symbol;
        }

        public boolean test(double actual, double value) {
            return switch (this) {
                case AT_LEAST -> actual >= value;
                case AT_MOST -> actual <= value;
                case BELOW -> actual < value;
                case ABOVE -> actual > value;
                case EQUALS -> Math.abs(actual - value) < 1e-9;
                case NOT_EQUALS -> Math.abs(actual - value) >= 1e-9;
            };
        }
    }

    /**
     * @param value numeric threshold (ignored for JOB_STATUS)
     * @param arg   item id for ITEM_COUNT, status name for JOB_STATUS, else empty
     */
    public record Condition(ConditionType type, Comparison comparison, double value, String arg) {
        public Condition {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(comparison, "comparison");
            arg = arg == null ? "" : arg.trim();
        }

        /** For TOOL_DURABILITY: whether {@link #value} is a percent of max durability rather than points. */
        public boolean durabilityPercent() {
            return ConditionType.UNIT_PERCENT.equalsIgnoreCase(arg);
        }

        public String describe() {
            return switch (type) {
                case JOB_STATUS -> "job status " + (comparison == Comparison.NOT_EQUALS ? "is not " : "is ")
                        + arg.toUpperCase(Locale.ROOT);
                case ITEM_COUNT -> "count of " + arg + " " + comparison.symbol() + " " + fmt(value);
                case INVENTORY_FULL_PCT -> "inventory " + comparison.symbol() + " " + fmt(value) + "% full";
                case TOOL_DURABILITY -> type.label() + " " + comparison.symbol() + " " + fmt(value)
                        + (durabilityPercent() ? "%" : "");
                case STRANGER_DISTANCE -> "a non-ally player is " + comparison.symbol() + " " + fmt(value) + " m away";
                case ARMOR_DURABILITY -> type.label() + " " + comparison.symbol() + " " + fmt(value) + "%";
                case TOTEM_POP_AGE -> "a totem popped (" + comparison.symbol() + " " + fmt(value) + " s ago)";
                default -> type.label() + " " + comparison.symbol() + " " + fmt(value);
            };
        }
    }

    public enum ActionType {
        /** Submit goal console text, e.g. "gather 32 coal". */
        SUBMIT_GOAL("run goal"),
        /** Travel to the named waypoint, open the container there and deposit; {@link Action#flag} = resume after. */
        DEPOSIT_AT("deposit at"),
        PAUSE_JOB("pause the job"),
        RESUME_JOB("resume the job"),
        /** {@link Action#arg} = module id, {@link Action#flag} = on/off. */
        TOGGLE_MODULE("switch module"),
        /** Cancels the active goal job. */
        STOP_JOB("stop the job"),
        /** Stop-All: revokes every lease and stops all automation. */
        STOP_ALL("Stop-All"),
        /** Asks Quick Pearl to throw an ender pearl the way the player is facing (its own lease and checks). */
        QUICK_PEARL("pearl escape"),
        /** Only posts a warning notice; {@link Action#arg} = the text. */
        ALERT("alert");

        /** Actions that end automation or flee; their firing is posted as a warning, not as info. */
        public boolean urgent() {
            return this == STOP_JOB || this == STOP_ALL || this == QUICK_PEARL || this == ALERT;
        }

        private final String label;

        ActionType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean usesText() {
            return this == SUBMIT_GOAL || this == DEPOSIT_AT || this == TOGGLE_MODULE || this == ALERT;
        }
    }

    public record Action(ActionType type, String arg, boolean flag) {
        public Action {
            Objects.requireNonNull(type, "type");
            arg = arg == null ? "" : arg.trim();
        }

        public String describe() {
            return switch (type) {
                case SUBMIT_GOAL -> "run \"" + arg + "\"";
                case DEPOSIT_AT -> "deposit at " + arg + (flag ? " and resume" : "");
                case TOGGLE_MODULE -> "switch " + arg + (flag ? " on" : " off");
                case ALERT -> "alert \"" + arg + "\"";
                default -> type.label();
            };
        }
    }

    static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : Double.toString(v);
    }
}
