package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.assistant.LineOfSight;
import com.utilitysuite.combat.assistant.stages.NearbyThreats;
import com.utilitysuite.combat.coach.ThreatMath;
import com.utilitysuite.combat.tactics.ThreatRank;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enderman;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link NearbyThreats} over the client level. A refresh (every {@link NearbyThreats#REFRESH_TICKS} fight ticks, only
 * while a threat stage runs, i.e. while engaged) does one bounded entity query for mobs and one for projectiles within
 * {@link #RADIUS} blocks, the same cost class as the proactive scan; rows are the nearest {@link NearbyThreats#CAPACITY}.
 * Players, armour stands, the local player and owned pets are never rows.
 * <p>
 * "Already hostile to the player" ({@link ThreatRank#HOSTILE}) is read from synced state only: an aggressive mob
 * (its synced aggressive flag, or a staring enderman) that faces the player or stands next to it, a creeper swelling
 * next to the player, or a bow or loaded crossbow pointed at the player. The stage adds the entity that last hit the
 * player. A wandering zombie that has not noticed the player is a row without the flag and is never picked.
 * <p>
 * Client thread only. Allocations per refresh: the two query boxes, as the proactive scan; type id strings are
 * cached per entity type.
 */
public final class McNearbyThreats extends NearbyThreats implements LineOfSight.SolidTest {

    public static final double RADIUS = 16.0;
    static final double PROJECTILE_RADIUS = 24.0;
    /** Facing cosine for "looking at the player". */
    static final double FACING_COS = 0.5;
    static final double MELEE_FACING_COS = 0.7;
    static final double MELEE_RANGE = 3.0;
    static final double NEXT_TO = 2.5;
    static final double SWELL_NEAR = 7.5;
    static final int CREEPER_FUSE = 30;
    static final int LOS_MAX_STEPS = 64;
    static final int SCAN_CAP = 48;

    private static final EntityTypeTest<Entity, LivingEntity> LIVING = EntityTypeTest.forClass(LivingEntity.class);
    private static final EntityTypeTest<Entity, Projectile> PROJECTILES = EntityTypeTest.forClass(Projectile.class);

    private final List<LivingEntity> buffer = new ArrayList<>(SCAN_CAP);
    private final List<Projectile> shots = new ArrayList<>(SCAN_CAP);
    private final int[] order = new int[SCAN_CAP];
    private final double[] orderDist = new double[SCAN_CAP];
    private final Map<EntityType<?>, String> typeIds = new IdentityHashMap<>();
    private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
    private ClientLevel level;

    @Override
    protected void refresh() {
        clear();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        if (p == null || lvl == null) {
            return;
        }
        level = lvl;
        buffer.clear();
        lvl.getEntities(LIVING, p.getBoundingBox().inflate(RADIUS), McNearbyThreats::candidate, buffer, SCAN_CAP);
        double px = p.getX();
        double py = p.getY();
        double pz = p.getZ();
        // nearest first (insertion into a fixed order array; at most SCAN_CAP entries)
        int n = 0;
        for (int i = 0; i < buffer.size(); i++) {
            LivingEntity e = buffer.get(i);
            if (e == p) {
                continue;
            }
            double dx = e.getX() - px;
            double dz = e.getZ() - pz;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > RADIUS) {
                continue;
            }
            int j = n++;
            while (j > 0 && orderDist[j - 1] > d) {
                order[j] = order[j - 1];
                orderDist[j] = orderDist[j - 1];
                j--;
            }
            order[j] = i;
            orderDist[j] = d;
        }
        double yawRad = Math.toRadians(p.getYRot());
        double fwdX = -Math.sin(yawRad); // Minecraft facing: yaw 0 looks toward +z
        double fwdZ = Math.cos(yawRad);
        double eyeY = p.getEyeY();
        for (int k = 0; k < n && size < CAPACITY; k++) {
            LivingEntity e = buffer.get(order[k]);
            double d = orderDist[k];
            double rx = e.getX() - px;
            double rz = e.getZ() - pz;
            int f = rowFlags(e, rx, e.getY() - py, rz, d, fwdX, fwdZ);
            if (LineOfSight.clear(px, eyeY, pz, e.getX(), e.getEyeY(), e.getZ(), this, LOS_MAX_STEPS)) {
                f |= ThreatRank.LOS;
            }
            int fuse = 0;
            if (e instanceof Creeper c && (f & ThreatRank.SWELLING) != 0) {
                fuse = Math.max(0, (int) Math.ceil((1f - c.getSwelling(1f)) * CREEPER_FUSE));
            }
            add(e.getId(), typeId(e), e.getX(), e.getY(), e.getZ(), d, e.getHealth() + e.getAbsorptionAmount(), f,
                    fuse);
        }
        buffer.clear();
        scanProjectiles(lvl, p);
    }

    private void scanProjectiles(ClientLevel lvl, LocalPlayer p) {
        shots.clear();
        lvl.getEntities(PROJECTILES, p.getBoundingBox().inflate(PROJECTILE_RADIUS), McNearbyThreats::liveShot, shots,
                SCAN_CAP);
        double cx = p.getX();
        double cy = p.getY() + p.getBbHeight() * 0.5;
        double cz = p.getZ();
        double best = Double.MAX_VALUE;
        for (int i = 0; i < shots.size(); i++) {
            Projectile pr = shots.get(i);
            if (pr.getOwner() == p) {
                continue;
            }
            double rx = pr.getX() - cx;
            double ry = pr.getY() - cy;
            double rz = pr.getZ() - cz;
            Vec3 v = pr.getDeltaMovement();
            double dsq = rx * rx + ry * ry + rz * rz;
            if (dsq < best && ThreatMath.incoming(rx, ry, rz, v.x, v.y, v.z)) {
                best = dsq;
                projectileIncoming = true;
                projectileRx = rx;
                projectileRz = rz;
            }
        }
        shots.clear();
    }

    private static boolean candidate(LivingEntity e) {
        if (!e.isAlive() || e.isRemoved() || e instanceof Player || e instanceof ArmorStand) {
            return false;
        }
        if (e instanceof OwnableEntity o && o.getOwnerReference() != null) {
            return false; // a pet (ours or someone's): never a fight row
        }
        return e instanceof Enemy || (e instanceof Mob m && m.isAggressive());
    }

    private static boolean liveShot(Projectile pr) {
        return pr.isAlive() && !pr.isRemoved();
    }

    /** Row flags from synced state ({@link ThreatRank} bits, without LOS). */
    static int rowFlags(LivingEntity e, double rx, double ry, double rz, double dist, double fwdX, double fwdZ) {
        int f = 0;
        double facing = facing(e, rx, rz, dist);
        boolean aggressive = e instanceof Mob m && m.isAggressive() || e instanceof Enderman em && em.isCreepy();
        boolean enemy = e instanceof Enemy && (!(e instanceof NeutralMob) || aggressive);
        boolean swelling = e instanceof Creeper c && c.getSwellDir() > 0;
        boolean drawing = drawing(e) && facing >= FACING_COS;
        if (swelling) {
            f |= ThreatRank.SWELLING;
        }
        if (drawing) {
            f |= ThreatRank.DRAWING;
        }
        if (e instanceof RangedAttackMob || e instanceof Ghast || e instanceof Blaze) {
            f |= ThreatRank.RANGED;
        }
        if (e.isBaby()) {
            f |= ThreatRank.BABY;
        }
        if (rx * fwdX + rz * fwdZ < 0.0) {
            f |= ThreatRank.BEHIND;
        }
        if (dist <= MELEE_RANGE && Math.abs(ry) <= 2.0 && facing >= MELEE_FACING_COS) {
            f |= ThreatRank.MELEE_IN_RANGE;
        }
        boolean hostile = (aggressive && (facing >= FACING_COS || dist <= NEXT_TO))
                || (swelling && dist <= SWELL_NEAR) || drawing;
        if (hostile && (enemy || aggressive)) {
            f |= ThreatRank.HOSTILE;
        }
        return f;
    }

    /** Cosine between the entity's head yaw and the direction back toward the player. */
    private static double facing(LivingEntity e, double rx, double rz, double dist) {
        if (dist < 1.0e-3) {
            return 1.0;
        }
        double hy = Math.toRadians(e.getYHeadRot());
        return (Math.sin(hy) * rx - Math.cos(hy) * rz) / dist;
    }

    private static boolean drawing(LivingEntity e) {
        if (e.isUsingItem() && e.getUseItem().getItem() instanceof ProjectileWeaponItem) {
            return true;
        }
        ItemStack main = e.getMainHandItem(); // a loaded crossbow held by a mob that has a target
        return e instanceof Mob m && m.isAggressive() && main.getItem() instanceof CrossbowItem
                && CrossbowItem.isCharged(main);
    }

    private String typeId(LivingEntity e) {
        EntityType<?> type = e.getType();
        String id = typeIds.get(type);
        if (id == null) {
            id = BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(); // once per type
            typeIds.put(type, id);
        }
        return id;
    }

    /** {@link LineOfSight.SolidTest}: a block with a collision shape blocks sight. */
    @Override
    public boolean solid(int x, int y, int z) {
        ClientLevel lvl = level;
        if (lvl == null) {
            return false;
        }
        mpos.set(x, y, z);
        return !lvl.getBlockState(mpos).getCollisionShape(lvl, mpos).isEmpty();
    }

    @Override
    public void reset() {
        super.reset();
        level = null;
        buffer.clear();
        shots.clear();
    }
}
