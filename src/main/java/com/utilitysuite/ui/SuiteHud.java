package com.utilitysuite.ui;

import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.api.visual.HudSlot;
import com.utilitysuite.jobs.schematic.SchematicBootstrap;
import com.utilitysuite.jobs.schematic.SchematicJobCard;
import com.utilitysuite.jobs.schematic.SchematicToolkit;
import com.utilitysuite.ui.holo.HoloKit;
import com.utilitysuite.ui.holo.HudHost;
import com.utilitysuite.ui.holo.Visuals;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;
import java.util.Optional;

/**
 * The in-game HUD module and its one GUI layer. HUD discipline: the layer runs the engine gates (hidden GUI, no
 * player) and hands the frame to the {@link HudHost}, which draws nothing while Clean-Screen is on (except the opt-in
 * Clean-Screen badge), nothing when no element wants to draw, and otherwise the status line (from the active goal
 * job, the autopilot's acting reflex or the schematic job card), the "automation stopped" badge (default on), the
 * optional info strip and the optional enabled-module list, every one drawn through the holo canvas in theme tokens.
 * Other modules register their own {@code HudElement}s with the same host through {@code Services.visuals().hud()}.
 * <p>
 * The status text is refreshed on {@code JobChanged} (and by a slow safety poll every {@value #POLL_TICKS} ticks,
 * which also covers schematic jobs running outside the goal engine); {@link HudStatusLine} rebuilds the string only
 * when the snapshot changed, and the render callback only draws cached strings. The info strip samples once per
 * client tick (early-out when off).
 */
public final class SuiteHud implements IModule, IConfigurable, LayeredDraw.Layer {

    public static final String ID = "suite_hud";
    public static final String K_STOPPED_BADGE = "stopped_badge";
    public static final String K_CLEAN_BADGE = "clean_screen_badge";
    public static final String K_POSITION = "position";

    public enum Position { TOP_CENTER, TOP_LEFT, TOP_RIGHT }

    /** Safety poll; normally the line updates on {@code JobChanged}, which the goal engine posts on every visible change. */
    private static final int POLL_TICKS = 40;
    private static final int TPS_EVERY_TICKS = 10;

    private final HudStatusLine line = new HudStatusLine();
    private final HudPill pill = new HudPill(line);
    private final HudPill.Badge stopped = new HudPill.Badge("stopped_badge", "Automation stopped", true, 90);
    private final HudPill.Badge clean = new HudPill.Badge("clean_badge", "Clean-Screen", false, 80);
    private final InfoStrip infoStrip = new InfoStrip();
    private final ModuleListHud moduleList = new ModuleListHud();
    private final HudHost host = new HudHost();
    private final Visuals visuals = new Visuals(host);
    private HoloKit kit;
    private boolean frameClean;
    private final Runnable frameTask = this::drawFrame;

    private boolean enabled = true;
    private boolean stoppedBadge = true;
    private boolean cleanBadge;
    private Position position = Position.TOP_CENTER;
    private int ticks;
    private boolean pollNow;
    private IAutopilot.Reflex lastReflex;

    // info strip sampling
    private final InfoStripText.TpsEstimator tpsEstimator = new InfoStripText.TpsEstimator(3_000_000_000L);
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private int infoTicks;

    public static final String K_MODULE_LIST = "module_list";
    public static final String K_INFO = "info_strip";
    public static final String K_INFO_POSITION = "info_position";
    public static final String K_INFO_COORDS = "info_coords";
    public static final String K_INFO_CONVERT = "info_conversion";
    public static final String K_INFO_BIOME = "info_biome";
    public static final String K_INFO_TPS = "info_tps";
    public static final String K_INFO_PING = "info_ping";
    public static final String K_INFO_DURABILITY = "info_durability";
    private static final String INFO_GROUP = "Info strip";

    public SuiteHud() {
        host.register(pill);
        host.register(stopped);
        host.register(clean);
        host.register(infoStrip);
        host.register(moduleList);
        host.setInstant(stopped, true);
        host.allowInCleanScreen(clean, true);
    }

    public void subscribe() {
        Services.events().subscribe(SuiteEvent.JobChanged.class, e -> pollNow = true);
        moduleList.subscribe();
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.CORE;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        pill.enabled = enabled;
        if (!enabled) {
            line.clear();
        }
    }

