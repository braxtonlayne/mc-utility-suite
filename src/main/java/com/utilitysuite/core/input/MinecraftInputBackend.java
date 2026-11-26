package com.utilitysuite.core.input;

import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;

/**
 * Minecraft side of the smoother. The one place (besides the mixins and {@link ManualInputHooks}) that touches vanilla
 * {@link KeyMapping}s, the player's rotation, the selected hotbar slot and {@code handleContainerInput} (1.21.1 {@code handleInventoryMouseClick}).
 * <p>
 * Sneak and sprint are {@code ToggleKeyMapping}s: with "toggle crouch/sprint" enabled, {@code setDown(true)} flips the
 * state instead of setting it. For those keys the backend only flips when the logical state differs, and on release
 * restores the toggle state the user had before automation took the key.
 */
public final class MinecraftInputBackend implements InputBackend {

    private static final SimKey[] KEYS = SimKey.all();
    private static final ContainerInput[] CLICK_TYPES = {ContainerInput.PICKUP, ContainerInput.QUICK_MOVE,
            ContainerInput.SWAP, ContainerInput.THROW};

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
        return Minecraft.getInstance().options.invertMouseY().get();
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
            m.setDown(KeyInputs.isPhysicallyDown(m));
        }
    }

    @Override
    public void clickKey(SimKey key) {
        KeyMapping m = mapping(Minecraft.getInstance().options, key);
        if (!m.isUnbound()) {
            KeyMapping.click(m.getKey()); // same as a hardware press edge
        }
    }

    @Override
    public boolean readMoveAxes(SharedInputOverlay.MoveFields f) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.input == null) {
            return false;
        }
        // the object MovementInputUpdateEvent carries: aiStep ticks player.input and fires the event with it
        f.forwardImpulse = MoveInputs.forward(p.input);
        f.leftImpulse = MoveInputs.left(p.input);
        return true;
    }

    @Override
    public void writeMoveAxes(SharedInputOverlay.MoveFields f) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.input == null) {
            return;
        }
        ClientInput in = p.input;
        MoveInputs.setMove(in, f.forwardImpulse, f.leftImpulse);
        net.minecraft.world.entity.player.Input k = in.keyPresses;
        MoveInputs.setKeys(in, f.up, f.down, f.left, f.right, k.jump(), k.shift());
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
            p.getInventory().setSelectedSlot(slot);
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
        mc.gameMode.handleContainerInput(menu.containerId, slot, button, CLICK_TYPES[click.ordinal()], p);
        return true;
    }

    @Override
    public boolean placeRecipe(String recipeId, boolean craftAll) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        int index = recipeDisplayIndex(recipeId);
        if (p == null || mc.gameMode == null || index < 0) {
            return false;
        }
        // 26.3: the client recipe book only knows display ids; the server resolves the recipe from it
        net.minecraft.world.item.crafting.display.RecipeDisplayId id =
                new net.minecraft.world.item.crafting.display.RecipeDisplayId(index);
        mc.gameMode.handlePlaceRecipe(p.containerMenu.containerId, id, craftAll);
        return true;
    }

    /** Parses {@code "123"} or {@code "#123"}; -1 for anything else (a 1.21.1 namespaced id included). */
    static int recipeDisplayIndex(String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) {
            return -1;
        }
        String s = recipeId.charAt(0) == '#' ? recipeId.substring(1) : recipeId;
        try {
            int i = Integer.parseInt(s);
            return i < 0 ? -1 : i;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Same as clicking an empty crafter slot in CrafterScreen: update the client menu, then send the vanilla
     * slot-state packet (MultiPlayerGameMode#handleSlotStateChanged). Only slots 0..8 of an open CrafterMenu.
     */
    @Override
    public boolean setCrafterSlotEnabled(int slot, boolean enabled) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null || slot < 0 || slot > 8
                || !(p.containerMenu instanceof net.minecraft.world.inventory.CrafterMenu menu)) {
            return false;
        }
        menu.setSlotState(slot, enabled);
        mc.gameMode.handleSlotStateChanged(slot, menu.containerId, enabled);
        return true;
    }

    @Override
    public int crosshairEntityId() {
        net.minecraft.world.phys.HitResult hr = Minecraft.getInstance().hitResult;
        return hr instanceof net.minecraft.world.phys.EntityHitResult ehr
                && hr.getType() == net.minecraft.world.phys.HitResult.Type.ENTITY ? ehr.getEntity().getId() : -1;
    }
}
