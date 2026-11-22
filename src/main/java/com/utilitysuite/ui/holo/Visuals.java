package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.AuthorityState;
import com.utilitysuite.core.api.visual.EffectLevel;
import com.utilitysuite.core.api.visual.IHudHost;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.core.api.visual.WorldMarkTable;

/**
 * The Shell's {@link IVisuals} ({@code Services.visuals()}): the HUD host, the effect level from the appearance
 * settings, and the shared mark and authority tables (the interface defaults). Notices and the world kit are not
 * wired yet and keep their no-op defaults.
 */
public final class Visuals implements IVisuals {

    private final HudHost host;

    public Visuals(HudHost host) {
        this.host = host;
    }

    @Override
    public IHudHost hud() {
        return host;
    }

    @Override
    public EffectLevel effects() {
        return HoloTheme.effects;
    }

    /** Once per client tick (the Shell): counts the authority override flash down. */
    public void tick() {
        AuthorityState.SHARED.tick();
    }

    /** World left: marks and the authority state belong to the old world. */
    public void onWorldUnload() {
        WorldMarkTable.SHARED.reset();
        AuthorityState.SHARED.reset();
    }
}
