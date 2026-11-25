package com.utilitysuite.travel.autopilot;

/**
 * Engine-free reading of an ice-boat track ahead of the boat and the racing line through its next corner. The track is
 * the corridor of ice cells the boat sits in, taken along the cardinal axis nearest the heading: its width is measured
 * across the boat's row, then the centre line is walked forward until it is blocked. The side that opens just before
 * the block is the turn (an L corner); with neither side open it is a dead end. For a corner the line is the classic
 * outside-inside-outside one: turn-in on the outer edge of the approach, apex at the inner corner (kept a boat
 * half-width plus a little off the edge), exit on the outer side of the new leg, joined by a quadratic Bezier through
 * the apex. Corner speed is what the boat's thrust can bend: {@code v = sqrt(a * r)} with {@code a} the forward thrust
 * (a boat on ice has no lateral grip) and {@code r} the radius of the circle through the three points; the braking
 * point is that far before the turn-in that BACK brings the current speed down to it
 * ({@link BoatDriftSim#brakingDistance}). Preallocated outputs; no allocation per call.
 */
public final class IceTrackCorridor {

    public static final int MAX_HALF_WIDTH = 12;
    public static final int LINE_SAMPLES = 17;
    /** Distance kept from the track edges: the boat's half width and a little slack. */
    public static final double EDGE_MARGIN = BoatDriftSim.HALF_WIDTH + 0.15;
    /** Lateral acceleration a boat can apply (all of it from thrust). */
    public static final double LATERAL_ACCEL = BoatDriftSim.THRUST_FORWARD;
    public static final double DEAD_END_SPEED = 0.05;

    private final double[] lineX = new double[LINE_SAMPLES];
    private final double[] lineZ = new double[LINE_SAMPLES];
    private boolean corner;
    private boolean deadEnd;
    private boolean brakeNow;
    private double apexX;
    private double apexZ;
    private double brakeX;
    private double brakeZ;
    private double cornerSpeed;
    private double radius;
    private int turnSign;
    private double distToTurnIn;

    // local frame of the current call
    private BoatDriftSim.Surface s;
    private int bx;
    private int bz;
    private int dx;
    private int dz;
    private int px;
    private int pz;

    /**
     * @param yaw       heading in degrees (the velocity direction when moving, else the boat's yaw)
     * @param speed     blocks per tick
     * @param lookAhead blocks walked forward along the track
     * @return true when a corner or dead end was found (see {@link #corner()} / {@link #deadEnd()})
     */
    public boolean analyze(BoatDriftSim.Surface surface, double x, double z, double yaw, double speed,
                           int lookAhead) {
        corner = false;
        deadEnd = false;
        brakeNow = false;
        s = surface;
        bx = floor(x);
        bz = floor(z);
        int here = surface.surface(bx, bz);
        if (!BoatDriftSim.isIce(here)) {
            s = null;
            return false;
        }
        double friction = BoatDriftSim.friction(here);
        double fx = -Math.sin(Math.toRadians(yaw));
        double fz = Math.cos(Math.toRadians(yaw));
        if (Math.abs(fx) >= Math.abs(fz)) {
            dx = fx >= 0 ? 1 : -1;
            dz = 0;
        } else {
            dx = 0;
            dz = fz >= 0 ? 1 : -1;
        }
        px = -dz;
        pz = dx;
        int qmin = -extent(-1);
        int qmax = extent(1);
        int centre = Math.floorDiv(qmin + qmax, 2);
        int sWall = -1;
        for (int a = 1; a <= lookAhead; a++) {
            if (!track(a, centre)) {
                sWall = a;
                break;
            }
        }
        if (sWall < 0) {
            s = null;
            return false;
        }
        double boatS = (x - (bx + 0.5)) * dx + (z - (bz + 0.5)) * dz;
        boolean plus = sWall >= 2 && track(sWall - 1, qmax + 1);
        boolean minus = sWall >= 2 && track(sWall - 1, qmin - 1);
        if (!plus && !minus) {
            deadEnd = true;
            double stopS = sWall - 0.5 - BoatDriftSim.HALF_WIDTH;
            double brakeS = stopS - BoatDriftSim.brakingDistance(speed, DEAD_END_SPEED, friction);
            setBrake(brakeS, boatS, (qmin + qmax) * 0.5);
            distToTurnIn = stopS - boatS;
            s = null;
            return true;
        }
        if (plus && minus) {
            plus = fx * px + fz * pz >= 0.0; // T junction: the side the boat already drifts toward
        }
        turnSign = plus ? 1 : -1;
        if (!plus) {
            px = -px;
            pz = -pz;
            int t = qmin;
            qmin = -qmax;
            qmax = -t;
        }
        int b1 = sWall - 1;
        int b0 = b1;
        while (b0 - 1 >= 1 && track(b0 - 1, qmax + 1)) {
            b0--;
        }
        int w1 = qmax - qmin + 1;
        int w2 = b1 - b0 + 1;
        double qminE = qmin - 0.5;
        double qmaxE = qmax + 0.5;
        double qMid = (qminE + qmaxE) * 0.5;
        double b0E = b0 - 0.5;
        double b1E = b1 + 0.5;
        double sMid = (b0E + b1E) * 0.5;
        double lead = Math.max(w1, w2) + 1.0;
        double tS = Math.max(boatS, b0E - lead);
        double tQ = Math.min(qminE + EDGE_MARGIN, qMid);
        double aS = Math.min(b0E + EDGE_MARGIN, sMid);
        double aQ = Math.max(qmaxE - EDGE_MARGIN, qMid);
        double eS = Math.max(b1E - EDGE_MARGIN, sMid);
        double eQ = qmaxE + lead;
        // quadratic Bezier through the apex at t = 0.5
        double cS = 2.0 * aS - 0.5 * (tS + eS);
        double cQ = 2.0 * aQ - 0.5 * (tQ + eQ);
        for (int i = 0; i < LINE_SAMPLES; i++) {
            double t = i / (double) (LINE_SAMPLES - 1);
            double u = 1.0 - t;
            double ls = u * u * tS + 2 * u * t * cS + t * t * eS;
            double lq = u * u * tQ + 2 * u * t * cQ + t * t * eQ;
            lineX[i] = worldX(ls, lq);
            lineZ[i] = worldZ(ls, lq);
        }
        apexX = worldX(aS, aQ);
        apexZ = worldZ(aS, aQ);
        radius = circumradius(tS, tQ, aS, aQ, eS, eQ);
        cornerSpeed = Math.min(BoatDriftSim.terminalSpeed(friction), Math.sqrt(LATERAL_ACCEL * radius));
        double brakeS = tS - BoatDriftSim.brakingDistance(speed, cornerSpeed, friction);
        setBrake(brakeS, boatS, tQ);
        distToTurnIn = tS - boatS;
        corner = true;
        s = null;
        return true;
    }

