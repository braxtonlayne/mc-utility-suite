package com.utilitysuite.tools.packetlab;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteEvent;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketType;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;

/**
 * PacketLab diagnostics: a read-only packet inspector (Netty handlers on the client connection feeding a primitive ring
 * buffer and per-second rates) plus a unified timeline of chunk loads, server position corrections, lease grants/ends,
 * job changes and Stop-All. Disabled means the handlers are removed from the pipeline.
 * <p>
 * Nothing here touches input. Only packet types and sizes are recorded (no payloads), so chat, authentication and
 * identifying content are never stored. The timeline subscribes to existing {@link SuiteEvent}s; it never posts
 * {@code PositionCorrection} itself.
 */
public final class PacketLab implements IModule, IConfigurable {

    public static final String ID = "packet_lab";
    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/packetlab");

    /** Packet rate channels (written by the Netty thread). */
    public static final int RATE_IN_COUNT = 0;
    public static final int RATE_OUT_COUNT = 1;
    public static final int RATE_IN_BYTES = 2;
    public static final int RATE_OUT_BYTES = 3;
    /** Event rate channels (written by the client thread). */
    public static final int EV_CHUNKS = 0;
    public static final int EV_CORRECTIONS = 1;
    public static final int EV_LEASE_GRANTS = 2;
    public static final int EV_LEASE_ENDS = 3;
    public static final int EV_JOBS = 4;

    public static final int WINDOW_SECONDS = 300;
    private static final int RING_CAPACITY = 8192;

    private static volatile PacketLab activeInstance;

    private final PacketRingBuffer ring = new PacketRingBuffer(RING_CAPACITY);
    private final PacketTypeTable types = new PacketTypeTable(PacketLab::typeName);
    private final RateRing packetRates = new RateRing(WINDOW_SECONDS, 4);
    private final RateRing eventRates = new RateRing(WINDOW_SECONDS, 5);
    private final TimelineEvents events = new TimelineEvents(512);
    private final CorrectionBursts bursts = new CorrectionBursts(3, 5000L);
    /** One radar, shared: fed while Packet Lab is on or while World Radar asks for acoustic pings (see {@link #setAcousticDemand}). */
    private static final AcousticRadar SHARED_RADAR = new AcousticRadar();
    private static volatile boolean acousticDemand;
    private final AcousticRadar acousticRadar = SHARED_RADAR;
    private final ServerPhysicsMonitor physicsMonitor = new ServerPhysicsMonitor();
    private final PacketProbes.Probe probe = new PacketProbes.Probe(ring, types, packetRates);
    private final PacketProbes.Frame frameHandler = new PacketProbes.Frame(probe);
    private final PacketProbes.Typed typedHandler = new PacketProbes.Typed(probe);

    private boolean enabled;
    private long sessionStartMillis;
    private long lastTickNs;
    private int rttSampleCooldown;

    private static String typeName(Object type) {
        if (type instanceof PacketType<?> pt) {
            return pt.id().getPath() + (pt.flow().name().startsWith("C") ? " (S→C)" : " (C→S)");
        }
        return String.valueOf(type);
    }

    /** Subscribes the timeline to suite events (once, at bootstrap). */
    public void subscribe(ISuiteEventBus bus) {
        bus.subscribe(SuiteEvent.PositionCorrection.class, this::onCorrection);
        bus.subscribe(SuiteEvent.LeaseGranted.class, e -> {
            if (enabled) {
                eventRates.add(e.timeMillis() / 1000L, EV_LEASE_GRANTS, 1);
                events.add(e.timeMillis(), TimelineEvents.Kind.LEASE_GRANTED,
                        "Lease #" + e.leaseId() + " granted: " + e.moduleId() + " (" + e.priority() + ")");
            }
        });
        bus.subscribe(SuiteEvent.LeaseEnded.class, e -> {
            if (enabled) {
                eventRates.add(e.timeMillis() / 1000L, EV_LEASE_ENDS, 1);
                events.add(e.timeMillis(), TimelineEvents.Kind.LEASE_ENDED, "Lease #" + e.leaseId() + " ended: "
                        + e.moduleId() + " " + e.reason() + (e.byModuleId() == null ? "" : " by " + e.byModuleId()));
            }
        });
        bus.subscribe(SuiteEvent.JobChanged.class, e -> {
            if (enabled && e.snapshot() != null) {
                eventRates.add(e.timeMillis() / 1000L, EV_JOBS, 1);
                events.add(e.timeMillis(), TimelineEvents.Kind.JOB_CHANGED,
                        "Job #" + e.snapshot().jobId() + " " + e.snapshot().status() + ": " + e.snapshot().goalLabel());
            }
        });
        bus.subscribe(SuiteEvent.StopAll.class, e -> {
            if (enabled) {
                events.add(e.timeMillis(), TimelineEvents.Kind.STOP_ALL, "Stop-All: " + e.reason());
            }
        });
        bus.subscribe(SuiteEvent.Diagnostic.class, e -> {
            if (enabled) {
                events.add(e.timeMillis(), TimelineEvents.Kind.DIAGNOSTIC, e.source() + ": " + e.message());
            }
        });
    }

    private void onCorrection(SuiteEvent.PositionCorrection e) {
        if (!enabled) {
            return;
        }
        eventRates.add(e.timeMillis() / 1000L, EV_CORRECTIONS, 1);
        physicsMonitor.recordSetback(e.timeMillis());
        boolean repeated = bursts.record(e.timeMillis());
        events.add(e.timeMillis(), TimelineEvents.Kind.CORRECTION, String.format(Locale.ROOT,
                "Position corrected by %.2f, %.2f, %.2f%s", e.dx(), e.dy(), e.dz(), repeated ? "  [REPEATED]" : ""));
    }

