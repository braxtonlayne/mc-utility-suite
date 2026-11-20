package com.utilitysuite.travel.autopilot;

/**
 * Two-dimensional (forward + vertical) port of vanilla 1.21.1 elytra flight ({@code LivingEntity.travel} fall-flying
 * branch) and the firework boost ({@code FireworkRocketEntity.tick} while attached), for controller tests.
 */
final class ElytraSim {

    static final int BOOST_TICKS = 16;

    double y;
    double vh;
    double vy;
    int boostLeft;
    double distance;

    ElytraSim(double y, double vh, double vy) {
        this.y = y;
        this.vh = vh;
        this.vy = vy;
    }

    void fireRocket() {
        boostLeft = BOOST_TICKS;
    }

    boolean boosting() {
        return boostLeft > 0;
    }

    /** One tick at the given pitch (degrees, positive down). */
    void tick(double pitchDeg) {
        double f = Math.toRadians(pitchDeg);
        double lf = Math.cos(f);  // horizontal look component
        double ly = -Math.sin(f); // vertical look component
        if (boostLeft > 0) {
            vh += lf * 0.1 + (lf * 1.5 - vh) * 0.5;
            vy += ly * 0.1 + (ly * 1.5 - vy) * 0.5;
            boostLeft--;
        }
        double c2 = lf * lf;
        vy += 0.08 * (-1.0 + c2 * 0.75);
        if (vy < 0.0) {
            double d5 = vy * -0.1 * c2;
            vh += d5;
            vy += d5;
        }
        if (f < 0.0) {
            double d9 = Math.abs(vh) * -Math.sin(f) * 0.04;
            vh -= d9;
            vy += d9 * 3.2;
        }
        vh *= 0.99;
        vy *= 0.98;
        y += vy;
        distance += vh;
    }
}
