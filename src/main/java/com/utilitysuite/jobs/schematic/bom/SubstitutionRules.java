package com.utilitysuite.jobs.schematic.bom;

import com.utilitysuite.jobs.schematic.model.BlockStateString;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * User-configurable material substitutions. A rule puts items into an interchangeable {@link Group}: any member can be
 * used for a block that asks for another member, and missing amounts are gathered as the group's preferred item.
 * <ul>
 *   <li>{@link #glob}: items whose id matches a pattern ({@code *_planks}) are interchangeable.</li>
 *   <li>{@link #mapping}: explicit from/to pairs ({@code cobbled_deepslate -> cobblestone}); each target and its
 *       sources form a group, preferring the target.</li>
 * </ul>
 * Each rule has a {@link Scope}: {@code HIDDEN_ONLY} applies only to blocks fully enclosed by other full blocks (no
 * visible face), so visible facades keep their exact material. Items marked strict never substitute, whatever the
 * rules say. Immutable once built; safe to share with background threads.
 */
public final class SubstitutionRules {

    public enum Scope { ANYWHERE, HIDDEN_ONLY }

    /** A set of interchangeable items. */
    public record Group(String key, String preferredItem, String description, java.util.function.Predicate<String> members) {
        public boolean accepts(String itemId) {
            return members.test(itemId);
        }
    }

    private record Rule(Group group, Scope scope, java.util.function.Predicate<String> matches) {
    }

    public static final SubstitutionRules NONE = new SubstitutionRules(List.of(), Set.of());

    private final List<Rule> rules;
    private final Set<String> strict;

    private SubstitutionRules(List<Rule> rules, Set<String> strict) {
        this.rules = List.copyOf(rules);
        this.strict = Set.copyOf(strict);
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isStrict(String itemId) {
        return strict.contains(itemId);
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /**
     * The group an item may be substituted within, or {@code null} for an exact requirement.
     *
     * @param hidden the block has no visible face (enables {@link Scope#HIDDEN_ONLY} rules)
     */
    public Group groupFor(String itemId, boolean hidden) {
        if (strict.contains(itemId)) {
            return null;
        }
        for (Rule r : rules) {
            if ((r.scope == Scope.ANYWHERE || hidden) && r.matches.test(itemId)) {
                return r.group;
            }
        }
        return null;
    }

    /** Pattern with {@code *} wildcards, matched against the full id; a pattern without a colon matches the path. */
    static java.util.function.Predicate<String> globPredicate(String glob) {
        String g = glob.trim().toLowerCase(java.util.Locale.ROOT);
        boolean withNamespace = g.indexOf(':') >= 0;
        StringBuilder re = new StringBuilder();
        for (char c : g.toCharArray()) {
            if (c == '*') {
                re.append(".*");
            } else {
                re.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(re.toString());
        return id -> p.matcher(withNamespace ? id : BlockStateString.path(id)).matches();
    }

    /** Deepslate building blocks to their stone counterparts (for {@link Builder#deepslateToStone}). */
    public static final Map<String, String> DEEPSLATE_TO_STONE = Map.ofEntries(
            Map.entry("minecraft:deepslate", "minecraft:stone"),
            Map.entry("minecraft:cobbled_deepslate", "minecraft:cobblestone"),
            Map.entry("minecraft:cobbled_deepslate_slab", "minecraft:cobblestone_slab"),
            Map.entry("minecraft:cobbled_deepslate_stairs", "minecraft:cobblestone_stairs"),
            Map.entry("minecraft:cobbled_deepslate_wall", "minecraft:cobblestone_wall"),
            Map.entry("minecraft:polished_deepslate", "minecraft:smooth_stone"),
            Map.entry("minecraft:polished_deepslate_slab", "minecraft:smooth_stone_slab"),
            Map.entry("minecraft:polished_deepslate_stairs", "minecraft:stone_stairs"),
            Map.entry("minecraft:polished_deepslate_wall", "minecraft:cobblestone_wall"),
            Map.entry("minecraft:deepslate_bricks", "minecraft:stone_bricks"),
            Map.entry("minecraft:cracked_deepslate_bricks", "minecraft:cracked_stone_bricks"),
            Map.entry("minecraft:deepslate_brick_slab", "minecraft:stone_brick_slab"),
            Map.entry("minecraft:deepslate_brick_stairs", "minecraft:stone_brick_stairs"),
            Map.entry("minecraft:deepslate_brick_wall", "minecraft:stone_brick_wall"),
            Map.entry("minecraft:deepslate_tiles", "minecraft:stone_bricks"),
            Map.entry("minecraft:cracked_deepslate_tiles", "minecraft:cracked_stone_bricks"),
            Map.entry("minecraft:deepslate_tile_slab", "minecraft:stone_brick_slab"),
            Map.entry("minecraft:deepslate_tile_stairs", "minecraft:stone_brick_stairs"),
            Map.entry("minecraft:deepslate_tile_wall", "minecraft:stone_brick_wall"),
            Map.entry("minecraft:chiseled_deepslate", "minecraft:chiseled_stone_bricks"));

    public static final class Builder {
        private final List<Rule> rules = new ArrayList<>();
        private final Set<String> strict = new HashSet<>();

        /** Items matching {@code glob} are interchangeable; shortfalls are gathered as {@code preferredItem}. */
        public Builder glob(String glob, String preferredItem, Scope scope) {
            java.util.function.Predicate<String> p = globPredicate(glob);
            Group g = new Group("glob:" + glob + "@" + scope, BlockStateString.normalizeId(preferredItem),
                    "any " + glob + (scope == Scope.HIDDEN_ONLY ? " (hidden blocks)" : ""), p);
            rules.add(new Rule(g, scope, p));
            return this;
        }

        /** Each {@code from} may be built with its {@code to} item and vice versa; {@code to} is preferred. */
        public Builder mapping(Map<String, String> fromTo, Scope scope) {
            Map<String, Set<String>> byTarget = new HashMap<>();
            for (Map.Entry<String, String> e : fromTo.entrySet()) {
                String to = BlockStateString.normalizeId(e.getValue());
                byTarget.computeIfAbsent(to, k -> new HashSet<>()).add(to);
                byTarget.get(to).add(BlockStateString.normalizeId(e.getKey()));
            }
            for (Map.Entry<String, Set<String>> e : byTarget.entrySet()) {
                Set<String> members = Set.copyOf(e.getValue());
                Group g = new Group("map:" + e.getKey() + "@" + scope, e.getKey(), "any of " + members,
                        members::contains);
                rules.add(new Rule(g, scope, members::contains));
            }
            return this;
        }

        /** Any plank variant (and its preferred oak) for blocks matching the scope. */
        public Builder anyPlanks(Scope scope) {
            return glob("*_planks", "minecraft:oak_planks", scope);
        }

        public Builder deepslateToStone(Scope scope) {
            return mapping(DEEPSLATE_TO_STONE, scope);
        }

        public Builder strict(String itemId) {
            strict.add(BlockStateString.normalizeId(itemId));
            return this;
        }

        public SubstitutionRules build() {
            return new SubstitutionRules(rules, strict);
        }
    }
}
