package com.utilitysuite.ui.radial;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ISuiteActions;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteAction;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.core.input.LookCapture;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.ui.kit.UiTheme;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Flick-gesture action wheel. Hold the wheel key (unbound by default) in game: an 8-slice wheel appears centred on
 * screen, the camera holds still ({@link LookCapture}, through the core's turn hook) and the mouse motion moves a
 * virtual stick ({@link RadialSelector}); releasing the key runs the selected slice's action from the action registry.
 * A quick flick and release selects in about 150 ms. Opening a screen, Stop-All or leaving the world closes the wheel
 * without running anything; after Stop-All, leaving the world or switching the module off it stays closed until the
 * key is released ({@link WheelHold}), so a still-held key cannot take the camera again. Slices are action ids (settings {@code slice_1..slice_8}, clockwise from the top).
 * <p>
 * Tick and render are allocation free: slice labels are resolved once when the wheel opens.
 */
public final class RadialWheel implements IModule, IConfigurable, LayeredDraw.Layer {

    public static final String ID = "radial_wheel";
    public static final int SLICES = 8;
    static final String K_THRESHOLD = "flick_distance";
    static final String[] DEFAULT_SLICES = {"stop_all", "drop_waypoint", "toggle_world_overlays", "take_me_back",
            "toggle_clean_screen", "toggle_scout_camera", "cycle_visual_preset", "command_palette"};

    private static final int RADIUS = 62;
    private static final int BOX_H = 16;
    private static final int BOX_MAX_W = 96;

    private final RadialSelector selector = new RadialSelector(SLICES, 40);
    private final WheelHold hold = new WheelHold();
    private final String[] sliceIds = DEFAULT_SLICES.clone();
    private final String[] labels = new String[SLICES];
    private boolean enabled = true;
    private boolean open;
    private int selected = -1;

    // ---- IModule ----------------------------------------------------------------------------------------------------

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
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            cancel();
        }
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        boolean held = hold.held(KeybindManager.isHeld(KeybindManager.KeyId.RADIAL_WHEEL)) && mc.screen == null
                && mc.player != null;
        if (held) {
            if (!open) {
                openWheel();
            }
            LookCapture.renew();
            pull();
        } else if (open) {
            pull();
            close(mc.screen == null); // released in game: fire; a screen opened meanwhile: cancel
        }
    }

    @Override
    public void onStopAll() {
        cancel();
    }

    @Override
    public void onWorldUnload() {
        cancel();
    }

    /** Closes without running anything; a still-held key does not reopen the wheel until it is released. */
    private void cancel() {
        if (open) {
            hold.suppressUntilRelease();
        }
        close(false);
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        read(profile);
    }

    /** True while the wheel is open (tests, HUD). */
    public boolean isOpen() {
        return open;
    }

    /** The configured action id of a slice. */
    public String sliceId(int slice) {
        return sliceIds[slice];
    }

    public double flickDistance() {
        return selector.threshold();
    }

    private void openWheel() {
        open = true;
        selected = -1;
        selector.reset();
        LookCapture.begin();
        ISuiteActions actions = Services.actionsOrNull();
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < SLICES; i++) {
            Optional<SuiteAction> a = actions == null ? Optional.empty() : actions.byId(sliceIds[i]);
            String l = a.map(SuiteAction::label).orElse(sliceIds[i].isEmpty() ? "-" : sliceIds[i] + " (n/a)");
            labels[i] = font == null ? l : UiRenderUtils.ellipsize(font, l, BOX_MAX_W - 10);
        }
    }

    private void pull() {
        selected = selector.add(LookCapture.takeX(), LookCapture.takeY());
    }

    private void close(boolean fire) {
        if (!open) {
            return;
        }
        open = false;
        LookCapture.end();
        int s = selected;
        selected = -1;
        if (fire && s >= 0) {
            ISuiteActions actions = Services.actionsOrNull();
            if (actions != null) {
                actions.run(sliceIds[s]);
            }
        }
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Action Wheel";
    }

    @Override
    public List<SettingSpec> settings() {
        List<SettingSpec> out = new ArrayList<>(SLICES + 1);
        out.add(SettingSpec.integer(K_THRESHOLD, "Flick distance", 40, 10, 200, "",
                "How far the mouse must move (in mouse turn steps) before a slice is selected. Lower is quicker, "
                        + "higher ignores small hand jitter."));
        String[] pos = {"top", "top right", "right", "bottom right", "bottom", "bottom left", "left", "top left"};
        for (int i = 0; i < SLICES; i++) {
            out.add(SettingSpec.text("slice_" + (i + 1), "Slice " + (i + 1) + " (" + pos[i] + ")", DEFAULT_SLICES[i],
                    "Slices", "Action id for this slice, e.g. stop_all, drop_waypoint, take_me_back, "
                            + "toggle_clean_screen, cycle_accent, command_palette, open_menu. Empty: no action."));
        }
        return out;
    }

    @Override
    public void onSettingChanged(String key) {
        read(Services.modState().profile());
    }

    private void read(ProfileConfig p) {
        selector.setThreshold(p.getInt(ID, K_THRESHOLD, 40));
        for (int i = 0; i < SLICES; i++) {
            sliceIds[i] = p.getString(ID, "slice_" + (i + 1), DEFAULT_SLICES[i]).trim();
        }
    }

    // ---- render -----------------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        if (!open) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || Services.modState().isCleanScreen()) {
            return;
        }
        pull(); // per frame: the highlight follows the flick between ticks
        Font font = mc.font;
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        UiRenderUtils.dot(g, cx - 3, cy - 3, 6, selected < 0 ? UiTheme.TEXT_MUTED : UiTheme.ACCENT);
        for (int i = 0; i < SLICES; i++) {
            double rad = Math.toRadians(RadialSelector.centreDeg(i, SLICES));
            int bx = cx + (int) Math.round(Math.sin(rad) * RADIUS);
            int by = cy - (int) Math.round(Math.cos(rad) * RADIUS);
            String label = labels[i];
            int w = Math.min(BOX_MAX_W, UiRenderUtils.width(font, label) + 10);
            boolean sel = i == selected;
            UiRenderUtils.panel(g, bx - w / 2, by - BOX_H / 2, w, BOX_H, 4,
                    sel ? UiTheme.ACCENT_TINT : UiTheme.CARD, sel ? UiTheme.ACCENT_BORDER : UiTheme.BORDER);
            UiRenderUtils.text(g, font, label, bx - w / 2 + 5, by - 4, sel ? UiTheme.TEXT : UiTheme.TEXT_SECONDARY);
        }
    }
}
