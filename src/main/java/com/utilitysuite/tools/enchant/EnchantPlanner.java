package com.utilitysuite.tools.enchant;

import java.util.List;

/**
 * Engine-free planner over a precomputed enchanting-table offer table (one {@link Offer} per bookshelf count and slot).
 * Picks the cheapest offer that contains the wanted enchantment at or above a minimum level; when none does, advises
 * the cheapest "dummy" enchant (lowest required level, slot 1 first: it costs 1 level and 1 lapis) to reroll the
 * player's enchantment seed. The seed after an enchant is random in single player, so this only ever plans one step.
 */
public final class EnchantPlanner {

    private EnchantPlanner() {
    }

    /** One enchantment in an offer, by registry id (e.g. {@code minecraft:sharpness}). */
    public record Ench(String id, int level) {
    }

    /**
     * What the table offers for one bookshelf count and slot (0..2). {@code cost} is the level requirement (0 = slot
     * disabled); {@code enchants} is the full list the enchant would apply (the table shows only one of them).
     */
    public record Offer(int bookshelves, int slot, int cost, List<Ench> enchants) {
        public Offer {
            enchants = List.copyOf(enchants);
        }

        /** Levels (and lapis) actually consumed by taking this slot. */
        public int levelsSpent() {
            return slot + 1;
        }

        public boolean contains(String id, int minLevel) {
            for (Ench e : enchants) {
                if (e.id().equals(id) && e.level() >= minLevel) {
                    return true;
                }
            }
            return false;
        }
    }

    public enum Kind { MATCH, MATCH_NEEDS_LEVELS, DUMMY, NOTHING }

    /** The advice: {@code offer} is the matching offer (MATCH*) or the dummy to take (DUMMY); null for NOTHING. */
    public record Plan(Kind kind, Offer offer) {
    }

    /**
     * @param offers      offer table (any order)
     * @param targetId    wanted enchantment id, or null/empty for "just show the cheapest dummy"
     * @param minLevel    minimum level of the wanted enchantment
     * @param playerLevel the player's current experience level
     */
    public static Plan plan(List<Offer> offers, String targetId, int minLevel, int playerLevel) {
        Offer bestAffordable = null;
        Offer bestAny = null;
        if (targetId != null && !targetId.isEmpty()) {
            for (Offer o : offers) {
                if (o.cost() <= 0 || o.enchants().isEmpty() || !o.contains(targetId, minLevel)) {
                    continue;
                }
                if (o.cost() <= playerLevel && o.levelsSpent() <= playerLevel && better(o, bestAffordable)) {
                    bestAffordable = o;
                }
                if (better(o, bestAny)) {
                    bestAny = o;
                }
            }
        }
        if (bestAffordable != null) {
            return new Plan(Kind.MATCH, bestAffordable);
        }
        Offer dummy = cheapestDummy(offers);
        if (bestAny != null && (dummy == null || dummy.cost() > playerLevel)) {
            return new Plan(Kind.MATCH_NEEDS_LEVELS, bestAny);
        }
        if (dummy != null) {
            return new Plan(Kind.DUMMY, dummy);
        }
        return bestAny != null ? new Plan(Kind.MATCH_NEEDS_LEVELS, bestAny) : new Plan(Kind.NOTHING, null);
    }

    /** Cheapest reroll: fewest levels/lapis spent, then lowest level requirement, then fewest bookshelves. */
    public static Offer cheapestDummy(List<Offer> offers) {
        Offer best = null;
        for (Offer o : offers) {
            if (o.cost() <= 0 || o.enchants().isEmpty()) {
                continue;
            }
            if (best == null || o.levelsSpent() < best.levelsSpent()
                    || (o.levelsSpent() == best.levelsSpent() && (o.cost() < best.cost()
                    || (o.cost() == best.cost() && o.bookshelves() < best.bookshelves())))) {
                best = o;
            }
        }
        return best;
    }

    /** Lower requirement first, then fewer levels spent, then fewer bookshelves. */
    private static boolean better(Offer o, Offer cur) {
        if (cur == null) {
            return true;
        }
        if (o.cost() != cur.cost()) {
            return o.cost() < cur.cost();
        }
        if (o.levelsSpent() != cur.levelsSpent()) {
            return o.levelsSpent() < cur.levelsSpent();
        }
        return o.bookshelves() < cur.bookshelves();
    }

    /** "minecraft:fire_aspect" -> "Fire Aspect". */
    public static String prettyName(String id) {
        String path = id.substring(id.indexOf(':') + 1);
        StringBuilder sb = new StringBuilder(path.length());
        boolean up = true;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '_') {
                sb.append(' ');
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

    public static String roman(int n) {
        return switch (n) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            default -> String.valueOf(n);
        };
    }

    /** "Sharpness IV, Unbreaking III". */
    public static String describe(List<Ench> enchants) {
        if (enchants.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (Ench e : enchants) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(prettyName(e.id())).append(' ').append(roman(e.level()));
        }
        return sb.toString();
    }

    /** One-line advice for the HUD card. */
    public static String advice(Plan plan, String targetId, int minLevel) {
        String want = targetId == null || targetId.isEmpty() ? null : prettyName(targetId) + " " + roman(minLevel) + "+";
        return switch (plan.kind()) {
            case MATCH -> "Take slot " + (plan.offer().slot() + 1) + " with " + plan.offer().bookshelves()
                    + " bookshelves (needs " + plan.offer().cost() + " levels, spends " + plan.offer().levelsSpent()
                    + "): " + describe(plan.offer().enchants());
            case MATCH_NEEDS_LEVELS -> want + " is on offer at slot " + (plan.offer().slot() + 1) + " with "
                    + plan.offer().bookshelves() + " bookshelves, but needs " + plan.offer().cost() + " levels";
            case DUMMY -> (want == null ? "" : want + " not offered now. ") + "Reroll: enchant a book at slot "
                    + (plan.offer().slot() + 1) + " with " + plan.offer().bookshelves() + " bookshelves (needs "
                    + plan.offer().cost() + " levels, spends " + plan.offer().levelsSpent() + " + "
                    + plan.offer().levelsSpent() + " lapis), then refresh";
            case NOTHING -> "The table offers nothing for this item";
        };
    }
}
