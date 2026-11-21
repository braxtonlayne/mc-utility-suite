package com.utilitysuite.core.autopilot;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.HazardKind;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IAutopilot;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IDangerModel;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteEvent;

import java.nio.file.Path;
import java.util.List;

/**
 * The autopilot module ({@link IAutopilot}). It owns no input itself: the reflex modules ask {@link #allows} (or
 * {@link #mayAct}) and run their reflex through the core when it says yes, even while their own module is off. Off by
 * default; its own toggle sits pinned across the top of the menu and on an (unbound) key.
 * <p>
 * Underneath: the {@link DangerModel} (what is about to hurt), the {@link ReflexScheduler} (which reflex acts), the
 * {@link ReflexChain} (one EMERGENCY lease across reflexes that follow each other), the {@link BlackBox} (the last
 * 10 s, dumped on death or a big hit) and the {@link VitalsMonitor} (health, air, fire). Engine free except
 * {@code core.autopilot.mc}, which is only reached through the lazily created vitals source.
 */
public final class Autopilot implements IModule, IConfigurable, IAutopilot {

    public static final String ID = "autopilot";
    static final String K_CLUTCH = "reflex_fall_clutch";
    static final String K_PARKOUR = "reflex_parkour";
    static final String K_BLOCKS = "reflex_parkour_blocks";
    static final String K_TOTEM = "reflex_totem";
    static final String K_SHIELD = "reflex_shield";
    static final String K_DODGE = "reflex_dodge";
    static final String K_ESCAPE = "reflex_threat_escape";
    static final String K_ELYTRA = "reflex_elytra_swap";
    static final String K_OFFHAND = "reflex_offhand";
    static final String K_REACH = "reach";
    static final String K_BLACKBOX = "black_box";
    static final String K_BLACKBOX_HIT = "black_box_hit";

    static final String WHY_DEATH = "death";
    static final String WHY_BIG_HIT = "big hit";
    /** A reflex declining the same way again within this many ticks is not recorded twice. */
    static final int DECLINE_REPEAT_TICKS = 20;

    private boolean enabled;
    private boolean clutch = true;
    private boolean parkour = true;
    private boolean blocks = true;
    private boolean totem = true;
    private boolean shield = true;
    private boolean dodge = true;
    private boolean escape = true;
    private boolean elytra = true;
    private boolean offhand = true;
    private boolean blackBoxOn = true;
    private float bigHit = 6f;
    private Reach reach = Reach.ASSIST;

    private final TickClock clock;
    private final DangerModel danger;
    private final ReflexScheduler scheduler;
    private final ReflexChain chain;
    private final BlackBox blackBox = new BlackBox();
    private final BlackBoxWriter writer = new BlackBoxWriter();
    private final VitalsMonitor vitalsMonitor = new VitalsMonitor();
    private final VitalsMonitor.Vitals vitals = new VitalsMonitor.Vitals();
    private VitalsMonitor.VitalsSource vitalsSource;
    private boolean vitalsTried;
    private boolean subscribed;
    private float lastSampledHealth = Float.NaN;
    private int lastDeclinedReflex = -1;
    private String lastDeclinedReason;
    private long lastDeclinedTick;
    private int dumps;

    public Autopilot() {
        this(TickClock.SYSTEM, null);
    }

