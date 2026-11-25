package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.IWorldKnowledgeService;
import com.utilitysuite.core.api.Waypoint;
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
 * {@code build schematic house.litematic anchor=10,64,-3 gather-only}, {@code goto 100 64 -200},
 * {@code travel to Base} and {@code sort hall}. Modifiers ({@code key=value}) may appear anywhere.
 */
public final class GoalParser {

    /**
     * A waypoint resolved for "travel to NAME", already converted into the player's current dimension. {@code error}
     * is non-null when the waypoint exists but cannot be reached from here (the parse then fails with that message).
     */
    public record WaypointTarget(int x, int y, int z, String dimension, String error) {
        public WaypointTarget(int x, int y, int z, String dimension) {
            this(x, y, z, dimension, null);
        }

        public static WaypointTarget unreachable(String error) {
            return new WaypointTarget(0, 0, 0, "", error);
        }
    }

    static final String OVERWORLD = "minecraft:overworld";
    static final String NETHER = "minecraft:the_nether";

    /**
     * Resolves a waypoint by name for travel from {@code currentDimension}, using the same 8:1 overworld/nether
     * conversion as the World tab "Go" ({@link IWaypointService#positionIn}). Waypoints in a dimension that is not the
     * current one or its overworld/nether partner resolve to an {@link WaypointTarget#unreachable} target, since
     * cross-dimension portal routing is not supported yet.
     */
    public static Optional<WaypointTarget> resolveWaypoint(IWaypointService service, String name,
                                                           String currentDimension) {
        if (service == null) {
            return Optional.empty();
        }
        Optional<Waypoint> found = service.byName(name);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Waypoint w = found.get();
        if (!convertible(w.dimension(), currentDimension)) {
            return Optional.of(WaypointTarget.unreachable("Waypoint '" + w.name() + "' is in "
                    + shortDim(w.dimension()) + " and you are in " + shortDim(currentDimension)
                    + "; routing between these dimensions (through portals) is not supported yet"));
        }
        long p = service.positionIn(w, currentDimension);
        return Optional.of(new WaypointTarget(PackedPos.x(p), PackedPos.y(p), PackedPos.z(p), currentDimension));
    }

    /** True for the same dimension or an overworld/nether pair (the pairs {@code positionIn} can convert). */
    static boolean convertible(String from, String to) {
        if (from.equals(to)) {
            return true;
        }
        return (OVERWORLD.equals(from) && NETHER.equals(to)) || (NETHER.equals(from) && OVERWORLD.equals(to));
    }

    static String shortDim(String dim) {
        return dim.startsWith("minecraft:") ? dim.substring("minecraft:".length()) : dim;
    }

    /**
     * Default data source for typed commands without {@code source=}: the World tab's routing mode, or OBSERVED_ONLY
     * when no world knowledge service is registered.
     */
    public static Supplier<DataSourceMode> routingDefault(Supplier<IWorldKnowledgeService> knowledge) {
        return () -> {
            IWorldKnowledgeService k = knowledge.get();
            DataSourceMode m = k == null ? null : k.routingMode();
            return m == null ? DataSourceMode.OBSERVED_ONLY : m;
        };
    }

    private static final List<String> VERBS = List.of("gather", "mine", "collect", "get", "craft", "make", "smelt",
            "build", "goto", "travel", "furnaces", "brew", "crafter", "sort");

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
            case "furnaces", "smelt-array", "blast", "smoke" -> parseSmeltArray(verb, rest, cost, source);
            case "brew" -> parseBrew(rest, cost, source);
            case "crafter" -> parseCrafter(rest, cost, source);
            case "sort", "tidy" -> parseSort(rest, cost, source);
            default -> ParseResult.error("Unknown verb '" + verb + "'", closestVerbs(verb));
        };
    }

    /** "sort hall", "sort storage", "sort the hall", "tidy storage": the Hall Sorter job. */
    private static ParseResult parseSort(List<String> rest, CostProfile cost, DataSourceMode source) {
        String what = String.join(" ", rest).toLowerCase(Locale.ROOT);
        if (what.startsWith("the ")) {
            what = what.substring(4);
        }
        return switch (what) {
            case "hall", "storage", "storage hall", "chests" -> ParseResult.ok(new GoalRequest.SortHall(cost, source));
            default -> ParseResult.error("Sort what? The storage hall is the only thing that can be sorted",
                    List.of("sort hall", "sort storage"));
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

    /** "furnaces 192 raw iron" (any furnace type), "blast 64 raw gold", "smoke 32 beef". */
    private ParseResult parseSmeltArray(String verb, List<String> rest, CostProfile cost, DataSourceMode source) {
        ParseResult item = parseItemGoal(rest, false, cost, source);
        if (!(item.request() instanceof GoalRequest.Gather g)) {
            return item;
        }
        String station = switch (verb) {
            case "blast" -> "minecraft:blast_furnace";
            case "smoke" -> "minecraft:smoker";
            default -> null;
        };
        return ParseResult.ok(new GoalRequest.SmeltArray(g.itemId(), g.count(), station, cost, source));
    }

    /** "crafter save buckets" (nearest remembered crafter), "crafter apply buckets". */
    private static ParseResult parseCrafter(List<String> rest, CostProfile cost, DataSourceMode source) {
        List<String> examples = List.of("crafter save buckets", "crafter apply buckets");
        if (rest.size() < 2) {
            return ParseResult.error("Usage: crafter save|apply <name>", examples);
        }
        String action = rest.get(0).toLowerCase(Locale.ROOT);
        if (!action.equals("save") && !action.equals("apply")) {
            return ParseResult.error("Unknown crafter action '" + action + "'", examples);
        }
        String name = String.join(" ", rest.subList(1, rest.size())).replace('|', '/');
        return ParseResult.ok(new GoalRequest.Crafter(action.equals("apply"), name, cost, source));
    }

    /** "brew 3 splash strong_healing", "brew 2 long swiftness", "brew lingering weakness". */
    private ParseResult parseBrew(List<String> rest, CostProfile cost, DataSourceMode source) {
        int qty = 1;
        String form = "DRINK";
        List<String> name = new ArrayList<>();
        for (String w : rest) {
            String lw = w.toLowerCase(Locale.ROOT);
            Integer q = parseQuantity(w);
            if (q != null) {
                qty = q;
            } else if (lw.equals("splash") || lw.equals("lingering")) {
                form = lw.toUpperCase(Locale.ROOT);
            } else if (!lw.equals("of") && !lw.equals("potion") && !lw.equals("potions")) {
                name.add(lw);
            }
        }
        if (qty <= 0) {
            return ParseResult.error("Quantity must be at least 1", List.of());
        }
        String potion = String.join("_", name).replace("ii", "").replace("__", "_");
        if (potion.endsWith("_")) {
            potion = "strong_" + potion.substring(0, potion.length() - 1);
        }
        if (potion.isEmpty() || com.utilitysuite.jobs.planner.BrewingPlanner.chain(potion,
                com.utilitysuite.jobs.planner.BrewingPlanner.Form.DRINK) == null) {
            return ParseResult.error("Unknown or unbrewable potion '" + String.join(" ", name) + "'",
                    List.of("brew 3 splash strong_healing", "brew 2 long_swiftness"));
        }
        return ParseResult.ok(new GoalRequest.Brew(potion, form, qty, cost, source));
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
        if (t.error() != null) {
            return ParseResult.error(t.error(), List.of());
        }
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
