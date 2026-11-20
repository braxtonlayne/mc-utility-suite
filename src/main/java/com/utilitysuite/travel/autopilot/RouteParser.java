package com.utilitysuite.travel.autopilot;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses an autopilot route: one or more stops separated by {@code ;} or {@code >}. A stop is {@code x y z},
 * {@code x z} (keep altitude / let the planner choose) or a waypoint name. Coordinates may be separated by spaces or
 * commas. Engine-free; waypoint names are resolved through {@link Resolver}. Parsing runs once when a route starts.
 */
public final class RouteParser {

    /** Resolves a waypoint name to a stop in the current dimension, or null when unknown. */
    @FunctionalInterface
    public interface Resolver {
        Stop resolve(String name);
    }

    /** A route stop. {@code hasY} is false for "x z" stops. */
    public record Stop(int x, int y, int z, boolean hasY, String label) {
    }

    /** Parsed stops, or an error message (stops empty). */
    public record Result(List<Stop> stops, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private RouteParser() {
    }

    public static Result parse(String spec, Resolver resolver) {
        if (spec == null || spec.isBlank()) {
            return new Result(List.of(), "empty route");
        }
        List<Stop> stops = new ArrayList<>();
        for (String raw : spec.split("[;>]")) {
            String part = raw.trim();
            if (part.isEmpty()) {
                continue;
            }
            Stop stop = parseCoordinates(part);
            if (stop == null) {
                stop = resolver == null ? null : resolver.resolve(part);
                if (stop == null) {
                    return new Result(List.of(), "unknown waypoint or bad coordinates: " + part);
                }
            }
            stops.add(stop);
        }
        if (stops.isEmpty()) {
            return new Result(List.of(), "empty route");
        }
        return new Result(List.copyOf(stops), null);
    }

    static Stop parseCoordinates(String part) {
        String[] t = part.split("[\\s,]+");
        if (t.length != 2 && t.length != 3) {
            return null;
        }
        int[] v = new int[t.length];
        for (int i = 0; i < t.length; i++) {
            try {
                v[i] = (int) Math.floor(Double.parseDouble(t[i]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        if (t.length == 3) {
            return new Stop(v[0], v[1], v[2], true, part);
        }
        return new Stop(v[0], 0, v[1], false, part);
    }
}
