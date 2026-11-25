package com.utilitysuite.core.api.visual;

/**
 * The drawing services of the Shell ({@code Services.visuals()}). Modules publish state or draw through these
 * token-based contracts and hold no colours, fonts or fill calls of their own. Every method has a working default,
 * so fakes and an absent Shell cost nothing.
 */
public interface IVisuals {

    /** Draws nothing; still hands out the shared mark and authority tables. */
    IVisuals NONE = new IVisuals() {
    };

    default IHudHost hud() {
        return IHudHost.NONE;
    }

    default INoticeBoard notices() {
        return INoticeBoard.NONE;
    }

    default IWorldMarks marks() {
        return WorldMarkTable.SHARED;
    }

    default AuthorityState authority() {
        return AuthorityState.SHARED;
    }

    /** The world drawing kit while the level-render pass of the Shell runs, null otherwise or when there is none. */
    default IHoloWorld world() {
        return null;
    }

    default EffectLevel effects() {
        return EffectLevel.OFF;
    }

    /** The context-morphing HUD factors; {@link IHudContext#NONE} (all 1) when the module is off or absent. */
    default IHudContext context() {
        return IHudContext.NONE;
    }
}
