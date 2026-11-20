package com.utilitysuite.travel.autopilot;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * Budgeted search for an elytra landing site: a grid of candidate columns in a frame (centre + forward axis) is sampled
 * {@value #CANDIDATES_PER_TICK} per tick with heightmap lookups and scored by {@link LandingSiteScorer}; the best
 * column wins when the grid is done. Uses only the client-maintained MOTION_BLOCKING heightmap; trees are recognised by
 * the top block (leaves/logs). No allocation per candidate.
 */
final class LandingSearch {

    static final int CANDIDATES_PER_TICK = 24;

    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private double cx;
    private double cz;
    private double fx;
    private double fz;
    private int uMin;
    private int uMax;
    private int vHalf;
    private int spacing;
    private int cursor;
    private int total;
    private boolean active;
    private boolean done;

    private double bestScore;
    private int bestX;
    private int bestY;
    private int bestZ;

    /**
     * Starts a search. Candidates are {@code centre + u*spacing*forward + v*spacing*right} for u in [uMin, uMax] and
     * v in [-vHalf, vHalf].
     */
    void start(double centreX, double centreZ, double forwardX, double forwardZ, int uMin, int uMax, int vHalf,
               int spacing) {
        double len = Math.sqrt(forwardX * forwardX + forwardZ * forwardZ);
        this.cx = centreX;
        this.cz = centreZ;
        this.fx = len < 1e-6 ? 0 : forwardX / len;
        this.fz = len < 1e-6 ? 1 : forwardZ / len;
        this.uMin = uMin;
        this.uMax = uMax;
        this.vHalf = vHalf;
        this.spacing = spacing;
        this.cursor = 0;
        this.total = (uMax - uMin + 1) * (2 * vHalf + 1);
        this.active = true;
        this.done = false;
        this.bestScore = LandingSiteScorer.REJECT;
    }

    boolean active() {
        return active;
    }

    boolean done() {
        return done;
    }

    boolean found() {
        return done && bestScore > LandingSiteScorer.REJECT;
    }

    int x() {
        return bestX;
    }

    int y() {
        return bestY;
    }

    int z() {
        return bestZ;
    }

    void cancel() {
        active = false;
        done = false;
    }

    /** Samples the next batch. {@code px/py/pz} is the glider's position. */
    void tick(Level level, double px, double py, double pz, double glideRatio) {
        if (!active || done) {
            return;
        }
        int side = 2 * vHalf + 1;
        for (int n = 0; n < CANDIDATES_PER_TICK && cursor < total; n++, cursor++) {
            int u = uMin + cursor / side;
            int v = -vHalf + cursor % side;
            // right vector = forward rotated: (-fz, fx)
            int x = (int) Math.floor(cx + (u * fx - v * fz) * spacing);
            int z = (int) Math.floor(cz + (u * fz + v * fx) * spacing);
            if (!level.hasChunk(x >> 4, z >> 4)) {
                continue;
            }
            double s = sample(level, x, z, px, py, pz, glideRatio);
            if (s > bestScore) {
                bestScore = s;
                bestX = x;
                bestY = surfaceY;
                bestZ = z;
            }
        }
        if (cursor >= total) {
            done = true;
        }
    }

    private int surfaceY;

    private double sample(Level level, int x, int z, double px, double py, double pz, double glideRatio) {
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z); // first free y
        surfaceY = top - 1;
        BlockState state = level.getBlockState(pos.set(x, surfaceY, z));
        FluidState fluid = state.getFluidState();
        boolean lava = fluid.is(FluidTags.LAVA);
        boolean wet = !fluid.isEmpty();
        boolean foliage = state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS);
        boolean harmful = state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS) || state.is(BlockTags.FIRE)
                || state.is(BlockTags.CAMPFIRES) || state.is(Blocks.SWEET_BERRY_BUSH);
        int maxDelta = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int d = Math.abs(level.getHeight(Heightmap.Types.MOTION_BLOCKING, x + dx, z + dz) - top);
                if (d > maxDelta) {
                    maxDelta = d;
                }
            }
        }
        int headroom = 0;
        for (int y = top; y < top + LandingSiteScorer.MIN_HEADROOM + 1; y++) {
            BlockState s = level.getBlockState(pos.set(x, y, z));
            if (!s.isAir() && !s.getCollisionShape(level, pos).isEmpty()) {
                break;
            }
            headroom++;
        }
        double dx = x + 0.5 - px;
        double dz = z + 0.5 - pz;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        // The scorer takes a sample record; this runs only during a landing search (a few hundred columns once).
        return LandingSiteScorer.score(new LandingSiteScorer.Sample(surfaceY, wet, lava, foliage, harmful, maxDelta,
                headroom), horizontal, py - top, glideRatio);
    }
}
