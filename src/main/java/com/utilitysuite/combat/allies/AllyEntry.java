package com.utilitysuite.combat.allies;

import java.util.Objects;
import java.util.UUID;

/**
 * One roster player. {@code neverTarget} defaults to true when added (the assistant never locks onto them);
 * {@code defend} is an explicit opt-in (the assistant engages players who attack them).
 *
 * @param global true when stored in the global list (all worlds) rather than the current world's list
 */
public record AllyEntry(UUID uuid, String name, boolean neverTarget, boolean defend, boolean global) {

    public AllyEntry {
        Objects.requireNonNull(uuid, "uuid");
        name = name == null ? "" : name;
    }

    public static AllyEntry added(UUID uuid, String name, boolean global) {
        return new AllyEntry(uuid, name, true, false, global);
    }

    public AllyEntry withNeverTarget(boolean v) {
        return new AllyEntry(uuid, name, v, defend, global);
    }

    public AllyEntry withDefend(boolean v) {
        return new AllyEntry(uuid, name, neverTarget, v, global);
    }

    public AllyEntry withName(String n) {
        return new AllyEntry(uuid, n, neverTarget, defend, global);
    }
}
