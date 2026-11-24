package com.utilitysuite.ui;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.ui.holo.AnimStore;
import com.utilitysuite.ui.holo.FrameClock;
import com.utilitysuite.ui.holo.HudHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 6.8: the opt-in "why did it stop?" control status element. */
class ControlStatusHudTest {

    /** A module that only reports a phase. */
    static final class PhaseModule implements IModule {
        final String id;
        String phase;

        PhaseModule(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ModuleCategory category() {
            return ModuleCategory.TRAVEL;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void setEnabled(boolean enabled) {
        }

        @Override
        public void onTick() {
        }

        @Override
        public String phase() {
            return phase;
        }
    }

    private ActionArbiter arbiter;
    private PhaseModule movement;
    private PhaseModule combat;
    private PhaseModule travel;
    private ControlStatusHud hud;
    private HudHost host;
    private RecordingCanvas canvas;

    @BeforeEach
    void setUp() {
        FrameClock.resetForTests();
        arbiter = new ActionArbiter();
        arbiter.bind(null, null, null);
        movement = new PhaseModule(ControlStatusHud.MOVEMENT_ID);
        combat = new PhaseModule(ControlStatusHud.COMBAT_ID);
        travel = new PhaseModule(ControlStatusHud.TRAVEL_ID);
        hud = new ControlStatusHud(arbiter, movement, combat, travel);
        hud.enabled = true;
        host = new HudHost(new AnimStore(64));
        host.sampleClock = false;
        host.register(hud);
        canvas = new RecordingCanvas();
    }

    @AfterEach
    void tearDown() {
        FrameClock.resetForTests();
    }

    private ControlLease grant(String module, ControlPriority p, ControlChannel first, ControlChannel... rest) {
        return arbiter.request(LeaseRequest.of(module, p, null, first, rest)).orElseThrow();
    }

    private long nanos = 1_000_000_000L;

    /** A few frames 50 ms apart, so the host's appear fade has finished; the canvas keeps only the last one. */
    private void frames(boolean clean) {
        for (int i = 0; i < 5; i++) {
            nanos += 50_000_000L;
            FrameClock.sampleAt(nanos);
            canvas.reset();
            host.frame(canvas, clean);
        }
    }

    @Test
    void offByDefault() {
        assertFalse(new ControlStatusHud(arbiter, movement, combat, travel).wantsDraw());
        grant("travel_autopilot", ControlPriority.TRAVEL, ControlChannel.MOVEMENT);
        hud.enabled = false;
        assertFalse(hud.wantsDraw());
    }

    @Test
    void idleDrawsNothing() {
        assertFalse(hud.wantsDraw());
        frames(false);
        assertEquals(0, canvas.calls);
    }

    @Test
    void showsLeaseHoldersAndPhases() {
        grant("combat_assistant", ControlPriority.COMBAT, ControlChannel.MOVEMENT, ControlChannel.CAMERA);
        grant("goal", ControlPriority.GOAL, ControlChannel.HOTBAR);
        combat.phase = "ENGAGED";
        assertTrue(hud.wantsDraw());
        assertEquals(2, hud.leaseCount());
        frames(false);
        assertTrue(canvas.textRuns > 0);
        assertEquals(1, canvas.frames);
    }

    @Test
    void aPhaseAloneIsEnough() {
        travel.phase = "ELYTRA_CRUISE";
        assertTrue(hud.wantsDraw());
        assertEquals(0, hud.leaseCount());
    }

    @Test
    void cleanScreenHidesIt() {
        grant("combat_assistant", ControlPriority.COMBAT, ControlChannel.MOVEMENT);
        frames(true);
        assertEquals(0, canvas.calls);
    }

    @Test
    void lastStopShowsForTenSecondsThenGoesIdle() {
        ControlLease l = grant("movement_assist", ControlPriority.TRAVEL, ControlChannel.MOVEMENT);
        arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        assertFalse(l.isActive());
        assertTrue(hud.wantsDraw());
        assertTrue(hud.showsStop());
        assertEquals("manual override", hud.stopReason());
        assertEquals(ActionArbiter.BY_MANUAL, hud.stopBy());
        frames(false);
        assertTrue(canvas.textRuns > 0);

        for (int i = 0; i < ControlStatusHud.SHOW_STOP_TICKS; i++) {
            arbiter.tick();
        }
        assertTrue(hud.wantsDraw(), "still inside the window");
        arbiter.tick();
        assertFalse(hud.wantsDraw(), "idle again after the window");
    }

    @Test
    void preemptionNamesTheWinner() {
        grant("travel_autopilot", ControlPriority.TRAVEL, ControlChannel.MOVEMENT);
        grant("combat_assistant", ControlPriority.COMBAT, ControlChannel.MOVEMENT);
        assertTrue(hud.wantsDraw());
        assertEquals("preempted", hud.stopReason());
        assertEquals("combat_assistant", hud.stopBy());
    }

    @Test
    void ownReleaseHidesTheBy() {
        arbiter.request(LeaseRequest.of("travel_autopilot", ControlPriority.TRAVEL, null, ControlChannel.MOVEMENT)
                .withTimeout(1));
        arbiter.tick();
        assertTrue(hud.wantsDraw());
        assertEquals("timed out", hud.stopReason());
    }

    @Test
    void channelLabelsArePrebuilt() {
        int mc = (1 << ControlChannel.MOVEMENT.ordinal()) | (1 << ControlChannel.CAMERA.ordinal());
        assertEquals("move+cam", ControlStatusHud.channelLabel(mc));
        assertSame(ControlStatusHud.channelLabel(mc), ControlStatusHud.channelLabel(mc));
        assertEquals("-", ControlStatusHud.channelLabel(0));
        assertEquals("move+cam+hotbar+use", ControlStatusHud.channelLabel(15));
    }

    @Test
    void stopAllUsesTheDangerToken() {
        grant("travel_autopilot", ControlPriority.TRAVEL, ControlChannel.MOVEMENT);
        arbiter.stopAll("test");
        assertTrue(hud.wantsDraw());
        assertEquals("stop-all", hud.stopReason());
        frames(false);
        assertEquals(HoloToken.TEXT_MUTED, canvas.lastToken, "the row ends with the 's ago' caption");
        assertTrue(canvas.textRuns >= 5);
    }

    @Test
    void frameAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        grant("combat_assistant", ControlPriority.COMBAT, ControlChannel.MOVEMENT, ControlChannel.CAMERA);
        grant("goal", ControlPriority.GOAL, ControlChannel.HOTBAR);
        arbiter.request(LeaseRequest.of("goal2", ControlPriority.GOAL, null, ControlChannel.HOTBAR)); // a denial
        movement.phase = "TAKEOFF";
        combat.phase = "ENGAGED";
        travel.phase = "ELYTRA_CRUISE";
        long[] nanos = {1_000_000_000L};
        long bytes = AllocationProbe.measure(5_000, () -> {
            nanos[0] += 16_000_000L;
            FrameClock.sampleAt(nanos[0]);
            arbiter.tick();
            canvas.reset();
            host.frame(canvas, false);
        });
        assertEquals(0L, bytes, "control status frame allocated " + bytes + " bytes");
    }
}
