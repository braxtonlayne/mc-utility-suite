package com.utilitysuite.travel.mobility;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Velocity scale: scales the knockback velocity the server sends for the local player (entity-motion packets) by a
 * setting, horizontal and vertical separately, never above 100%. Single-player or an allow-listed server AND the
 * Relaxed tuning only ({@link RelaxedGate}); switches itself off when that stops holding. The packet hook is the small
 * mixin {@code mixin.travel.ClientPacketListenerVelocityMixin}; the math is {@link RelaxedGate#scale}. Off by default.
 */
public final class VelocityScale implements IModule, IConfigurable {

    public static final String ID = "velocity_scale";
    static final String K_HORIZONTAL = "horizontal_percent";
    static final String K_VERTICAL = "vertical_percent";
    static final int RECHECK_TICKS = 40;

    private static VelocityScale instance;

    private boolean enabled;
    private double horizontal = 0.6;
    private double vertical = 1.0;
    private int clock;

    public VelocityScale() {
        instance = this;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TRAVEL;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled == this.enabled) {
            return;
        }
        if (enabled) {
            String why = refusal();
            if (why != null) {
                MobilityContext.say("Velocity scale", why);
                return;
            }
            clock = 0;
        }
        this.enabled = enabled;
    }

    String refusal() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) {
            return "join a world first";
        }
        return RelaxedGate.velocityRefusal(MobilityContext.singleplayer(mc), MobilityContext.allowListed(mc),
                MobilityContext.level());
    }

    @Override
    public void onTick() {
        if (enabled && ++clock >= RECHECK_TICKS) {
            clock = 0;
            String why = refusal();
            if (why != null) {
                MobilityContext.say("Velocity scale", why);
                enabled = false;
            }
        }
    }

    /**
     * Mixin hook, client thread, after vanilla applied an entity-motion packet: when it was for the local player,
     * replace the velocity with the scaled one. Allocation free apart from the game's own Vec3.
     */
    public static void afterEntityMotion(int entityId, double xa, double ya, double za) {
        VelocityScale v = instance;
        if (v == null || !v.enabled) {
            return;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.getId() != entityId) {
            return;
        }
        p.setDeltaMovement(RelaxedGate.scale(xa, v.horizontal), RelaxedGate.scale(ya, v.vertical),
                RelaxedGate.scale(za, v.horizontal));
    }

    @Override
    public void onWorldUnload() {
        enabled = false;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        horizontal = profile.getInt(ID, K_HORIZONTAL, 60) / 100.0;
        vertical = profile.getInt(ID, K_VERTICAL, 100) / 100.0;
    }

    @Override
    public String displayName() {
        return "Velocity Scale";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer(K_HORIZONTAL, "Horizontal knockback %", 60, 0, 100, "",
                        "Share of the sideways knockback kept. Single-player or allow-listed servers, Relaxed only."),
                SettingSpec.integer(K_VERTICAL, "Vertical knockback %", 100, 0, 100, "",
                        "Share of the upward knockback kept."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
