package com.utilitysuite.core.input;

import com.utilitysuite.core.api.IHumanizer;
import com.utilitysuite.core.api.IInputSmoother;

/**
 * The core's {@link IHumanizer}: one per-session skill value and one primitive random stream shared by every module.
 * Engine free and allocation free (all state in primitive fields). Client thread only.
 * <ul>
 *   <li>Skill: drawn once from the session seed in [{@value #SKILL_MIN}, {@value #SKILL_MAX}] (a practised player),
 *       or a fixed override from the settings.</li>
 *   <li>Reaction: log-normal around the requested median (the median stays the median), spread shrinking with
 *       skill.</li>
 *   <li>Turn time: Fitts's law {@code a + b * log2(angle / width + 1)}, a and b shrinking with skill, never below
 *       {@code angle / 450} (the no-snap cap).</li>
 *   <li>Aim: a centre-weighted point inside the hitbox (triangular distribution per axis), tighter with skill.</li>
 * </ul>
 * With humanization off every call behaves like {@link IHumanizer#NEUTRAL} except {@link #nextUnit()}, which still
 * draws from the stream.
 */
public final class Humanizer implements IHumanizer {

    public static final double SKILL_MIN = 0.6;
    public static final double SKILL_MAX = 0.9;
    /** Narrowest target width Fitts's law sees (degrees); keeps tiny targets from producing huge index values. */
    static final double MIN_TARGET_WIDTH_DEG = 0.5;

    private final PrimitiveRandom random;
    private final double sessionSkill;
    private double skillOverride = Double.NaN;
    private double skillRequest = Double.NaN;
    private String skillRequestOwner;
    private boolean enabled = true;

    public Humanizer(PrimitiveRandom random) {
        this.random = random;
        this.sessionSkill = SKILL_MIN + (SKILL_MAX - SKILL_MIN) * random.nextDouble();
    }

    /** The shared generator (the smoother draws its curve and click jitter from the same stream). */
    public PrimitiveRandom random() {
        return random;
    }

    public void setEnabled(boolean on) {
        this.enabled = on;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Fixed skill in [0, 1]; NaN (or anything outside [0, 1]) returns to the per-session value. */
    public void setSkillOverride(double skill) {
        this.skillOverride = skill >= 0.0 && skill <= 1.0 ? skill : Double.NaN;
    }

    /** The value drawn for this session, ignoring any override. */
    public double sessionSkill() {
        return sessionSkill;
    }

    @Override
    public double skill() {
        if (!Double.isNaN(skillOverride)) {
            return skillOverride; // the user's fixed value wins
        }
        return Double.isNaN(skillRequest) ? sessionSkill : skillRequest;
    }

    @Override
    public void requestSkill(String moduleId, double skill) {
        if (moduleId == null || Double.isNaN(skill)) {
            return;
        }
        skillRequestOwner = moduleId;
        skillRequest = skill < 0.0 ? 0.0 : Math.min(skill, 1.0);
    }

    @Override
    public void clearSkillRequest(String moduleId) {
        if (moduleId != null && moduleId.equals(skillRequestOwner)) {
            skillRequestOwner = null;
            skillRequest = Double.NaN;
        }
    }

    /** The module holding the current skill request, or null. */
    public String skillRequestOwner() {
        return skillRequestOwner;
    }

    @Override
    public int reactionTicks(int medianTicks, int minTicks, int maxTicks) {
        int lo = Math.min(minTicks, maxTicks);
        int hi = Math.max(minTicks, maxTicks);
        if (!enabled || medianTicks <= 0) {
            return clamp(medianTicks, lo, hi);
        }
        double sigma = 0.35 - 0.2 * skill(); // 0.15 (sharp) .. 0.35 (loose)
        double v = medianTicks * Math.exp(sigma * random.nextGaussian());
        long r = Math.round(v);
        return (int) (r < lo ? lo : (r > hi ? hi : r));
    }

    @Override
    public double turnSeconds(double angleDeg, double targetWidthDeg) {
        double angle = Math.abs(angleDeg);
        if (!(angle > 1.0e-6)) {
            return 0.0;
        }
        double floor = angle / IInputSmoother.MAX_TURN_DEG_PER_SEC;
        if (!enabled) {
            return floor;
        }
        double s = skill();
        double a = 0.10 - 0.06 * s; // seconds: 0.04 (pro) .. 0.10
        double b = 0.16 - 0.08 * s; // seconds per bit: 0.08 (pro) .. 0.16
        double w = targetWidthDeg > MIN_TARGET_WIDTH_DEG ? targetWidthDeg : MIN_TARGET_WIDTH_DEG;
        double t = a + b * (Math.log(angle / w + 1.0) * INV_LN2);
        return t > floor ? t : floor;
    }

    private static final double INV_LN2 = 1.0 / Math.log(2.0);

    @Override
    public void aimOffset(double halfWidth, double halfHeight, double[] out) {
        if (!enabled) {
            out[0] = 0.0;
            out[1] = 0.0;
            return;
        }
        double spread = 0.35 + 0.4 * (1.0 - skill()); // fraction of the half extent used: 0.35 (pro) .. 0.75
        out[0] = triangular() * spread * Math.max(0.0, halfWidth);
        out[1] = triangular() * spread * Math.max(0.0, halfHeight);
    }

    /** Sum of two uniforms, centred: triangular on (-1, 1), densest at 0. */
    private double triangular() {
        return random.nextDouble() + random.nextDouble() - 1.0;
    }

    @Override
    public double nextUnit() {
        return random.nextDouble();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
