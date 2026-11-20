package com.utilitysuite.world.oresim;

import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.knowledge.PredictionSource;

import java.util.ArrayList;
import java.util.List;

/** Exposes the OreSim snapshots as a {@link PredictionSource} for the knowledge service. Any thread. */
public final class OreSimPredictions implements PredictionSource {

    private final OreSimService service;
    private final ThreadLocal<List<ChunkOreSnapshot>> scratch = ThreadLocal.withInitial(ArrayList::new);

    public OreSimPredictions(OreSimService service) {
        this.service = service;
    }

    @Override
    public void forEachPrediction(String dimension, long origin, int radius, Visitor visitor) {
        OreSimService.Config c = service.config();
        if (c == null || !c.dimension().equals(dimension)) {
            return;
        }
        List<ChunkOreSnapshot> list = scratch.get();
        list.clear();
        int cx = BlockPosPacking.x(origin) >> 4;
        int cz = BlockPosPacking.z(origin) >> 4;
        service.snapshotsNear(cx, cz, (radius >> 4) + 1, list);
        long r2 = (long) radius * radius;
        for (int s = 0; s < list.size(); s++) {
            ChunkOreSnapshot snap = list.get(s);
            for (int i = 0; i < snap.size(); i++) {
                long pos = snap.pos(i);
                if (BlockPosPacking.distSq(pos, origin) > r2) {
                    continue;
                }
                OreSpec spec = snap.spec(i);
                String id = snap.blockId(i);
                String alt = spec.deepslateOreId() == null ? null
                        : id.equals(spec.oreId()) ? spec.deepslateOreId() : spec.oreId();
                visitor.visit(pos, id, alt, snap.confidence(i), snap.clusterKey(i));
            }
        }
        list.clear();
    }

    private int find(ChunkOreSnapshot snap, long pos) {
        if (snap == null) {
            return -1;
        }
        for (int i = 0; i < snap.size(); i++) {
            if (snap.pos(i) == pos) {
                return i;
            }
        }
        return -1;
    }

    /** Veins may spill into neighbouring chunks, so the owning snapshot can be any of the 3 x 3 around the block. */
    private ChunkOreSnapshot owner(String dimension, long pos, int[] index) {
        OreSimService.Config c = service.config();
        if (c == null || !c.dimension().equals(dimension)) {
            return null;
        }
        int cx = BlockPosPacking.x(pos) >> 4;
        int cz = BlockPosPacking.z(pos) >> 4;
        for (int d = 0; d < 9; d++) {
            ChunkOreSnapshot snap = service.get(cx + (d % 3) - 1, cz + (d / 3) - 1);
            int i = find(snap, pos);
            if (i >= 0) {
                index[0] = i;
                return snap;
            }
        }
        return null;
    }

    @Override
    public long clusterAt(String dimension, long pos) {
        int[] idx = new int[1];
        ChunkOreSnapshot snap = owner(dimension, pos, idx);
        return snap == null ? NO_CLUSTER : snap.clusterKey(idx[0]);
    }

    @Override
    public String predictedIdAt(String dimension, long pos) {
        int[] idx = new int[1];
        ChunkOreSnapshot snap = owner(dimension, pos, idx);
        return snap == null ? null : snap.blockId(idx[0]);
    }
}