    /** For tests: a manual clock and a fake vitals source (null = none; the game source is never loaded). */
    Autopilot(TickClock clock, VitalsMonitor.VitalsSource source) {
        this.clock = clock;
        this.danger = new DangerModel(clock);
        this.scheduler = new ReflexScheduler(clock);
        this.chain = new ReflexChain(clock);
        if (clock != TickClock.SYSTEM) {
            this.vitalsSource = source;
            this.vitalsTried = true;
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.CORE;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (this.enabled && !enabled) {
            scheduler.reset();
            chain.release();
        }
        this.enabled = enabled;
    }

    /** Hot path: vitals, chain upkeep, one black-box row. Allocation free. */
    @Override
    public void onTick() {
        if (!subscribed) {
            subscribe();
        }
        VitalsMonitor.VitalsSource src = vitalsSource();
        int vitalsEvent = VitalsMonitor.NOTHING;
        if (src != null) {
            src.sample(vitals);
            vitalsEvent = vitalsMonitor.update(vitals, danger, bigHit);
        }
        if (chain.lease() != null) {
            chain.tick(arbiterOrNull());
        }
        if (blackBoxOn) {
            float hp = vitals.present ? vitals.health : Float.NaN;
            boolean anyDanger = danger.any();
            if (anyDanger || Float.compare(hp, lastSampledHealth) != 0) {
                lastSampledHealth = hp;
                blackBox.record(clock.now(), hp, anyDanger ? danger.worst() : null, scheduler.active(),
                        vitalsEvent == VitalsMonitor.NOTHING ? BlackBox.EV_SAMPLE : BlackBox.EV_DAMAGE,
                        vitalsEvent == VitalsMonitor.DIED ? WHY_DEATH
                                : vitalsEvent == VitalsMonitor.BIG_HIT ? WHY_BIG_HIT : null);
            }
            if (vitalsEvent == VitalsMonitor.DIED) {
                dump(WHY_DEATH);
            } else if (vitalsEvent == VitalsMonitor.BIG_HIT) {
                dump(WHY_BIG_HIT);
            }
        }
    }

    private VitalsMonitor.VitalsSource vitalsSource() {
        if (!vitalsTried) {
            vitalsTried = true;
            try {
                vitalsSource = new com.utilitysuite.core.autopilot.mc.MinecraftVitals();
                writer.setDirectory(com.utilitysuite.core.autopilot.mc.MinecraftVitals.blackBoxDirectory());
            } catch (LinkageError | RuntimeException e) {
                vitalsSource = null; // no game (unit tests): the model is fed by other sources only
            }
        }
        return vitalsSource;
    }

    private void subscribe() {
        subscribed = true;
        ISuiteEventBus bus = Services.eventsOrNull();
        if (bus != null) {
            bus.subscribe(SuiteEvent.PlayerDied.class, e -> {
                if (enabled && blackBoxOn) {
                    dump(WHY_DEATH);
                }
            });
        } else {
            subscribed = false; // try again next tick once the bus exists
        }
    }

    /** Snapshots the last 10 s and writes it off-thread (debounced). Returns the snapshot, or null when debounced. */
    BlackBox.Snapshot dump(String cause) {
        long now = clock.now();
        if (!writer.claim(now)) {
            return null;
        }
        BlackBox.Snapshot snap = blackBox.snapshot(now, cause);
        writer.write(snap);
        dumps++;
        ISuiteEventBus bus = Services.eventsOrNull();
        if (bus != null) {
            Path dir = writer.directory();
            bus.post(new SuiteEvent.Diagnostic(System.currentTimeMillis(), ID, "Black box saved (" + cause + ", "
                    + snap.rows() + " rows)" + (dir == null ? "" : " to " + dir)));
        }
        return snap;
    }

    int dumpCount() {
        return dumps;
    }

    BlackBox blackBox() {
        return blackBox;
    }

    void setBlackBoxDirectory(Path dir) {
        writer.setDirectory(dir);
    }

    private static IActionArbiter arbiterOrNull() {
        try {
            return Services.arbiter();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public void onStopAll() {
        scheduler.reset();
        danger.clearAll();
        chain.release();
    }

    @Override
    public void onWorldUnload() {
        onStopAll();
        blackBox.clear();
        vitalsMonitor.reset();
        lastSampledHealth = Float.NaN;
    }

    @Override
    public boolean isOn() {
        return enabled;
    }

    @Override
    public boolean allows(Reflex reflex) {
        if (!enabled) {
            return false;
        }
        return switch (reflex) {
            case FALL_CLUTCH -> clutch;
            case PARKOUR -> parkour && reach == Reach.ASSIST;
            case PARKOUR_BLOCKS -> blocks && reach == Reach.ASSIST;
            case TOTEM -> totem;
            case SHIELD -> shield;
            case DODGE -> dodge;
            case THREAT_ESCAPE -> escape;
            case ELYTRA_SWAP -> elytra;
            case OFFHAND -> offhand;
        };
    }

    @Override
    public Reach reach() {
        return reach;
    }

    // ---- danger model and reflex scheduler ------------------------------------------------------------------------

    @Override
    public IDangerModel danger() {
        return danger;
    }

    @Override
    public boolean mayAct(Reflex reflex, int ticksNeeded, float damageAverted) {
        return scheduler.mayAct(reflex, reflex != null && allows(reflex), ticksNeeded, damageAverted, danger);
    }

    @Override
    public void noteActed(Reflex reflex, String reason) {
        if (reflex == null) {
            return;
        }
        scheduler.noteActed(reflex);
        record(reflex, BlackBox.EV_ACTED, reason);
    }

    @Override
    public void noteDeclined(Reflex reflex, String reason) {
        if (reflex == null) {
            return;
        }
        long now = clock.now();
        int r = reflex.ordinal();
        if (r == lastDeclinedReflex && reason == lastDeclinedReason && now - lastDeclinedTick < DECLINE_REPEAT_TICKS) {
            return;
        }
        lastDeclinedReflex = r;
        lastDeclinedReason = reason;
        lastDeclinedTick = now;
        record(reflex, BlackBox.EV_DECLINED, reason);
    }

    @Override
    public void noteDone(Reflex reflex) {
        if (reflex == null) {
            return;
        }
        scheduler.noteDone(reflex);
        chain.done(reflex);
        record(reflex, BlackBox.EV_DONE, null);
    }

    private void record(Reflex reflex, byte ev, String reason) {
        if (blackBoxOn) {
            boolean anyDanger = danger.any();
            blackBox.record(clock.now(), vitals.present ? vitals.health : Float.NaN,
                    anyDanger ? danger.worst() : null, reflex, ev, reason);
        }
    }

    @Override
    public Reflex activeReflex() {
        return enabled ? scheduler.active() : null;
    }

    @Override
    public ControlLease chainLease(Reflex reflex, String moduleId) {
        if (reflex == null || !allows(reflex)) {
            return null;
        }
        return chain.join(reflex, arbiterOrNull());
    }

    ReflexChain chain() {
        return chain;
    }

    // ---- settings -------------------------------------------------------------------------------------------------

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        clutch = p.getBool(ID, K_CLUTCH, true);
        parkour = p.getBool(ID, K_PARKOUR, true);
        blocks = p.getBool(ID, K_BLOCKS, true);
        totem = p.getBool(ID, K_TOTEM, true);
        shield = p.getBool(ID, K_SHIELD, true);
        dodge = p.getBool(ID, K_DODGE, true);
        escape = p.getBool(ID, K_ESCAPE, true);
        elytra = p.getBool(ID, K_ELYTRA, true);
        offhand = p.getBool(ID, K_OFFHAND, true);
        blackBoxOn = p.getBool(ID, K_BLACKBOX, true);
        bigHit = Math.max(1, p.getInt(ID, K_BLACKBOX_HIT, 6));
        try {
            reach = Reach.valueOf(p.getString(ID, K_REACH, Reach.ASSIST.name()));
        } catch (IllegalArgumentException e) {
            reach = Reach.ASSIST;
        }
    }

    @Override
    public void onSettingChanged(String key) {
        com.utilitysuite.core.api.IModState s = Services.modState();
        onProfileLoaded(s.profile());
    }

    @Override
    public String displayName() {
        return "Autopilot";
    }

    /** One line for the top bar: what the autopilot may do right now. */
    public String summary() {
        if (!enabled) {
            return "Off. Turn it on to let reflexes act for you.";
        }
        StringBuilder sb = new StringBuilder();
        add(sb, clutch, "fall clutch");
        add(sb, totem, "totem");
        add(sb, shield, "shield");
        add(sb, dodge, "dodge");
        add(sb, escape, "escape");
        add(sb, elytra, "elytra");
        add(sb, offhand, "offhand");
        if (reach == Reach.ASSIST) {
            add(sb, parkour, "parkour");
            add(sb, blocks, "parkour blocks");
        }
        return sb.isEmpty() ? "On, but no reflex is allowed" : (reach == Reach.DANGER_ONLY ? "Danger only: "
                : "Watching: ") + sb;
    }

    private static void add(StringBuilder sb, boolean on, String name) {
        if (on) {
            sb.append(sb.isEmpty() ? "" : ", ").append(name);
        }
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice(K_REACH, "How far it may go", Reach.ASSIST, "",
                        "DANGER_ONLY: steps in only when you are about to get hurt (a fall, an arrow, a blast)."
                                + " ASSIST: also helps while you move (parkour timing, a block mid-jump). Your own"
                                + " keys and mouse always win."),
                SettingSpec.bool(K_CLUTCH, "Fall clutch", true, "Reflexes",
                        "A fall that would hurt: look down and clutch with water, powder snow, hay, slime or cobweb"
                                + " from the hotbar (Movement Assist's clutch settings apply)."),
                SettingSpec.bool(K_TOTEM, "Keep a totem in the offhand", true, "Reflexes",
                        "An empty offhand is refilled with a totem of undying from your inventory (one inventory click,"
                                + " like a player). Single-player and allow-listed servers only."),
                SettingSpec.bool(K_SHIELD, "Shield", true, "Reflexes",
                        "Raise the shield against an arrow, a blast or a melee wind-up, and lower it to swing."),
                SettingSpec.bool(K_DODGE, "Dodge", true, "Reflexes",
                        "Step out of a projectile's line with a timed strafe tap (keys only)."),
                SettingSpec.bool(K_ESCAPE, "Back away from blasts", true, "Reflexes",
                        "Back away from a creeper fuse or primed TNT before it goes off (keys only)."),
                SettingSpec.bool(K_ELYTRA, "Elytra swap", true, "Reflexes",
                        "A long fall with an elytra in the inventory: swap it on, and the chestplate back after"
                                + " landing."),
                SettingSpec.bool(K_OFFHAND, "Offhand rules", true, "Reflexes",
                        "Offhand beyond the totem: a shield against skeletons, a totem while gliding."),
                SettingSpec.bool(K_PARKOUR, "Parkour timing", true, "Reflexes",
                        "While you run forward: jump on the last tick before an edge, steer in the air toward a safe"
                                + " landing. ASSIST only."),
                SettingSpec.bool(K_BLOCKS, "Parkour blocks", true, "Reflexes",
                        "Mid-jump, place one block when it keeps the run going (uses hotbar blocks). ASSIST only."),
                SettingSpec.bool(K_BLACKBOX, "Black box", true, "Black box",
                        "Keeps the last 10 seconds of health, dangers and reflex decisions, and saves them to"
                                + " utilitysuite/blackbox when you die or take a big hit."),
                SettingSpec.integer(K_BLACKBOX_HIT, "Big hit (half hearts)", 6, 2, 40, "Black box",
                        "Losing at least this much health in one tick saves the black box."));
    }

    /** For the HUD and diagnostics: true while any hazard is live. */
    public boolean inDanger() {
        return danger.any();
    }

    /** The worst live hazard, or null. */
    public HazardKind worstHazard() {
        return danger.worst();
    }
}
