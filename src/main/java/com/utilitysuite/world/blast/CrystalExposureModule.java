package com.utilitysuite.world.blast;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.NoticeKind;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Crystal / respawn-anchor exposure (defensive read-out only): for end crystals and charged respawn anchors (outside
 * the Nether) within range, the vanilla explosion damage you would take ({@link ExplosionDamage}: exposure rays,
 * distance falloff, difficulty, armour + toughness, Resistance, Protection / Blast Protection), labelled over each
 * source; a notice when a single detonation would be lethal; the lowest-damage standable blocks next to you
 * highlighted; and a notice when you are being boxed in (obsidian appearing on three or more sides of you within the
 * last few seconds, an approximation of "player-placed").
 * <p>
 * Budgets: crystals gathered every {@link #INTERVAL} ticks into fixed arrays (capped); anchors found by a sweep of a
 * few block layers per tick; safe-spot candidates scored a few per tick; frames only draw cached results. Nothing is
 * drawn without a source near you. Off by default.
 */
public final class CrystalExposureModule extends WorldModule {

    public static final String ID = "crystal_exposure";
    static final int MAX_SOURCES = 8;
    static final int INTERVAL = 5;
    static final int LAYERS_PER_TICK = 4;
    static final int SPOTS_PER_TICK = 6;
    static final int SPOT_RADIUS = 2;
    static final int MAX_SPOTS = (SPOT_RADIUS * 2 + 1) * (SPOT_RADIUS * 2 + 1) * 3;
    static final int BEST = 3;
    static final int BOX_WINDOW_TICKS = 100;
    static final long NOTICE_COOLDOWN_MS = 4000L;
    private static final String[] LABELS = new String[101];

    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final ExplosionDamage.Solid solid = this::solidAt;
    private ClientLevel solidLevel;
    private long excluded = Long.MIN_VALUE; // the anchor cell (removed before it explodes)

    // sources: x, y, z centre, power, scales-with-difficulty-always, anchor cell
    private final double[] srcX = new double[MAX_SOURCES];
    private final double[] srcY = new double[MAX_SOURCES];
    private final double[] srcZ = new double[MAX_SOURCES];
    private final float[] srcPower = new float[MAX_SOURCES];
    private final long[] srcCell = new long[MAX_SOURCES];
    private final float[] srcDamage = new float[MAX_SOURCES];
    private final boolean[] srcLethal = new boolean[MAX_SOURCES];
    private int sources;
    // anchors found by the running sweep (published to `anchors` when it completes)
    private final long[] anchorBuild = new long[MAX_SOURCES];
    private int anchorBuildCount;
    private final long[] anchors = new long[MAX_SOURCES];
    private int anchorCount;
    private int sweepLayer;
    // safe spots
    private final long[] spotCell = new long[MAX_SPOTS];
    private final float[] spotDamage = new float[MAX_SPOTS];
    private int spotCount;
    private int spotCursor;
    private final long[] best = new long[BEST];
    private final float[] bestDamage = new float[BEST];
    private int bestCount;
    // protection
    private double armour;
    private double toughness;
    private int epf;
    private int resistanceAmp = -1;
    private int difficulty = ExplosionDamage.NORMAL;
    private double health = 20;
    private Holder<Enchantment> protection;
    private Holder<Enchantment> blastProtection;
    private ClientLevel holdersLevel;
    // boxing in
    private int lastSides;
    private int lastIncreaseTick = -10_000;
    private boolean boxed;
    private boolean lethal;
    private long lastLethalNotice;
    private long lastBoxNotice;
    private int ticks;

    private int range;
    private boolean anchorsOn;
    private boolean safeSpots;
    private boolean boxWarn;
    private boolean labels;

    public CrystalExposureModule() {
        super(ID, "Crystal Exposure", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("range", "Range", 12, 4, 16, "Sources", "Crystals and anchors within this "
                        + "distance are rated."),
                SettingSpec.bool("anchors", "Respawn anchors", true, "Sources",
                        "Also rate charged respawn anchors (they explode outside the Nether)."),
                SettingSpec.bool("safe_spots", "Safe foot blocks", true, "Display",
                        "Highlight the nearby blocks where a detonation hurts you least."),
                SettingSpec.bool("box_warning", "Box-in warning", true, "Warnings",
                        "Warn when obsidian appears around you on three or more sides."),
                SettingSpec.bool("labels", "Damage labels", true, "Display",
                        "Damage you would take, over each source."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        range = integer(p, "range");
        anchorsOn = bool(p, "anchors");
        safeSpots = bool(p, "safe_spots");
        boxWarn = bool(p, "box_warning");
        labels = bool(p, "labels");
    }

    @Override
    protected void onDisable() {
        clear();
    }

    @Override
    public void onWorldUnload() {
        clear();
        holdersLevel = null;
        protection = null;
        blastProtection = null;
    }

    private void clear() {
        sources = 0;
        anchorCount = 0;
        anchorBuildCount = 0;
        sweepLayer = 0;
        spotCount = 0;
        bestCount = 0;
        boxed = false;
        lethal = false;
        lastSides = 0;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        ClientLevel level = mc.level;
        if (self == null || level == null) {
            return;
        }
        ticks++;
        // positional attribute: sampled at the player (anchors nearby share the biome in practice)
        if (anchorsOn && !level.environmentAttributes().getValue(
                net.minecraft.world.attribute.EnvironmentAttributes.RESPAWN_ANCHOR_WORKS, self.position())) {
            sweepAnchors(level, self);
        } else {
            anchorCount = 0;
        }
        if (boxWarn) {
            checkBoxIn(level, self);
        }
        if (ticks % INTERVAL == 0) {
            if (ticks % (INTERVAL * 4) == 0 || holdersLevel != level) {
                readProtection(level, self);
            }
            gather(level, self);
            rateSources(level, self);
        }
        if (safeSpots && sources > 0) {
            scoreSpots(level);
        } else {
            spotCount = 0;
            bestCount = 0;
        }
    }

    // ---- sources ----------------------------------------------------------------------------------------------------

    private void sweepAnchors(ClientLevel level, LocalPlayer self) {
        int r = range;
        int px = self.getBlockX();
        int py = self.getBlockY();
        int pz = self.getBlockZ();
        for (int l = 0; l < LAYERS_PER_TICK; l++) {
            int y = py - r + sweepLayer;
            for (int x = px - r; x <= px + r; x++) {
                for (int z = pz - r; z <= pz + r; z++) {
                    if (anchorBuildCount >= MAX_SOURCES) {
                        break;
                    }
                    BlockState s = level.getBlockState(probe.set(x, y, z));
                    if (s.is(Blocks.RESPAWN_ANCHOR) && s.getValue(RespawnAnchorBlock.CHARGE) > 0) {
                        anchorBuild[anchorBuildCount++] = BlockPos.asLong(x, y, z);
                    }
                }
            }
            if (++sweepLayer > r * 2) {
                System.arraycopy(anchorBuild, 0, anchors, 0, anchorBuildCount);
                anchorCount = anchorBuildCount;
                anchorBuildCount = 0;
                sweepLayer = 0;
            }
        }
    }

    private void gather(ClientLevel level, LocalPlayer self) {
        int n = 0;
        double r2 = (double) range * range;
        for (Entity e : level.entitiesForRendering()) {
            if (n >= MAX_SOURCES) {
                break;
            }
            if (e instanceof EndCrystal && !e.isRemoved() && e.distanceToSqr(self) <= r2) {
                srcX[n] = e.getX();
                srcY[n] = e.getY();
                srcZ[n] = e.getZ();
                srcPower[n] = ExplosionDamage.POWER_CRYSTAL;
                srcCell[n] = Long.MIN_VALUE;
                n++;
            }
        }
        for (int i = 0; i < anchorCount && n < MAX_SOURCES; i++) {
            long c = anchors[i];
            int x = BlockPos.getX(c);
            int y = BlockPos.getY(c);
            int z = BlockPos.getZ(c);
            BlockState s = level.getBlockState(probe.set(x, y, z));
            if (!s.is(Blocks.RESPAWN_ANCHOR) || s.getValue(RespawnAnchorBlock.CHARGE) <= 0) {
                continue; // used or broken since the sweep
            }
            srcX[n] = x + 0.5;
            srcY[n] = y + 0.5;
            srcZ[n] = z + 0.5;
            srcPower[n] = ExplosionDamage.POWER_ANCHOR;
            srcCell[n] = c;
            n++;
        }
        sources = n;
    }

    private void readProtection(ClientLevel level, LocalPlayer self) {
        if (holdersLevel != level) {
            holdersLevel = level;
            var reg = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
            protection = reg.get(Enchantments.PROTECTION).orElse(null);
            blastProtection = reg.get(Enchantments.BLAST_PROTECTION).orElse(null);
        }
        armour = self.getArmorValue();
        toughness = self.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        int prot = 0;
        int blast = 0;
        for (EquipmentSlot slot : ARMOUR) {
            ItemStack s = self.getItemBySlot(slot);
            if (s.isEmpty()) {
                continue;
            }
            if (protection != null) {
                prot += EnchantmentHelper.getItemEnchantmentLevel(protection, s);
            }
            if (blastProtection != null) {
                blast += EnchantmentHelper.getItemEnchantmentLevel(blastProtection, s);
            }
        }
        epf = ExplosionDamage.epf(prot, blast);
        MobEffectInstance res = self.getEffect(MobEffects.RESISTANCE);
        resistanceAmp = res == null ? -1 : res.getAmplifier();
        difficulty = level.getDifficulty().getId();
    }

    private static final EquipmentSlot[] ARMOUR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private void rateSources(ClientLevel level, LocalPlayer self) {
        health = self.getHealth() + self.getAbsorptionAmount();
        boolean anyLethal = false;
        solidLevel = level;
        double fx = self.getX();
        double fy = self.getY();
        double fz = self.getZ();
        for (int i = 0; i < sources; i++) {
            float feet = (float) damageAt(i, fx, fy, fz);
            // head: distance from the eyes, exposure of the eye point alone (e.g. peeking over a wall)
            excluded = srcCell[i];
            double ey = fy + self.getEyeHeight();
            double dist = dist(i, fx, ey, fz);
            double expo = ExplosionDamage.blocked(fx, ey, fz, srcX[i], srcY[i], srcZ[i], solid) ? 0.0 : 1.0;
            float head = (float) reduce(i, ExplosionDamage.raw(srcPower[i], dist, expo));
            srcDamage[i] = Math.max(feet, head);
            srcLethal[i] = ExplosionDamage.lethal(srcDamage[i], health, 0);
            anyLethal |= srcLethal[i];
        }
        excluded = Long.MIN_VALUE;
        solidLevel = null;
        if (anyLethal && !lethal && System.currentTimeMillis() - lastLethalNotice > NOTICE_COOLDOWN_MS) {
            lastLethalNotice = System.currentTimeMillis();
            Services.visuals().notices().post(NoticeKind.WARN, "Lethal blast in range",
                    "One crystal or anchor detonation would kill you here.");
        }
        lethal = anyLethal;
    }

    private double dist(int i, double x, double y, double z) {
        double dx = x - srcX[i];
        double dy = y - srcY[i];
        double dz = z - srcZ[i];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Vanilla damage from source i to a player with feet at (x, y, z). Needs {@code solidLevel} set. */
    private double damageAt(int i, double x, double y, double z) {
        excluded = srcCell[i];
        double expo = ExplosionDamage.exposure(srcX[i], srcY[i], srcZ[i], x, y, z, solid);
        return reduce(i, ExplosionDamage.raw(srcPower[i], dist(i, x, y, z), expo));
    }

    private double reduce(int i, double raw) {
        if (raw <= 0.0) {
            return 0.0;
        }
        // crystals popped by a player do not scale with difficulty, anchors always do: rate a crystal at the worse
        double d = srcCell[i] == Long.MIN_VALUE
                ? Math.max(raw, ExplosionDamage.difficulty(raw, difficulty))
                : ExplosionDamage.difficulty(raw, difficulty);
        d = ExplosionDamage.armour(d, armour, toughness);
        d = ExplosionDamage.resistance(d, resistanceAmp);
        return ExplosionDamage.enchantments(d, epf);
    }

    // ---- safe spots -------------------------------------------------------------------------------------------------

    private void scoreSpots(ClientLevel level) {
        LocalPlayer self = Minecraft.getInstance().player;
        if (spotCursor >= spotCount) {
            publishBest();
            collectSpots(level, self);
            spotCursor = 0;
        }
        solidLevel = level;
        for (int k = 0; k < SPOTS_PER_TICK && spotCursor < spotCount; k++, spotCursor++) {
            long c = spotCell[spotCursor];
            double x = BlockPos.getX(c) + 0.5;
            double y = BlockPos.getY(c);
            double z = BlockPos.getZ(c) + 0.5;
            float worst = 0f;
            for (int i = 0; i < sources; i++) {
                worst = Math.max(worst, (float) damageAt(i, x, y, z));
            }
            spotDamage[spotCursor] = worst;
        }
        excluded = Long.MIN_VALUE;
        solidLevel = null;
    }

    private void collectSpots(ClientLevel level, LocalPlayer self) {
        int n = 0;
        int px = self.getBlockX();
        int py = self.getBlockY();
        int pz = self.getBlockZ();
        solidLevel = level;
        excluded = Long.MIN_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -SPOT_RADIUS; dx <= SPOT_RADIUS; dx++) {
                for (int dz = -SPOT_RADIUS; dz <= SPOT_RADIUS; dz++) {
                    int x = px + dx;
                    int y = py + dy;
                    int z = pz + dz;
                    if (solidAt(x, y - 1, z) && !solidAt(x, y, z) && !solidAt(x, y + 1, z) && n < MAX_SPOTS) {
                        spotCell[n++] = BlockPos.asLong(x, y, z);
                    }
                }
            }
        }
        solidLevel = null;
        spotCount = n;
    }

    /** Keeps the {@link #BEST} lowest-damage spots of the finished scoring round. */
    private void publishBest() {
        int count = 0;
        for (int i = 0; i < spotCount; i++) {
            float d = spotDamage[i];
            int at = count;
            while (at > 0 && bestDamage[at - 1] > d) {
                at--;
            }
            if (at >= BEST) {
                continue;
            }
            for (int j = Math.min(count, BEST - 1); j > at; j--) {
                best[j] = best[j - 1];
                bestDamage[j] = bestDamage[j - 1];
            }
            best[at] = spotCell[i];
            bestDamage[at] = d;
            count = Math.min(BEST, count + 1);
        }
        bestCount = count;
    }

    // ---- boxing in --------------------------------------------------------------------------------------------------

    private void checkBoxIn(ClientLevel level, LocalPlayer self) {
        int x = self.getBlockX();
        int y = self.getBlockY();
        int z = self.getBlockZ();
        int sides = obsidianSide(level, x + 1, y, z) + obsidianSide(level, x - 1, y, z)
                + obsidianSide(level, x, y, z + 1) + obsidianSide(level, x, y, z - 1);
        if (sides > lastSides) {
            lastIncreaseTick = ticks;
        }
        lastSides = sides;
        boolean now = BoxIn.boxed(sides, ticks - lastIncreaseTick, BOX_WINDOW_TICKS);
        if (now && !boxed && System.currentTimeMillis() - lastBoxNotice > NOTICE_COOLDOWN_MS) {
            lastBoxNotice = System.currentTimeMillis();
            Services.visuals().notices().post(NoticeKind.WARN, "Being boxed in",
                    "Obsidian is going up around you on three or more sides.");
        }
        boxed = now;
    }

    private int obsidianSide(ClientLevel level, int x, int y, int z) {
        return isObsidian(level.getBlockState(probe.set(x, y, z)))
                || isObsidian(level.getBlockState(probe.set(x, y + 1, z))) ? 1 : 0;
    }

    private static boolean isObsidian(BlockState s) {
        return s.is(Blocks.OBSIDIAN) || s.is(Blocks.CRYING_OBSIDIAN);
    }

    /** Engine-free box-in rule, for tests. */
    public static final class BoxIn {
        private BoxIn() {
        }

        /** Boxed when obsidian is on three or more sides and the count last went up within the window. */
        public static boolean boxed(int sides, int ticksSinceIncrease, int window) {
            return sides >= 3 && ticksSinceIncrease <= window;
        }
    }

    private boolean solidAt(int x, int y, int z) {
        if (excluded != Long.MIN_VALUE && BlockPos.asLong(x, y, z) == excluded) {
            return false;
        }
        ClientLevel level = solidLevel;
        BlockState s = level.getBlockState(probe.set(x, y, z));
        return !s.isAir() && !s.getCollisionShape(level, probe).isEmpty();
    }

    // ---- drawing ----------------------------------------------------------------------------------------------------

    @Override
    public void renderLines(RenderContext ctx) {
        if (sources == 0) {
            return;
        }
        for (int i = 0; i < sources; i++) {
            float x = ctx.rx(srcX[i]);
            float y = ctx.ry(srcY[i]);
            float z = ctx.rz(srcZ[i]);
            int c = srcLethal[i] ? 0xF0FF3030 : 0xC0FFB040;
            WorldRenderKit.box(ctx.lines, ctx.pose, x - 0.5f, y - 0.05f, z - 0.5f, x + 0.5f, y + 0.05f, z + 0.5f, c);
        }
        if (!safeSpots) {
            return;
        }
        for (int i = 0; i < bestCount; i++) {
            long c = best[i];
            float x0 = ctx.rx(BlockPos.getX(c));
            float y = ctx.ry(BlockPos.getY(c) + 0.02);
            float z0 = ctx.rz(BlockPos.getZ(c));
            int col = ExplosionDamage.lethal(bestDamage[i], health, 0) ? 0xC0FF8040 : 0xE040FF60;
            WorldRenderKit.square(ctx.lines, ctx.pose, x0 + 0.1f, y, z0 + 0.1f, x0 + 0.9f, z0 + 0.9f, col);
        }
    }

    @Override
    public void renderLabels(RenderContext ctx) {
        if (!labels || sources == 0) {
            return;
        }
        for (int i = 0; i < sources; i++) {
            Matrix4f m = ctx.kit.beginLabel(ctx.pose, ctx.rx(srcX[i]), ctx.ry(srcY[i] + 2.2), ctx.rz(srcZ[i]));
            String s = label(srcDamage[i]);
            WorldRenderKit.text(ctx.font, ctx.buffers, m, s, -ctx.font.width(s) / 2f,
                    srcLethal[i] ? 0xFFFF4040 : 0xFFFFD080);
        }
    }

    /** "7 dmg" for a damage value (hit points, 2 per heart); cached, so frames allocate nothing after warm-up. */
    static String label(float damage) {
        int v = Math.max(0, Math.min(LABELS.length - 1, Math.round(damage)));
        String s = LABELS[v];
        if (s == null) {
            s = v == LABELS.length - 1 ? v + "+ dmg" : v + " dmg";
            LABELS[v] = s;
        }
        return s;
    }

    // test / HUD accessors
    public boolean lethal() {
        return lethal;
    }

    public boolean boxedIn() {
        return boxed;
    }
}
