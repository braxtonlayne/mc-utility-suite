package com.utilitysuite.devtest;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.NoticeKind;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.ui.UiBootstrap;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.holo.VisualSettings;
import com.utilitysuite.ui.radial.RadialWheel;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.HashSet;
import java.util.Set;

/** Appearance (seen in the drawn HUD) and the Action Wheel for {@link ToolsExtraScenario}. */
final class ToolsExtraUiTests {

    private static final int WHEEL_TEST_KEY = GLFW.GLFW_KEY_KP_9;

    private ToolsExtraUiTests() {
    }

    static void add(Scenario s) {
        appearance(s);
        radialWheel(s);
    }

    // ---- Appearance -------------------------------------------------------------------------------------------------

    /** Records what the HUD fills: colour, alpha and the pose scale at the time of the fill. Draws no text. */
    private static final class RecordingGraphics extends GuiGraphics {
        int fills;
        int maxAlpha;
        float maxScale;
        final Set<Integer> rgb = new HashSet<>();

        RecordingGraphics() {
            super(Minecraft.getInstance(), Minecraft.getInstance().renderBuffers().bufferSource());
        }

        @Override
        public void fill(RenderType renderType, int minX, int minY, int maxX, int maxY, int z, int color) {
            fills++;
            maxAlpha = Math.max(maxAlpha, color >>> 24);
            maxScale = Math.max(maxScale, pose().last().pose().m00());
            rgb.add(color & 0xFFFFFF);
        }

        @Override
        public int drawString(Font font, String text, float x, float y, int color, boolean dropShadow) {
            return 0;
        }

        @Override
        public int drawString(Font font, FormattedCharSequence text, float x, float y, int color, boolean dropShadow) {
            return 0;
        }

        @Override
        public String toString() {
            return fills + " fills, max alpha " + maxAlpha + Harness.fmt(", scale %.2f", maxScale) + ", "
                    + rgb.size() + " colours";
        }
    }

    private static RecordingGraphics renderHud() {
        RecordingGraphics g = new RecordingGraphics();
        UiBootstrap.hud().render(g, DeltaTracker.ZERO);
        return g;
    }

    private static void appearance(Scenario s) {
        s.run("AP post a probe notice", () -> {
            Services.modState().setCleanScreen(false);
            Services.visuals().notices().post(NoticeKind.INFO, "Appearance probe", "HUD check");
        });
        s.waitTicks(15); // past the enter slide and fade
        s.run("AP render with each setting", () -> {
            int accentMagenta = VisualSettings.Accent.MAGENTA.rgb();
            int accentDefault = HoloTheme.DEFAULT_ACCENT_RGB;
            RecordingGraphics base = renderHud();
            H.setting(VisualSettings.ID, VisualSettings.K_HUD_OPACITY, 40);
            RecordingGraphics dim = renderHud();
            ToolsExtraScenario.unset(VisualSettings.ID, VisualSettings.K_HUD_OPACITY);
            H.setting(VisualSettings.ID, VisualSettings.K_HUD_SCALE, 150);
            RecordingGraphics big = renderHud();
            ToolsExtraScenario.unset(VisualSettings.ID, VisualSettings.K_HUD_SCALE);
            H.setting(VisualSettings.ID, VisualSettings.K_ACCENT, VisualSettings.Accent.MAGENTA.name());
            RecordingGraphics magenta = renderHud();
            ToolsExtraScenario.unset(VisualSettings.ID, VisualSettings.K_ACCENT);
            RecordingGraphics back = renderHud();

            s.check("the HUD draws the probe notice", base.fills > 0, "default: " + base);
            int expected = Math.round(base.maxAlpha * 0.4f);
            s.check("HUD opacity 40% scales every HUD alpha (seen in the drawn fills)",
                    dim.fills > 0 && Math.abs(dim.maxAlpha - expected) <= 3,
                    "default " + base + "; opacity 40%: " + dim + " (expected max alpha about " + expected + ")");
            s.check("HUD scale 150% scales the drawn HUD", Math.abs(base.maxScale - 1f) < 0.01f
                            && Math.abs(big.maxScale - 1.5f) < 0.01f,
                    Harness.fmt("default scale %.2f, at 150%% %.2f", base.maxScale, big.maxScale));
            s.check("the accent colour preset recolours the HUD's accent lines",
                    base.rgb.contains(accentDefault) && !base.rgb.contains(accentMagenta)
                            && magenta.rgb.contains(accentMagenta) && !magenta.rgb.contains(accentDefault),
                    "default has cyan " + base.rgb.contains(accentDefault) + ", magenta " + base.rgb.contains(
                            accentMagenta) + "; MAGENTA has magenta " + magenta.rgb.contains(accentMagenta)
                            + ", cyan " + magenta.rgb.contains(accentDefault));
            s.check("back to defaults the HUD draws exactly as before",
                    back.maxAlpha == base.maxAlpha && Math.abs(back.maxScale - base.maxScale) < 0.001f
                            && back.rgb.contains(accentDefault) && HoloTheme.hudOpacity == 1f
                            && HoloTheme.hudScale == 1f,
                    "after: " + back + ", theme opacity " + HoloTheme.hudOpacity + ", scale " + HoloTheme.hudScale);
        });
        s.always("AP cleanup", () -> {
            ToolsExtraScenario.unset(VisualSettings.ID, VisualSettings.K_HUD_OPACITY);
            ToolsExtraScenario.unset(VisualSettings.ID, VisualSettings.K_HUD_SCALE);
            ToolsExtraScenario.unset(VisualSettings.ID, VisualSettings.K_ACCENT);
        });
    }

