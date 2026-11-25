package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.tactics.memory.HabitRecorder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.entity.projectile.windcharge.WindCharge;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Feeds the opponent-habit record (C6) from entity join events: when an ender pearl, an arrow, a trident or a
 * player's wind charge joins the client level, its kind, owner id (from the spawn packet; -1 when unsynced), spawn
 * position and velocity go to {@link CombatAssistant#onProjectileSpawn}, which keeps only the engaged target's own
 * throws and shots. No level scans, no allocation (type checks and field reads). Client thread.
 */
public final class ProjectileHabitFeed {

    private final CombatAssistant assistant;

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
        Vec3 v = e.getDeltaMovement();
        assistant.onProjectileSpawn(kind, owner == null ? -1 : owner.getId(), e.getX(), e.getY(), e.getZ(), v.x, v.y,
                v.z);
    }
}
