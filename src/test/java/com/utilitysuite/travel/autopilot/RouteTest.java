package com.utilitysuite.travel.autopilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteTest {

    private static final RouteParser.Resolver WAYPOINTS = name -> switch (name) {
        case "Home" -> new RouteParser.Stop(100, 70, -200, true, "Home");
        case "Mine Entrance" -> new RouteParser.Stop(-50, 12, 30, true, "Mine Entrance");
        default -> null;
    };

    @Test
    void parsesCoordinatesAndWaypoints() {
        RouteParser.Result r = RouteParser.parse("10 64 -20; 30,-40 > Home", WAYPOINTS);
        assertTrue(r.ok(), r.error());
        assertEquals(3, r.stops().size());
        assertEquals(new RouteParser.Stop(10, 64, -20, true, "10 64 -20"), r.stops().get(0));
        RouteParser.Stop xz = r.stops().get(1);
        assertEquals(30, xz.x());
        assertEquals(-40, xz.z());
        assertFalse(xz.hasY());
        assertEquals("Home", r.stops().get(2).label());
    }

    @Test
    void waypointNamesMayContainSpacesAndDecimalsAreFloored() {
        RouteParser.Result r = RouteParser.parse("Mine Entrance; -0.5 64 10.9", WAYPOINTS);
        assertTrue(r.ok());
        assertEquals(-50, r.stops().get(0).x());
        assertEquals(-1, r.stops().get(1).x());
        assertEquals(10, r.stops().get(1).z());
    }

    @Test
    void reportsErrors() {
        assertFalse(RouteParser.parse("", WAYPOINTS).ok());
        assertFalse(RouteParser.parse(" ; ", WAYPOINTS).ok());
        RouteParser.Result r = RouteParser.parse("Home; Nowhere", WAYPOINTS);
        assertFalse(r.ok());
        assertTrue(r.error().contains("Nowhere"));
        assertFalse(RouteParser.parse("1 2 3 4", null).ok());
        assertNull(RouteParser.parseCoordinates("abc 1"));
    }

    @Test
    void routeStateDistancesBearingsAndCorners() {
        RouteState s = new RouteState(RouteParser.parse("0 64 10; 10 64 10", null).stops());
        // From the origin to (0.5, 10.5): due south (+z) = yaw 0.
        assertEquals(0.0, s.bearing(0.5, 0.5), 1e-9);
        assertEquals(10.0, s.horizontalDistance(0.5, 0.5), 1e-9);
        assertEquals(20.0, s.remainingDistance(0.5, 0.5), 1e-9);
        assertEquals(90.0, s.cornerTurn(0.5, 0.5), 1e-9, "south then east is a right-angle corner");
        assertFalse(s.isFinalStop());
        assertTrue(s.advance());
        assertTrue(s.isFinalStop());
        assertFalse(s.advance());
        assertEquals(0.0, s.cornerTurn(0.5, 10.5));
    }
}
