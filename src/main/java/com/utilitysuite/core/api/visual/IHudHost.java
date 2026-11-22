package com.utilitysuite.core.api.visual;

/** Registry of HUD elements. Register once at start-up (client thread); never per frame. */
public interface IHudHost {

    IHudHost NONE = new IHudHost() {
        @Override
        public void register(HudElement element) {
        }

        @Override
        public void unregister(HudElement element) {
        }
    };

    void register(HudElement element);

    void unregister(HudElement element);
}
