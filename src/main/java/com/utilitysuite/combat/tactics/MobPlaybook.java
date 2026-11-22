package com.utilitysuite.combat.tactics;

import java.util.HashMap;
import java.util.Map;

/**
 * How a pro fights each mob: a small static table keyed by entity type id (registry id strings, so it is engine-free,
 * testable, and works for modded mobs, which get {@link #DEFAULT}). Each row has a preferred hit policy (null = the
 * user's "Hit choice" setting), extra spacing in blocks, and one special rule.
 * <ul>
 *   <li>creeper: sprint knockback hit, back out past the blast radius while it swells, then return.</li>
 *   <li>skeleton, stray, bogged: close in on a strafe (never walk straight down the arrow line).</li>
 *   <li>spider, cave spider: no crit hops (a hop into its leap trades for nothing).</li>
 *   <li>witch: rush (no extra spacing, never kite: distance is where its potions win).</li>
 *   <li>baby zombie family: no crit hops (the hop overshoots the small box).</li>
 *   <li>warden, wither, ender dragon: never requested as a target.</li>
 *   <li>enderman: no special move yet (the design's "fight under a 2-high roof or stand in water" needs the arena
 *       read to find one); it gets the default row on purpose.</li>
 * </ul>
 * Lookups are a {@link HashMap} get on an existing String (its hash is cached): no allocation per call.
 */
public final class MobPlaybook {

    /** The one special rule of a row. */
    public enum Rule {
        NONE,
        /** Back out past the blast radius while it swells, return when it stops. */
        FUSE_BACKOFF,
        /** Close the distance on a strafe. */
        STRAFE_IN,
        /** No crit hops. */
        NO_HOPS,
        /** No extra spacing, never kite. */
        RUSH,
        /** Never requested as a target. */
        NEVER_TARGET
    }

    /** One row. {@code policy} null keeps the user's setting. */
    public record Row(String typeId, HitPlanner.Policy policy, double spacingExtra, Rule rule) {
        public boolean neverTarget() {
            return rule == Rule.NEVER_TARGET;
        }
    }

    public static final Row DEFAULT = new Row("*", null, 0.0, Rule.NONE);
    public static final Row CREEPER = new Row("minecraft:creeper", HitPlanner.Policy.KNOCKBACK, 0.0,
            Rule.FUSE_BACKOFF);
    public static final Row BABY_ZOMBIE = new Row("minecraft:zombie#baby", null, 0.0, Rule.NO_HOPS);

    /** Where a swelling creeper's fuse stops (vanilla SwellGoal gives up past 7 blocks) plus a margin. */
    public static final double BLAST_CLEAR = 7.5;

    private static final Map<String, Row> ROWS = new HashMap<>();
    private static final Map<String, Boolean> ZOMBIE_FAMILY = new HashMap<>();

    static {
        put(CREEPER);
        put(new Row("minecraft:skeleton", null, 0.0, Rule.STRAFE_IN));
        put(new Row("minecraft:stray", null, 0.0, Rule.STRAFE_IN));
        put(new Row("minecraft:bogged", null, 0.0, Rule.STRAFE_IN));
        put(new Row("minecraft:spider", null, 0.0, Rule.NO_HOPS));
        put(new Row("minecraft:cave_spider", null, 0.0, Rule.NO_HOPS));
        put(new Row("minecraft:witch", null, 0.0, Rule.RUSH)); // a knockback hit would push it back out
        put(new Row("minecraft:warden", null, 0.0, Rule.NEVER_TARGET));
        put(new Row("minecraft:wither", null, 0.0, Rule.NEVER_TARGET));
        put(new Row("minecraft:ender_dragon", null, 0.0, Rule.NEVER_TARGET));
        for (String z : new String[]{"minecraft:zombie", "minecraft:husk", "minecraft:drowned",
                "minecraft:zombie_villager", "minecraft:zombified_piglin"}) {
            ZOMBIE_FAMILY.put(z, Boolean.TRUE);
        }
    }

    private static void put(Row r) {
        ROWS.put(r.typeId(), r);
    }

    private MobPlaybook() {
    }

    /** The row for an entity type id ({@link #DEFAULT} for null, unknown and modded ids). */
    public static Row row(String typeId, boolean baby) {
        if (typeId == null) {
            return DEFAULT;
        }
        if (baby && ZOMBIE_FAMILY.containsKey(typeId)) {
            return BABY_ZOMBIE;
        }
        Row r = ROWS.get(typeId);
        return r != null ? r : DEFAULT;
    }

    public static Row row(String typeId) {
        return row(typeId, false);
    }

    /** True for a type that is never requested as a target. */
    public static boolean neverTarget(String typeId) {
        return row(typeId, false).neverTarget();
    }
}
