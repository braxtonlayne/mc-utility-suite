package com.utilitysuite.combat.tactics;

/**
 * The read side of {@link ArenaField}: what the tactics ask of the scanned arena. Split out so tactic code can be
 * tested against a scripted arena. See {@link ArenaField} for the meaning of every method. Allocation free.
 */
public interface ArenaQuery {

    double coverage();

    int floorY(int x, int z);

    int headroom(int x, int z);

    int floorKind(int x, int z);

    boolean evalKnock(double tx, double ty, double tz);

    double knockX();

    double knockZ();

    double knockValue();

    int knockKind();

    boolean findHighGround(double px, double py, double pz, double tx, double ty, double tz, double maxDist);

    boolean findLowCeiling(double px, double py, double pz, double tx, double ty, double tz, double maxDist);

    double spotX();

    double spotY();

    double spotZ();

    double spotGain();
}
