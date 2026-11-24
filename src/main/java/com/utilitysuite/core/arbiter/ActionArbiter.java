package com.utilitysuite.core.arbiter;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.IModuleRegistry;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.SuiteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Single-owner arbitration over {@link ControlChannel}s. Pure Java (no Minecraft classes) so it is unit-testable.
 * Client thread only.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Requests are atomic over their channels. A channel is available when it is free, held by a strictly lower
 *       priority (that whole lease is preempted), or held by the requesting module itself.</li>
 *   <li><b>Re-request by the same module:</b> a module owns at most one lease. If it requests again while holding one,
 *       the new request is evaluated as if its own channels were free; when granted, the old lease ends with
 *       {@link LeaseEndReason#RELEASED} (no {@code onPreempted} callback, its simulated keys are released) and is
 *       replaced. When refused, the old lease stays untouched.</li>
 *   <li><b>Resume:</b> a lease that ended by preemption or manual override is remembered. As soon as every channel it
 *       covered is free and not in a manual-override pause, its listener gets {@code onResumable(formerLease)} (most
 *       recently suspended first). A callback may request a new lease right away; later candidates are re-checked
 *       afterwards. Nothing is remembered after Stop-All or a timeout. A granted request clears the module's
 *       remembered leases.</li>
 *   <li><b>Manual override:</b> real user input on a channel ends a lease below {@link ControlPriority#EMERGENCY} on
 *       it ({@link LeaseEndReason#MANUAL_OVERRIDE}, by {@value #BY_MANUAL}) and (re)starts that channel's pause, during
 *       which non-emergency requests for it are refused.</li>
 *   <li><b>Stop-All:</b> revokes everything, releases all simulated input, notifies every module and refuses all
 *       requests until {@link #resumeAutomation()}.</li>
 *   <li><b>Shared grant</b> (one at a time, {@link #beginShared}): MOVEMENT counts as held by the grant's module at its
 *       priority for every other request (refused at or below it, preempting it at a strictly higher priority, an
 *       EMERGENCY request always wins). The user's own MOVEMENT keys do NOT end it (they still end ordinary leases and
 *       start the override pause for others). Stop-All, world unload and a lease that wins MOVEMENT end it through
 *       {@link SharedHolder#onSharedEnded}. Used by {@code core.input.SharedInputOverlay}; not part of
 *       {@link IActionArbiter}.</li>
 *   <li><b>Assist grant</b> (one at a time, {@link #beginAssist}): rides along with the lease of another module that
 *       holds MOVEMENT below EMERGENCY, without holding or blocking anything itself. It is bound to that one lease
 *       and ends with it for the same reason (manual override, preemption, release, timeout, Stop-All, world unload)
 *       through {@link SharedHolder#onSharedEnded}.</li>
 * </ul>
 * The steady-state {@link #tick()} path allocates nothing.
 */
public final class ActionArbiter implements IActionArbiter {

    public static final String BY_MANUAL = "manual";
    public static final String BY_STOP_ALL = "stop_all";
    public static final String BY_TIMEOUT = "timeout";

    /** Remembered (suspended) leases are capped; the oldest is dropped beyond this. */
    private static final int MAX_SUSPENDED = 32;

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/arbiter");
    private static final ControlChannel[] CHANNELS = ControlChannel.all();
    private static final int MOVEMENT = ControlChannel.MOVEMENT.ordinal();

    private final Lease[] holders = new Lease[CHANNELS.length];
    private final int[] pauseTicks = new int[CHANNELS.length];
    /** Leases that lost channels by preemption/manual override, oldest first. */
    private final ArrayList<Lease> suspended = new ArrayList<>();
    /** Scratch for preemption victims (at most one per channel). */
    private final Lease[] victims = new Lease[CHANNELS.length];

    private IInputSmoother input;
    private IModuleRegistry modules;
    private ISuiteEventBus events;
    private LongSupplier clock = System::currentTimeMillis;

    /** The live shared grant (null: none), its module and priority. */
    private SharedHolder shared;
    private String sharedModule;
    private ControlPriority sharedPriority;

    /** The live assist grant (null: none), its module and the MOVEMENT lease it rides along with. */
    private SharedHolder assist;
    private String assistModule;
    private Lease assistLease;

    private int manualOverridePauseTicks = 20;
    private long nextLeaseId = 1;
    private long tickCounter;
    private boolean stopped;
    private boolean inResumeCheck;
    private boolean resumeCheckAgain;

    /** Wires collaborators; any of them may be null (tests, early startup). */
    public void bind(IInputSmoother input, IModuleRegistry modules, ISuiteEventBus events) {
        this.input = input;
        this.modules = modules;
        this.events = events;
    }

    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    /** Ticks during which non-emergency automation is blocked on a channel after manual input (default 20). */
    public void setManualOverridePauseTicks(int ticks) {
        this.manualOverridePauseTicks = Math.max(0, ticks);
    }

    public int manualOverridePauseTicks() {
        return manualOverridePauseTicks;
    }

    // ---- requests ---------------------------------------------------------------------------------------------------

    @Override
    public Optional<ControlLease> request(LeaseRequest request) {
        if (stopped) {
            return Optional.empty();
        }
        String moduleId = request.moduleId();
        ControlPriority priority = request.priority();
        boolean emergency = priority.level() >= ControlPriority.EMERGENCY.level();
        int mask = 0;
        for (ControlChannel c : request.channels()) {
            int i = c.ordinal();
            mask |= 1 << i;
            if (pauseTicks[i] > 0 && !emergency) {
                return Optional.empty();
            }
            Lease h = holders[i];
            if (h != null && !h.moduleId().equals(moduleId) && !priority.outranks(h.priority())) {
                return Optional.empty();
            }
            if (i == MOVEMENT && shared != null && !sharedModule.equals(moduleId)
                    && !emergency && !priority.outranks(sharedPriority)) {
                return Optional.empty();
            }
        }
        if ((mask & (1 << MOVEMENT)) != 0 && shared != null) {
            // a lease that wins MOVEMENT ends the shared grant (the module's own lease replaces its grant)
            endSharedInvoluntary(sharedModule.equals(moduleId) ? LeaseEndReason.RELEASED : LeaseEndReason.PREEMPTED,
                    moduleId);
        }

        // Granted. Replace the module's own lease, wherever it is.
        for (Lease h : holders) {
            if (h != null && h.active && h.moduleId().equals(moduleId)) {
                endLease(h, LeaseEndReason.RELEASED, moduleId);
            }
        }
        // Collect distinct lower-priority victims on the requested channels.
        int victimCount = 0;
        for (int i = 0; i < CHANNELS.length; i++) {
            Lease h = holders[i];
            if ((mask & (1 << i)) == 0 || h == null) {
                continue;
            }
            boolean seen = false;
            for (int v = 0; v < victimCount; v++) {
                if (victims[v] == h) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                victims[victimCount++] = h;
            }
        }
        Lease lease = new Lease(this, nextLeaseId++, moduleId, priority, request.channels(), request.timeoutTicks(),
                request.listener());
        lease.lastTickStamp = tickCounter;
        removeSuspendedOf(moduleId);
        // End victims without callbacks first so the new lease owns the channels before anyone is notified.
        for (int v = 0; v < victimCount; v++) {
            Lease victim = victims[v];
            deactivate(victim, LeaseEndReason.PREEMPTED, moduleId);
        }
        for (int i = 0; i < CHANNELS.length; i++) {
            if ((mask & (1 << i)) != 0) {
                holders[i] = lease;
            }
        }
        post(new SuiteEvent.LeaseGranted(clock.getAsLong(), lease.id(), moduleId, priority));
        for (int v = 0; v < victimCount; v++) {
            Lease victim = victims[v];
            victims[v] = null;
            suspend(victim);
            notifyPreempted(victim, LeaseEndReason.PREEMPTED, moduleId);
        }
        // Channels freed by a replaced/preempted lease outside this request may let older leases resume.
        if (!suspended.isEmpty()) {
            checkResumable();
        }
        return Optional.of(lease);
    }

    @Override
    public void release(ControlLease lease) {
        if (lease instanceof Lease l && l.active) {
            endLease(l, LeaseEndReason.RELEASED, l.moduleId());
            checkResumable();
        }
    }

    @Override
    public Optional<ControlLease> holder(ControlChannel channel) {
        return Optional.ofNullable(holders[channel.ordinal()]);
    }

    /** Allocation-free variant of {@link #holder}: the holder or null. */
    @Override
    public ControlLease holderOrNull(ControlChannel channel) {
        return holders[channel.ordinal()];
    }

    /** Also true for MOVEMENT while {@code moduleId} holds the shared grant. */
    @Override
    public boolean isHeldBy(ControlChannel channel, String moduleId) {
        Lease h = holders[channel.ordinal()];
        if (h != null) {
            return h.moduleId().equals(moduleId);
        }
        return channel.ordinal() == MOVEMENT && shared != null && sharedModule.equals(moduleId);
    }

    // ---- shared grant -----------------------------------------------------------------------------------------------

    /** Owner of the shared grant; told once when the grant ends for any reason other than its own {@link #endShared}. */
    public interface SharedHolder {
        void onSharedEnded(LeaseEndReason reason, String by);
    }

    /**
     * Starts the shared grant on MOVEMENT for {@code holder}. False when automation is stopped, another holder's grant
     * is live, or a lease at or above {@code priority} holds MOVEMENT (the module's own included). A lower lease on
     * MOVEMENT is preempted as by a request (the module's own lower lease is released). The same holder may begin
     * again (its grant is restarted). Not refused by the manual-override pause: the grant exists to share control with
     * the user. Allocation free.
     */
    public boolean beginShared(String moduleId, ControlPriority priority, SharedHolder holder) {
        if (stopped || moduleId == null || priority == null || holder == null) {
            return false;
        }
        if (shared != null && shared != holder) {
            return false;
        }
        Lease h = holders[MOVEMENT];
        if (h != null && !priority.outranks(h.priority())) {
            return false;
        }
        shared = holder;
        sharedModule = moduleId;
        sharedPriority = priority;
        if (h != null) {
            if (h.moduleId().equals(moduleId)) {
                endLease(h, LeaseEndReason.RELEASED, moduleId);
            } else {
                deactivate(h, LeaseEndReason.PREEMPTED, moduleId);
                suspend(h);
                notifyPreempted(h, LeaseEndReason.PREEMPTED, moduleId);
            }
        }
        return true;
    }

    /** The holder ends its own grant (no callback). Idempotent; a stale holder is ignored. */
    public void endShared(SharedHolder holder) {
        if (holder != null && shared == holder) {
            clearShared();
            if (!suspended.isEmpty()) {
                checkResumable();
            }
        }
    }

    /** Module id of the live shared grant, or null. */
    public String sharedModuleId() {
        return shared == null ? null : sharedModule;
    }

    /** Priority of the live shared grant, or null. */
    public ControlPriority sharedPriority() {
        return shared == null ? null : sharedPriority;
    }

    private void endSharedInvoluntary(LeaseEndReason reason, String by) {
        SharedHolder h = shared;
        if (h == null) {
            return;
        }
        clearShared();
        try {
            h.onSharedEnded(reason, by);
        } catch (RuntimeException e) {
            LOG.error("Shared grant holder failed in onSharedEnded", e);
        }
    }

    private void clearShared() {
        shared = null;
        sharedModule = null;
        sharedPriority = null;
    }

    // ---- assist grant -----------------------------------------------------------------------------------------------

    /**
     * Starts the assist grant for {@code holder}: it rides along with the lease that holds MOVEMENT now and changes
     * nothing about who holds what. False when automation is stopped, another holder's assist grant is live, no lease
     * holds MOVEMENT, the lease is the module's own or it is at EMERGENCY or above. True without any change when the
     * same holder's grant already rides along with that lease. Allocation free.
     */
    public boolean beginAssist(String moduleId, SharedHolder holder) {
        if (stopped || moduleId == null || holder == null) {
            return false;
        }
        if (assist != null && assist != holder) {
            return false;
        }
        Lease h = holders[MOVEMENT];
        if (h == null || h.moduleId().equals(moduleId) || !ControlPriority.EMERGENCY.outranks(h.priority())) {
            return false;
        }
        assist = holder;
        assistModule = moduleId;
        assistLease = h;
        return true;
    }

    /** The holder ends its own assist grant (no callback). Idempotent; a stale holder is ignored. */
    public void endAssist(SharedHolder holder) {
        if (holder != null && assist == holder) {
            clearAssist();
        }
    }

    /** Module id of the live assist grant, or null. */
    public String assistModuleId() {
        return assist == null ? null : assistModule;
    }

    private void endAssistInvoluntary(LeaseEndReason reason, String by) {
        SharedHolder h = assist;
        if (h == null) {
            return;
        }
        clearAssist();
        try {
            h.onSharedEnded(reason, by);
        } catch (RuntimeException e) {
            LOG.error("Assist grant holder failed in onSharedEnded", e);
        }
    }

    private void clearAssist() {
        assist = null;
        assistModule = null;
        assistLease = null;
    }

    // ---- manual override --------------------------------------------------------------------------------------------

    @Override
    public void onManualInput(ManualInput input) {
        ControlChannel channel = channelOf(input);
        int i = channel.ordinal();
        if (manualOverridePauseTicks > 0) {
            pauseTicks[i] = manualOverridePauseTicks;
        }
        Lease h = holders[i];
        if (h != null && ControlPriority.EMERGENCY.outranks(h.priority())) {
            LOG.info("Manual input {} preempted {} on {}", input, h.moduleId(), channel);
            deactivate(h, LeaseEndReason.MANUAL_OVERRIDE, BY_MANUAL);
            suspend(h);
            notifyPreempted(h, LeaseEndReason.MANUAL_OVERRIDE, BY_MANUAL);
            checkResumable();
        }
    }

    public static ControlChannel channelOf(ManualInput input) {
        return switch (input) {
            case MOVEMENT_KEY -> ControlChannel.MOVEMENT;
            case MOUSE_LOOK -> ControlChannel.CAMERA;
            case ATTACK_OR_USE -> ControlChannel.WORLD_INTERACT;
            case HOTBAR_SELECT -> ControlChannel.HOTBAR;
        };
    }

    @Override
    public boolean isManualOverrideActive(ControlChannel channel) {
        return pauseTicks[channel.ordinal()] > 0;
    }

    // ---- stop-all ---------------------------------------------------------------------------------------------------

    @Override
    public void stopAll(String reason) {
        stopped = true;
        suspended.clear();
        endSharedInvoluntary(LeaseEndReason.STOP_ALL, BY_STOP_ALL);
        endAssistInvoluntary(LeaseEndReason.STOP_ALL, BY_STOP_ALL);
        for (int i = 0; i < CHANNELS.length; i++) {
            Lease h = holders[i];
            if (h != null) {
                endLease(h, LeaseEndReason.STOP_ALL, BY_STOP_ALL);
            }
        }
        if (input != null) {
            input.releaseAll();
        }
        if (modules != null) {
            List<IModule> all = modules.all();
            for (int i = 0; i < all.size(); i++) {
                IModule m = all.get(i);
                try {
                    m.onStopAll();
                } catch (RuntimeException e) {
                    LOG.error("Module {} failed in onStopAll", m.id(), e);
                }
            }
        }
        post(new SuiteEvent.StopAll(clock.getAsLong(), reason == null ? "" : reason));
        LOG.info("Stop-All: {}", reason);
    }

    @Override
    public boolean isAutomationStopped() {
        return stopped;
    }

    @Override
    public void resumeAutomation() {
        stopped = false;
    }

    /**
     * World/server left: every lease ends ({@link LeaseEndReason#RELEASED}, modules get {@code onWorldUnload} from the
     * registry instead of callbacks), pauses and remembered leases are dropped and the stopped state is cleared, so the
     * next world starts clean. Not part of {@link IActionArbiter}; called by the core.
     */
    public void resetForWorldUnload() {
        suspended.clear();
        endSharedInvoluntary(LeaseEndReason.RELEASED, BY_STOP_ALL);
        endAssistInvoluntary(LeaseEndReason.RELEASED, BY_STOP_ALL);
        for (int i = 0; i < CHANNELS.length; i++) {
            Lease h = holders[i];
            if (h != null) {
                endLease(h, LeaseEndReason.RELEASED, BY_STOP_ALL);
            }
            pauseTicks[i] = 0;
        }
        if (input != null) {
            input.releaseAll();
        }
        stopped = false;
    }

    // ---- tick -------------------------------------------------------------------------------------------------------

    @Override
    public void tick() {
        tickCounter++;
        boolean pauseEnded = false;
        for (int i = 0; i < CHANNELS.length; i++) {
            if (pauseTicks[i] > 0 && --pauseTicks[i] == 0) {
                pauseEnded = true;
            }
        }
        boolean leaseEnded = false;
        for (int i = 0; i < CHANNELS.length; i++) {
            Lease h = holders[i];
            if (h == null || h.timeoutTicks == 0 || h.lastTickStamp == tickCounter) {
                continue;
            }
            h.lastTickStamp = tickCounter;
            if (--h.ticksLeft <= 0) {
                endLease(h, LeaseEndReason.TIMED_OUT, BY_TIMEOUT);
                leaseEnded = true;
            }
        }
        if ((pauseEnded || leaseEnded) && !suspended.isEmpty()) {
            checkResumable();
        }
    }

    /** Number of remembered (resumable) leases, for diagnostics and tests. */
    public int suspendedCount() {
        return suspended.size();
    }

    // ---- internals --------------------------------------------------------------------------------------------------

    /** Ends a lease and notifies its listener when the end was involuntary (not after RELEASED). */
    private void endLease(Lease lease, LeaseEndReason reason, String by) {
        if (!lease.active) {
            return;
        }
        deactivate(lease, reason, by);
        if (reason != LeaseEndReason.RELEASED) {
            notifyPreempted(lease, reason, by);
        }
    }

    /** Marks inactive, frees channels, releases simulated input and posts LeaseEnded. No listener callback. */
    private void deactivate(Lease lease, LeaseEndReason reason, String by) {
        lease.active = false;
        lease.endReason = reason;
        for (int i = 0; i < CHANNELS.length; i++) {
            if (holders[i] == lease) {
                holders[i] = null;
            }
        }
        if (input != null) {
            input.releaseFor(lease);
        }
        if (lease == assistLease) {
            // the assist grant rode along with this lease: it ends with it, for the same reason
            endAssistInvoluntary(reason, by);
        }
        post(new SuiteEvent.LeaseEnded(clock.getAsLong(), lease.id(), lease.moduleId(), reason, by));
    }

    private void notifyPreempted(Lease lease, LeaseEndReason reason, String by) {
        try {
            lease.listener.onPreempted(lease, reason, by);
        } catch (RuntimeException e) {
            LOG.error("Lease listener of {} failed in onPreempted", lease.moduleId(), e);
        }
    }

    private void suspend(Lease lease) {
        if (stopped) {
            return;
        }
        if (suspended.size() >= MAX_SUSPENDED) {
            suspended.remove(0);
        }
        suspended.add(lease);
    }

    private void removeSuspendedOf(String moduleId) {
        for (int i = suspended.size() - 1; i >= 0; i--) {
            if (suspended.get(i).moduleId().equals(moduleId)) {
                suspended.remove(i);
            }
        }
    }

    private boolean channelsFree(Lease lease) {
        for (int i = 0; i < CHANNELS.length; i++) {
            if ((lease.mask & (1 << i)) != 0 && (holders[i] != null || pauseTicks[i] > 0
                    || (i == MOVEMENT && shared != null))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fires {@code onResumable} for remembered leases whose channels are all free, most recent first. Re-scans after
     * each callback because the callback may grab channels; re-entrant calls just request another pass.
     */
    private void checkResumable() {
        if (inResumeCheck) {
            resumeCheckAgain = true;
            return;
        }
        inResumeCheck = true;
        try {
            boolean again = true;
            while (again && !stopped) {
                again = false;
                resumeCheckAgain = false;
                for (int i = suspended.size() - 1; i >= 0; i--) {
                    Lease s = suspended.get(i);
                    if (channelsFree(s)) {
                        suspended.remove(i);
                        try {
                            s.listener.onResumable(s);
                        } catch (RuntimeException e) {
                            LOG.error("Lease listener of {} failed in onResumable", s.moduleId(), e);
                        }
                        again = true;
                        break;
                    }
                }
                again |= resumeCheckAgain;
            }
        } finally {
            inResumeCheck = false;
        }
    }

    private void post(SuiteEvent event) {
        if (events != null) {
            try {
                events.post(event);
            } catch (RuntimeException e) {
                LOG.error("Suite event listener failed for {}", event, e);
            }
        }
    }
}
