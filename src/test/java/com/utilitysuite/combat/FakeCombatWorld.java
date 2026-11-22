package com.utilitysuite.combat;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.CombatWorld;
import com.utilitysuite.combat.assistant.TargetCandidates;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Test double for {@link CombatWorld}: a handful of entities and a template for the local player's state. */
public final class FakeCombatWorld implements CombatWorld {

    public static final class E {
        public final int id;
        public final UUID uuid;
        public final String name;
        public boolean living = true;
        public boolean alive = true;
        public boolean player;
        public double x, y, z;
        public double vx, vy, vz;
        public boolean blocking;
        /** Hostile mob (only meaningful for non-players). */
        public boolean hostile;
        public boolean aggressive;
        public boolean swelling;
        public boolean ranged;
        public float health = 20f;
        /** Line of sight from the local player (proactive scan). */
        public boolean visible = true;
        public boolean onGround = true;
        /** A food animal (cow, pig...). */
        public boolean food;
        /** Cooldown reading: an arm swing started this tick (cleared by the next sample), and its attack speed. */
        public boolean swingStarted;
        public double attackSpeed = 4.0;
        public int hurtTime;
        public float yaw;

        E(int id, String name) {
            this.id = id;
            this.name = name;
            this.uuid = UUID.nameUUIDFromBytes(name.getBytes());
        }
    }

    public final Map<Integer, E> entities = new HashMap<>();
    /** Player state copied into every sample (position comes from the local entity). */
    public final CombatSnapshot self = new CombatSnapshot();
    public int localId = 1;
    public boolean lineOfSight = true;
    public boolean crosshairOnTarget = true;
    public int crosshairId = -1;

    public FakeCombatWorld() {
        E me = add(localId, "LocalPlayer", 0, 64, 0);
        me.player = true;
        self.health = 20f;
        self.maxHealth = 20f;
        self.onGround = true;
        self.attackStrength = 1f;
        self.reach = 3.0;
        self.selectedSlot = 0;
    }

    public E add(int id, String name, double x, double y, double z) {
        E e = new E(id, name);
        e.x = x;
        e.y = y;
        e.z = z;
        entities.put(id, e);
        return e;
    }

    public E addPlayer(int id, String name, double x, double y, double z) {
        E e = add(id, name, x, y, z);
        e.player = true;
        return e;
    }

    public E local() {
        return entities.get(localId);
    }

    @Override
    public int localPlayerId() {
        return localId;
    }

    @Override
    public boolean isLivingAlive(int entityId) {
        E e = entities.get(entityId);
        return e != null && e.living && e.alive;
    }

    @Override
    public boolean isPlayer(int entityId) {
        E e = entities.get(entityId);
        return e != null && e.player;
    }

    @Override
    public UUID uuidOf(int entityId) {
        E e = entities.get(entityId);
        return e == null ? null : e.uuid;
    }

    @Override
    public String describe(int entityId) {
        E e = entities.get(entityId);
        return e == null ? "#" + entityId : e.name;
    }

