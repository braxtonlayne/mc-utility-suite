package com.utilitysuite.jobs.schematic.plan;

/**
 * Orders the support faces of a placement target by how little the camera has to turn to aim at them: the score of a
 * face is the cosine between the current look direction and the ray from the eye to the centre of the face that gets
 * clicked (the support block's side facing the target). Highest score first.
 * <p>
 * Face indices follow {@code net.minecraft.core.Direction.values()} (DOWN, UP, NORTH, SOUTH, WEST, EAST); each index is
 * the direction from the target to its support. Engine-agnostic and allocation-free: one instance reuses its arrays,
 * so it is safe to call from the per-tick target search (client thread only).
 */
public final class SupportFaceRanker {

    public static final int FACES = 6;
    static final int[] STEP_X = {0, 0, 0, 0, -1, 1};
    static final int[] STEP_Y = {-1, 1, 0, 0, 0, 0};
    static final int[] STEP_Z = {0, 0, -1, 1, 0, 0};

    private final double[] score = new double[FACES];
    private final int[] order = new int[FACES];
    private int count;

    /**
     * Ranks the faces in {@code validMask} (bit {@code d} set = face {@code d} has a solid support).
     *
     * @param tx target block x (likewise ty, tz)
     * @param ex eye position x (likewise ey, ez)
     * @param lx look direction x, any length (likewise ly, lz)
     * @return number of ranked faces; read them with {@link #face(int)}
     */
    public int rank(int tx, int ty, int tz, double ex, double ey, double ez, double lx, double ly, double lz,
                    int validMask) {
        double ll = Math.sqrt(lx * lx + ly * ly + lz * lz);
        if (ll > 1e-9) {
            lx /= ll;
            ly /= ll;
            lz /= ll;
        }
        count = 0;
        for (int d = 0; d < FACES; d++) {
            if ((validMask & (1 << d)) == 0) {
                continue;
            }
            double px = tx + 0.5 + STEP_X[d] * 0.5 - ex;
            double py = ty + 0.5 + STEP_Y[d] * 0.5 - ey;
            double pz = tz + 0.5 + STEP_Z[d] * 0.5 - ez;
            double pl = Math.sqrt(px * px + py * py + pz * pz);
            double s = pl < 1e-9 ? 1.0 : (px * lx + py * ly + pz * lz) / pl;
            // insertion sort, descending; ties keep Direction order (stable)
            int i = count++;
            while (i > 0 && score[i - 1] < s) {
                score[i] = score[i - 1];
                order[i] = order[i - 1];
                i--;
            }
            score[i] = s;
            order[i] = d;
        }
        return count;
    }

    /** The {@code i}-th best face of the last {@link #rank} call. */
    public int face(int i) {
        return order[i];
    }

    /** Best face of the last {@link #rank} call, or -1 when no face was valid. */
    public int best() {
        return count == 0 ? -1 : order[0];
    }

    /** Look direction x for a Minecraft yaw/pitch in degrees (same formula as {@code Entity.calculateViewVector}). */
    public static double lookX(float yawDeg, float pitchDeg) {
        return -Math.sin(Math.toRadians(yawDeg)) * Math.cos(Math.toRadians(pitchDeg));
    }

    public static double lookY(float pitchDeg) {
        return -Math.sin(Math.toRadians(pitchDeg));
    }

    public static double lookZ(float yawDeg, float pitchDeg) {
        return Math.cos(Math.toRadians(yawDeg)) * Math.cos(Math.toRadians(pitchDeg));
    }
}