    @Override
    public void onTick() {
        moduleList.tick();
        // the autopilot's acting reflex is short-lived: check it every tick (two field reads, no allocation)
        IAutopilot ap = Services.autopilotOrNull();
        IAutopilot.Reflex reflex = ap != null && ap.isOn() ? ap.activeReflex() : null;
        if (reflex != lastReflex) {
            lastReflex = reflex;
            pollNow = true;
        }
        if (!pollNow && ++ticks % POLL_TICKS != 0) {
            return;
        }
        pollNow = false;
        if (Services.modState().isCleanScreen()) {
            return;
        }
        if (reflex != null) {
            // what is acting right now outranks the job line; one line only
            line.updateAutopilot(ReflexChips.statusLine(reflex));
            return;
        }
        IGoalEngine goals = Services.goalsOrNull();
        Optional<JobSnapshot> job = goals == null ? Optional.empty() : goals.activeJob();
        if (job.isPresent() && !job.get().status().isTerminal()) {
            line.update(job.get()); // the status line re-measures itself when the text was rebuilt
        } else {
            SchematicToolkit t = SchematicBootstrap.toolkit();
            Optional<SchematicJobCard> card = t == null || !t.isEnabled() ? Optional.empty() : t.jobCard();
            if (card.isPresent()) {
                line.updateSchematic(card.get().statusLine());
            } else {
                line.clear();
            }
        }
    }

    @Override
    public void onWorldUnload() {
        line.clear();
        visuals.onWorldUnload();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        read(profile);
    }

    // ---- client tick: shared visual state and info strip sampling ---------------------------------------------------

    private void onClientTick(ClientTickEvent.Post event) {
        visuals.tick();
        if (!infoStrip.enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        InfoStripText text = infoStrip.text();
        if (p == null || mc.level == null) {
            text.reset();
            tpsEstimator.reset();
            infoStrip.bars = 0;
            return;
        }
        int x = p.getBlockX();
        int y = p.getBlockY();
        int z = p.getBlockZ();
        text.setCoords(x, y, z);
        var dim = mc.level.dimension();
        text.setConversion(dim == Level.OVERWORLD ? InfoStripText.DIM_OVERWORLD
                : dim == Level.NETHER ? InfoStripText.DIM_NETHER : InfoStripText.DIM_OTHER, x, z);
        if (infoStrip.showBiome) {
            Holder<Biome> holder = mc.level.getBiome(probe.set(x, y, z));
            if (!text.isBiome(holder)) {
                text.setBiome(holder, holder.unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
            }
        }
        sampleTps(mc, text);
        if (infoStrip.showPing) {
            ClientPacketListener conn = mc.getConnection();
            PlayerInfo info = conn == null ? null : conn.getPlayerInfo(p.getUUID());
            text.setPing(info == null ? -1 : info.getLatency());
        }
        if (infoStrip.showDurability) {
            sampleBar(0, p.getMainHandItem());
            for (int i = 0; i < 4; i++) {
                sampleBar(i + 1, p.getInventory().armor.get(3 - i)); // head first
            }
            infoStrip.countBars();
        }
    }

    private void sampleTps(Minecraft mc, InfoStripText text) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            if (++infoTicks % TPS_EVERY_TICKS == 0) {
                long avg = server.getAverageTickTimeNanos();
                float rate = server.tickRateManager().tickrate();
                double tps = avg <= 0 ? rate : Math.min(rate, 1e9 / avg);
                text.setTpsTenths((int) Math.round(tps * 10.0));
            }
            return;
        }
        tpsEstimator.sample(mc.level.getGameTime(), System.nanoTime());
        text.setTpsTenths(tpsEstimator.tenths());
    }

    private void sampleBar(int slot, ItemStack s) {
        if (s.isEmpty() || !s.isDamageableItem()) {
            infoStrip.setBar(slot, 0, 0);
            return;
        }
        infoStrip.setBar(slot, s.getDamageValue(), s.getMaxDamage());
    }

