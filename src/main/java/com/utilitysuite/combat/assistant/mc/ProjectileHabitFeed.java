package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.PendingSpawns;
import com.utilitysuite.combat.tactics.memory.HabitRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Feeds the opponent-habit record (C6) from entity join events: when an ender pearl, an arrow, a trident or a
 * player's wind charge joins the client level, its kind, owner id (from the spawn packet; -1 when unsynced), spawn
 * position and velocity go to {@link CombatAssistant#onProjectileSpawn}, which keeps only the engaged target's own
 * throws and shots.
 * <p>
 * The velocity is not known at join on the client (it comes in a motion packet handled right after; playtest
 * 2026-11-25: four skeleton arrows all joined at speed 0 and none counted as a shot), so each spawn is parked in
 * {@link PendingSpawns} and reported from the next client tick's start, before the entity has moved. No level scans,
 * no allocation (type checks, field reads, a fixed primitive queue). Client thread.
 */
public final class ProjectileHabitFeed {

    private static final double KNOWN_SPEED_SQ = 1.0e-8;

    private final CombatAssistant assistant;
    private final PendingSpawns pending = new PendingSpawns(16);

    public ProjectileHabitFeed(CombatAssistant assistant) {
        this.assistant = assistant;
    }

    public void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        Entity e = event.getEntity();
        int kind;
        if (e instanceof ThrownEnderpearl) {
            kind = HabitRecorder.PROJ_PEARL;
        } else if (e instanceof ThrownTrident) {
            kind = HabitRecorder.PROJ_TRIDENT;
        } else if (e instanceof AbstractArrow) {
            kind = HabitRecorder.PROJ_ARROW;
        } else if (e instanceof WindCharge) {
            kind = HabitRecorder.PROJ_WIND; // a player's wind charge (a breeze's is a different class)
        } else {
            return;
        }
        Entity owner = ((Projectile) e).getOwner();
        pending.add(e.getId(), kind, owner == null ? -1 : owner.getId(), e.getX(), e.getY(), e.getZ());
    }

    /** Reports parked spawns whose velocity has arrived (or that waited long enough). */
    public void onClientTick(ClientTickEvent.Pre event) {
        if (pending.size() == 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            pending.clear();
            return;
        }
        for (int i = 0; i < pending.size(); ) {
            Entity e = mc.level.getEntity(pending.id(i));
            Vec3 v = e == null ? Vec3.ZERO : e.getDeltaMovement();
            if (!pending.ready(i, v.lengthSqr() > KNOWN_SPEED_SQ)) {
                i++;
                continue;
            }
            assistant.onProjectileSpawn(pending.kind(i), pending.owner(i), pending.x(i), pending.y(i), pending.z(i),
                    v.x, v.y, v.z);
            pending.remove(i);
        }
    }
}
