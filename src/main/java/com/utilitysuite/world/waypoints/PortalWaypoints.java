package com.utilitysuite.world.waypoints;

import com.utilitysuite.core.api.Waypoint;

import java.util.Collection;

/**
 * Engine-free rules for the automatic "Portals" waypoints: which dimension changes count, de-duplication and naming.
 * Only called when the player changes dimension, so it is not a hot path.
 */
public final class PortalWaypoints {

    /** An existing Portals waypoint in the same dimension within this many blocks suppresses a new one. */
    public static final int DEDUPE_RADIUS = 16;

    private PortalWaypoints() {
    }

    /** True for an overworld ↔ nether change (the only portal pair with an 8:1 paired coordinate). */
    public static boolean isPortalTransition(String from, String to) {
        return from != null && to != null && !from.equals(to) && DimensionScale.convertible(from, to);
    }

    /** True when a Portals waypoint stored in {@code dimension} lies within {@link #DEDUPE_RADIUS} blocks (3D). */
    public static boolean hasNearby(Collection<Waypoint> all, String dimension, int x, int y, int z) {
        long r2 = (long) DEDUPE_RADIUS * DEDUPE_RADIUS;
        for (Waypoint w : all) {
            if (!WaypointStore.GROUP_PORTALS.equals(w.group()) || !dimension.equals(w.dimension())) {
                continue;
            }
            long dx = w.x() - x;
            long dy = w.y() - y;
            long dz = w.z() - z;
            if (dx * dx + dy * dy + dz * dz <= r2) {
                return true;
            }
        }
        return false;
    }

    /** "Nether" / "Overworld"; other dimensions use their id path ("the_end"). */
    public static String dimensionLabel(String dimension) {
        if (DimensionScale.NETHER.equals(dimension)) {
            return "Nether";
        }
        if (DimensionScale.OVERWORLD.equals(dimension)) {
            return "Overworld";
        }
        int colon = dimension.indexOf(':');
        return colon >= 0 ? dimension.substring(colon + 1) : dimension;
    }

    /** "Portal (Nether)", then "Portal (Nether) 2", 3, ... skipping any name already used (case-insensitive). */
    public static String nextName(Collection<Waypoint> all, String dimension) {
        String base = "Portal (" + dimensionLabel(dimension) + ")";
        if (!nameTaken(all, base)) {
            return base;
        }
        for (int n = 2; ; n++) {
            String candidate = base + " " + n;
            if (!nameTaken(all, candidate)) {
                return candidate;
            }
        }
    }

    private static boolean nameTaken(Collection<Waypoint> all, String name) {
        for (Waypoint w : all) {
            if (w.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }
}
