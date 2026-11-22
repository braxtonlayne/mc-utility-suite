package com.utilitysuite.world.minimap;

import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.holo.HoloFrames;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.kit.PillButton;
import com.utilitysuite.ui.kit.UiRenderUtils;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * HUD edit mode for the minimap: the map is drawn with a dashed hairline outline; drag the body to move it, drag the
 * bottom-right handle to resize (square, clamped to 64..320 and to the screen). ESC or Done saves x/y as screen
 * fractions and the size to the profile, then returns to the previous screen. Drawn in theme tokens with the kit's
 * pill button.
 */
public final class MinimapLayoutScreen extends Screen {

    private static final int HANDLE = 8;
    private static final String HINT = "Drag to move, corner to resize. ESC or Done to save.";
    private static final String OFF_HINT = "Minimap is off (turn it on in the World tab).";

    private final MinimapModule module;
    private final MinimapHud hud;
    private final Screen parent;
    private PillButton done;
    private int mapX;
    private int mapY;
    private int mapSize;
    private boolean moving;
    private boolean resizing;
    private int grabDx;
    private int grabDy;

    public MinimapLayoutScreen(MinimapModule module, MinimapHud hud, Screen parent) {
        super(Component.literal("Edit HUD layout"));
        this.module = module;
        this.hud = hud;
        this.parent = parent;
    }

    @Override
    protected void init() {
        mapSize = MinimapLayout.clampSize(module.size, width, height);
        mapX = MinimapLayout.toX(module.xFrac, width, mapSize);
        mapY = MinimapLayout.toY(module.yFrac, height, mapSize);
        done = new PillButton(font, "Done", 80, PillButton.Style.PRIMARY, b -> onClose()).pill(true);
        done.x = width / 2 - done.w / 2;
        done.y = height - 28;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No blur: the point is to see the HUD in place.
        HoloFrames.plate(g, 0, 0, width, height, HoloToken.VOID, 0.19f);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        hud.draw(g, mapX, mapY, mapSize, partialTick);
        int b = MinimapLayout.BEZEL;
        dashedRect(g, mapX - b - 1, mapY - b - 1, mapX + mapSize + b + 1, mapY + mapSize + b + 1);
        int hx = mapX + mapSize + b - HANDLE / 2;
        int hy = mapY + mapSize + b - HANDLE / 2;
        // the resize handle: a bracketed square, accent while dragged
        HoloFrames.plate(g, hx, hy, HANDLE, HANDLE, HoloToken.GLASS_RAISED, 0.9f);
        HoloFrames.edge(g, hx, hy, HANDLE, HANDLE, HoloTheme.argb(resizing ? HoloToken.ACCENT : HoloToken.LINE_STRONG));
        centered(g, HINT, 8, HoloToken.TEXT);
        if (!module.isEnabled()) {
            centered(g, OFF_HINT, 20, HoloToken.WARN);
        }
        done.render(g, font, done.x, done.y, mouseX, mouseY);
    }

    private void centered(GuiGraphics g, String s, int y, HoloToken token) {
        UiRenderUtils.text(g, font, s, (width - font.width(s)) / 2, y, HoloTheme.argb(token));
    }

    private static void dashedRect(GuiGraphics g, int x0, int y0, int x1, int y1) {
        int line = HoloTheme.argb(HoloToken.LINE_STRONG);
        for (int x = x0; x < x1; x += 6) {
            HoloFrames.hLine(g, x, Math.min(x + 3, x1), y0, line);
            HoloFrames.hLine(g, x, Math.min(x + 3, x1), y1 - 1, line);
        }
        for (int y = y0; y < y1; y += 6) {
            HoloFrames.vLine(g, x0, y, Math.min(y + 3, y1), line);
            HoloFrames.vLine(g, x1 - 1, y, Math.min(y + 3, y1), line);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (done.contains(mx, my, done.x, done.y) && done.onClick(mx, my, button, done.x, done.y)) {
            return true;
        }
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (button != 0) {
            return false;
        }
        int b = MinimapLayout.BEZEL;
        int hx = mapX + mapSize + b;
        int hy = mapY + mapSize + b;
        if (Math.abs(mx - hx) <= HANDLE && Math.abs(my - hy) <= HANDLE) {
            resizing = true;
            return true;
        }
        if (mx >= mapX - b && mx <= mapX + mapSize + b && my >= mapY - b && my <= mapY + mapSize + b) {
            moving = true;
            grabDx = (int) mx - mapX;
            grabDy = (int) my - mapY;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (resizing) {
            mapSize = MinimapLayout.resize(mapX, mapY, (int) mx - MinimapLayout.BEZEL, (int) my - MinimapLayout.BEZEL,
                    width, height);
            return true;
        }
        if (moving) {
            mapX = MinimapLayout.clampX((int) mx - grabDx, width, mapSize);
            mapY = MinimapLayout.clampY((int) my - grabDy, height, mapSize);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        moving = false;
        resizing = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public void onClose() {
        module.saveLayout(MinimapLayout.toFracX(mapX, width, mapSize), MinimapLayout.toFracY(mapY, height, mapSize),
                mapSize);
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
