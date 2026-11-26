package com.utilitysuite.devtest;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Small helpers shared by the scenarios. Test scaffolding: may touch vanilla state directly. */
final class H {

    private H() {
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static LocalPlayer p() {
        return mc().player;
    }

    static double dist(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static double[] pos() {
        LocalPlayer p = p();
        return new double[] {p.getX(), p.getY(), p.getZ()};
    }

    static String posStr() {
        LocalPlayer p = p();
        return Harness.fmt("(%.2f, %.2f, %.2f)", p.getX(), p.getY(), p.getZ());
    }

    static Item item(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    static int count(String id) {
        Item it = item(id);
        int n = 0;
        var inv = p().getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(it)) {
                n += s.getCount();
            }
        }
        return n;
    }

    static BlockState block(int x, int y, int z) {
        return mc().level.getBlockState(new BlockPos(x, y, z));
    }

    static String blockId(int x, int y, int z) {
        return BuiltInRegistries.BLOCK.getKey(block(x, y, z).getBlock()).toString();
    }

    @SuppressWarnings("unchecked")
    static <T extends IModule> T module(String id) {
        return (T) Services.modules().byId(id).orElseThrow(() -> new IllegalStateException("no module " + id));
    }

    static void enable(String id, boolean on) {
        CoreBootstrap.modules().setModuleEnabled(id, on, Services.modState().profile());
    }

    /** Sets a module setting in the active profile and notifies the module, like the menu does. */
    static void setting(String moduleId, String key, Object value) {
        var profile = Services.modState().profile();
        if (value instanceof Boolean b) {
            profile.set(moduleId, key, b);
        } else if (value instanceof Number n) {
            profile.set(moduleId, key, n);
        } else {
            profile.set(moduleId, key, String.valueOf(value));
        }
        IModule m = module(moduleId);
        if (m instanceof com.utilitysuite.core.api.IConfigurable c) {
            c.onSettingChanged(key);
        }
        m.onProfileLoaded(profile);
    }

    /** Simulates a physical key press+release through the real GLFW callback path (fires InputEvent.Key). */
    static void physicalKey(int glfwKey, boolean press) {
        Minecraft mc = mc();
        int scan = GLFW.glfwGetKeyScancode(glfwKey);
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), glfwKey, scan,
                press ? GLFW.GLFW_PRESS : GLFW.GLFW_RELEASE, 0);
    }

    static List<AbstractWidget> widgets(Screen s) {
        List<AbstractWidget> out = new ArrayList<>();
        for (GuiEventListener l : s.children()) {
            if (l instanceof AbstractWidget w) {
                out.add(w);
            }
        }
        return out;
    }

    static Button buttonByText(Screen s, String text) {
        for (AbstractWidget w : widgets(s)) {
            if (w instanceof Button b && b.getMessage().getString().equals(text)) {
                return b;
            }
        }
        return null;
    }

    static int floorX() {
        return net.minecraft.util.Mth.floor(p().getX());
    }

    static int floorY() {
        return net.minecraft.util.Mth.floor(p().getY());
    }

    static int floorZ() {
        return net.minecraft.util.Mth.floor(p().getZ());
    }

    /** Diagnostic: "name=value" of the given (private) fields of {@code o}, read reflectively. */
    static String fields(Object o, String... names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            try {
                java.lang.reflect.Field f = o.getClass().getDeclaredField(n);
                f.setAccessible(true);
                sb.append(n).append('=').append(f.get(o)).append(' ');
            } catch (ReflectiveOperationException | RuntimeException e) {
                sb.append(n).append("=? ");
            }
        }
        return sb.toString().trim();
    }

    /** Diagnostic: arbiter state (stopped, holders, manual-override pauses) and the open screen. */
    static String arbiterState() {
        var arb = com.utilitysuite.core.api.Services.arbiter();
        StringBuilder sb = new StringBuilder("stopped=" + arb.isAutomationStopped());
        for (var c : com.utilitysuite.core.api.ControlChannel.all()) {
            sb.append(' ').append(c).append('=').append(arb.holder(c).map(l -> l.moduleId()).orElse("-"));
            if (arb.isManualOverrideActive(c)) {
                sb.append("(override)");
            }
        }
        sb.append(" screen=").append(mc().screen == null ? "-" : mc().screen.getClass().getSimpleName());
        return sb.toString();
    }
}
