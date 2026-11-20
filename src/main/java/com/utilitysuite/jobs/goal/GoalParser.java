package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.jobs.planner.PackedPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * GoalConsole parser: deterministic tokenizer for commands such as {@code gather 32 diamonds},
 * {@code craft 16 hoppers cost=cheapest}, {@code mine 64 iron_ingots source=hybrid},
 * {@code build schematic house.litematic anchor=10,64,-3 gather-only}, {@code goto 100 64 -200} and
 * {@code travel to Base}. Modifiers ({@code key=value}) may appear anywhere.
 */
public final class GoalParser {

    /** A waypoint resolved for "travel to NAME". */
    public record WaypointTarget(int x, int y, int z, String dimension) {
    }

    private static final List<String> VERBS = List.of("gather", "mine", "collect", "get", "craft", "make", "smelt",
            "build", "goto", "travel");

    private final Supplier<ItemNameMatcher> matcher;
    private final Function<String, Optional<WaypointTarget>> waypoints;
    private final Supplier<DataSourceMode> defaultSource;
    private final LongSupplier playerPos;
    private final Supplier<String> dimension;

    /**
     * @param matcher       id matcher (rebuilt when the registry snapshot changes)
     * @param waypoints     name lookup, or null when no waypoint service exists
     * @param defaultSource routing data source when the command has no {@code source=}
     * @param playerPos     packed player position for "~" relative coordinates
     */
    public GoalParser(Supplier<ItemNameMatcher> matcher, Function<String, Optional<WaypointTarget>> waypoints,
                      Supplier<DataSourceMode> defaultSource, LongSupplier playerPos, Supplier<String> dimension) {
        this.matcher = matcher;
        this.waypoints = waypoints;
        this.defaultSource = defaultSource;
        this.playerPos = playerPos;
        this.dimension = dimension;
    }

    public ParseResult parse(String command) {
        if (command == null || command.isBlank()) {
            return ParseResult.error("Empty command", VERBS);
        }
        List<String> words = new ArrayList<>();
        CostProfile cost = CostProfile.FASTEST;
        DataSourceMode source = defaultSource.get();
        long anchor = Long.MIN_VALUE;
        boolean gatherOnly = false;
        for (String tok : command.trim().split("\\s+")) {
            String lower = tok.toLowerCase(Locale.ROOT);
            int eq = lower.indexOf('=');
            if (eq > 0) {
                String key = lower.substring(0, eq);
                String val = lower.substring(eq + 1);
                switch (key) {
                    case "cost", "profile" -> {
                        CostProfile c = parseCost(val);
                        if (c == null) {
                            return ParseResult.error("Unknown cost profile '" + val + "'",
                                    List.of("cost=fastest", "cost=safest", "cost=cheapest", "cost=preserve_terrain"));
                        }
                        cost = c;
                    }
                    case "source", "data", "mode" -> {
                        DataSourceMode m = parseSource(val);
                        if (m == null) {
                            return ParseResult.error("Unknown data source '" + val + "'",
                                    List.of("source=observed", "source=simulated", "source=hybrid"));
                        }
                        source = m;
                    }
                    case "anchor", "at" -> {
                        String[] p = val.split(",");
                        if (p.length != 3) {
                            return ParseResult.error("anchor needs x,y,z", List.of("anchor=0,64,0"));
                        }
                        try {
                            anchor = PackedPos.pack(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                                    Integer.parseInt(p[2]));
                        } catch (NumberFormatException e) {
                            return ParseResult.error("anchor needs whole numbers", List.of("anchor=0,64,0"));
                        }
                    }
                    default -> {
                        return ParseResult.error("Unknown modifier '" + key + "'",
                                List.of("cost=", "source=", "anchor="));
                    }
                }
                continue;
            }
            if (lower.equals("gather-only") || lower.equals("gatheronly") || lower.equals("--gather-only")
                    || lower.equals("materials-only")) {
                gatherOnly = true;
                continue;
            }
            words.add(tok);
        }
        if (words.isEmpty()) {
            return ParseResult.error("Missing verb", VERBS);
        }
        String verb = words.get(0).toLowerCase(Locale.ROOT);
        List<String> rest = words.subList(1, words.size());
        return switch (verb) {
            case "gather", "mine", "collect", "get", "obtain", "dig" -> parseItemGoal(rest, false, cost, source);
            case "craft", "make", "smelt" -> parseItemGoal(rest, true, cost, source);
            case "build" -> parseBuild(rest, anchor, gatherOnly, cost, source);
            case "goto" -> parseCoords(rest, cost, source);
            case "go", "travel", "walk" -> parseTravel(rest, cost, source);
            default -> ParseResult.error("Unknown verb '" + verb + "'", closestVerbs(verb));
        };
    }