    // ---- Action Wheel -----------------------------------------------------------------------------------------------

    private static RadialWheel wheel() {
        return H.module(RadialWheel.ID);
    }

    private static int selected() {
        return (Integer) ToolsExtraScenario.field(wheel(), "selected");
    }

    private static void radialWheel(Scenario s) {
        float[] rot = new float[2];
        int[] sel = new int[1];
        boolean[] maxSel = new boolean[1];
        s.cmd(() -> "tp @s " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 0.5) + " 0 0");
        s.run("RW bind the wheel key", () -> {
            Services.modState().setCleanScreen(false);
            ToolsExtraScenario.bind(KeybindManager.mapping(KeybindManager.KeyId.RADIAL_WHEEL), WHEEL_TEST_KEY);
            s.check("the wheel's bottom slice is Toggle Clean-Screen by default", "toggle_clean_screen".equals(
                    wheel().sliceId(4)), "slice 5 = " + wheel().sliceId(4));
        });
        s.waitTicks(3);

        // 1) hold, flick down, release: the bottom slice runs; the camera does not move meanwhile
        s.run("RW hold the key", () -> H.physicalKey(WHEEL_TEST_KEY, true));
        s.waitTicks(3);
        s.run("RW flick down", () -> {
            rot[0] = H.p().getYRot();
            rot[1] = H.p().getXRot();
            s.check("holding the wheel key opens the wheel", wheel().isOpen(), "open " + wheel().isOpen());
            ToolsExtraScenario.mouseUnits(0, 60);
            ToolsExtraScenario.mouseUnits(0, 60);
        });
        s.waitTicks(2);
        s.run("RW selection + camera held", () -> {
            sel[0] = selected();
            float dy = Math.abs(H.p().getYRot() - rot[0]);
            float dp = Math.abs(H.p().getXRot() - rot[1]);
            s.check("a downward flick selects the bottom slice and the camera stays still",
                    sel[0] == 4 && dy < 0.01f && dp < 0.01f,
                    "selected slice " + (sel[0] + 1) + Harness.fmt(", camera moved yaw %.3f pitch %.3f", dy, dp));
        });
        s.run("RW release", () -> H.physicalKey(WHEEL_TEST_KEY, false));
        s.waitTicks(3);
        s.run("RW fired check", () -> {
            boolean clean = Services.modState().isCleanScreen();
            s.check("releasing the key runs the selected slice (Clean-Screen on) and closes the wheel",
                    clean && !wheel().isOpen(), "clean screen " + clean + ", open " + wheel().isOpen());
            Services.modState().setCleanScreen(false);
        });

        // 2) a motion below the flick distance selects nothing
        s.run("RW hold again", () -> H.physicalKey(WHEEL_TEST_KEY, true));
        s.waitTicks(3);
        s.run("RW small motion", () -> {
            maxSel[0] = false;
            ToolsExtraScenario.mouseUnits(12, 0);
        });
        s.waitTicks(2);
        s.run("RW small selection", () -> {
            maxSel[0] = selected() >= 0;
            H.physicalKey(WHEEL_TEST_KEY, false);
        });
        s.waitTicks(3);
        s.run("RW small check", () -> s.check("a motion under the flick distance selects and runs nothing",
                !maxSel[0] && !wheel().isOpen() && !Services.modState().isCleanScreen(),
                "selected " + maxSel[0] + ", open " + wheel().isOpen() + ", clean screen "
                        + Services.modState().isCleanScreen()));