    @Override
    public double distanceSqToLocal(int entityId) {
        E e = entities.get(entityId);
        E me = local();
        if (e == null) {
            return Double.MAX_VALUE;
        }
        double dx = e.x - me.x;
        double dy = e.y - me.y;
        double dz = e.z - me.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Single-player / allow-listed server. */
    public boolean allowed = true;

    @Override
    public int totemRefillSlot() {
        return self.offhandTotem || self.screenOpen ? -1 : self.totemInvSlot;
    }

    @Override
    public boolean extrasAllowedHere() {
        return allowed;
    }

    @Override
    public int crosshairEntityId() {
        return crosshairId;
    }

    @Override
    public boolean sample(int targetId, CombatSnapshot out) {
        E t = entities.get(targetId);
        E me = local();
        if (t == null || !t.living || !t.alive || me == null) {
            return false;
        }
        CombatSnapshot s = self;
        out.px = me.x;
        out.py = me.y;
        out.pz = me.z;
        out.eyeY = me.y + 1.62;
        out.yaw = s.yaw;
        out.pitch = s.pitch;
        out.health = s.health;
        out.maxHealth = s.maxHealth;
        out.absorption = s.absorption;
        out.onGround = s.onGround;
        out.inWater = s.inWater;
        out.climbing = s.climbing;
        out.usingItem = s.usingItem;
        out.screenOpen = s.screenOpen;
        out.fallDistance = s.fallDistance;
        out.attackStrength = s.attackStrength;
        out.reach = s.reach;
        out.selectedSlot = s.selectedSlot;
        out.offhandTotem = s.offhandTotem;
        out.totemInvSlot = s.totemInvSlot;
        out.gappleSlot = s.gappleSlot;
        out.axeSlot = s.axeSlot;
        out.maceSlot = s.maceSlot;
        out.windChargeSlot = s.windChargeSlot;
        out.gappleOnCooldown = s.gappleOnCooldown;
        out.windChargeOnCooldown = s.windChargeOnCooldown;
        out.sustainInvSlot = s.sustainInvSlot;
        out.emptyHotbarSlot = s.emptyHotbarSlot;
        out.hazardFront = s.hazardFront;
        out.hazardBack = s.hazardBack;
        out.hazardLeft = s.hazardLeft;
        out.hazardRight = s.hazardRight;
        out.tx = t.x;
        out.ty = t.y;
        out.tz = t.z;
        out.minX = t.x - 0.3;
        out.maxX = t.x + 0.3;
        out.minY = t.y;
        out.maxY = t.y + 1.8;
        out.minZ = t.z - 0.3;
        out.maxZ = t.z + 0.3;
        out.tvx = t.vx;
        out.tvy = t.vy;
        out.tvz = t.vz;
        out.targetBlocking = t.blocking;
        out.lineOfSight = lineOfSight;
        out.crosshairOnTarget = crosshairOnTarget;
        out.targetOnGround = t.onGround;
        out.pvx = s.pvx;
        out.pvy = s.pvy;
        out.pvz = s.pvz;
        out.sprinting = s.sprinting;
        out.targetYaw = t.yaw;
        out.targetSwingStarted = t.swingStarted;
        t.swingStarted = false; // one tick only
        out.targetAttackSpeed = t.attackSpeed;
        out.targetHurtTime = t.hurtTime;
        if (out.wantTerrain) {
            out.gapNear = s.gapNear;
            out.gapFar = s.gapFar;
            out.targetDropBelow = s.targetDropBelow;
            out.knockX = s.knockX;
            out.knockZ = s.knockZ;
            out.knockDepth = s.knockDepth;
        }
        return true;
    }

    public int invFood = 5;
    public int storedFood;
    /** Offset of the nearest food drop from the player, or null for none. */
    public double[] drop;

    @Override
    public int inventoryFood() {
        return invFood;
    }

    @Override
    public int storedFoodNearby(double radius) {
        return storedFood;
    }

    @Override
    public boolean nearestFoodDrop(double radius, double[] outXyz) {
        if (drop == null) {
            return false;
        }
        System.arraycopy(drop, 0, outXyz, 0, 3);
        return true;
    }

    public int scans;

    /** A body-sim provider whose bodies stand still (enough to build the production hit-trade solver in tests). */
    public static com.utilitysuite.core.api.IBodySimProvider stillBodies() {
        return StillBody::new;
    }

    static final class StillBody implements com.utilitysuite.core.api.BodySim {
        double x, y, z, vx, vy, vz;
        boolean onGround;

        @Override
        public com.utilitysuite.core.api.BodySim set(double x, double y, double z, double vx, double vy, double vz,
                                                     boolean onGround) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.onGround = onGround;
            return this;
        }

        @Override
        public com.utilitysuite.core.api.BodySim copyFrom(com.utilitysuite.core.api.BodySim o) {
            return set(o.x(), o.y(), o.z(), o.vx(), o.vy(), o.vz(), o.onGround());
        }

        @Override
        public void tick(int forward, int strafe, float yawDeg, boolean sprint, boolean jump) {
        }

        @Override
        public void knockback(double strength, double towardSourceX, double towardSourceZ) {
        }

        @Override
        public void scaleHorizontal(double factor) {
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

        @Override
        public boolean onGround() {
            return onGround;
        }

        @Override
        public double fallDistance() {
            return 0;
        }

        @Override
        public boolean horizontalCollision() {
            return false;
        }
    }

    @Override
    public void scanTargets(double radius, boolean computeLos, TargetCandidates out) {
        scans++;
        out.clear();
        for (E e : entities.values()) {
            if (e.id == localId || !e.living || !e.alive) {
                continue;
            }
            double d = distanceSqToLocal(e.id);
            if (d > radius * radius) {
                continue;
            }
            int f = (e.player ? TargetCandidates.PLAYER : 0) | (e.hostile ? TargetCandidates.HOSTILE : 0)
                    | (e.aggressive ? TargetCandidates.AGGRESSIVE : 0) | (e.swelling ? TargetCandidates.SWELLING : 0)
                    | (e.ranged ? TargetCandidates.RANGED : 0) | (e.food ? TargetCandidates.FOOD : 0) | (computeLos && e.visible ? TargetCandidates.LOS : 0);
            out.add(e.id, d, e.health, f);
        }
    }
}
