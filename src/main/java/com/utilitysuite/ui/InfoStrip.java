package com.utilitysuite.ui;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.ui.kit.UiTheme;
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

/**
 * Optional Info HUD strip (off by default; settings live on the {@code suite_hud} module): coordinates, Nether /
 * Overworld conversion, biome, TPS, ping and durability bars for the main hand and armor, in one compact rounded
 * dark-glass strip.
 * <p>
 * Values are sampled once per client tick (early-out when disabled) into {@link InfoStripText}, which rebuilds a string
 * only when its integers changed; the biome name is resolved only when the biome holder changes. The render callback
 * only draws cached strings and re-measures widths when {@link InfoStripText#version()} moved. Hidden in Clean-Screen,
 * with F1, with F3 and while any screen is open.
 */
public final class InfoStrip implements LayeredDraw.Layer {

    public enum Position { BOTTOM_LEFT, TOP_LEFT, TOP_RIGHT }

    static final int BG = 0xCC111318;
    private static final int H = 16;
    private static final int PAD = 7;
    private static final int GAP = 10;
    private static final int BAR_W = 14;
    private static final int BAR_H = 2;
    private static final int BAR_GAP = 3;
    private static final int SLOTS = 5;
    private static final int TPS_EVERY_TICKS = 10;

    private final InfoStripText text = new InfoStripText();
    private final InfoStripText.TpsEstimator tpsEstimator = new InfoStripText.TpsEstimator(3_000_000_000L);
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final SuiteHud hud;

    // settings (written by SuiteHud.read on the client thread)
    boolean enabled;
    Position position = Position.TOP_LEFT;
    boolean showCoords = true;
    boolean showConversion = true;
    boolean showBiome = true;
    boolean showTps = true;
    boolean showPing = true;
    boolean showDurability = true;

    // durability per slot (0 = main hand, 1..4 = armor feet..head); fill < 0 = no bar
    private final int[] barFill = new int[SLOTS];
    private final int[] barColor = new int[SLOTS];
    private int bars;

    // cached layout
    private int seenVersion = -1;
    private int coordsW, convW, biomeW, tpsW, pingW;
    private int ticks;

    InfoStrip(SuiteHud hud) {
        this.hud = hud;
    }

    void subscribe() {
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
    }

    /** The text cache (tests and preview). */
    InfoStripText text() {
        return text;
    }

    // ---- sampling (client tick) -------------------------------------------------------------------------------------

    private void onClientTick(ClientTickEvent.Post event) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            text.reset();
            tpsEstimator.reset();
            bars = 0;
            return;
        }
        int x = p.getBlockX();
        int y = p.getBlockY();
        int z = p.getBlockZ();
        text.setCoords(x, y, z);
        var dim = mc.level.dimension();
        text.setConversion(dim == Level.OVERWORLD ? InfoStripText.DIM_OVERWORLD
                : dim == Level.NETHER ? InfoStripText.DIM_NETHER : InfoStripText.DIM_OTHER, x, z);
        if (showBiome) {
            Holder<Biome> holder = mc.level.getBiome(probe.set(x, y, z));
            if (!text.isBiome(holder)) {
                text.setBiome(holder, holder.unwrapKey().map(k -> k.location().toString()).orElse("unknown"));
            }
        }
        sampleTps(mc);
        if (showPing) {
            ClientPacketListener conn = mc.getConnection();
            PlayerInfo info = conn == null ? null : conn.getPlayerInfo(p.getUUID());
            text.setPing(info == null ? -1 : info.getLatency());
        }
        if (showDurability) {
            bars = 0;
            sampleBar(0, p.getMainHandItem());
            for (int i = 0; i < 4; i++) {
                sampleBar(i + 1, p.getInventory().armor.get(3 - i)); // head first
            }
        }
    }

    private void sampleTps(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server != null) {
            if (++ticks % TPS_EVERY_TICKS == 0) {
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
            barFill[slot] = -1;
            return;
        }
        int max = s.getMaxDamage();
        int dmg = s.getDamageValue();
        barFill[slot] = InfoStripText.barFill(dmg, max, BAR_W);
        barColor[slot] = InfoStripText.barColor(dmg, max);
        bars++;
    }

    // ---- render -----------------------------------------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null || mc.getDebugOverlay().showDebugScreen()
                || Services.modState().isCleanScreen()) {
            return;
        }
        Font font = mc.font;
        if (text.version() != seenVersion) {
            seenVersion = text.version();
            coordsW = width(font, text.coords());
            convW = width(font, text.conversion());
            biomeW = width(font, text.biome());
            tpsW = width(font, text.tps());
            pingW = width(font, text.ping());
        }
        int w = PAD;
        w += seg(showCoords, coordsW);
        w += seg(showConversion, convW);
        w += seg(showBiome, biomeW);
        w += seg(showTps, tpsW);
        w += seg(showPing, pingW);
        int barsW = showDurability && bars > 0 ? bars * (BAR_W + BAR_GAP) - BAR_GAP + GAP : 0;
        w += barsW;
        if (w == PAD) {
            return; // nothing to show
        }
        w = w - GAP + PAD;
        int sw = g.guiWidth();
        int sh = g.guiHeight();
        int x;
        int y;
        switch (position) {
            case BOTTOM_LEFT -> {
                x = 6;
                y = sh - H - 6;
            }
            case TOP_RIGHT -> {
                x = sw - w - 6;
                y = 6 + hud.topUsed(SuiteHud.Position.TOP_RIGHT);
            }
            default -> {
                x = 6;
                y = 6 + hud.topUsed(SuiteHud.Position.TOP_LEFT);
            }
        }
        UiRenderUtils.roundedRect(g, x, y, w, H, 6, BG);
        UiRenderUtils.roundedOutline(g, x, y, w, H, 6, 0xCC2A2F3B);
        int cx = x + PAD;
        int ty = y + (H - 8) / 2;
        cx = draw(g, font, showCoords, text.coords(), coordsW, cx, ty, UiTheme.TEXT);
        cx = draw(g, font, showConversion, text.conversion(), convW, cx, ty, UiTheme.TEXT_DIM);
        cx = draw(g, font, showBiome, text.biome(), biomeW, cx, ty, UiTheme.TEXT_SECONDARY);
        cx = draw(g, font, showTps, text.tps(), tpsW, cx, ty, UiTheme.TEXT_SECONDARY);
        cx = draw(g, font, showPing, text.ping(), pingW, cx, ty, UiTheme.TEXT_SECONDARY);
        if (barsW > 0) {
            int by = y + (H - BAR_H) / 2;
            for (int i = 0; i < SLOTS; i++) {
                int fill = barFill[i];
                if (fill < 0) {
                    continue;
                }
                g.fill(cx, by, cx + BAR_W, by + BAR_H, 0xFF2A2F3B);
                if (fill > 0) {
                    g.fill(cx, by, cx + fill, by + BAR_H, barColor[i]);
                }
                cx += BAR_W + BAR_GAP;
            }
        }
    }

    private static int width(Font font, String s) {
        return s.isEmpty() ? 0 : font.width(s);
    }

    private static int seg(boolean on, int w) {
        return on && w > 0 ? w + GAP : 0;
    }

    private static int draw(GuiGraphics g, Font font, boolean on, String s, int w, int x, int y, int color) {
        if (!on || w == 0) {
            return x;
        }
        UiRenderUtils.text(g, font, s, x, y, color);
        return x + w + GAP;
    }
}
