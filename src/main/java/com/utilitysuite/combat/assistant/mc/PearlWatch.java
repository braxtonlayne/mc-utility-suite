package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.tactics.PearlSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/**
 * {@link PearlSource} over the client level: thrown ender pearls are tracked from entity join/leave events into a
 * small fixed array (no level scans, no allocation per query). Client thread only.
 * <p>
 * The owner of a client-side pearl can be unset on the first tick (or for good on some servers), so each pearl also
 * records its spawn position at join time; {@link #pearlOf} matches the owner id, else a spawn near the thrower's eye.
 */
public final class PearlWatch implements PearlSource {

    private static final int CAPACITY = 16;
    /** A pearl spawns at the thrower's eye: this close counts as "thrown by that entity". */
    private static final double SPAWN_RADIUS_SQ = 2.5 * 2.5;

    private final ThrownEnderpearl[] pearls = new ThrownEnderpearl[CAPACITY];
    /** Spawn position per pearl (x,y,z), recorded at join. */
    private final double[] spawn = new double[CAPACITY * 3];
    /** Join order: oldest at index 0, newest at size-1. */
    private int size;

    public PearlWatch() {
    }

    public void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        Entity e = event.getEntity();
        if (!(e instanceof ThrownEnderpearl pearl)) {
            return;
        }
        if (size == CAPACITY) {
            removeAt(0); // overwrite the oldest
        }
        pearls[size] = pearl;
        spawn[size * 3] = pearl.getX();
        spawn[size * 3 + 1] = pearl.getY();
        spawn[size * 3 + 2] = pearl.getZ();
        size++;
    }

    public void onLeave(EntityLeaveLevelEvent event) {
        if (size == 0) {
            return;
        }
        Entity e = event.getEntity();
        for (int i = 0; i < size; i++) {
            if (pearls[i] == e) {
                removeAt(i);
                return;
            }
        }
    }

    /** Drops every tracked pearl (logout, world unload). */
    public void clear() {
        for (int i = 0; i < size; i++) {
            pearls[i] = null;
        }
        size = 0;
    }

    /** Order-preserving removal so index size-1 stays the newest. */
    private void removeAt(int i) {
        int last = size - 1;
        for (int j = i; j < last; j++) {
            pearls[j] = pearls[j + 1];
            spawn[j * 3] = spawn[(j + 1) * 3];
            spawn[j * 3 + 1] = spawn[(j + 1) * 3 + 1];
            spawn[j * 3 + 2] = spawn[(j + 1) * 3 + 2];
        }
        pearls[last] = null;
        size = last;
    }

    @Override
    public boolean pearlOf(int throwerEntityId, double[] out) {
        Entity thrower = null; // resolved lazily, only for the owner-less fallback
        boolean throwerLooked = false;
        for (int i = size - 1; i >= 0; i--) {
            ThrownEnderpearl p = pearls[i];
            if (p.isRemoved()) {
                removeAt(i);
                continue;
            }
            Entity owner = p.getOwner();
            boolean match;
            if (owner != null) {
                match = owner.getId() == throwerEntityId;
            } else {
                if (!throwerLooked) {
                    thrower = p.level().getEntity(throwerEntityId);
                    throwerLooked = true;
                }
                match = thrower != null && nearSpawn(i, thrower);
            }
            if (match) {
                Vec3 v = p.getDeltaMovement();
                out[0] = p.getX();
                out[1] = p.getY();
                out[2] = p.getZ();
                out[3] = v.x;
                out[4] = v.y;
                out[5] = v.z;
                return true;
            }
        }
        return false;
    }

    private boolean nearSpawn(int i, Entity thrower) {
        double dx = spawn[i * 3] - thrower.getX();
        double dy = spawn[i * 3 + 1] - thrower.getEyeY();
        double dz = spawn[i * 3 + 2] - thrower.getZ();
        return dx * dx + dy * dy + dz * dz <= SPAWN_RADIUS_SQ;
    }
}
