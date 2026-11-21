package com.utilitysuite.travel.mobility;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Abilities;

import java.util.List;

/**
 * Fly: creative-style flight (double-tap jump) through the player's ability flag, no mixin. Only with the Relaxed
 * tuning selected AND where flight is allowed: single-player, an allow-listed server, or a server that already granted
 * flight. Otherwise it refuses with one line ({@link RelaxedGate}). The gate is re-checked every
 * {@value #RECHECK_TICKS} ticks; leaving the allowed place or tuning switches it off. Off by default.
 */
public final class Fly implements IModule, IConfigurable {

    public static final String ID = "fly";
    static final int RECHECK_TICKS = 40;

    private boolean enabled;
    /** We set mayfly ourselves (the server had not granted it): clear it again on disable. */
    private boolean grantedByUs;
    private int clock;

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
                MobilityContext.say("Fly", why);
                return;
            }
            this.enabled = true;
            clock = 0;
        } else {
            this.enabled = false;
            revoke();
        }
    }

    /** Null when flying is allowed here, else the reason. */
    String refusal() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.getConnection() == null) {
            return "join a world first";
        }
        boolean granted = p.getAbilities().mayfly && !grantedByUs;
        return RelaxedGate.flyRefusal(MobilityContext.singleplayer(mc), MobilityContext.allowListed(mc), granted,
                MobilityContext.level());
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!enabled || p == null) {
            return;
        }
        if (++clock >= RECHECK_TICKS) {
            clock = 0;
            String why = refusal();
            if (why != null) {
                MobilityContext.say("Fly", why);
                enabled = false;
                revoke();
                return;
            }
        }
        Abilities a = p.getAbilities();
        if (!a.mayfly) {
            // an abilities packet (respawn, game mode change) cleared it: grant it again
            a.mayfly = true;
            grantedByUs = true;
        }
    }

    private void revoke() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p != null && grantedByUs && !p.isCreative() && !p.isSpectator()) {
            Abilities a = p.getAbilities();
            a.mayfly = false;
            if (a.flying) {
                a.flying = false;
                p.onUpdateAbilities();
            }
        }
        grantedByUs = false;
    }

    @Override
    public void onStopAll() {
        // Stop-All revokes automation; flight is a manual mode, but a stop means "everything off"
        setEnabled(false);
    }

    @Override
    public void onWorldUnload() {
        enabled = false;
        grantedByUs = false;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
    }

    @Override
    public String displayName() {
        return "Fly";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of();
    }

    @Override
    public void onSettingChanged(String key) {
    }
}
