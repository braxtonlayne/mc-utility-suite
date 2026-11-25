package com.utilitysuite.combat.boss;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.projectile.WitherSkull;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

import java.util.List;

/**
 * Boss-fight hints on the HUD status line (roadmap W13): dragon phase with perch countdown and perch odds, standing
 * in dragon breath, wither armour phase ("melee only") and incoming wither skulls. Entities are tracked from
 * join/leave events (no world scans); the tick only reads tracked fields and swaps between constant strings, rebuilding
 * the dragon line only when its phase or shown second changes. Off by default; Clean-Screen hides it (SuiteHud).
 */
public final class BossHelperModule implements IModule, IConfigurable {

    public static final String ID = "boss_helper";
    public static final String K_DRAGON = "dragon_hints";
    public static final String K_WITHER = "wither_hints";

    static final String IN_BREATH = "In dragon breath: move out";
    static final String BLUE_SKULL = "Blue wither skull incoming";
    static final String SKULL = "Wither skull incoming";
    static final String WITHER_ARMOUR = "Wither armoured: melee only";
    static final String WITHER_ARROWS = "Wither: arrows work";

    private static final int MAX_TRACKED = 16;
    private static volatile String line;

    private boolean enabled;
    private boolean dragonHints = true;
    private boolean witherHints = true;

    private EnderDragon dragon;
    private WitherBoss wither;
    private int crystals;
    private final WitherSkull[] skulls = new WitherSkull[MAX_TRACKED];
    private final AreaEffectCloud[] clouds = new AreaEffectCloud[MAX_TRACKED];

    private int lastPhase = -1;
    private int ticksInPhase;
    private int dragonKey = Integer.MIN_VALUE;
    private String dragonLine;

    /** The current status line or null. Client thread. */
    public static String line() {
        return line;
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
    public String displayName() {
        return "Boss Helper";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            line = null;
            clearMarks();
        }
    }

    // ---- tracking (game bus) -------------------------------------------------------------------------------------

