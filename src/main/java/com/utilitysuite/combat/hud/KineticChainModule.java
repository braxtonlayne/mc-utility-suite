package com.utilitysuite.combat.hud;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.combat.tactics.KineticChainPlanner;
import com.utilitysuite.combat.tactics.KineticSpotScanner;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.IHudHost;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.core.api.visual.TextRole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.List;

/**
 * M1 kinetic chain calculator (visual only, off by default). While a wind charge is held: outlines the floor and wall
 * faces whose burst would launch the player ({@link KineticSpotScanner}, brighter = higher apex), and under the
 * crosshair shows the predicted apex of a self-launch from the targeted face. With a mace in the hotbar it adds the
 * predicted smash damage from that apex onto the targeted entity (or back onto the spot). Knockback is vanilla's wind
 * charge explosion ({@link KineticChainPlanner}, calibrated against a real launch in the 19-combatextra playtest).
 * <p>
 * Budget: one 7x7x7 scan every {@link #SCAN_EVERY} ticks; spots draw with one batched line buffer; readout strings are
 * cached per tenth so the render path does not allocate. Nothing draws unless a wind charge is held; Clean-Screen and
 * F1 hide everything. Never sends input.
 */
public final class KineticChainModule implements IModule, IConfigurable, HudElement {

    public static final String ID = "kinetic_chain";
    public static final String K_SPOTS = "show_spots";
    public static final String K_DAMAGE = "show_mace_damage";
    public static final String K_DENSITY = "assume_density";

    static final int SCAN_EVERY = 4;
    private static final int MAX_TENTHS = 2000;

    private final KineticSpotScanner scanner = new KineticSpotScanner(64);
    private final KineticChainPlanner.Launch launch = new KineticChainPlanner.Launch();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final KineticSpotScanner.Solid solid = this::solid;
    private final String[] apexLabels = new String[MAX_TENTHS + 1];
    private final String[] damageLabels = new String[MAX_TENTHS + 1];
    private Level level;
    private boolean enabled;
    private boolean showSpots = true;
    private boolean showDamage = true;
    private int density;
    private boolean hosted;

    // tick state
    private boolean active;
    private int timer;
    private int apexTenths = -1;
    private int damageTenths = -1;

    @Override
    public void onTick() {
        if (!hosted) {
            IVisuals vis = Services.visualsOrNull();
            if (vis != null && vis.hud() != IHudHost.NONE) {
                vis.hud().register(this);
                hosted = true;
            }
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        active = enabled && p != null && mc.level != null && holdingCharge(p);
        if (!active) {
            apexTenths = -1;
            timer = 0; // scan at once when a charge is held again (not the spots of where it was last held)
            return;
        }
        Vec3 v = p.getDeltaMovement();
        if (--timer <= 0) {
            timer = SCAN_EVERY;
            level = mc.level;
            scanner.scan(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, solid);
        }
        apexTenths = -1;
        damageTenths = -1;
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            Direction d = bh.getDirection();
            int f = KineticSpotScanner.faceOf(d.getStepX(), d.getStepY(), d.getStepZ());
            if (f >= 0) {
                BlockPos b = bh.getBlockPos();
                double sx = KineticChainPlanner.faceCoord(b.getX(), d.getStepX());
                double sy = KineticChainPlanner.faceCoord(b.getY(), d.getStepY());
                double sz = KineticChainPlanner.faceCoord(b.getZ(), d.getStepZ());
                KineticChainPlanner.evaluate(launch, p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, sx, sy, sz);
                if (launch.inRange && launch.ky > 0) {
                    apexTenths = tenths(launch.apex);
                    if (showDamage && hasMace(p.getInventory())) {
                        damageTenths = tenths(KineticChainPlanner.smashDamage(launch.apex, 0, density));
                    }
                }
            }
        } else if (hit instanceof EntityHitResult eh && hit.getType() == HitResult.Type.ENTITY && scanner.best >= 0) {
            // aiming at an entity: use the best spot's launch onto the entity's height
            apexTenths = tenths(scanner.apex[scanner.best]);
            if (showDamage && hasMace(p.getInventory())) {
                Entity e = eh.getEntity();
                damageTenths = tenths(KineticChainPlanner.smashDamage(scanner.apex[scanner.best], e.getY() - p.getY(),
                        density));
            }
        }
    }

    private static int tenths(double v) {
        return (int) Math.max(0, Math.min(MAX_TENTHS, Math.round(v * 10)));
    }

    private boolean solid(int x, int y, int z) {
        Level l = level;
        if (l == null || !l.hasChunk(x >> 4, z >> 4)) {
            return false;
        }
        pos.set(x, y, z);
        return !l.getBlockState(pos).getCollisionShape(l, pos).isEmpty();
    }

    private static boolean holdingCharge(LocalPlayer p) {
        return p.getMainHandItem().is(Items.WIND_CHARGE) || p.getOffhandItem().is(Items.WIND_CHARGE);
    }

