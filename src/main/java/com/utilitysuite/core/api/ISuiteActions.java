package com.utilitysuite.core.api;

import java.util.List;
import java.util.Optional;

/**
 * Registry of named suite actions. Modules register their entry points during construction; the radial wheel and the
 * command palette list and run them. Client thread for {@link #run}; registration order is kept.
 */
public interface ISuiteActions {

    /** Adds (or replaces, same id) an action. */
    void register(SuiteAction action);

    /** Removes an action (dynamic entries such as user-named loadouts). Returns false when there was none. */
    default boolean unregister(String id) {
        return false;
    }

    /** Every action in registration order (a snapshot). */
    List<SuiteAction> all();

    Optional<SuiteAction> byId(String id);

    /** Runs the action with this id. Returns false when there is none (or it threw). */
    boolean run(String id);
}
