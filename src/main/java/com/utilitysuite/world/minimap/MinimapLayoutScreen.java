package com.utilitysuite.world.minimap;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * HUD edit mode for the minimap: the map is drawn with a dashed outline; drag the body to move it, drag the
 * bottom-right handle to resize (square, clamped to 64..320 and to the screen). ESC or Done saves x/y as screen
 * fractions and the size to the profile, then returns to the previous screen.
 */
public final class MinimapLayoutScreen extends Screen {

    private static final int HANDLE = 8;
    private static final int OUTLINE = 0xFFE0E0E0;

    private final MinimapModule module;
    private final MinimapHud hud;
    private final Screen parent;
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
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(width / 2 - 40, height - 28, 80, 20).build());
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No blur: the point is to see the HUD in place.
        g.fill(0, 0, width, height, 0x30000000);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hud.draw(g, mapX, mapY, mapSize, partialTick);
        int b = MinimapLayout.BEZEL;
        dashedRect(g, mapX - b - 1, mapY - b - 1, mapX + mapSize + b + 1, mapY + mapSize + b + 1);
        int hx = mapX + mapSize + b - HANDLE / 2;
        int hy = mapY + mapSize + b - HANDLE / 2;
        g.fill(hx, hy, hx + HANDLE, hy + HANDLE, resizing ? 0xFFFFD040 : OUTLINE);
        g.drawCenteredString(font, "Drag to move, corner to resize. ESC or Done to save.", width / 2, 8, 0xFFFFFFFF);
        if (!module.isEnabled()) {
            g.drawCenteredString(font, "Minimap is off (turn it on in the World tab).", width / 2, 20, 0xFFFFB020);
        }
    }

    private static void dashedRect(GuiGraphics g, int x0, int y0, int x1, int y1) {
        for (int x = x0; x < x1; x += 6) {
            g.fill(x, y0, Math.min(x + 3, x1), y0 + 1, OUTLINE);
            g.fill(x, y1 - 1, Math.min(x + 3, x1), y1, OUTLINE);
        }
        for (int y = y0; y < y1; y += 6) {
            g.fill(x0, y, x0 + 1, Math.min(y + 3, y1), OUTLINE);
            g.fill(x1 - 1, y, x1, Math.min(y + 3, y1), OUTLINE);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
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
