package com.utilitysuite.core.api;

import java.util.UUID;

/**
 * Read-only view of the ally roster for modules outside combat (e.g. Local Rules skipping allies when it looks for
 * strangers nearby). Registered by the combat bootstrap. Client thread only.
 */
@FunctionalInterface
public interface IAllyLookup {

    /** No allies at all; used while the combat module is absent. */
    IAllyLookup NONE = uuid -> false;

    /** True when the player with this UUID is on the ally roster (this world or global). */
    boolean isAlly(UUID uuid);
}
