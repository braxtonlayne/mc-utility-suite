package com.utilitysuite.combat.cues;

import com.utilitysuite.combat.coach.CoachReadout;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.Holder;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

import java.util.List;

/**
 * Procedural holo-audio cues: short UI tones played through the vanilla sound system (existing note-block and UI
 * sounds with a per-cue pitch and volume, so no assets are needed). Cues: weapon charged (from the Fight Coach readout,
 * higher when the swing would crit), a rising tone as a mace fall nears lethal smash damage, a soft blip when a player
 * not on the ally roster comes into range, and a click when automation takes or loses a control lease. Every cue has a
 * toggle, all share a master volume, and {@link CueScheduler} rate-limits them. Off by default; silent under
 * Clean-Screen. UI sounds are client-only: nothing is sent.
 */
public final class HoloAudioCues implements IModule, IConfigurable {

    public static final String ID = "holo_audio_cues";

    private final CoachReadout coach;
    private final CueScheduler scheduler = new CueScheduler();
    private final MaceRiseCue mace = new MaceRiseCue();
    private final ProximityTracker nearby = new ProximityTracker(64);
    private final int[] played = new int[CueScheduler.Cue.values().length];
    private final float[] lastPitch = new float[CueScheduler.Cue.values().length];

    private boolean enabled;
    private double masterVolume = 0.6;
    private boolean swingCue = true;
    private boolean maceCue = true;
    private boolean blipCue = true;
    private boolean leaseCue = true;
    private int blipRange = 48;
    private long tick;
    /** Ticks after enabling / joining before blips start (players already around are not announced). */
    private int warmup;
    private boolean lastSwing;
    private boolean lastCrit;

    /** @param coach the Fight Coach readout (may be null: no swing cue) */
    public HoloAudioCues(CoachReadout coach) {
        this.coach = coach;
        applyToggles();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.COMBAT;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean on) {
        enabled = on;
        resetState();
    }

    @Override
    public void onStopAll() {
        mace.reset();
    }

    @Override
    public void onWorldUnload() {
        resetState();
    }

    private void resetState() {
        mace.reset();
        nearby.clear();
        scheduler.reset();
        warmup = 40;
        lastSwing = false;
        lastCrit = false;
    }

