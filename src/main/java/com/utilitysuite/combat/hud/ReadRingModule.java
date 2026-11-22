package com.utilitysuite.combat.hud;

import com.utilitysuite.combat.tactics.ReadState;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * "The Read": a ring over the Combat Assistant's target that fills with the target's attack cooldown and tilts toward
 * its predicted strafe. Reads {@link ReadState}; draws in {@link RenderLevelStageEvent} with one batched buffer; off
 * by default; nothing is drawn when idle or in Clean-Screen.
 * <p>
 * CONTRACT STUB: the constructor, {@link #ID} and {@link #onRenderLevel} are fixed for the volley (the combat wiring
 * registers the module and the listener); the drawing and the settings are filled in by the visuals task.
 */
public final class ReadRingModule implements IModule {

    public static final String ID = "the_read";

    private final ReadState read;
    private boolean enabled;

    public ReadRingModule(ReadState read) {
        this.read = read;
    }

    public void onRenderLevel(RenderLevelStageEvent event) {
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
    }

    @Override
    public void onTick() {
    }

    ReadState read() {
        return read;
    }
}