    // ---- layer ------------------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) {
            return;
        }
        boolean debug = mc.getDebugOverlay().showDebugScreen();
        infoStrip.blocked = debug || mc.screen != null;
        moduleList.blocked = debug;
        renderOverlay(g, mc.font, g.guiWidth(), Services.modState().isCleanScreen(),
                Services.arbiter().isAutomationStopped());
    }

    /**
     * Draws the HUD for the given state (also used by the UI preview with a fake job). Returns before touching the
     * buffers when nothing wants to draw.
     */
    void renderOverlay(GuiGraphics g, Font font, int sw, boolean cleanScreen, boolean automationStopped) {
        stopped.active = stoppedBadge && automationStopped && !cleanScreen;
        clean.active = cleanBadge && cleanScreen;
        if (!host.wantsAny(cleanScreen)) {
            host.frame(null, cleanScreen); // resets the appear state; makes no canvas call
            return;
        }
        if (kit == null) {
            kit = new HoloKit();
        }
        frameClean = cleanScreen;
        kit.begin(g);
        try {
            g.drawManaged(frameTask); // one batch for the whole HUD frame
        } finally {
            kit.end();
        }
    }

    private void drawFrame() {
        host.frame(kit, frameClean);
    }

    /** The HUD host (other modules register through {@code Services.visuals().hud()}). */
    public HudHost host() {
        return host;
    }

    /** The visuals service backed by this HUD (registered by {@link UiBootstrap}). */
    public Visuals visuals() {
        return visuals;
    }

    /** The optional list of enabled modules (top-right). */
    ModuleListHud moduleList() {
        return moduleList;
    }

    /** The optional info strip (settings live on this module). */
    InfoStrip infoStrip() {
        return infoStrip;
    }

    /** The status line driving the HUD (preview harness feeds it a fake snapshot). */
    HudStatusLine line() {
        return line;
    }

    // ---- settings ---------------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "HUD status line";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice(K_POSITION, "Position", Position.TOP_CENTER, "",
                        "Where the job status line is drawn."),
                SettingSpec.bool(K_STOPPED_BADGE, "\"Automation stopped\" badge", true, "",
                        "Small badge while Stop-All is active."),
                SettingSpec.bool(K_CLEAN_BADGE, "Clean-Screen indicator", false, "",
                        "Small badge while Clean-Screen hides everything else."),
                SettingSpec.bool(K_MODULE_LIST, "Enabled module list", false, "",
                        "List of the modules that are on, top-right, longest name first, with a category colour"
                                + " bar. The autopilot heads it while it is on; a module acting right now is"
                                + " marked. Hidden with F1, F3 and Clean-Screen."),
                SettingSpec.bool(K_INFO, "Info strip", false, INFO_GROUP,
                        "Compact strip with coordinates, biome, TPS, ping and durability. Hidden with F1, F3, "
                                + "open screens and Clean-Screen."),
                SettingSpec.choice(K_INFO_POSITION, "Strip position", InfoStrip.Position.TOP_LEFT, INFO_GROUP,
                        "Corner the info strip is drawn in."),
                SettingSpec.bool(K_INFO_COORDS, "Coordinates", true, INFO_GROUP, "X Y Z block coordinates."),
                SettingSpec.bool(K_INFO_CONVERT, "Nether/Overworld conversion", true, INFO_GROUP,
                        "Matching coordinates in the other dimension (x/8 or x*8). Not shown in the End."),
                SettingSpec.bool(K_INFO_BIOME, "Biome", true, INFO_GROUP, "Biome at your feet."),
                SettingSpec.bool(K_INFO_TPS, "TPS", true, INFO_GROUP,
                        "Server ticks per second (exact in single-player, estimated on servers)."),
                SettingSpec.bool(K_INFO_PING, "Ping", true, INFO_GROUP, "Your latency to the server."),
                SettingSpec.bool(K_INFO_DURABILITY, "Durability bars", true, INFO_GROUP,
                        "Bars for the main-hand item and worn armor (damageable items only)."));
    }

    @Override
    public void onSettingChanged(String key) {
        read(Services.modState().profile());
    }

    private void read(ProfileConfig p) {
        stoppedBadge = p.getBool(ID, K_STOPPED_BADGE, true);
        cleanBadge = p.getBool(ID, K_CLEAN_BADGE, false);
        try {
            position = Position.valueOf(p.getString(ID, K_POSITION, Position.TOP_CENTER.name()));
        } catch (IllegalArgumentException e) {
            position = Position.TOP_CENTER;
        }
        HudSlot slot = switch (position) {
            case TOP_LEFT -> HudSlot.TOP_LEFT;
            case TOP_RIGHT -> HudSlot.TOP_RIGHT;
            default -> HudSlot.TOP_CENTER;
        };
        pill.slot = slot;
        stopped.slot = slot;
        clean.slot = slot;
        moduleList.enabled = p.getBool(ID, K_MODULE_LIST, false);
        moduleList.invalidate();
        InfoStrip s = infoStrip;
        s.enabled = p.getBool(ID, K_INFO, false);
        try {
            s.position = InfoStrip.Position.valueOf(p.getString(ID, K_INFO_POSITION, InfoStrip.Position.TOP_LEFT.name()));
        } catch (IllegalArgumentException e) {
            s.position = InfoStrip.Position.TOP_LEFT;
        }
        s.showCoords = p.getBool(ID, K_INFO_COORDS, true);
        s.showConversion = p.getBool(ID, K_INFO_CONVERT, true);
        s.showBiome = p.getBool(ID, K_INFO_BIOME, true);
        s.showTps = p.getBool(ID, K_INFO_TPS, true);
        s.showPing = p.getBool(ID, K_INFO_PING, true);
        s.showDurability = p.getBool(ID, K_INFO_DURABILITY, true);
    }

    /** Current status text (tests, diagnostics). */
    public String text() {
        return line.text();
    }
}