    private static boolean hasMace(Inventory inv) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.MACE)) {
                return true;
            }
        }
        return false;
    }

    private boolean visible(Minecraft mc) {
        return enabled && active && !mc.gui.hud.isHidden() && !Services.modState().isCleanScreen();
    }

    // ---- HUD element: apex and mace-damage readout beside the crosshair ------------------------------------------

    @Override
    public HudSlot slot() {
        return HudSlot.CROSSHAIR;
    }

    @Override
    public boolean wantsDraw() {
        return enabled && active && apexTenths >= 0;
    }

    @Override
    public void draw(IHoloCanvas c, float cx, float cy, float alpha) {
        String a = apexLabels[apexTenths];
        if (a == null) {
            a = apexLabels[apexTenths] = "APEX " + (apexTenths / 10) + "." + (apexTenths % 10);
        }
        c.text(TextRole.MONO, a, cx + 8, cy + 8, HoloToken.ACCENT, alpha);
        if (damageTenths > 0) {
            String d = damageLabels[damageTenths];
            if (d == null) {
                d = damageLabels[damageTenths] = "MACE " + (damageTenths / 10) + "." + (damageTenths % 10);
            }
            c.text(TextRole.MONO, d, cx + 8, cy + 8 + c.lineHeight(TextRole.MONO), HoloToken.WARN, alpha);
        }
    }

    /** World pass: outline every launch face, brighter for a higher apex, in one batched line buffer. */
    public void onRenderLevel(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (!showSpots || !visible(mc) || scanner.count == 0) {
            return;
        }
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        spotCamX = cam.x;
        spotCamY = cam.y;
        spotCamZ = cam.z;
        lineWidth = Math.max(1f, mc.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth);
        event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), RenderTypes.linesTranslucent(),
                drawSpots);
    }

    /** The deferred draw (26.3 submits run later in the same frame): one instance. */
    private final SubmitNodeCollector.CustomGeometryRenderer drawSpots = this::drawSpots;
    private double spotCamX;
    private double spotCamY;
    private double spotCamZ;
    private float lineWidth = 1f;

    private void drawSpots(PoseStack.Pose last, VertexConsumer lines) {
        double top = scanner.best >= 0 ? Math.max(1e-3, scanner.apex[scanner.best]) : 1;
        double camX = spotCamX;
        double camY = spotCamY;
        double camZ = spotCamZ;
        for (int i = 0; i < scanner.count; i++) {
            float t = (float) (scanner.apex[i] / top);
            float r = 0.3f + 0.7f * t, gr = 0.8f, b = 1f - 0.6f * t, a = 0.35f + 0.6f * t;
            int f = scanner.face[i];
            int nx = KineticSpotScanner.normalX(f), ny = KineticSpotScanner.normalY(f);
            float x = (float) (scanner.bx[i] - camX), y = (float) (scanner.by[i] - camY);
            float z = (float) (scanner.bz[i] - camZ);
            // tangent axes of the face plane
            float ux, uy, uz, wx, wy, wz;
            if (ny != 0) {
                ux = 0.3f; uy = 0; uz = 0;
                wx = 0; wy = 0; wz = 0.3f;
            } else if (nx != 0) {
                ux = 0; uy = 0.3f; uz = 0;
                wx = 0; wy = 0; wz = 0.3f;
            } else {
                ux = 0.3f; uy = 0; uz = 0;
                wx = 0; wy = 0.3f; wz = 0;
            }
            edge(lines, last, x - ux - wx, y - uy - wy, z - uz - wz, x + ux - wx, y + uy - wy, z + uz - wz, r, gr, b, a);
            edge(lines, last, x + ux - wx, y + uy - wy, z + uz - wz, x + ux + wx, y + uy + wy, z + uz + wz, r, gr, b, a);
            edge(lines, last, x + ux + wx, y + uy + wy, z + uz + wz, x - ux + wx, y - uy + wy, z - uz + wz, r, gr, b, a);
            edge(lines, last, x - ux + wx, y - uy + wy, z - uz + wz, x - ux - wx, y - uy - wy, z - uz - wz, r, gr, b, a);
        }
    }

    private void edge(VertexConsumer c, PoseStack.Pose p, float x0, float y0, float z0, float x1, float y1,
                             float z1, float r, float g, float b, float a) {
        float nx = x1 - x0, ny = y1 - y0, nz = z1 - z0;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-5f) {
            return;
        }
        nx /= len;
        ny /= len;
        nz /= len;
        c.addVertex(p, x0, y0, z0).setColor(r, g, b, a).setNormal(p, nx, ny, nz).setLineWidth(lineWidth);
        c.addVertex(p, x1, y1, z1).setColor(r, g, b, a).setNormal(p, nx, ny, nz).setLineWidth(lineWidth);
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
            active = false;
            apexTenths = -1;
        }
    }

    // ---- read side (playtests, diagnostics; client thread) -----------------------------------------------------------

    /** Predicted apex of a launch from the targeted face, in tenths of a block, or -1. */
    public int apexTenths() {
        return apexTenths;
    }

    /** Predicted mace damage from that apex in tenths (0 or -1 without a mace or a smash). */
    public int damageTenths() {
        return damageTenths;
    }

    /** Launch faces found by the last scan (0 while inactive). */
    public int spotCount() {
        return active ? scanner.count : 0;
    }

    /** Highest predicted apex among the scanned faces, or -1. */
    public double bestApex() {
        return active && scanner.best >= 0 ? scanner.apex[scanner.best] : -1;
    }

    @Override
    public String displayName() {
        return "Kinetic Chain";
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        showSpots = p.getBool(ID, K_SPOTS, true);
        showDamage = p.getBool(ID, K_DAMAGE, true);
        density = p.getInt(ID, K_DENSITY, 0);
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_SPOTS, "Show launch spots", true, "",
                        "Outline floor and wall faces a wind charge would launch you from (brighter = higher)."),
                SettingSpec.bool(K_DAMAGE, "Show mace damage", true, "",
                        "With a mace in the hotbar, show the predicted smash damage from the launch apex."),
                SettingSpec.integer(K_DENSITY, "Assumed Density level", 0, 0, 5, "",
                        "Density enchantment level used for the damage prediction."));
    }
}
