package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.AuthorityState;
import com.utilitysuite.core.api.visual.EffectLevel;
import com.utilitysuite.core.api.visual.IHoloWorld;
import com.utilitysuite.core.api.visual.IHudHost;
import com.utilitysuite.core.api.visual.INoticeBoard;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.core.api.visual.WorldMarkTable;

/**
 * The Shell's {@link IVisuals} ({@code Services.visuals()}): the HUD host, the effect level from the appearance
 * settings, and the shared mark and authority tables (the interface defaults). The notice board and the world kit
 * are plugged in by their own bootstraps ({@link #useNotices}, {@link #useWorld}) and keep the no-op defaults until
 * then.
 */
public final class Visuals implements IVisuals {

    private static volatile INoticeBoard notices = INoticeBoard.NONE;
    private static volatile IHoloWorld world;

    private final HudHost host;

    public Visuals(HudHost host) {
        this.host = host;
    }

    /** Installs the notice board (start-up, once). Null restores the no-op board. */
    public static void useNotices(INoticeBoard board) {
        notices = board != null ? board : INoticeBoard.NONE;
    }

    /**
     * Sets the world kit for the duration of the Shell's level-render pass and clears it (null) when the pass ends,
     * so {@link #world()} is non-null only while drawing is valid. Client render thread.
     */
    public static void useWorld(IHoloWorld kit) {
        world = kit;
    }

    @Override
    public IHudHost hud() {
        return host;
    }

    @Override
    public INoticeBoard notices() {
        return notices;
    }

    @Override
    public IHoloWorld world() {
        return world;
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