    @Override
    public void onTick() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || cleanScreen()) {
            return;
        }
        if (swingCue && coach != null) {
            boolean swing = coach.visible && coach.swingReady;
            boolean crit = coach.visible && coach.critReady;
            if (crit && !lastCrit) {
                play(CueScheduler.Cue.SWING_READY, SoundEvents.NOTE_BLOCK_HAT, 1.9f);
            } else if (swing && !lastSwing) {
                play(CueScheduler.Cue.SWING_READY, SoundEvents.NOTE_BLOCK_HAT, 1.4f);
            }
            lastSwing = swing;
            lastCrit = crit;
        }
        if (maceCue) {
            float needed = Float.NaN;
            if (mc.crosshairPickEntity instanceof LivingEntity t && t.isAlive()) {
                needed = t.getHealth() + t.getAbsorptionAmount();
            }
            // Density is not read (needs the enchantment registry): the estimate errs low, so "lethal" comes late.
            int r = mace.update(p.getMainHandItem().is(Items.MACE), p.onGround(), p.fallDistance, 0, needed);
            if (r == MaceRiseCue.RISE) {
                play(CueScheduler.Cue.MACE_RISE, SoundEvents.NOTE_BLOCK_BIT, mace.pitch());
            } else if (r == MaceRiseCue.LETHAL) {
                play(CueScheduler.Cue.MACE_LETHAL, SoundEvents.NOTE_BLOCK_CHIME, mace.pitch());
            }
        }
        if (blipCue) {
            double r2 = (double) blipRange * blipRange;
            List<AbstractClientPlayer> players = mc.level.players();
            for (int i = 0, n = players.size(); i < n; i++) {
                AbstractClientPlayer o = players.get(i);
                if (o == p || o.distanceToSqr(p) > r2 || Services.allies().isAlly(o.getUUID())) {
                    continue;
                }
                if (nearby.observe(o.getId(), tick) && warmup <= 0) {
                    play(CueScheduler.Cue.PLAYER_BLIP, SoundEvents.NOTE_BLOCK_PLING, 0.7f);
                }
            }
            nearby.sweep(tick);
        }
        if (warmup > 0) {
            warmup--;
        }
    }

    /** A control lease was granted to an automation module (from the suite event bus). */
    public void onLeaseGranted() {
        if (enabled && leaseCue && !cleanScreen()) {
            play(CueScheduler.Cue.LEASE_CLICK, SoundEvents.UI_BUTTON_CLICK, 1.8f);
        }
    }

    /** A lease ended; the user taking over or Stop-All gets a lower click. Normal releases stay silent. */
    public void onLeaseEnded(LeaseEndReason reason) {
        if (enabled && leaseCue && !cleanScreen()
                && (reason == LeaseEndReason.MANUAL_OVERRIDE || reason == LeaseEndReason.STOP_ALL
                || reason == LeaseEndReason.PREEMPTED)) {
            play(CueScheduler.Cue.LEASE_CLICK, SoundEvents.UI_BUTTON_CLICK, 1.2f);
        }
    }

    private void play(CueScheduler.Cue cue, Holder<SoundEvent> sound, float pitch) {
        if (!scheduler.request(cue, tick)) {
            return;
        }
        // One small sound instance per played cue (rate-limited to a few a second), not per tick.
        float p = Math.max(0.5f, Math.min(2f, pitch));
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound.value(), p,
                scheduler.volume(cue)));
        played[cue.ordinal()]++;
        lastPitch[cue.ordinal()] = p;
    }

    /** Cues of this kind played since start-up (diagnostics, playtests). */
    public int played(CueScheduler.Cue cue) {
        return played[cue.ordinal()];
    }

    /** Pitch of the last cue of this kind played (0 before the first). */
    public float lastPitch(CueScheduler.Cue cue) {
        return lastPitch[cue.ordinal()];
    }

    private static boolean cleanScreen() {
        var s = Services.modState();
        return s != null && s.isCleanScreen();
    }

    private void applyToggles() {
        scheduler.setMaster((float) masterVolume);
        scheduler.setEnabled(CueScheduler.Cue.SWING_READY, swingCue);
        scheduler.setEnabled(CueScheduler.Cue.MACE_RISE, maceCue);
        scheduler.setEnabled(CueScheduler.Cue.MACE_LETHAL, maceCue);
        scheduler.setEnabled(CueScheduler.Cue.PLAYER_BLIP, blipCue);
        scheduler.setEnabled(CueScheduler.Cue.LEASE_CLICK, leaseCue);
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Holo Audio Cues";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.decimal("master_volume", "Master volume", 0.6, 0.0, 1.0, "",
                        "Volume of every cue."),
                SettingSpec.bool("swing_ready", "Weapon charged tick", true, "Cues",
                        "Tick when your weapon is fully charged in a fight (higher when the swing would crit). "
                                + "Uses the Fight Coach."),
                SettingSpec.bool("mace", "Mace fall tone", true, "Cues",
                        "Rising tone while a mace fall builds damage; a chime once it would kill your target."),
                SettingSpec.bool("player_blip", "Player in range blip", true, "Cues",
                        "Soft blip when a player not on your ally list comes into range."),
                SettingSpec.integer("blip_range", "Blip range (blocks)", 48, 8, 128, "Cues",
                        "How close a player has to come for the blip."),
                SettingSpec.bool("lease_click", "Automation handoff click", true, "Cues",
                        "Click when automation takes control, lower when you take it back."));
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        masterVolume = Math.max(0.0, Math.min(1.0, p.getDouble(ID, "master_volume", 0.6)));
        swingCue = p.getBool(ID, "swing_ready", true);
        maceCue = p.getBool(ID, "mace", true);
        blipCue = p.getBool(ID, "player_blip", true);
        blipRange = Math.max(8, Math.min(128, p.getInt(ID, "blip_range", 48)));
        leaseCue = p.getBool(ID, "lease_click", true);
        applyToggles();
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
