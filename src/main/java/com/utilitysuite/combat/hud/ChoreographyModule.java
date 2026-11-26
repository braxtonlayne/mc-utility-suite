package com.utilitysuite.combat.hud;

import com.utilitysuite.combat.assistant.mc.ArenaVoxels;
import com.utilitysuite.combat.tactics.Afterimages;
import com.utilitysuite.combat.tactics.KnockbackLanding;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloWorld;
import com.utilitysuite.core.api.visual.IVisuals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;
import java.util.function.IntSupplier;

/**
 * Combat choreography overlay, while the Combat Assistant has a target: a ground ring around the opponent coloured by
 * whether they are inside the 3.0-block melee (crit / trade) distance, an arrow from them to where your next hit would
 * knock them ({@link KnockbackLanding}) that turns red with a landing mark when that is over a drop of more than three
 * blocks, and afterimage boxes of their last half second of positions ({@link Afterimages}). Read-out only: it never
 * acts. Tick work is one target lookup and one short point simulation; frames hand segments to the Shell's world kit
 * (one batch, AFTER_PARTICLES). Nothing is drawn without a target or in Clean-Screen. Off by default.
 */
public final class ChoreographyModule implements IModule, IConfigurable {

    public static final String ID = "combat_choreography";
    public static final String K_RING = "ring";
    public static final String K_ARROW = "knockback_arrow";
    public static final String K_AFTERIMAGES = "afterimages";
    public static final String K_REACH = "reach";
    static final double SHOW_RANGE = 16.0;

    private final IntSupplier target;
    private final Afterimages trail = new Afterimages();
    private final KnockbackLanding kb = new KnockbackLanding();
    private ArenaVoxels voxels;

    private boolean enabled;
    private boolean ring = true;
    private boolean arrow = true;
    private boolean afterimages = true;
    private double reach = 3.0;

    private int targetId = -1;
    private boolean inReach;
    private boolean hasLanding;
    private boolean ledge;
    private float halfWidth = 0.3f;
    private float height = 1.8f;

