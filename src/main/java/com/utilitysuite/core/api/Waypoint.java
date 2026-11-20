package com.utilitysuite.core.api;

/**
 * A saved location.
 *
 * @param group e.g. "Bases", "Mines", "Portals", "Death Sites"
 */
public record Waypoint(String id, String name, String group, int x, int y, int z, String dimension, int argbColor) {
}
