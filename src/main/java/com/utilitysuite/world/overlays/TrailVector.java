package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

/**
 * Sliding 2D PCA / dominant-axis corridor estimator over nearby {@link ChunkAgeTracker#OLD} chunks.
 * Computes a normalized 2D heading {@code (dx, dz)}, an anisotropy confidence in {@code [0, 1]}, and a
 * lookahead target chunk {@code (tx, tz)} for trail-following. Zero steady-state allocation.
 */
public final class TrailVector {

    private double dx;
    private double dz;
    private double confidence;
    private int targetChunkX;
    private int targetChunkZ;
    private int sampleCount;

    public double dx() {
        return dx;
    }

    public double dz() {
        return dz;
    }

    public double confidence() {
        return confidence;
    }

    public int targetChunkX() {
        return targetChunkX;
    }

    public int targetChunkZ() {
        return targetChunkZ;
    }

    public int sampleCount() {
        return sampleCount;
    }

    public boolean valid() {
        return sampleCount >= 2 && confidence > 0.0 && (dx != 0.0 || dz != 0.0);
    }

    public void reset() {
        dx = 0.0;
        dz = 0.0;
        confidence = 0.0;
        targetChunkX = 0;
        targetChunkZ = 0;
        sampleCount = 0;
    }

    /**
     * Estimates the dominant trail corridor around {@code (centerChunkX, centerChunkZ)} within
     * {@code radiusChunks}, projecting {@code lookaheadChunks} ahead along the corridor.
     */
    public boolean estimate(ChunkAgeTracker tracker, int centerChunkX, int centerChunkZ,
                            int radiusChunks, int lookaheadChunks) {
        return estimate(tracker.stateMap(), centerChunkX, centerChunkZ, radiusChunks, lookaheadChunks, 0.0, 0.0);
    }

    /**
     * Estimates the dominant trail corridor, using {@code (preferDx, preferDz)} (if non-zero) to resolve
     * the forward sign of the principal axis.
     */
    public boolean estimate(ChunkAgeTracker tracker, int centerChunkX, int centerChunkZ,
                            int radiusChunks, int lookaheadChunks, double preferDx, double preferDz) {
        return estimate(tracker.stateMap(), centerChunkX, centerChunkZ, radiusChunks, lookaheadChunks,
                preferDx, preferDz);
    }

    public boolean estimate(Long2ByteOpenHashMap state, int centerChunkX, int centerChunkZ,
                            int radiusChunks, int lookaheadChunks, double preferDx, double preferDz) {
        if (state == null || state.isEmpty() || radiusChunks <= 0) {
            reset();
            targetChunkX = centerChunkX;
            targetChunkZ = centerChunkZ;
            return false;
        }

        int r2 = radiusChunks * radiusChunks;
        int n = 0;
        double sumX = 0.0;
        double sumZ = 0.0;

        Long2ByteMap.FastEntrySet entries = (Long2ByteMap.FastEntrySet) state.long2ByteEntrySet();
        ObjectIterator<Long2ByteMap.Entry> it1 = entries.fastIterator();
        while (it1.hasNext()) {
            Long2ByteMap.Entry e = it1.next();
            if (e.getByteValue() != ChunkAgeTracker.OLD) {
                continue;
            }
            long k = e.getLongKey();
            int cx = BlockPosPacking.chunkX(k);
            int cz = BlockPosPacking.chunkZ(k);
            int ox = cx - centerChunkX;
            int oz = cz - centerChunkZ;
            if (ox * ox + oz * oz <= r2) {
                sumX += cx;
                sumZ += cz;
                n++;
            }
        }

        sampleCount = n;
        if (n < 2) {
            dx = 0.0;
            dz = 0.0;
            confidence = 0.0;
            targetChunkX = centerChunkX;
            targetChunkZ = centerChunkZ;
            return false;
        }

        double mx = sumX / n;
        double mz = sumZ / n;

        double cxx = 0.0;
        double czz = 0.0;
        double cxz = 0.0;
        ObjectIterator<Long2ByteMap.Entry> it2 = entries.fastIterator();
        while (it2.hasNext()) {
            Long2ByteMap.Entry e = it2.next();
            if (e.getByteValue() != ChunkAgeTracker.OLD) {
                continue;
            }
            long k = e.getLongKey();
            int cx = BlockPosPacking.chunkX(k);
            int cz = BlockPosPacking.chunkZ(k);
            int ox = cx - centerChunkX;
            int oz = cz - centerChunkZ;
            if (ox * ox + oz * oz <= r2) {
                double ux = cx - mx;
                double uz = cz - mz;
                cxx += ux * ux;
                czz += uz * uz;
                cxz += ux * uz;
            }
        }

        double trace = cxx + czz;
        double diff = cxx - czz;
        double disc = Math.sqrt(diff * diff + 4.0 * cxz * cxz);
        double lambda1 = 0.5 * (trace + disc);
        double lambda2 = 0.5 * (trace - disc);

        if (lambda1 <= 1e-9) {
            dx = 0.0;
            dz = 0.0;
            confidence = 0.0;
            targetChunkX = centerChunkX;
            targetChunkZ = centerChunkZ;
            return false;
        }

        double vx;
        double vz;
        if (Math.abs(cxz) > 1e-9) {
            if (cxx >= czz) {
                vx = lambda1 - czz;
                vz = cxz;
            } else {
                vx = cxz;
                vz = lambda1 - cxx;
            }
        } else if (cxx >= czz) {
            vx = 1.0;
            vz = 0.0;
        } else {
            vx = 0.0;
            vz = 1.0;
        }

        double len = Math.hypot(vx, vz);
        if (len <= 1e-9) {
            dx = 0.0;
            dz = 0.0;
            confidence = 0.0;
            targetChunkX = centerChunkX;
            targetChunkZ = centerChunkZ;
            return false;
        }
        vx /= len;
        vz /= len;

        // Disambiguate forward direction along the principal axis.
        if (preferDx != 0.0 || preferDz != 0.0) {
            if (vx * preferDx + vz * preferDz < 0.0) {
                vx = -vx;
                vz = -vz;
            }
        } else {
            double toMeanX = mx - centerChunkX;
            double toMeanZ = mz - centerChunkZ;
            if (vx * toMeanX + vz * toMeanZ < 0.0) {
                vx = -vx;
                vz = -vz;
            }
        }

        double anisotropy = Math.max(0.0, (lambda1 - lambda2) / (lambda1 + lambda2));
        double sampleScale = Math.min(1.0, n / 4.0);
        this.dx = vx;
        this.dz = vz;
        this.confidence = anisotropy * sampleScale;

        double proj = (centerChunkX - mx) * vx + (centerChunkZ - mz) * vz;
        this.targetChunkX = (int) Math.round(mx + (proj + lookaheadChunks) * vx);
        this.targetChunkZ = (int) Math.round(mz + (proj + lookaheadChunks) * vz);
        return true;
    }
}
