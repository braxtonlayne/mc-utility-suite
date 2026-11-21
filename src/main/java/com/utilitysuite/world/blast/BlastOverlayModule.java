package com.utilitysuite.world.blast;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.WorldRuntime;
import com.utilitysuite.world.render.PoseLineSink;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.Shapes;
import com.utilitysuite.world.render.WorldRenderKit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Blast overlay: for swelling creepers, primed TNT and (setting) end crystals near you, draws the blast radius
 * (entities inside take damage) as a ground ring or a sphere outline, coloured by the fuse left, with a countdown label.
 * Entities are gathered every other tick into fixed arrays (capped); frames only iterate them.
 */
public final class BlastOverlayModule extends WorldModule {

    public static final String ID = "blast_overlay";
    private static final int MAX = 32;
    private static final int GATHER_INTERVAL = 2;
    private static final int KIND_CREEPER = 0;
    private static final int KIND_TNT = 1;
    private static final int KIND_CRYSTAL = 2;
    private static final int CRYSTAL_COLOR = 0xC0C070FF;

    private final Entity[] entities = new Entity[MAX];
    private final byte[] kinds = new byte[MAX];
    private int count;
    private int ticks;
    private final PoseLineSink sink = new PoseLineSink();

    private int range;
    private boolean sphere;
    private boolean crystals;
    private boolean labels;

    public BlastOverlayModule() {
        super(ID, "Blast Radius", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("range", "Range", 32, 8, 96, "Display", "Sources farther than this are ignored."),
                SettingSpec.bool("sphere", "Sphere outline", false, "Display",
                        "Draw a sphere instead of a ring on the ground."),
                SettingSpec.bool("crystals", "End crystals", true, "Sources", "Also mark end crystals (always armed)."),
                SettingSpec.bool("labels", "Fuse countdown", true, "Display", "Seconds left above the source."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        range = integer(p, "range");
        sphere = bool(p, "sphere");
        crystals = bool(p, "crystals");
        labels = bool(p, "labels");
    }

    @Override
    protected void onDisable() {
        clear();
    }

    @Override
    public void onWorldUnload() {
        clear();
    }

    private void clear() {
        java.util.Arrays.fill(entities, null);
        count = 0;
    }

    @Override
    public void onTick() {
        if (++ticks % GATHER_INTERVAL != 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        ClientLevel level = mc.level;
        int n = 0;
        if (self != null && level != null) {
            double r2 = WorldRuntime.sq(range);
            for (Entity e : level.entitiesForRendering()) {
                if (n >= MAX) {
                    break;
                }
                int kind;
                if (e instanceof Creeper c) {
                    if (c.getSwellDir() <= 0 && c.getSwelling(1f) <= 0f) {
                        continue;
                    }
                    kind = KIND_CREEPER;
                } else if (e instanceof PrimedTnt) {
                    kind = KIND_TNT;
                } else if (crystals && e instanceof EndCrystal) {
                    kind = KIND_CRYSTAL;
                } else {
                    continue;
                }
                if (e.distanceToSqr(self) > r2) {
                    continue;
                }
                entities[n] = e;
                kinds[n] = (byte) kind;
                n++;
            }
        }
        for (int i = n; i < count; i++) {
            entities[i] = null;
        }
        count = n;
    }

    /** Fuse ticks left for source i (crystals: 0, always armed). */
    private int ticksLeft(int i, float pt) {
        Entity e = entities[i];
        return switch (kinds[i]) {
            case KIND_CREEPER -> BlastMath.creeperTicksLeft(((Creeper) e).getSwelling(pt), BlastMath.CREEPER_FUSE);
            case KIND_TNT -> ((PrimedTnt) e).getFuse();
            default -> 0;
        };
    }

    private static float power(Entity e, int kind) {
        return switch (kind) {
            case KIND_CREEPER -> ((Creeper) e).isPowered() ? BlastMath.POWER_CHARGED_CREEPER : BlastMath.POWER_CREEPER;
            case KIND_TNT -> BlastMath.POWER_TNT;
            default -> BlastMath.POWER_END_CRYSTAL;
        };
    }

    private int color(int i, int ticksLeft) {
        return switch (kinds[i]) {
            case KIND_CREEPER -> BlastMath.fuseColor(ticksLeft, BlastMath.CREEPER_FUSE);
            case KIND_TNT -> BlastMath.fuseColor(ticksLeft, BlastMath.TNT_FUSE);
            default -> CRYSTAL_COLOR;
        };
    }

    @Override
    public void renderLines(RenderContext ctx) {
        if (count == 0) {
            return;
        }
        PoseLineSink s = sink.bind(ctx);
        float pt = ctx.partialTick;
        for (int i = 0; i < count; i++) {
            Entity e = entities[i];
            if (e == null || e.isRemoved()) {
                continue;
            }
            float x = ctx.rx(Mth.lerp(pt, e.xOld, e.getX()));
            float y = ctx.ry(Mth.lerp(pt, e.yOld, e.getY()));
            float z = ctx.rz(Mth.lerp(pt, e.zOld, e.getZ()));
            float r = BlastMath.damageRadius(power(e, kinds[i]));
            int c = color(i, ticksLeft(i, pt));
            if (sphere) {
                Shapes.sphereOutline(s, x, y + e.getBbHeight() * 0.0625f, z, r, c);
            } else {
                Shapes.ring(s, x, y + 0.05f, z, r, c);
            }
        }
    }

    @Override
    public void renderLabels(RenderContext ctx) {
        if (!labels || count == 0) {
            return;
        }
        float pt = ctx.partialTick;
        for (int i = 0; i < count; i++) {
            Entity e = entities[i];
            if (e == null || e.isRemoved() || kinds[i] == KIND_CRYSTAL) {
                continue;
            }
            int left = ticksLeft(i, pt);
            float x = ctx.rx(Mth.lerp(pt, e.xOld, e.getX()));
            float y = ctx.ry(Mth.lerp(pt, e.yOld, e.getY()) + e.getBbHeight() + 0.5);
            float z = ctx.rz(Mth.lerp(pt, e.zOld, e.getZ()));
            Matrix4f m = ctx.kit.beginLabel(ctx.pose, x, y, z);
            String s = BlastMath.fuseLabel(left);
            WorldRenderKit.text(ctx.font, ctx.buffers, m, s, -ctx.font.width(s) / 2f, color(i, left) | 0xFF000000);
        }
    }
}
