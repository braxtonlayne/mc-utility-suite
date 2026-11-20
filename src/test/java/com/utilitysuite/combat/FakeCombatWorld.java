package com.utilitysuite.combat;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.CombatWorld;

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
        return true;
    }
}
