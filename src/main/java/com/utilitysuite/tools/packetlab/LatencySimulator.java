package com.utilitysuite.tools.packetlab;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * Local test latency simulator (PacketLab). Off by default, clearly labeled, and only allowed in single-player or on a
 * server whose address is in the allow list (the user's own test server). It switches itself off on disconnect, so it
 * never carries over into another world or server.
 */
public final class LatencySimulator implements IModule, IConfigurable {

    public static final String ID = "latency_simulator";
    public static final String K_LATENCY = "latency_ms";
    public static final String K_JITTER = "jitter_ms";
    public static final String K_ALLOWED = "allowed_servers";

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/latency");

    private boolean enabled;
    private int latencyMs = 150;
    private int jitterMs = 30;
    private String allowed = "localhost,127.0.0.1";
    private LatencyHandler handler;
    private String lastStatus = "Off";

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
        if (enabled == this.enabled) {
            return;
        }
        if (enabled) {
            String why = refusal();
            if (why != null) {
                lastStatus = why;
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal("Latency simulator: " + why), true);
                }
                return;
            }
            this.enabled = true;
            install();
        } else {
            this.enabled = false;
            remove();
        }
    }

    /** Null when allowed here, else the reason. */
    public String refusal() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return "join a single-player world or your test server first";
        }
        if (mc.hasSingleplayerServer()) {
            return null;
        }
        ServerData server = mc.getCurrentServer();
        String ip = server == null ? "" : server.ip.toLowerCase(Locale.ROOT).trim();
        return isAllowedServer(ip, allowed) ? null
                : "only for single-player or servers in the allow list (" + allowed + ")";
    }

    /** Pure: whether {@code address} (host or host:port) matches an allow-list entry ("host" or "host:port"). */
    public static boolean isAllowedServer(String address, String allowList) {
        return com.utilitysuite.core.ServerAllowList.isAllowedServer(address, allowList);
    }

    private void install() {
        Connection c = PacketLab.currentConnection();
        if (c == null || c.channel() == null) {
            return;
        }
        Channel ch = c.channel();
        LatencyHandler h = new LatencyHandler(latencyMs, jitterMs);
        handler = h;
        lastStatus = "On: " + latencyMs + " ms ± " + jitterMs + " ms";
        ch.eventLoop().execute(() -> {
            ChannelPipeline p = ch.pipeline();
            try {
                if (p.get(LatencyHandler.NAME) != null) {
                    p.remove(LatencyHandler.NAME);
                }
                if (p.get(PacketProbes.TYPED_NAME) != null) {
                    p.addBefore(PacketProbes.TYPED_NAME, LatencyHandler.NAME, h);
                } else if (p.get("packet_handler") != null) {
                    p.addBefore("packet_handler", LatencyHandler.NAME, h);
                }
            } catch (RuntimeException e) {
                LOG.warn("Could not install the latency simulator: {}", e.toString());
            }
        });
    }

    private void remove() {
        LatencyHandler h = handler;
        handler = null;
        lastStatus = "Off";
        if (h == null) {
            return;
        }
        h.deactivate();
        Connection c = PacketLab.currentConnection();
        if (c == null || c.channel() == null) {
            return;
        }
        Channel ch = c.channel();
        ch.eventLoop().execute(() -> {
            ChannelHandlerContext ctx = ch.pipeline().context(h);
            if (ctx != null) {
                h.removeIfIdle(ctx);
            }
        });
    }

    @Override
    public void onTick() {
    }

    @Override
    public void onStopAll() {
        // Diagnostics, not automation: Stop-All does not change simulated latency.
    }

    /** Auto-off on disconnect (the enabled flag is captured as false before the profile is saved). */
    @Override
    public void onWorldUnload() {
        enabled = false;
        handler = null;
        lastStatus = "Off";
        Services.modState().profile().set(ID, "enabled", false);
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        readSettings(profile);
    }

    public String status() {
        return lastStatus;
    }

    @Override
    public String displayName() {
        return "Latency Simulator (test worlds only)";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer(K_LATENCY, "Added latency (ms)", 150, 0, 2000, "",
                        "Delay added to every packet in each direction."),
                SettingSpec.integer(K_JITTER, "Jitter (ms)", 30, 0, 500, "",
                        "Random variation (±) of the delay. Packet order is always preserved."),
                SettingSpec.text(K_ALLOWED, "Allowed servers", "localhost,127.0.0.1", "",
                        "Server addresses (host or host:port) where the simulator may run. Single-player is always "
                                + "allowed.").asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        readSettings(Services.modState().profile());
        LatencyHandler h = handler;
        if (h != null) {
            h.configure(latencyMs, jitterMs);
            lastStatus = "On: " + latencyMs + " ms ± " + jitterMs + " ms";
        }
    }

    private void readSettings(ProfileConfig p) {
        latencyMs = Math.max(0, Math.min(2000, p.getInt(ID, K_LATENCY, 150)));
        jitterMs = Math.max(0, Math.min(500, p.getInt(ID, K_JITTER, 30)));
        allowed = p.getString(ID, K_ALLOWED, "localhost,127.0.0.1");
    }
}
