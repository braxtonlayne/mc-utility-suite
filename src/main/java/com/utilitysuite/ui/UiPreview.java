package com.utilitysuite.ui;

import com.mojang.blaze3d.platform.NativeImage;
import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.PlanPreview;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Dev-only visual check of the menu ({@code -Dutilitysuite.uiPreview=true}, Gradle task {@code runUiPreview}). Once
 * the title screen is up it opens the menu with no world loaded, renders every tab, an active demo job, one expanded
 * collapsible section, the HUD pill (fake job) and the menu at GUI scale 4, saves each frame to
 * {@code run/ui-preview/<name>.png}, then stops the game. Without the property nothing is registered and
 * {@link #activeJob}/{@link #lastPreview} simply forward to the goal engine.
 */
public final class UiPreview {

    public static final String PROPERTY = "utilitysuite.uiPreview";
    private static final int SETTLE_TICKS = 12;

    private static boolean demo;
    private static JobSnapshot demoJob;
    private static PlanPreview demoPreview;

    private record Step(String name, Runnable action) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static int stepIndex;
    private static int wait;
    private static int stableTicks;
    private static boolean started;
    private static int originalScale;
    private static SuiteMenuScreen menu;

    /** Smaller than this on a side means the target is the 1x1 placeholder, not a window. */
    private static final int MIN_SIDE = 1;
    private static final int FALLBACK_W = 1280;
    private static final int FALLBACK_H = 720;
    private static final int SIZE_TIMEOUT_TICKS = 200;
    private static final int SHOT_TIMEOUT_TICKS = 100;
    private static final int MIN_FRAMES_BEFORE_SHOT = 2;
    /** Rendered frames (counted by {@code RenderFrameEvent.Post}). */
    private static int frames;
    private static int frameMark;
    private static boolean sizing;
    private static int sizeTicks;
    private static String pendingShot;
    private static int pendingFrames;
    private static int pendingTicks;

    private UiPreview() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** The active job, or the demo job while the preview shows it. */
    public static Optional<JobSnapshot> activeJob(IGoalEngine goals) {
        return demo ? Optional.of(demoJob) : goals.activeJob();
    }

    /** The last dry-run preview, or the demo preview while the preview shows it. */
    public static Optional<PlanPreview> lastPreview(IGoalEngine goals) {
        return demo ? Optional.of(demoPreview) : goals.lastPreview();
    }

    /** Queued jobs, or two demo jobs while the preview shows the demo. */
    public static List<JobSnapshot> queuedJobs(IGoalEngine goals) {
        if (!demo) {
            return goals.queuedJobs();
        }
        return List.of(new JobSnapshot(8, "Craft 16 minecraft:hopper", JobStatus.QUEUED, "", 0, 16, "", "", 0),
                new JobSnapshot(9, "Travel to 120 64 -340", JobStatus.QUEUED, "", 0, 0, "", "", 0));
    }

    static JobSnapshot demoJob() {
        return new JobSnapshot(7, "Gather 32 minecraft:diamond", JobStatus.RUNNING, "Mining at Y=-54 (branch 3 of 6)",
                18, 32, "Diamonds are most common between Y=-64 and Y=-48", "Return to Base Chest when inventory is full",
                0);
    }

    static void install() {
        demoJob = demoJob();
        demoPreview = new PlanPreview(new GoalRequest.Gather("minecraft:diamond", 32, CostProfile.FASTEST,
                DataSourceMode.HYBRID), "32 x minecraft:diamond",
                List.of("Craft an iron pickaxe (have 3 iron ingots)", "Travel to Y=-54 near Base",
                        "Branch-mine along the predicted veins (6 branches)", "Return to Base Chest and deposit"),
                Map.of("minecraft:torch", 24), List.of("Seed predictions are unverified in 3 chunks"));
        NeoForge.EVENT_BUS.addListener(UiPreview::onTick);
        NeoForge.EVENT_BUS.addListener(RenderFrameEvent.Post.class, event -> frames++);
        UtilitySuiteMod.LOGGER.info("UI preview mode: will capture the menu to run/ui-preview and quit");
    }

    private static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!started) {
            if (mc.getOverlay() == null && mc.screen != null) {
                if (++stableTicks >= 40) {
                    started = true;
                    sizing = true;
                }
            } else {
                stableTicks = 0;
            }
            return;
        }
        if (sizing) {
            awaitWindowSize(mc);
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        if (pendingShot != null) {
            capturePending(mc);
            return;
        }
        if (stepIndex >= STEPS.size()) {
            return;
        }
        Step s = STEPS.get(stepIndex++);
        try {
            s.action().run();
        } catch (RuntimeException e) {
            UtilitySuiteMod.LOGGER.error("UI preview step {} failed", s.name(), e);
        }
        wait = SETTLE_TICKS;
    }

    private static void plan(Minecraft mc) {
        originalScale = mc.options.guiScale().get();
        add("scale2", () -> setScale(mc, 2));
        add("open", () -> {
            menu = new SuiteMenuScreen(null);
            mc.setScreen(menu);
        });
        for (SuiteMenuScreen.Tab t : SuiteMenuScreen.Tab.values()) {
            add("tab " + t, () -> menu.selectTab(t));
            add("shot " + t, () -> shot(mc, t.name().toLowerCase(java.util.Locale.ROOT)));
        }
        // three-column layout: a module opened in column 3, then a cross-category search
        add("open module", () -> menu.selectModule(SuiteMenuScreen.Tab.TRAVEL, "terrain_assist"));
        add("shot module-detail", () -> shot(mc, "module-detail"));
        add("search", () -> {
            menu.setState(SuiteMenuScreen.K_SEARCH, "water");
            menu.requestRebuild();
        });
        add("shot search", () -> shot(mc, "search"));
        add("legit level", () -> menu.applyLegitLevel(com.utilitysuite.core.LegitPresets.Level.LEGIT));
        add("shot legit-level", () -> shot(mc, "legit-level"));
        add("balanced level", () -> menu.applyLegitLevel(com.utilitysuite.core.LegitPresets.Level.BALANCED));
        add("search off", () -> {
            menu.setState(SuiteMenuScreen.K_SEARCH, "");
            menu.selectModule(SuiteMenuScreen.Tab.TRAVEL, "");
        });
        add("autopilot on", () -> {
            com.utilitysuite.core.CoreBootstrap.setAutopilot(true);
            menu.requestRebuild();
        });
        add("shot autopilot-on", () -> shot(mc, "autopilot-on"));
        add("autopilot page", () -> menu.toggleAutopilotPage());
        add("shot autopilot-page", () -> shot(mc, "autopilot-page"));
        add("autopilot off", () -> {
            menu.toggleAutopilotPage();
            com.utilitysuite.core.CoreBootstrap.setAutopilot(false);
            menu.requestRebuild();
        });
        add("demo on", () -> {
            demo = true;
            menu.selectTab(SuiteMenuScreen.Tab.JOBS);
            menu.requestRebuild();
        });
        add("shot jobs-active", () -> shot(mc, "jobs-active"));
        add("scroll jobs", () -> menu.scrollForPreview(250));
        add("shot jobs-queue", () -> shot(mc, "jobs-queue"));
        add("demo off", () -> {
            demo = false;
            menu.requestRebuild();
        });
        add("expand", () -> {
            for (SuiteMenuScreen.Tab t : new SuiteMenuScreen.Tab[]{SuiteMenuScreen.Tab.COMBAT,
                    SuiteMenuScreen.Tab.TRAVEL, SuiteMenuScreen.Tab.WORLD, SuiteMenuScreen.Tab.JOBS,
                    SuiteMenuScreen.Tab.TOOLS}) {
                menu.selectTab(t);
                if (menu.expandFirstSection(true)) {
                    return;
                }
            }
        });
        add("shot expanded", () -> shot(mc, menu.tab().name().toLowerCase(java.util.Locale.ROOT) + "-advanced"));
        add("collapse", () -> {
            menu.expandFirstSection(false);
            menu.scrollForPreview(0);
            menu.selectTab(SuiteMenuScreen.Tab.TOOLS);
        });
        add("scroll tools", () -> menu.scrollForPreview(10_000));
        add("shot tools-bottom", () -> shot(mc, "tools-bottom"));
        add("hud", () -> mc.setScreen(new HudPreviewScreen()));
        add("shot hud", () -> shot(mc, "hud"));
        add("scale4", () -> {
            setScale(mc, 4);
            demo = true;
            menu = new SuiteMenuScreen(null);
            mc.setScreen(menu);
            menu.selectTab(SuiteMenuScreen.Tab.JOBS);
        });
        add("shot jobs-scale4", () -> shot(mc, "jobs-scale4"));
        add("world scale4", () -> {
            demo = false;
            menu.selectTab(SuiteMenuScreen.Tab.COMBAT);
        });
        add("shot combat-scale4", () -> shot(mc, "combat-scale4"));
        add("quit", () -> {
            mc.setScreen(null);
            setScale(mc, originalScale);
            UtilitySuiteMod.LOGGER.info("UI preview finished");
            mc.stop();
        });
    }

    private static void add(String name, Runnable r) {
        STEPS.add(new Step(name, r));
    }

    private static void setScale(Minecraft mc, int scale) {
        mc.options.guiScale().set(scale);
        mc.resizeDisplay();
    }

    /** True when the main render target and the window agree on a real (more than 1 px) size. */
    private static boolean sizeUsable(Minecraft mc) {
        RenderTarget t = mc.getMainRenderTarget();
        Window w = mc.getWindow();
        return t.width > MIN_SIDE && t.height > MIN_SIDE && w.getWidth() > MIN_SIDE && w.getHeight() > MIN_SIDE
                && t.width == w.getWidth() && t.height == w.getHeight();
    }

    /**
     * Before the first step: the captures came out 1x1 because {@code Window} caches the GLFW framebuffer size once,
     * at creation, and substitutes 1 when GLFW reports 0 (window hidden, minimised or not yet laid out); it is only
     * corrected by a framebuffer-size callback with a non-zero size. When that never arrives the main render
     * target stays 1x1 and {@code Screenshot.takeScreenshot} (which reads {@code RenderTarget.width/height}) writes a
     * 1x1 image. So: restore and show the window, ask GLFW for a real size (the callback then resizes the display)
     * and wait until target and window agree before taking any step.
     */
    private static void awaitWindowSize(Minecraft mc) {
        if (sizeUsable(mc)) {
            UtilitySuiteMod.LOGGER.info("UI preview window {}x{}, target {}x{}", mc.getWindow().getWidth(),
                    mc.getWindow().getHeight(), mc.getMainRenderTarget().width, mc.getMainRenderTarget().height);
            sizing = false;
            frameMark = frames;
            plan(mc);
            return;
        }
        if (sizeTicks == 0) {
            long handle = mc.getWindow().getWindow();
            UtilitySuiteMod.LOGGER.warn("UI preview: window {}x{}, target {}x{}: restoring and resizing to {}x{}",
                    mc.getWindow().getWidth(), mc.getWindow().getHeight(), mc.getMainRenderTarget().width,
                    mc.getMainRenderTarget().height, FALLBACK_W, FALLBACK_H);
            GLFW.glfwRestoreWindow(handle);
            GLFW.glfwShowWindow(handle);
            GLFW.glfwSetWindowSize(handle, FALLBACK_W, FALLBACK_H);
        } else if (sizeTicks % 20 == 0) {
            // the callback may have been swallowed: re-apply the display size from the (possibly updated) window
            mc.resizeDisplay();
        }
        if (++sizeTicks > SIZE_TIMEOUT_TICKS) {
            UtilitySuiteMod.LOGGER.error("UI preview aborted: the window never reached a usable size (window {}x{}, "
                    + "target {}x{}); nothing captured", mc.getWindow().getWidth(), mc.getWindow().getHeight(),
                    mc.getMainRenderTarget().width, mc.getMainRenderTarget().height);
            sizing = false;
            mc.stop();
        }
    }

    /** Queues a capture: it is taken once at least two frames were rendered after the step that asked for it. */
    private static void shot(Minecraft mc, String name) {
        pendingShot = name;
        pendingFrames = frames;
        pendingTicks = 0;
    }

    private static void capturePending(Minecraft mc) {
        if (frames - pendingFrames < MIN_FRAMES_BEFORE_SHOT && ++pendingTicks < SHOT_TIMEOUT_TICKS) {
            return; // no frame has been rendered since the step: the target may hold stale or cleared pixels
        }
        String name = pendingShot;
        pendingShot = null;
        RenderTarget target = mc.getMainRenderTarget();
        if (target.width <= MIN_SIDE || target.height <= MIN_SIDE) {
            UtilitySuiteMod.LOGGER.error("UI preview screenshot {} skipped: render target is {}x{} (window {}x{})",
                    name, target.width, target.height, mc.getWindow().getWidth(), mc.getWindow().getHeight());
            return;
        }
        Path dir = mc.gameDirectory.toPath().resolve("ui-preview");
        try (NativeImage img = Screenshot.takeScreenshot(target)) {
            Files.createDirectories(dir);
            img.writeToFile(dir.resolve(name + ".png"));
            UtilitySuiteMod.LOGGER.info("UI preview saved {} ({}x{}, window {}x{}, gui scale {}, {} frames rendered)",
                    dir.resolve(name + ".png"), img.getWidth(), img.getHeight(), mc.getWindow().getWidth(),
                    mc.getWindow().getHeight(), mc.getWindow().getGuiScale(), frames - frameMark);
        } catch (Exception e) {
            UtilitySuiteMod.LOGGER.error("UI preview screenshot {} failed", name, e);
        }
    }

    /** A game-like backdrop with the HUD pill (fake running job) and the "automation stopped" badge. */
    private static final class HudPreviewScreen extends Screen {
        private final SuiteHud hud = new SuiteHud();

        HudPreviewScreen() {
            super(Component.literal("HUD preview"));
            hud.line().update(demoJob());
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            g.fillGradient(0, 0, width, height * 2 / 3, 0xFF79A6D9, 0xFFB5D3EE);
            g.fill(0, height * 2 / 3, width, height * 2 / 3 + 6, 0xFF5D8C3A);
            g.fill(0, height * 2 / 3 + 6, width, height, 0xFF7A5A3A);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            renderBackground(g, mouseX, mouseY, partialTick);
            hud.renderOverlay(g, font, width, false, true);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