        // 3) a screen opening while the wheel is open cancels it
        s.run("RW hold for cancel", () -> H.physicalKey(WHEEL_TEST_KEY, true));
        s.waitTicks(3);
        s.run("RW flick then open chat", () -> {
            ToolsExtraScenario.mouseUnits(0, 60);
            ToolsExtraScenario.mouseUnits(0, 60);
        });
        s.waitTicks(1);
        s.run("RW open chat", () -> {
            sel[0] = selected();
            H.mc().setScreen(new ChatScreen(""));
        });
        s.waitTicks(3);
        s.run("RW cancel check", () -> {
            s.check("opening a screen while the wheel is open closes it without running the slice",
                    sel[0] == 4 && !wheel().isOpen() && !Services.modState().isCleanScreen(),
                    "selected before " + (sel[0] + 1) + ", open " + wheel().isOpen() + ", clean screen "
                            + Services.modState().isCleanScreen());
            H.mc().setScreen(null);
            H.physicalKey(WHEEL_TEST_KEY, false);
        });
        s.waitTicks(3);

        // 4) Stop-All while the wheel is open and its key still held: the wheel closes, stays closed and frees the camera
        boolean[] reopened = new boolean[1];
        s.run("RW hold for Stop-All", () -> H.physicalKey(WHEEL_TEST_KEY, true));
        s.waitTicks(3);
        s.run("RW flick before Stop-All", () -> {
            ToolsExtraScenario.mouseUnits(0, 60);
            ToolsExtraScenario.mouseUnits(0, 60);
        });
        s.waitTicks(2);
        s.run("RW Stop-All", () -> {
            sel[0] = selected();
            reopened[0] = false;
            Services.arbiter().stopAll("playtest: wheel open");
        });
        s.waitUpTo("RW key still held after Stop-All", 10, () -> {
            reopened[0] |= wheel().isOpen();
            return false;
        });
        s.run("RW Stop-All check", () -> {
            rot[0] = H.p().getYRot();
            ToolsExtraScenario.mouseUnits(40, 0);
            float turned = H.p().getYRot() - rot[0];
            ToolsExtraScenario.mouseUnits(-40, 0);
            H.physicalKey(WHEEL_TEST_KEY, false);
            s.check("Stop-All closes the wheel for good while its key is still held (camera free, nothing runs)",
                    sel[0] == 4 && !reopened[0] && !wheel().isOpen() && turned > 3f,
                    "selected before " + (sel[0] + 1) + ", reopened while held " + reopened[0] + ", open "
                            + wheel().isOpen() + Harness.fmt(", mouse turned the camera %.2f deg", turned));
        });
        s.waitTicks(3);
        s.run("RW Stop-All release check", () -> {
            s.check("releasing the key after Stop-All runs nothing", !Services.modState().isCleanScreen()
                    && !wheel().isOpen(), "clean screen " + Services.modState().isCleanScreen() + ", open "
                    + wheel().isOpen());
            Services.arbiter().resumeAutomation();
        });

        // 5) once closed, the mouse turns the camera again (the hold lapsed)
        s.run("RW camera free again", () -> {
            rot[0] = H.p().getYRot();
            ToolsExtraScenario.mouseUnits(40, 0);
            float turned = H.p().getYRot() - rot[0];
            ToolsExtraScenario.mouseUnits(-40, 0);
            s.check("after the wheel closes the mouse turns the camera again", turned > 3f && turned < 9f,
                    Harness.fmt("40 turn units turned the camera %.2f deg (6 expected)", turned));
        });
        s.always("RW cleanup", () -> {
            H.physicalKey(WHEEL_TEST_KEY, false);
            if (Services.arbiter().isAutomationStopped()) {
                Services.arbiter().resumeAutomation();
            }
            ToolsExtraScenario.bind(KeybindManager.mapping(KeybindManager.KeyId.RADIAL_WHEEL), -1);
            Services.modState().setCleanScreen(false);
            if (H.mc().screen instanceof ChatScreen) {
                H.mc().setScreen(null);
            }
        });
    }
}
