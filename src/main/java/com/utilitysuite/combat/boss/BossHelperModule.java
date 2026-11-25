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
        } else if (e instanceof EndCrystal) {
            crystals++;
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
            return;
        }
        line = compute(p);
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

    @Override
    public void onWorldUnload() {
        dragon = null;
        wither = null;
        crystals = 0;
        java.util.Arrays.fill(skulls, null);
        java.util.Arrays.fill(clouds, null);
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
