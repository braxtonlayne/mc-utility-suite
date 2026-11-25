package com.utilitysuite.core.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import org.lwjgl.glfw.GLFW;

/**
 * Minecraft side of the smoother. The one place (besides the mixins and {@link ManualInputHooks}) that touches vanilla
 * {@link KeyMapping}s, the player's rotation, the selected hotbar slot and {@code handleInventoryMouseClick}.
 * <p>
 * Sneak and sprint are {@code ToggleKeyMapping}s: with "toggle crouch/sprint" enabled, {@code setDown(true)} flips the
 * state instead of setting it. For those keys the backend only flips when the logical state differs, and on release
 * restores the toggle state the user had before automation took the key.
 */
public final class MinecraftInputBackend implements InputBackend {

    private static final SimKey[] KEYS = SimKey.all();
    private static final ClickType[] CLICK_TYPES = {ClickType.PICKUP, ClickType.QUICK_MOVE, ClickType.SWAP,
            ClickType.THROW};

    private final boolean[] priorToggleState = new boolean[KEYS.length];

    private static KeyMapping mapping(Options o, SimKey key) {
        return switch (key) {
            case FORWARD -> o.keyUp;
            case BACK -> o.keyDown;
            case LEFT -> o.keyLeft;
            case RIGHT -> o.keyRight;
            case JUMP -> o.keyJump;
            case SNEAK -> o.keyShift;
            case SPRINT -> o.keySprint;
            case ATTACK -> o.keyAttack;
            case USE -> o.keyUse;
        };
    }

    private static boolean toggleMode(Options o, SimKey key) {
        if (key == SimKey.SNEAK) {
            return o.toggleCrouch().get();
        }
        if (key == SimKey.SPRINT) {
            return o.toggleSprint().get();
        }
        return false;
    }

    @Override
    public boolean invertMouseY() {
        return Minecraft.getInstance().options.invertYMouse().get();
    }

    @Override
    public boolean isReady() {
        return Minecraft.getInstance().player != null;
    }

    @Override
    public void pressKey(SimKey key) {
        Options o = Minecraft.getInstance().options;
        KeyMapping m = mapping(o, key);
        if (toggleMode(o, key)) {
            priorToggleState[key.ordinal()] = m.isDown();
            if (!m.isDown()) {
                m.setDown(true); // flips to down
            }
        } else {
            m.setDown(true);
        }
    }

    @Override
    public void holdKey(SimKey key) {
        Options o = Minecraft.getInstance().options;
        KeyMapping m = mapping(o, key);
        if (toggleMode(o, key)) {
            if (!m.isDown()) {
                m.setDown(true);
            }
        } else {
            m.setDown(true);
        }
    }

    @Override
    public void releaseKey(SimKey key) {
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        KeyMapping m = mapping(o, key);
        if (toggleMode(o, key)) {
            if (m.isDown() != priorToggleState[key.ordinal()]) {
                m.setDown(true); // flips back
            }
        } else {
            // Never clobber a key the user is physically holding.
            m.setDown(isPhysicallyDown(mc, m));
        }
    }

    @Override
    public void clickKey(SimKey key) {
        KeyMapping m = mapping(Minecraft.getInstance().options, key);
        if (!m.isUnbound()) {
            KeyMapping.click(m.getKey()); // same as a hardware press edge
        }
    }

    private static boolean isPhysicallyDown(Minecraft mc, KeyMapping m) {
        if (m.isUnbound()) {
            return false;
        }
        InputConstants.Key k = m.getKey();
        long window = mc.getWindow().getWindow();
        if (k.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(window, k.getValue());
        }
        if (k.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, k.getValue()) == GLFW.GLFW_PRESS;
        }
        return false;
    }

    @Override
    public boolean readMoveAxes(SharedInputOverlay.MoveFields f) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.input == null) {
            return false;
        }
        // the object MovementInputUpdateEvent carries: aiStep ticks player.input and fires the event with it
        f.forwardImpulse = p.input.forwardImpulse;
        f.leftImpulse = p.input.leftImpulse;
        return true;
    }

    @Override
    public void writeMoveAxes(SharedInputOverlay.MoveFields f) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.input == null) {
            return;
        }
        Input in = p.input;
        in.forwardImpulse = f.forwardImpulse;
        in.leftImpulse = f.leftImpulse;
        in.up = f.up;
        in.down = f.down;
        in.left = f.left;
        in.right = f.right;
    }

    @Override
    public float yaw() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 0f : p.getYRot();
    }

    @Override
    public float pitch() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 0f : p.getXRot();
    }

    @Override
    public double mouseSensitivity() {
        return Minecraft.getInstance().options.sensitivity().get();
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null) {
            p.setYRot(yaw);
            p.setXRot(pitch);
        }
    }

    @Override
    public void selectHotbarSlot(int slot) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p != null && slot >= 0 && slot < 9) {
            // The carried-item packet is sent by MultiPlayerGameMode.ensureHasSentCarriedItem() on the next tick.
            p.getInventory().selected = slot;
        }
    }

    @Override
    public boolean clickContainerSlot(int slot, int button, IContainerClicker.Click click) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) {
            return false;
        }
        AbstractContainerMenu menu = p.containerMenu;
        if (slot != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && (slot < 0 || slot >= menu.slots.size())) {
            return false;
        }
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, CLICK_TYPES[click.ordinal()], p);
        return true;
    }

    @Override
    public boolean placeRecipe(String recipeId, boolean craftAll) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(recipeId);
        if (p == null || mc.gameMode == null || mc.level == null || id == null) {
            return false;
        }
        var recipe = mc.level.getRecipeManager().byKey(id);
        if (recipe.isEmpty()) {
            return false;
        }
        mc.gameMode.handlePlaceRecipe(p.containerMenu.containerId, recipe.get(), craftAll);
        return true;
    }

    @Override
    public int crosshairEntityId() {
        net.minecraft.world.phys.HitResult hr = Minecraft.getInstance().hitResult;
        return hr instanceof net.minecraft.world.phys.EntityHitResult ehr
                && hr.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY ? ehr.getEntity().getId() : -1;
    }
}
