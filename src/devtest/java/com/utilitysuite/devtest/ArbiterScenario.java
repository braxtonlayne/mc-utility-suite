package com.utilitysuite.devtest;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;

/** Scenario 3: a test module leases MOVEMENT, holds FORWARD/JUMP; Stop-All, Resume and manual override. */
final class ArbiterScenario {

    static final String OWNER = "playtest_mover";

    private ArbiterScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("3-arbiter", "Arbiter, Stop-All, Resume, manual override");
        IActionArbiter arb = Services.arbiter();
        ControlLease[] lease = new ControlLease[1];
        LeaseEndReason[] ended = new LeaseEndReason[1];
        double[][] start = new double[1][];
        LeaseListener listener = new LeaseListener() {
            @Override
            public void onPreempted(ControlLease l, LeaseEndReason reason, String by) {
                ended[0] = reason;
            }
        };

        Arena.ensure(s); // flat deck: movement checks must not depend on the seed's terrain
        s.cmd(() -> Arena.tp(0.5, -6.5, 0f));
        s.waitTicks(5);
        s.run("lease + hold FORWARD/JUMP", () -> {
            Optional<ControlLease> l = arb.request(LeaseRequest.of(OWNER, ControlPriority.GOAL, listener,
                    ControlChannel.MOVEMENT));
            s.check("lease granted", l.isPresent(), "holder=" + arb.holder(ControlChannel.MOVEMENT).map(
                    ControlLease::moduleId).orElse("none"));
            lease[0] = l.orElseThrow();
            start[0] = H.pos();
            boolean f = Services.input().setKeyHeld(lease[0], SimKey.FORWARD, true);
            boolean j = Services.input().setKeyHeld(lease[0], SimKey.JUMP, true);
            s.check("setKeyHeld accepted", f && j, "forward=" + f + " jump=" + j);
        });
        s.waitTicks(20);
        s.run("keys down + player moves", () -> {
            var o = H.mc().options;
            s.check("vanilla keyUp/keyJump are down", o.keyUp.isDown() && o.keyJump.isDown(),
                    "keyUp=" + o.keyUp.isDown() + " keyJump=" + o.keyJump.isDown());
            double d = H.dist(start[0], H.pos());
            s.check("player moved while FORWARD held", d > 1.0, Harness.fmt("moved %.2f blocks to %s", d,
                    H.posStr()));
        });
        s.run("Stop-All via Backspace key", () -> {
            H.physicalKey(GLFW.GLFW_KEY_BACKSPACE, true);
            H.physicalKey(GLFW.GLFW_KEY_BACKSPACE, false);
        });
        s.waitUpTo("stopped", 5, arb::isAutomationStopped);
        s.run("after Stop-All", () -> {
            boolean viaKey = arb.isAutomationStopped();
            s.check("Backspace (Stop-All key) stops automation", viaKey, "stopped=" + viaKey);
            if (!viaKey) {
                arb.stopAll("playtest fallback");
            }
        });
        s.waitTicks(2);
        s.run("Stop-All released everything", () -> {
            var o = H.mc().options;
            boolean anyDown = o.keyUp.isDown() || o.keyJump.isDown() || o.keyDown.isDown() || o.keyLeft.isDown()
                    || o.keyRight.isDown() || o.keyShift.isDown() || o.keySprint.isDown() || o.keyAttack.isDown()
                    || o.keyUse.isDown();
            s.check("every KeyMapping released", !anyDown, "keyUp=" + o.keyUp.isDown() + " keyJump="
                    + o.keyJump.isDown() + " any=" + anyDown);
            s.check("lease revoked with STOP_ALL", !lease[0].isActive() && ended[0] == LeaseEndReason.STOP_ALL,
                    "active=" + lease[0].isActive() + " reason=" + ended[0]);
            boolean anyHolder = false;
            for (ControlChannel c : ControlChannel.all()) {
                anyHolder |= arb.holder(c).isPresent();
            }
            s.check("no channel holders", !anyHolder, "anyHolder=" + anyHolder);
            Optional<ControlLease> refused = arb.request(LeaseRequest.of(OWNER, ControlPriority.GOAL, listener,
                    ControlChannel.MOVEMENT));
            s.check("new lease refused while stopped", refused.isEmpty(), "granted=" + refused.isPresent());
            refused.ifPresent(ControlLease::close);
            start[0] = H.pos();
        });
        s.waitTicks(10);
        s.run("player stopped", () -> {
            double d = H.dist(start[0], H.pos());
            s.check("player stops moving after Stop-All", d < 0.6, Harness.fmt("drifted %.2f", d));
        });
        s.run("Resume", () -> {
            arb.resumeAutomation();
            ended[0] = null;
            Optional<ControlLease> l = arb.request(LeaseRequest.of(OWNER, ControlPriority.GOAL, listener,
                    ControlChannel.MOVEMENT));
            s.check("Resume: lease granted again", l.isPresent(), "granted=" + l.isPresent());
            lease[0] = l.orElseThrow();
            Services.input().setKeyHeld(lease[0], SimKey.FORWARD, true);
            start[0] = H.pos();
        });
        s.waitTicks(10);
        s.run("moving again", () -> {
            double d = H.dist(start[0], H.pos());
            s.check("Resume: moves again", d > 0.5 && H.mc().options.keyUp.isDown(), Harness.fmt("moved %.2f", d));
        });
        s.run("simulate physical W press", () -> {
            H.physicalKey(GLFW.GLFW_KEY_W, true);
        });
        s.waitTicks(2);
        s.run("release physical W", () -> H.physicalKey(GLFW.GLFW_KEY_W, false));
        s.waitTicks(1);
        s.run("manual override", () -> {
            s.check("physical W press ends the lease (MANUAL_OVERRIDE)",
                    !lease[0].isActive() && ended[0] == LeaseEndReason.MANUAL_OVERRIDE,
                    "active=" + lease[0].isActive() + " reason=" + ended[0]);
            s.check("override pause active", arb.isManualOverrideActive(ControlChannel.MOVEMENT),
                    "pause=" + arb.isManualOverrideActive(ControlChannel.MOVEMENT));
            Optional<ControlLease> l = arb.request(LeaseRequest.of(OWNER, ControlPriority.GOAL, listener,
                    ControlChannel.MOVEMENT));
            s.check("non-emergency lease refused during the pause", l.isEmpty(), "granted=" + l.isPresent());
            l.ifPresent(ControlLease::close);
            s.check("simulated FORWARD released after override", !Services.input().isKeyHeldByAutomation(
                    SimKey.FORWARD), "heldByAutomation=" + Services.input().isKeyHeldByAutomation(SimKey.FORWARD));
        });
        s.waitUpTo("pause ends", 200, () -> !arb.isManualOverrideActive(ControlChannel.MOVEMENT));
        s.run("pause ended", () -> s.check("override pause expires", !arb.isManualOverrideActive(
                ControlChannel.MOVEMENT), "pause=" + arb.isManualOverrideActive(ControlChannel.MOVEMENT)));
        s.always("cleanup", () -> {
            if (lease[0] != null) {
                lease[0].close();
            }
            if (arb.isAutomationStopped()) {
                arb.resumeAutomation();
            }
        });
        return s;
    }
}
