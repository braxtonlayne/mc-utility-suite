package com.utilitysuite.combat.coach;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * {@link ThreatSource} over the client level. Candidates (projectiles, creepers, primed TNT) are tracked from entity
 * join/leave events into a fixed array, so evaluation never scans the level and never allocates. Client thread only.
 */
public final class ThreatTracker implements ThreatSource {

    private static final int CAPACITY = 128;
    private static final double CREEPER_RANGE_SQ = 5.0 * 5.0;
    private static final double TNT_RANGE_SQ = 7.0 * 7.0;

    private final Entity[] tracked = new Entity[CAPACITY];
    private int size;
    private int level = NONE;
    private float yaw;

    public void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        Entity e = event.getEntity();
        if ((e instanceof Projectile || e instanceof Creeper || e instanceof PrimedTnt) && size < CAPACITY) {
            tracked[size++] = e;
        }
    }

    public void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        Entity e = event.getEntity();
        for (int i = 0; i < size; i++) {
            if (tracked[i] == e) {
                removeAt(i);
                return;
            }
        }
    }

    public void clear() {
        for (int i = 0; i < size; i++) {
            tracked[i] = null;
        }
        size = 0;
        level = NONE;
    }

    private void removeAt(int i) {
        tracked[i] = tracked[--size];
        tracked[size] = null;
    }

    @Override
    public void evaluate() {
        level = NONE;
        yaw = 0f;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return;
        }
        double cx = p.getX();
        double cy = p.getY() + p.getBbHeight() * 0.5;
        double cz = p.getZ();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < size; i++) {
            Entity e = tracked[i];
            if (e.isRemoved()) {
                removeAt(i--);
                continue;
            }
            double rx = e.getX() - cx;
            double ry = e.getY() - cy;
            double rz = e.getZ() - cz;
            double dsq = rx * rx + ry * ry + rz * rz;
            int kind = NONE;
            if (e instanceof Projectile pr) {
                if (pr.getOwner() == p) {
                    continue;
                }
                Vec3 v = pr.getDeltaMovement();
                if (ThreatMath.incoming(rx, ry, rz, v.x, v.y, v.z)) {
                    kind = PROJECTILE;
                }
            } else if (e instanceof Creeper c) {
                if (c.getSwelling(1f) > 0f && dsq <= CREEPER_RANGE_SQ) {
                    kind = BLAST;
                }
            } else if (e instanceof PrimedTnt && dsq <= TNT_RANGE_SQ) {
                kind = BLAST;
            }
            if (kind != NONE && (kind > level || (kind == level && dsq < best))) {
                level = kind;
                best = dsq;
                yaw = ThreatMath.relativeYaw(rx, rz, p.getYRot());
            }
        }
    }

    @Override
    public int level() {
        return level;
    }

    @Override
    public float relativeYawDeg() {
        return yaw;
    }
}
