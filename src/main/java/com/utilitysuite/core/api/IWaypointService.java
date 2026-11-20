package com.utilitysuite.core.api;

import java.util.List;
import java.util.Optional;

/** Saved waypoints and recorded breadcrumbs for the current world. Client thread for writes; reads return copies. */
public interface IWaypointService {

    List<Waypoint> all();

    Optional<Waypoint> byName(String name);

    Waypoint add(String name, String group, int x, int y, int z, String dimension);

    void remove(String id);

    /** The waypoint's coordinates converted to {@code dimension} (overworld/nether 8:1), as a packed BlockPos. */
    long positionIn(Waypoint waypoint, String dimension);

    /** Recorded breadcrumb trail, newest last, as packed BlockPos values. */
    long[] breadcrumbs();
}
