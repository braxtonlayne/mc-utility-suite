package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.IWorldMarks;
import com.utilitysuite.core.api.visual.MarkKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * Context HUD (V6, off by default): feeds the {@link HudContextMachine} once per client tick from the player (fall,
 * hurt, movement and look) and the published world marks (a primary target or threat means combat), and from block
 * placement ({@link #onRightClickBlock} with a block item in hand means building). The machine's factors reach the
 * tactical layers through {@code Services.visuals().context()} and the HUD host. Off, the machine reports 1 for every
 * factor, so nothing changes. Allocation free on tick.
 */
public final class HudContextModule implements IModule, IConfigurable {

    public static final String ID = "context_hud";
    public static final String K_IDLE_SECONDS = "idle_seconds";
    public static final String K_IDLE_OPACITY = "idle_opacity";
    public static final String K_QUIET_OPACITY = "quiet_opacity";
    public static final String K_POP = "pop";
    /** Falling: at least this far fallen and still dropping this fast (blocks / tick). */
    static final float FALL_DISTANCE = 2.5f;
    static final double FALL_SPEED = -0.3;

    private static final List<SettingSpec> SPECS = List.of(
            SettingSpec.integer(K_IDLE_SECONDS, "Idle after (s)", 8, 2, 60, "",
                    "Seconds of standing still (no movement, no looking around) before the HUD fades back."),
            SettingSpec.integer(K_IDLE_OPACITY, "Idle opacity (%)", 35, 0, 100, "",
                    "How visible the HUD stays while you stand still."),
            SettingSpec.integer(K_QUIET_OPACITY, "Quiet tactical opacity (%)", 60, 10, 100, "",
                    "Opacity of the reticles, the Read and the control bar outside combat and falls."),
            SettingSpec.bool(K_POP, "Pop in on combat", true, "",
                    "The tactical layers grow in briefly when combat or a fall starts."));

    private final HudContextMachine machine = new HudContextMachine();
    private boolean buildSignal;
    private double lastX;
    private double lastY;
    private double lastZ;
    private float lastYaw;
    private float lastPitch;

    public HudContextMachine machine() {
        return machine;
    }

    /** Block placement with a block item: the BUILDING signal for the next tick. */
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (machine.enabled && event.getLevel().isClientSide() && event.getItemStack().getItem() instanceof BlockItem) {
            buildSignal = true;
        }
    }

    @Override
    public void onTick() {
        if (!machine.enabled) {
            return;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            machine.reset();
            return;
        }
        boolean falling = !p.onGround() && !p.getAbilities().flying && !p.isFallFlying() && !p.isInWater()
                && p.fallDistance >= FALL_DISTANCE && p.getDeltaMovement().y < FALL_SPEED;
        boolean combat = p.hurtTime > 0 || markedCombat(Services.visuals().marks());
        double x = p.getX();
        double y = p.getY();
        double z = p.getZ();
        float yaw = p.getYRot();
        float pitch = p.getXRot();
        boolean still = x == lastX && y == lastY && z == lastZ && yaw == lastYaw && pitch == lastPitch;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastYaw = yaw;
        lastPitch = pitch;
        machine.tick(combat, falling, buildSignal, still);
        buildSignal = false;
    }

    /** Any published primary target or threat. */
    static boolean markedCombat(IWorldMarks marks) {
        for (int s = 0; s < IWorldMarks.SLOTS; s++) {
            if (marks.occupied(s)) {
                MarkKind k = marks.kind(s);
                if (k == MarkKind.PRIMARY_TARGET || k == MarkKind.THREAT) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TOOLS;
    }

    @Override
    public boolean isEnabled() {
        return machine.enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        machine.enabled = enabled;
        machine.reset();
    }

    @Override
    public void onStopAll() {
        // presentation only: nothing to revoke
    }

    @Override
    public void onWorldUnload() {
        machine.reset();
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        machine.idleTicks = clamp(p.getInt(ID, K_IDLE_SECONDS, 8), 2, 60) * 20;
        machine.idleAlpha = clamp(p.getInt(ID, K_IDLE_OPACITY, 35), 0, 100) / 100f;
        machine.quietAlpha = clamp(p.getInt(ID, K_QUIET_OPACITY, 60), 10, 100) / 100f;
        machine.pop = p.getBool(ID, K_POP, true);
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    @Override
    public String displayName() {
        return "Context HUD";
    }

    @Override
    public List<SettingSpec> settings() {
        return SPECS;
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