    /** Game bus. Client-side chunk loads feed the timeline (client levels only). */
    public void onChunkLoad(ChunkEvent.Load event) {
        if (enabled && event.getLevel() != null && event.getLevel().isClientSide()) {
            eventRates.add(System.currentTimeMillis() / 1000L, EV_CHUNKS, 1);
        }
    }

    /** Game bus. */
    public void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
        if (enabled) {
            install(event.getConnection());
        }
    }

    // ---- pipeline ---------------------------------------------------------------------------------------------------

    static Connection currentConnection() {
        ClientPacketListener l = Minecraft.getInstance().getConnection();
        return l == null ? null : l.getConnection();
    }

    private void install(Connection connection) {
        if (connection == null) {
            return;
        }
        Channel ch = connection.channel();
        if (ch == null) {
            return;
        }
        sessionStartMillis = System.currentTimeMillis();
        ch.eventLoop().execute(() -> {
            ChannelPipeline p = ch.pipeline();
            try {
                probe.pendingInbound = PacketRingBuffer.UNKNOWN_SIZE;
                if (p.get(PacketProbes.TYPED_NAME) == null && p.get("packet_handler") != null) {
                    p.addBefore("packet_handler", PacketProbes.TYPED_NAME, typedHandler);
                }
                if (p.get(PacketProbes.FRAME_NAME) == null && p.get("decoder") != null) {
                    p.addBefore("decoder", PacketProbes.FRAME_NAME, frameHandler);
                }
            } catch (RuntimeException e) {
                LOG.warn("Could not install the packet inspector: {}", e.toString());
            }
        });
    }

    private void uninstall(Connection connection) {
        if (connection == null || connection.channel() == null) {
            return;
        }
        Channel ch = connection.channel();
        ch.eventLoop().execute(() -> {
            ChannelPipeline p = ch.pipeline();
            try {
                if (p.get(PacketProbes.TYPED_NAME) != null) {
                    p.remove(PacketProbes.TYPED_NAME);
                }
                if (p.get(PacketProbes.FRAME_NAME) != null) {
                    p.remove(PacketProbes.FRAME_NAME);
                }
            } catch (RuntimeException e) {
                LOG.warn("Could not remove the packet inspector: {}", e.toString());
            }
        });
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

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
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;
        Connection c = currentConnection();
        if (enabled) {
            activeInstance = this;
            ServerPhysicsMonitor.setCurrent(physicsMonitor);
            lastTickNs = 0L;
            install(c);
        } else {
            if (activeInstance == this) {
                activeInstance = null;
            }
            ServerPhysicsMonitor.setCurrent(null);
            lastTickNs = 0L;
            uninstall(c);
        }
    }

    @Override
    public void onTick() {
        long nowNs = System.nanoTime();
        if (lastTickNs != 0L) {
            physicsMonitor.recordTickIntervalNs(nowNs - lastTickNs);
        }
        lastTickNs = nowNs;
        if (++rttSampleCooldown >= 20) {
            rttSampleCooldown = 0;
            Minecraft mc = Minecraft.getInstance();
            ClientPacketListener conn = mc.getConnection();
            if (conn != null && mc.player != null) {
                PlayerInfo info = conn.getPlayerInfo(mc.player.getUUID());
                if (info != null && info.getLatency() > 0) {
                    physicsMonitor.recordRttMs(info.getLatency());
                }
            }
        }
    }

    @Override
    public void onWorldUnload() {
        bursts.clear();
        physicsMonitor.clear();
        acousticRadar.clear();
        lastTickNs = 0L;
    }

    // ---- read API (timeline screen, client thread) ------------------------------------------------------------------

    public PacketRingBuffer ring() {
        return ring;
    }

    public PacketTypeTable types() {
        return types;
    }

    public RateRing packetRates() {
        return packetRates;
    }

    public RateRing eventRates() {
        return eventRates;
    }

    public TimelineEvents events() {
        return events;
    }

    public CorrectionBursts bursts() {
        return bursts;
    }

    /**
     * The acoustic radar while it should be fed: Packet Lab is on, or World Radar has acoustic pings, ripples or edge
     * cues on ({@link #setAcousticDemand}). {@code null} otherwise, so the sound mixin stays a cheap no-op.
     */
    public static AcousticRadar acousticRadar() {
        return activeInstance != null || acousticDemand ? SHARED_RADAR : null;
    }

    /**
     * World Radar's acoustic display wants pings without the user having to switch Packet Lab on too. Turning the
     * demand off (with Packet Lab also off) clears old pings so nothing stale shows when it comes back.
     */
    public static void setAcousticDemand(boolean on) {
        if (acousticDemand == on) {
            return;
        }
        acousticDemand = on;
        if (!on && activeInstance == null) {
            SHARED_RADAR.clear();
        }
    }

    public AcousticRadar radar() {
        return acousticRadar;
    }

    public ServerPhysicsMonitor physicsMonitor() {
        return physicsMonitor;
    }

    public long sessionStartMillis() {
        return sessionStartMillis;
    }

    /** Clears the timeline (the ring is cleared on the writer thread to keep a single writer). */
    public void clearTimeline() {
        events.clear();
        eventRates.clear();
        bursts.clear();
        physicsMonitor.clear();
        acousticRadar.clear();
        Connection c = currentConnection();
        if (c != null && c.channel() != null) {
            c.channel().eventLoop().execute(() -> {
                ring.clear();
                packetRates.clear();
            });
        } else {
            ring.clear();
            packetRates.clear();
        }
    }

    // ---- settings ---------------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Packet Lab (packet inspector + timeline)";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of();
    }

    @Override
    public void onSettingChanged(String key) {
    }
}
