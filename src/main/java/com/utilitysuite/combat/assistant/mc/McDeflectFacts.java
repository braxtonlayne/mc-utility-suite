package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.assistant.stages.DeflectFacts;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.windcharge.AbstractWindCharge;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * Minecraft side of {@link DeflectFacts}: ghast fireballs and wind charges within {@link #RADIUS} that we did not
 * launch, nearest first. One bounded entity query into a reused list per {@link #refresh}. Client thread only.
 */
public final class McDeflectFacts implements DeflectFacts {

    static final double RADIUS = 24.0;
    static final int SCAN_CAP = 16;
    private static final EntityTypeTest<Entity, AbstractHurtingProjectile> HURTING =
            EntityTypeTest.forClass(AbstractHurtingProjectile.class);

    private final List<AbstractHurtingProjectile> buffer = new ArrayList<>(SCAN_CAP);
    private boolean found;
    private double x, y, z, vx, vy, vz;

    @Override
    public void refresh() {
        found = false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        if (p == null || lvl == null) {
            return;
        }
        buffer.clear();
        lvl.getEntities(HURTING, p.getBoundingBox().inflate(RADIUS), McDeflectFacts::deflectable, buffer, SCAN_CAP);
        double best = Double.MAX_VALUE;
        for (int i = 0; i < buffer.size(); i++) {
            AbstractHurtingProjectile pr = buffer.get(i);
            if (pr.getOwner() == p) {
                continue;
            }
            double d = pr.distanceToSqr(p);
            if (d < best) {
                best = d;
                found = true;
                x = pr.getX();
                y = pr.getY() + pr.getBbHeight() * 0.5;
                z = pr.getZ();
                Vec3 v = pr.getDeltaMovement();
                vx = v.x;
                vy = v.y;
                vz = v.z;
            }
        }
        buffer.clear();
    }

    private static boolean deflectable(AbstractHurtingProjectile pr) {
        return pr.isAlive() && !pr.isRemoved() && (pr instanceof LargeFireball || pr instanceof AbstractWindCharge);
    }

    @Override
    public boolean found() {
        return found;
    }

    @Override
    public double x() {
        return x;
    }

    @Override
    public double y() {
        return y;
    }

    @Override
    public double z() {
        return z;
    }

    @Override
    public double vx() {
        return vx;
    }

    @Override
    public double vy() {
        return vy;
    }

    @Override
    public double vz() {
        return vz;
    }
}