    public void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        Entity e = event.getEntity();
        if (e instanceof EnderDragon d) {
            dragon = d;
        } else if (e instanceof WitherBoss w) {
            wither = w;
        } else if (e instanceof EndCrystal c) {
            crystals++;
            put(crystalRefs, c);
        } else if (e instanceof WitherSkull s) {
            put(skulls, s);
        } else if (e instanceof AreaEffectCloud c && c.getParticle().getType() == ParticleTypes.DRAGON_BREATH) {
            put(clouds, c);
        }
    }

    public void onLeave(EntityLeaveLevelEvent event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        Entity e = event.getEntity();
        if (e == dragon) {
            dragon = null;
        } else if (e == wither) {
            wither = null;
        } else if (e instanceof EndCrystal) {
            crystals = Math.max(0, crystals - 1);
            remove(crystalRefs, e);
        } else if (e instanceof WitherSkull) {
            remove(skulls, e);
        } else if (e instanceof AreaEffectCloud) {
            remove(clouds, e);
        }
    }

    private static <T> void put(T[] arr, T v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == null) {
                arr[i] = v;
                return;
            }
        }
    }

    private static void remove(Object[] arr, Object v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == v) {
                arr[i] = null;
            }
        }
    }

    // ---- tick ----------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || (dragon == null && wither == null && !anyTracked())) {
            line = null;
            clearMarks();
            return;
        }
        line = compute(p);
        if (++markTicks % 10 == 0) {
            publishMarks(p); // crystal cage/range is budgeted: every half second
        }
    }

    private boolean anyTracked() {
        for (int i = 0; i < MAX_TRACKED; i++) {
            if (skulls[i] != null || clouds[i] != null) {
                return true;
            }
        }
        return false;
    }

    private String compute(LocalPlayer p) {
        double px = p.getX(), py = p.getY(), pz = p.getZ();
        if (dragonHints) {
            for (int i = 0; i < MAX_TRACKED; i++) {
                AreaEffectCloud c = clouds[i];
                if (c != null && BossFightAdvisor.inCloud(px, py, pz, c.getX(), c.getY(), c.getZ(), c.getRadius(),
                        0.5)) {
                    return IN_BREATH;
                }
            }
        }
        if (witherHints) {
            double cy = py + p.getBbHeight() * 0.5;
            for (int i = 0; i < MAX_TRACKED; i++) {
                WitherSkull s = skulls[i];
                if (s == null) {
                    continue;
                }
                var v = s.getDeltaMovement();
                if (BossFightAdvisor.skullIncoming(s.getX(), s.getY(), s.getZ(), v.x, v.y, v.z, s.isDangerous(), px,
                        cy, pz, 1.5, 40)) {
                    return s.isDangerous() ? BLUE_SKULL : SKULL;
                }
            }
            if (wither != null) {
                return BossFightAdvisor.witherArmoured(wither.getHealth(), wither.getMaxHealth())
                        ? WITHER_ARMOUR : WITHER_ARROWS;
            }
        }
        if (dragonHints && dragon != null) {
            int phase = dragon.getPhaseManager().getCurrentPhase().getPhase().getId();
            if (phase != lastPhase) {
                lastPhase = phase;
                ticksInPhase = 0;
            } else {
                ticksInPhase++;
            }
            int secs = BossFightAdvisor.perchSecondsLeft(phase, ticksInPhase);
            int key = phase * 10_000 + secs * 100 + Math.min(99, crystals);
            if (key != dragonKey) { // rebuilt only when the shown text changes
                dragonKey = key;
                dragonLine = BossFightAdvisor.dragonHint(phase, ticksInPhase, crystals);
            }
            return dragonLine;
        }
        return null;
    }

    // ---- world marks (Shell reticles via IWorldMarks) ------------------------------------------------------------

    private static final String[] MARK_OWNERS = {"boss_helper.crystal", "boss_helper.boss", "boss_helper.skull"};
    private static final int M_CRYSTAL = 0, M_BOSS = 1, M_SKULL = 2;
    private final EndCrystal[] crystalRefs = new EndCrystal[MAX_TRACKED];
    private final int[] markSlot = {-2, -2, -2};
    private final boolean[] markOn = new boolean[3];
    private final net.minecraft.core.BlockPos.MutableBlockPos cursor = new net.minecraft.core.BlockPos.MutableBlockPos();
    private final com.utilitysuite.combat.tactics.RangedBallistics ballistics =
            new com.utilitysuite.combat.tactics.RangedBallistics();
    private int markTicks;

    private void publishMarks(LocalPlayer p) {
        com.utilitysuite.core.api.visual.IVisuals v = Services.visualsOrNull();
        if (v == null || Services.modState().isCleanScreen()) {
            clearMarks();
            return;
        }
        com.utilitysuite.core.api.visual.IWorldMarks marks = v.marks();
        // nearest live crystal (dragon fight only): caged / in bow range on the low arc
        EndCrystal best = null;
        double bestD = Double.MAX_VALUE;
        if (dragonHints && dragon != null) {
            for (int i = 0; i < MAX_TRACKED; i++) {
                EndCrystal c = crystalRefs[i];
                if (c != null && c.isAlive()) {
                    double d = c.distanceToSqr(p);
                    if (d < bestD) {
                        bestD = d;
                        best = c;
                    }
                }
            }
        }
        if (best != null && slot(marks, M_CRYSTAL) >= 0) {
            boolean caged = BossFightAdvisor.caged(ironBarsAround(p, best));
            double dx = best.getX() - p.getX(), dz = best.getZ() - p.getZ();
            BossFightAdvisor.ShotVerdict sv = BossFightAdvisor.bowShot(ballistics, Math.sqrt(dx * dx + dz * dz),
                    best.getY() + 1.0 - p.getEyeY(), caged, true);
            String word = switch (sv) {
                case CAGED -> "CAGED";
                case OUT_OF_RANGE -> "OUT OF RANGE";
                default -> "BOW RANGE";
            };
            marks.setEntity(markSlot[M_CRYSTAL], com.utilitysuite.core.api.visual.MarkKind.OBJECTIVE, best.getId(),
                    -1f, -1f, (float) Math.sqrt(bestD), "End crystal", word,
                    com.utilitysuite.core.api.visual.IWorldMarks.FLAG_TAG);
            markOn[M_CRYSTAL] = true;
        } else {
            clear(marks, M_CRYSTAL);
        }
        net.minecraft.world.entity.LivingEntity boss = dragonHints && dragon != null ? dragon
                : witherHints && wither != null ? wither : null;
        if (boss != null && slot(marks, M_BOSS) >= 0) {
            float max = boss.getMaxHealth();
            String word = boss == wither && BossFightAdvisor.witherArmoured(wither.getHealth(), max) ? "MELEE ONLY"
                    : null;
            marks.setEntity(markSlot[M_BOSS], com.utilitysuite.core.api.visual.MarkKind.THREAT, boss.getId(),
                    max > 0f ? boss.getHealth() / max : -1f, -1f, (float) Math.sqrt(boss.distanceToSqr(p)),
                    boss == dragon ? "Ender dragon" : "Wither", word,
                    com.utilitysuite.core.api.visual.IWorldMarks.FLAG_TAG);
            markOn[M_BOSS] = true;
        } else {
            clear(marks, M_BOSS);
        }
        WitherSkull incoming = null;
        if (witherHints) {
            double cy = p.getY() + p.getBbHeight() * 0.5;
            for (int i = 0; i < MAX_TRACKED && incoming == null; i++) {
                WitherSkull s = skulls[i];
                if (s != null) {
                    var vel = s.getDeltaMovement();
                    if (BossFightAdvisor.skullIncoming(s.getX(), s.getY(), s.getZ(), vel.x, vel.y, vel.z,
                            s.isDangerous(), p.getX(), cy, p.getZ(), 1.5, 40)) {
                        incoming = s;
                    }
                }
            }
        }
        if (incoming != null && slot(marks, M_SKULL) >= 0) {
            marks.setEntity(markSlot[M_SKULL], com.utilitysuite.core.api.visual.MarkKind.THREAT, incoming.getId(),
                    -1f, -1f, (float) Math.sqrt(incoming.distanceToSqr(p)),
                    incoming.isDangerous() ? "Blue skull" : "Wither skull", null,
                    com.utilitysuite.core.api.visual.IWorldMarks.FLAG_TAG
                            | (incoming.isDangerous() ? com.utilitysuite.core.api.visual.IWorldMarks.FLAG_LETHAL : 0));
            markOn[M_SKULL] = true;
        } else {
            clear(marks, M_SKULL);
        }
    }

    /** Iron bars in the 8 horizontal neighbours of the crystal's block and the block above. */
    private int ironBarsAround(LocalPlayer p, EndCrystal c) {
        int bx = c.getBlockX(), by = c.getBlockY(), bz = c.getBlockZ(), n = 0;
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if ((dx != 0 || dz != 0) && p.level().getBlockState(cursor.set(bx + dx, by + dy, bz + dz))
                            .is(net.minecraft.world.level.block.Blocks.IRON_BARS)) {
                        n++;
                    }
                }
            }
        }
        // a cage also has bars above the crystal
        if (p.level().getBlockState(cursor.set(bx, by + 2, bz)).is(net.minecraft.world.level.block.Blocks.IRON_BARS)) {
            n++;
        }
        return n;
    }

    private int slot(com.utilitysuite.core.api.visual.IWorldMarks marks, int which) {
        if (markSlot[which] == -2) {
            markSlot[which] = marks.claim(MARK_OWNERS[which]);
        }
        return markSlot[which];
    }

    private void clear(com.utilitysuite.core.api.visual.IWorldMarks marks, int which) {
        if (markOn[which] && markSlot[which] >= 0) {
            marks.clear(markSlot[which]);
        }
        markOn[which] = false;
    }

    private void clearMarks() {
        com.utilitysuite.core.api.visual.IVisuals v = Services.visualsOrNull();
        if (v == null) {
            return;
        }
        for (int i = 0; i < 3; i++) {
            clear(v.marks(), i);
        }
    }

    @Override
    public void onWorldUnload() {
        dragon = null;
        wither = null;
        crystals = 0;
        java.util.Arrays.fill(skulls, null);
        java.util.Arrays.fill(clouds, null);
        java.util.Arrays.fill(crystalRefs, null);
        clearMarks();
        lastPhase = -1;
        line = null;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_DRAGON, "Ender dragon hints", true, "",
                        "Dragon phase, perch countdown and odds, and a warning while standing in dragon breath."),
                SettingSpec.bool(K_WITHER, "Wither hints", true, "",
                        "Armour phase (below half health arrows bounce: melee only) and incoming skulls."));
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        dragonHints = p.getBool(ID, K_DRAGON, true);
        witherHints = p.getBool(ID, K_WITHER, true);
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