    public ChoreographyModule(IntSupplier target) {
        this.target = target;
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        ClientLevel level = mc.level;
        int id = target.getAsInt();
        Entity e = id < 0 || level == null || self == null ? null : level.getEntity(id);
        if (!(e instanceof LivingEntity t) || !t.isAlive() || t.distanceToSqr(self) > SHOW_RANGE * SHOW_RANGE) {
            reset();
            return;
        }
        targetId = id;
        halfWidth = t.getBbWidth() * 0.5f;
        height = t.getBbHeight();
        if (afterimages) {
            trail.push(id, t.getX(), t.getY(), t.getZ(), System.currentTimeMillis());
        }
        AABB box = t.getBoundingBox();
        inReach = KnockbackLanding.reach(self.getX(), self.getEyeY(), self.getZ(), box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ) <= reach;
        if (arrow) {
            if (voxels == null) {
                voxels = new ArenaVoxels();
            }
            Vec3 v = t.getDeltaMovement();
            double bonus = self.isSprinting() ? 1.0 : 0.0;
            kb.predict(t.getX(), t.getY(), t.getZ(), v.x, v.y, v.z, t.onGround(), self.getX(), self.getZ(),
                    self.getYRot(), bonus, t.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE), voxels);
            hasLanding = true;
            ledge = kb.ledge();
        } else {
            hasLanding = false;
        }
    }

    private void reset() {
        targetId = -1;
        hasLanding = false;
        ledge = false;
        trail.clear();
    }

    public void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || targetId < 0 || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Entity e = mc.level == null ? null : mc.level.getEntity(targetId);
        IVisuals visuals = Services.visualsOrNull();
        IHoloWorld world = visuals != null ? visuals.world() : null;
        if (e == null || world == null) {
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        float x = (float) (Mth.lerp(pt, e.xo, e.getX()) - cam.x);
        float y = (float) (Mth.lerp(pt, e.yo, e.getY()) - cam.y);
        float z = (float) (Mth.lerp(pt, e.zo, e.getZ()) - cam.z);
        if (ring) {
            HoloToken tone = inReach ? HoloToken.ALLY : HoloToken.WARN;
            world.ring(x, y + 0.03f, z, halfWidth + 0.45f, tone, 0.9f);
        }
        if (arrow && hasLanding) {
            float lx = (float) (kb.landX() - cam.x);
            float ly = (float) (kb.landY() - cam.y);
            float lz = (float) (kb.landZ() - cam.z);
            float sy = y + 0.1f;
            float ey = ledge ? sy : ly + 0.1f; // over a drop: keep the arrow level, the mark shows the fall
            HoloToken tone = ledge ? HoloToken.DANGER : HoloToken.ACCENT;
            world.ribbon(x, sy, z, lx, ey, lz, tone, 0.9f);
            float dx = lx - x;
            float dz = lz - z;
            float len = (float) Math.sqrt(dx * dx + dz * dz);
            if (len > 0.05f) {
                dx /= len;
                dz /= len;
                float hx = lx - dx * 0.3f;
                float hz = lz - dz * 0.3f;
                world.ribbon(lx, ey, lz, hx - dz * 0.18f, ey, hz + dx * 0.18f, tone, 0.9f);
                world.ribbon(lx, ey, lz, hx + dz * 0.18f, ey, hz - dx * 0.18f, tone, 0.9f);
            }
            if (ledge) {
                world.ribbon(lx, ey, lz, lx, ly, lz, HoloToken.DANGER, 0.6f);
                world.groundMark(lx, ly, lz, 0.8f, HoloToken.DANGER, 0.9f);
            }
        }
        if (afterimages) {
            long now = System.currentTimeMillis();
            int n = trail.size();
            for (int i = 0; i < n - 1; i++) { // the newest pose is the entity itself
                float a = trail.alpha(i, now);
                if (a <= 0.02f) {
                    continue;
                }
                float ax = (float) (trail.x(i) - cam.x);
                float ay = (float) (trail.y(i) - cam.y);
                float az = (float) (trail.z(i) - cam.z);
                world.boxEdges(ax - halfWidth, ay, az - halfWidth, ax + halfWidth, ay + height, az + halfWidth,
                        HoloToken.CAT_COMBAT, a * 0.45f);
            }
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.COMBAT;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            reset();
        }
    }

    @Override
    public void onStopAll() {
        reset();
    }

    // ---- read side (playtests, diagnostics; client thread) -----------------------------------------------------------

    /** The entity the overlay shows, or -1. */
    public int shownTarget() {
        return targetId;
    }

    /** The reach ring's state: true = inside melee distance (ALLY tone), false = outside (WARN). */
    public boolean inReach() {
        return inReach;
    }

    /** True when the knockback arrow has a landing this tick. */
    public boolean hasLanding() {
        return hasLanding;
    }

    /** True when the predicted knockback landing is over a drop of more than three blocks. */
    public boolean ledge() {
        return ledge;
    }

    public double landX() {
        return kb.landX();
    }

    public double landY() {
        return kb.landY();
    }

    public double landZ() {
        return kb.landZ();
    }

    /** Remembered afterimage poses. */
    public int afterimageCount() {
        return trail.size();
    }

    @Override
    public void onWorldUnload() {
        reset();
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        ring = p.getBool(ID, K_RING, true);
        arrow = p.getBool(ID, K_ARROW, true);
        afterimages = p.getBool(ID, K_AFTERIMAGES, true);
        reach = p.getDouble(ID, K_REACH, 3.0);
    }

    @Override
    public String displayName() {
        return "Combat Choreography";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_RING, "Reach ring", true, "",
                        "Ring under your target: green inside melee distance, amber outside."),
                SettingSpec.bool(K_ARROW, "Knockback arrow", true, "",
                        "Where your next hit sends them; red with a mark when that is over a drop of 3+ blocks."),
                SettingSpec.bool(K_AFTERIMAGES, "Afterimages", true, "",
                        "Fading boxes of where they were over the last half second."),
                SettingSpec.decimal(K_REACH, "Melee distance", 3.0, 2.0, 4.5, "",
                        "Eye-to-hitbox distance counted as in reach for the ring colour."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
