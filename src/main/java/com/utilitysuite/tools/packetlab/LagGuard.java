package com.utilitysuite.tools.packetlab;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.IServerHealth;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;

import java.util.List;

/**
 * Lag Guard: publishes the {@link IServerHealth} signal ({@link Services#serverHealth()}) from the time since the last
 * server time packet (tracked by a small mixin on the packet handler), the TPS those packets imply, and the client tick
 * jitter of {@link ServerPhysicsMonitor}. While it says pause, high-risk drivers (elytra cruise, bridging, parkour
 * reflexes) hold a safe state; they resume only after the configured number of stable ticks. Off by default (then
 * the signal is always healthy). Allocation free per tick; client thread only (the packet hook runs on the client
 * thread after vanilla's own thread hop).
 */
public final class LagGuard implements IModule, IConfigurable, IServerHealth {

    public static final String ID = "lag_guard";
    public static final String K_STALL_MS = "stall_ms";
    public static final String K_MIN_TPS = "min_tps";
    public static final String K_MAX_JITTER = "max_jitter_ms";
    public static final String K_STABLE_TICKS = "stable_ticks";

    private static volatile LagGuard active;

    private final ServerStallDetector detector = new ServerStallDetector();
    private boolean enabled;
    private int stallMs = (int) ServerStallDetector.DEFAULT_STALL_MS;
    private double minTps = ServerStallDetector.DEFAULT_MIN_TPS;
    private double maxJitterMs = ServerStallDetector.DEFAULT_MAX_JITTER_MS;
    private int stableTicks = ServerStallDetector.DEFAULT_STABLE_TICKS;

    public LagGuard() {
        active = this;
        apply();
    }

    /** Packet hook (mixin): a server time packet with the server's game time arrived. */
    public static void onServerTimePacket(long gameTime) {
        LagGuard g = active;
        if (g != null && g.enabled) {
            g.detector.onServerTime(System.nanoTime(), gameTime);
        }
    }

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
        this.enabled = enabled;
        detector.reset();
    }

    @Override
    public void onTick() {
        ServerPhysicsMonitor m = ServerPhysicsMonitor.current();
        detector.tick(System.nanoTime(), m == null ? 0.0 : m.tickJitterMs());
    }

    @Override
    public void onWorldUnload() {
        detector.reset();
    }

    @Override
    public String phase() {
        if (!enabled || !detector.paused()) {
            return null;
        }
        return switch (detector.reason()) { // constant strings: no allocation per frame
            case "stall" -> "paused: server stall";
            case "low tps" -> "paused: low TPS";
            case "jitter" -> "paused: tick jitter";
            default -> "paused: recovering";
        };
    }

    // ---- IServerHealth ---------------------------------------------------------------------------------------------

    @Override
    public boolean pauseHighRisk() {
        return enabled && detector.paused();
    }

    @Override
    public double tps() {
        return detector.tps();
    }

    @Override
    public long msSinceServerTick() {
        return detector.msSinceServerTick();
    }

    public ServerStallDetector detector() {
        return detector;
    }

    // ---- settings --------------------------------------------------------------------------------------------------

    private void apply() {
        detector.stallMs = stallMs;
        detector.minTps = minTps;
        detector.maxJitterMs = maxJitterMs;
        detector.stableTicks = stableTicks;
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        stallMs = p.getInt(ID, K_STALL_MS, (int) ServerStallDetector.DEFAULT_STALL_MS);
        minTps = p.getDouble(ID, K_MIN_TPS, ServerStallDetector.DEFAULT_MIN_TPS);
        maxJitterMs = p.getDouble(ID, K_MAX_JITTER, ServerStallDetector.DEFAULT_MAX_JITTER_MS);
        stableTicks = p.getInt(ID, K_STABLE_TICKS, ServerStallDetector.DEFAULT_STABLE_TICKS);
        apply();
    }

    @Override
    public String displayName() {
        return "Lag Guard";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer(K_STALL_MS, "Stall after (ms)", (int) ServerStallDetector.DEFAULT_STALL_MS, 1200,
                        10_000, "", "Pause risky automation when the server sends no tick for this long."),
                SettingSpec.decimal(K_MIN_TPS, "Minimum TPS", ServerStallDetector.DEFAULT_MIN_TPS, 5.0, 19.5, "",
                        "Pause risky automation while the server runs slower than this."),
                SettingSpec.decimal(K_MAX_JITTER, "Client tick jitter limit (ms)",
                        ServerStallDetector.DEFAULT_MAX_JITTER_MS, 10.0, 500.0, "",
                        "Pause when the client's own tick cadence jitters more than this (needs Packet Lab on)."),
                SettingSpec.integer(K_STABLE_TICKS, "Resume after stable ticks", ServerStallDetector.DEFAULT_STABLE_TICKS,
                        5, 200, "", "Healthy ticks in a row before elytra, bridging and parkour resume."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