    private ParseResult parseItemGoal(List<String> rest, boolean craft, CostProfile cost, DataSourceMode source) {
        if (rest.isEmpty()) {
            return ParseResult.error("Missing item, e.g. '" + (craft ? "craft 16 hoppers" : "gather 32 diamonds") + "'",
                    List.of());
        }
        int qty = -1;
        List<String> name = new ArrayList<>();
        for (String w : rest) {
            Integer q = qty < 0 ? parseQuantity(w) : null;
            if (q != null) {
                qty = q;
            } else {
                name.add(w);
            }
        }
        if (qty == 0) {
            return ParseResult.error("Quantity must be at least 1", List.of());
        }
        if (qty < 0) {
            qty = 1;
        }
        if (name.isEmpty()) {
            return ParseResult.error("Missing item name", List.of());
        }
        String phrase = String.join(" ", name);
        if (phrase.toLowerCase(Locale.ROOT).startsWith("of ")) {
            phrase = phrase.substring(3);
        }
        ItemNameMatcher.Match m = matcher.get().match(phrase);
        if (!m.found()) {
            return ParseResult.error("Unknown or ambiguous item '" + phrase + "'", m.suggestions());
        }
        GoalRequest req = craft
                ? new GoalRequest.Craft(m.id(), qty, cost, source)
                : new GoalRequest.Gather(m.id(), qty, cost, source);
        return ParseResult.ok(req);
    }

    private ParseResult parseBuild(List<String> rest, long anchor, boolean gatherOnly, CostProfile cost,
                                   DataSourceMode source) {
        List<String> parts = new ArrayList<>(rest);
        if (!parts.isEmpty() && parts.get(0).equalsIgnoreCase("schematic")) {
            parts.remove(0);
        }
        if (parts.isEmpty()) {
            return ParseResult.error("Missing schematic file", List.of("build schematic house.litematic"));
        }
        String path = String.join(" ", parts);
        return ParseResult.ok(new GoalRequest.Build(path, anchor, gatherOnly, cost, source));
    }

    private ParseResult parseCoords(List<String> rest, CostProfile cost, DataSourceMode source) {
        List<String> r = rest;
        if (!r.isEmpty() && r.get(0).equalsIgnoreCase("to")) {
            r = r.subList(1, r.size());
        }
        if (r.size() != 3) {
            if (r.size() >= 1 && parseCoord(r.get(0), 0) == null) {
                return parseWaypoint(r, cost, source);
            }
            return ParseResult.error("goto needs x y z", List.of("goto 100 64 -200", "goto ~ ~ ~10"));
        }
        long here = playerPos.getAsLong();
        Integer x = parseCoord(r.get(0), PackedPos.x(here));
        Integer y = parseCoord(r.get(1), PackedPos.y(here));
        Integer z = parseCoord(r.get(2), PackedPos.z(here));
        if (x == null || y == null || z == null) {
            return ParseResult.error("Coordinates must be whole numbers or ~", List.of("goto 100 64 -200"));
        }
        return ParseResult.ok(new GoalRequest.Travel(x, y, z, dimension.get(), cost, source));
    }

    private ParseResult parseTravel(List<String> rest, CostProfile cost, DataSourceMode source) {
        List<String> r = rest;
        if (!r.isEmpty() && r.get(0).equalsIgnoreCase("to")) {
            r = r.subList(1, r.size());
        }
        if (r.size() == 3 && parseCoord(r.get(0), 0) != null) {
            return parseCoords(r, cost, source);
        }
        return parseWaypoint(r, cost, source);
    }

    private ParseResult parseWaypoint(List<String> r, CostProfile cost, DataSourceMode source) {
        if (r.isEmpty()) {
            return ParseResult.error("Missing waypoint name", List.of("travel to Base"));
        }
        if (waypoints == null) {
            return ParseResult.error("Waypoints are not available (world module not loaded)", List.of());
        }
        String name = String.join(" ", r);
        Optional<WaypointTarget> wp = waypoints.apply(name);
        if (wp.isEmpty()) {
            return ParseResult.error("Unknown waypoint '" + name + "'", List.of());
        }
        WaypointTarget t = wp.get();
        return ParseResult.ok(new GoalRequest.Travel(t.x(), t.y(), t.z(), t.dimension(), cost, source));
    }

    // ---- token helpers ----------------------------------------------------------------------------------------------

    static Integer parseQuantity(String w) {
        String s = w.toLowerCase(Locale.ROOT);
        if (s.startsWith("x")) {
            s = s.substring(1);
        } else if (s.endsWith("x")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isEmpty() || s.length() > 7) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return null;
            }
        }
        return Integer.parseInt(s);
    }

    static Integer parseCoord(String w, int base) {
        try {
            if (w.startsWith("~")) {
                return w.length() == 1 ? base : base + Integer.parseInt(w.substring(1));
            }
            return Integer.parseInt(w);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static CostProfile parseCost(String v) {
        return switch (v) {
            case "fastest", "fast", "speed" -> CostProfile.FASTEST;
            case "safest", "safe" -> CostProfile.SAFEST;
            case "cheapest", "cheap" -> CostProfile.CHEAPEST;
            case "preserve", "preserve_terrain", "terrain", "preserve-terrain" -> CostProfile.PRESERVE_TERRAIN;
            default -> null;
        };
    }

    static DataSourceMode parseSource(String v) {
        return switch (v) {
            case "observed", "obs", "observed_only", "loaded" -> DataSourceMode.OBSERVED_ONLY;
            case "simulated", "sim", "simulated_only", "seed" -> DataSourceMode.SIMULATED_ONLY;
            case "hybrid", "hyb", "both" -> DataSourceMode.HYBRID;
            default -> null;
        };
    }

    private static List<String> closestVerbs(String verb) {
        List<String> sorted = new ArrayList<>(VERBS);
        sorted.sort((a, b) -> Integer.compare(ItemNameMatcher.editDistance(verb, a),
                ItemNameMatcher.editDistance(verb, b)));
        return sorted.subList(0, 3);
    }
}
