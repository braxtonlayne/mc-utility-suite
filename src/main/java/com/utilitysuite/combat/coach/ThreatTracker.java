package com.utilitysuite.combat.coach;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IDangerModel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
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

    /** AbstractArrow default base damage (the field is server-side state, never synced). */
    private static final double ARROW_BASE_DAMAGE = 2.0;
    private static final int CAPACITY = 128;
    private static final double CREEPER_RANGE_SQ = 5.0 * 5.0;
    private static final double TNT_RANGE_SQ = 7.0 * 7.0;

    private static final int MELEE_CAPACITY = 32;
    /** Melee candidates are only checked within this horizontal distance (reach 3 plus a step). */
    private static final double MELEE_RANGE = 3.6;
    /** Facing tolerance (cosine) for "winding up toward the player". */
    private static final double MELEE_FACING_COS = 0.7;
    private static final double CREEPER_POWER = 3.0;
    private static final double TNT_POWER = 4.0;

    private final Entity[] tracked = new Entity[CAPACITY];
    /** Per tracked entity: the game tick it joined and the velocity first seen (a launch record, set once). */
    private final long[] spawnTick = new long[CAPACITY];
    private final double[] spawnVx = new double[CAPACITY];
    private final double[] spawnVy = new double[CAPACITY];
    private final double[] spawnVz = new double[CAPACITY];
    private final boolean[] spawnVSet = new boolean[CAPACITY];
    private int size;
    private final LivingEntity[] melee = new LivingEntity[MELEE_CAPACITY];
    private int meleeSize;
    private int level = NONE;
    private float yaw;
    private int evaluatedTick = Integer.MIN_VALUE;

    // ---- per-kind danger (filled by evaluate; Integer.MAX_VALUE ticks = none) ------------------------------------
    /** Most imminent projectile on a hit course. */
    public int projTicks = Integer.MAX_VALUE;
    public float projDamage;
    /** That projectile's age in ticks since it joined the level, and the velocity it was first seen with. */
    public int projAgeTicks;
    public double projSpawnVx, projSpawnVy, projSpawnVz;
    /** World yaw from the player toward the source (Minecraft convention). */
    public float projBearing;
    /** That projectile's position relative to the player's body centre and its velocity. */
    public double projRx, projRy, projRz, projVx, projVy, projVz;
    /** Most imminent blast (lit creeper or primed TNT) within its damage radius. */
    public int blastTicks = Integer.MAX_VALUE;
    public float blastDamage;
    public float blastBearing;
    public double blastRx, blastRz, blastRadius;
    /** Nearest hostile facing the player within melee reach. */
    public int meleeTicks = Integer.MAX_VALUE;
    public float meleeDamage;
    public float meleeBearing;
    public int meleeEntityId = -1;

    public void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        Entity e = event.getEntity();
        if ((e instanceof Projectile || e instanceof Creeper || e instanceof PrimedTnt) && size < CAPACITY) {
            spawnTick[size] = e.level().getGameTime();
            spawnVSet[size] = false;
            tracked[size++] = e;
        }
        if ((e instanceof Monster || e instanceof Player) && !(e instanceof Creeper) && meleeSize < MELEE_CAPACITY
                && e instanceof LivingEntity le && e != Minecraft.getInstance().player) {
            melee[meleeSize++] = le;
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
        for (int i = 0; i < meleeSize; i++) {
            if (melee[i] == e) {
                removeMeleeAt(i);
                return;
            }
        }
    }

    private void removeMeleeAt(int i) {
        melee[i] = melee[--meleeSize];
        melee[meleeSize] = null;
    }

    public void clear() {
        for (int i = 0; i < size; i++) {
            tracked[i] = null;
        }
        size = 0;
        for (int i = 0; i < meleeSize; i++) {
            melee[i] = null;
        }
        meleeSize = 0;
        level = NONE;
        evaluatedTick = Integer.MIN_VALUE;
        resetDanger();
    }

    private void resetDanger() {
        projTicks = Integer.MAX_VALUE;
        projDamage = 0f;
        projAgeTicks = 0;
        blastTicks = Integer.MAX_VALUE;
        blastDamage = 0f;
        meleeTicks = Integer.MAX_VALUE;
        meleeDamage = 0f;
        meleeEntityId = -1;
    }

    /**
     * Pushes the per-kind dangers of the last {@link #evaluate} into the shared danger model: PROJECTILE, EXPLOSION
     * and MELEE are reported while present and cleared when gone. Allocation free.
     */
    public void publish(IDangerModel d) {
        if (projTicks != Integer.MAX_VALUE) {
            d.report(HazardKind.PROJECTILE, projTicks, projDamage, projBearing);
        } else if (d.has(HazardKind.PROJECTILE)) {
            d.clear(HazardKind.PROJECTILE);
        }
        if (blastTicks != Integer.MAX_VALUE) {
            d.report(HazardKind.EXPLOSION, blastTicks, blastDamage, blastBearing);
        } else if (d.has(HazardKind.EXPLOSION)) {
            d.clear(HazardKind.EXPLOSION);
        }
        if (meleeTicks != Integer.MAX_VALUE) {
            d.report(HazardKind.MELEE, meleeTicks, meleeDamage, meleeBearing);
        } else if (d.has(HazardKind.MELEE)) {
            d.clear(HazardKind.MELEE);
        }
    }

    /** Clears this tracker's kinds from the danger model (reflexes off, world unload). */
    public static void clearPublished(IDangerModel d) {
        d.clear(HazardKind.PROJECTILE);
        d.clear(HazardKind.EXPLOSION);
        d.clear(HazardKind.MELEE);
    }

    private void removeAt(int i) {
        tracked[i] = tracked[--size];
        tracked[size] = null;
        spawnTick[i] = spawnTick[size];
        spawnVx[i] = spawnVx[size];
        spawnVy[i] = spawnVy[size];
        spawnVz[i] = spawnVz[size];
        spawnVSet[i] = spawnVSet[size];
    }

    @Override
    public void evaluate() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && p.tickCount == evaluatedTick) {
            return; // the coach and the reflexes share one evaluation per tick
        }
        level = NONE;
        yaw = 0f;
        resetDanger();
        if (p == null) {
            return;
        }
        evaluatedTick = p.tickCount;
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
                if (!spawnVSet[i] && (v.x != 0.0 || v.y != 0.0 || v.z != 0.0)) {
                    spawnVx[i] = v.x;
                    spawnVy[i] = v.y;
                    spawnVz[i] = v.z;
                    spawnVSet[i] = true;
                }
                if (ThreatMath.incoming(rx, ry, rz, v.x, v.y, v.z)) {
                    kind = PROJECTILE;
                    double vv = v.x * v.x + v.y * v.y + v.z * v.z;
                    int ticks = Math.max(0, (int) Math.ceil(-(rx * v.x + ry * v.y + rz * v.z) / vv));
                    if (ticks < projTicks) {
                        projTicks = ticks;
                        projDamage = projectileDamage(pr, Math.sqrt(vv));
                        projBearing = ThreatMath.worldYaw(rx, rz);
                        projAgeTicks = (int) Math.max(0L, p.level().getGameTime() - spawnTick[i]);
                        projSpawnVx = spawnVx[i];
                        projSpawnVy = spawnVy[i];
                        projSpawnVz = spawnVz[i];
                        projRx = rx;
                        projRy = ry;
                        projRz = rz;
                        projVx = v.x;
                        projVy = v.y;
                        projVz = v.z;
                    }
                }
            } else if (e instanceof Creeper c) {
                float swell = c.getSwelling(1f);
                if (swell > 0f && dsq <= CREEPER_RANGE_SQ) {
                    kind = BLAST;
                }
                if (swell > 0f) {
                    noteBlast((int) Math.ceil((1f - swell) * 30f), c.isPowered() ? CREEPER_POWER * 2 : CREEPER_POWER,
                            rx, rz, dsq);
                }
            } else if (e instanceof PrimedTnt tnt) {
                if (dsq <= TNT_RANGE_SQ) {
                    kind = BLAST;
                }
                noteBlast(tnt.getFuse(), TNT_POWER, rx, rz, dsq);
            }
            if (kind != NONE && (kind > level || (kind == level && dsq < best))) {
                level = kind;
                best = dsq;
                yaw = ThreatMath.relativeYaw(rx, rz, p.getYRot());
            }
        }
        evaluateMelee(p, cx, p.getY(), cz);
    }

    private void noteBlast(int fuse, double power, double rx, double rz, double dsq) {
        double radius = power * 2.0;
        if (dsq >= radius * radius || fuse >= blastTicks) {
            return;
        }
        blastTicks = Math.max(0, fuse);
        blastDamage = (float) ThreatMath.blastDamage(Math.sqrt(dsq), power);
        blastBearing = ThreatMath.worldYaw(rx, rz);
        blastRx = rx;
        blastRz = rz;
        blastRadius = radius;
    }

    private static float projectileDamage(Projectile pr, double speed) {
        if (pr instanceof AbstractArrow a) {
            // base damage is not synced (and private in 26.3): every client-side arrow reads the default 2.0
            return (float) Math.ceil(speed * ARROW_BASE_DAMAGE);
        }
        if (pr instanceof AbstractHurtingProjectile) {
            return 6f; // fireballs, wither skulls, wind charges (knockback)
        }
        return 0.5f; // snowballs, eggs, pearls, potions: not worth a shield
    }

    private void evaluateMelee(LocalPlayer p, double cx, double feetY, double cz) {
        double best = MELEE_RANGE * MELEE_RANGE;
        for (int i = 0; i < meleeSize; i++) {
            LivingEntity e = melee[i];
            if (e.isRemoved()) {
                removeMeleeAt(i--);
                continue;
            }
            if (e == p || !e.isAlive() || Math.abs(e.getY() - feetY) > 2.0) {
                continue;
            }
            double rx = e.getX() - cx;
            double rz = e.getZ() - cz;
            double dsq = rx * rx + rz * rz;
            if (dsq >= best || dsq < 1.0e-4) {
                continue;
            }
            // facing the player: its head yaw looks back along -r
            double d = Math.sqrt(dsq);
            double hy = Math.toRadians(e.getYHeadRot());
            double facing = (Math.sin(hy) * rx - Math.cos(hy) * rz) / d;
            if (facing < MELEE_FACING_COS) {
                continue;
            }
            best = dsq;
            meleeTicks = e instanceof Player ? 2 : 4;
            meleeDamage = e instanceof Player ? 6f : 4f;
            meleeBearing = ThreatMath.worldYaw(rx, rz);
            meleeEntityId = e.getId();
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