    private void setBrake(double brakeS, double boatS, double q) {
        if (brakeS <= boatS) {
            brakeNow = true;
            brakeS = boatS;
        }
        brakeX = worldX(brakeS, q);
        brakeZ = worldZ(brakeS, q);
    }

    /** Contiguous ice cells from the boat's row toward +p (side 1) or -p (side -1), not counting the boat's cell. */
    private int extent(int side) {
        int n = 0;
        while (n < MAX_HALF_WIDTH && track(0, side * (n + 1))) {
            n++;
        }
        return n;
    }

    private boolean track(int a, int q) {
        return BoatDriftSim.isIce(s.surface(bx + a * dx + q * px, bz + a * dz + q * pz));
    }

    private double worldX(double ls, double lq) {
        return bx + 0.5 + ls * dx + lq * px;
    }

    private double worldZ(double ls, double lq) {
        return bz + 0.5 + ls * dz + lq * pz;
    }

    static double circumradius(double ax, double ay, double bx, double by, double cx, double cy) {
        double ab = Math.hypot(bx - ax, by - ay);
        double bc = Math.hypot(cx - bx, cy - by);
        double ca = Math.hypot(ax - cx, ay - cy);
        double area2 = Math.abs((bx - ax) * (cy - ay) - (by - ay) * (cx - ax));
        if (area2 < 1e-9) {
            return 1e6;
        }
        return ab * bc * ca / (2.0 * area2);
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    public boolean corner() {
        return corner;
    }

    public boolean deadEnd() {
        return deadEnd;
    }

    /** Already past the braking point: brake now. */
    public boolean brakeNow() {
        return brakeNow;
    }

    public double lineX(int i) {
        return lineX[i];
    }

    public double lineZ(int i) {
        return lineZ[i];
    }

    public double apexX() {
        return apexX;
    }

    public double apexZ() {
        return apexZ;
    }

    public double brakeX() {
        return brakeX;
    }

    public double brakeZ() {
        return brakeZ;
    }

    public double cornerSpeed() {
        return cornerSpeed;
    }

    public double radius() {
        return radius;
    }

    /** +1 / -1: which side of the approach axis the track turns to (relative to the axis' left-hand perpendicular). */
    public int turnSign() {
        return turnSign;
    }

    /** Blocks along the track from the boat to the turn-in point (or to the stop point at a dead end). */
    public double distToTurnIn() {
        return distToTurnIn;
    }
}
