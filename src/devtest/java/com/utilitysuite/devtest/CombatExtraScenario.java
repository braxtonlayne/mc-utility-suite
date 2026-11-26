package com.utilitysuite.devtest;

import com.mojang.authlib.GameProfile;
import com.utilitysuite.combat.CombatBootstrap;
import com.utilitysuite.combat.allies.AllyRosterModule;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.cues.CueScheduler;
import com.utilitysuite.combat.cues.HoloAudioCues;
import com.utilitysuite.combat.hud.ChoreographyModule;
import com.utilitysuite.combat.hud.KineticChainModule;
import com.utilitysuite.combat.hud.ReadRingModule;
import com.utilitysuite.combat.tactics.ReadState;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IRouteRunner;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.api.visual.IWorldMarks;
import com.utilitysuite.core.api.visual.MarkKind;
import com.utilitysuite.jobs.fieldwork.CropWorkModule;
import com.utilitysuite.travel.TravelBootstrap;
import com.utilitysuite.travel.movement.GhostLine;
import com.utilitysuite.travel.movement.GhostLineModule;
import com.utilitysuite.travel.movement.MovementAssist;
import com.utilitysuite.travel.movement.RunnersVision;
import com.utilitysuite.travel.movement.RunnersVisionModule;
import com.utilitysuite.travel.pursuit.MinecraftRouteRunner;
import com.utilitysuite.travel.terrain.TerrainAssist;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;

import java.util.List;
import java.util.UUID;

/**
 * Scenario 19-combatextra: in-game coverage for eight modules no other scenario touched: Ally Roster (middle-click
 * toggle on a player, the never-target rule on an attacker, the player blip skipping allies), The Read (the target's
 * attack charge on the reticle mark), Combat Choreography (reach ring tone, knockback arrow and its ledge warning,
 * afterimages), Kinetic Chain (launch spots, apex and mace readout, checked against a real wind-charge launch), Holo
 * Audio Cues (lease clicks, weapon charged tick, player blip, mace rise and lethal chime), Ghost Line (route ghost
 * ahead on the route, jump-landing mark, miss mark at a void edge), Runner's Vision (the flow-line over a gap to a
 * wall) and Crop Work (a real harvest and replant of a small planted farm with the hoe and seeds through its leases).
 * Opponents are plain zombies. Every section turns its modules and settings back off and clears what it built.
 * Select it alone with {@code -PplaytestOnly=CombatExtra}.
 */
final class CombatExtraScenario {

    private static final String TARGET_HUD = "target_hud";
    private static final String FIGHT_COACH = "fight_coach";
    private static final String[] MODULES = {ReadRingModule.ID, ChoreographyModule.ID, KineticChainModule.ID,
            HoloAudioCues.ID, GhostLineModule.ID, RunnersVisionModule.ID, CropWorkModule.ID, CombatAssistant.ID,
            TARGET_HUD, FIGHT_COACH, MovementAssist.ID, TerrainAssist.ID};
    private static final UUID PAL = UUID.nameUUIDFromBytes("utilitysuite-playtest-pal".getBytes());

    private static RemotePlayer pal;
    private static ControlLease lease;

    private CombatExtraScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("19-combatextra", "Combat/travel/jobs extras: allies, read, choreography, kinetic"
                + " chain, audio cues, ghost line, runner's vision, crop work");
        WorldProbe.install();
        Arena.ensure(s);
        reset(s);
        allies(s);
        fight(s);
        choreography(s);
        kinetic(s);
        ghostLine(s);
        runnersVision(s);
        crops(s);
        s.always("disarm", () -> {
            WorldProbe.disarm();
            closeLease();
            removePal();
            for (String id : MODULES) {
                H.enable(id, false);
            }
            H.enable(AllyRosterModule.ID, true);
            H.setting(AllyRosterModule.ID, AllyRosterModule.MIDDLE_CLICK, true);
            H.setting(ReadRingModule.ID, ReadRingModule.K_RING, false);
            H.setting(MovementAssist.ID, "assist_automation", false);
            CombatBootstrap.allies().roster().remove(PAL);
            Zombie z = CombatScenario.nearestZombie();
            if (z != null) {
                CombatBootstrap.allies().roster().remove(z.getUUID());
            }
            if (Services.arbiter().isAutomationStopped()) {
                Services.arbiter().resumeAutomation();
            }
            Harness.command("kill @e[type=!minecraft:player]");
            Harness.command("clear @s");
            Harness.command("effect clear @s");
            Harness.command("effect give @s minecraft:instant_health 1 5 true");
            restoreArena();
            Harness.command("tp @s " + Arena.cx + ".5 " + (Arena.FLOOR + 1) + " " + Arena.cz + ".5 0 0");
        });
        s.waitTicks(20);
        return s;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static void reset(Scenario s) {
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("clear @s");
        s.cmd("effect clear @s");
        s.cmd("effect give @s minecraft:instant_health 1 5 true");
        s.cmd("effect give @s minecraft:resistance 600 4 true");
        s.cmd("effect give @s minecraft:saturation 600 0 true");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
    }

    /** The arena as {@link Arena#ensure} built it, plus the space under the deck cleared. */
    private static void restoreArena() {
        Harness.command(Arena.fill(-12, 1, -12, 12, 9, 12, "air"));
        Harness.command(Arena.fill(-12, -13, -12, 12, -1, 12, "air"));
        Harness.command(Arena.fill(-12, 0, -12, 12, 0, 12, "stone"));
        Harness.command(Arena.fill(4, -1, -8, 8, -1, -4, "stone"));
        Harness.command(Arena.fill(4, 0, -8, 8, 0, -4, "water"));
    }

    private static String tpLook(double dx, double dy, double dz, float yaw, float pitch) {
        return "tp @s " + (Arena.cx + dx) + " " + (Arena.FLOOR + dy) + " " + (Arena.cz + dz) + " " + yaw + " " + pitch;
    }

    private static String zombie(double dx, double dz, boolean ai, int health) {
        return "summon minecraft:zombie " + (Arena.cx + dx) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + dz)
                + " {PersistenceRequired:1b,NoAI:" + (ai ? "0b" : "1b") + ",Health:" + health + "f,"
                + "attributes:[{id:\"minecraft:generic.max_health\",base:" + health + "d}],"
                + "ArmorItems:[{},{},{},{id:\"minecraft:leather_helmet\",count:1}]}";
    }

    /** The user's own melee hit (test scaffolding plays the user). */
    private static void hit(Entity e) {
        H.mc().gameMode.attack(H.p(), e);
        H.p().swing(InteractionHand.MAIN_HAND);
    }

    private static ControlLease takeLease(String id, ControlChannel first, ControlChannel... rest) {
        closeLease();
        lease = Services.arbiter().request(LeaseRequest.of(id, ControlPriority.TRAVEL, null, first, rest))
                .orElseThrow(() -> new IllegalStateException("no lease for " + id + ": " + H.arbiterState()));
        return lease;
    }

    private static void closeLease() {
        if (lease != null) {
            if (lease.isActive()) {
                Services.input().setKeyHeld(lease, SimKey.FORWARD, false);
                Services.input().setKeyHeld(lease, SimKey.SPRINT, false);
                IRouteRunner rr = Services.routeRunnerOrNull();
                if (rr != null) {
                    rr.stop(lease);
                }
            }
            lease.close();
            lease = null;
        }
    }

    /** A client-side stand-in for another player ({@code RemotePlayer}), {@code dz} blocks in front of the centre. */
    private static void spawnPal(double dz) {
        removePal();
        RemotePlayer rp = new RemotePlayer(H.mc().level, new GameProfile(PAL, "PlaytestPal"));
        rp.moveTo(Arena.cx + 0.5, Arena.FLOOR + 1, Arena.cz + dz, 180f, 0f);
        rp.setOldPosAndRot();
        H.mc().level.addEntity(rp);
        pal = rp;
    }

    private static void removePal() {
        if (pal != null && H.mc().level != null) {
            H.mc().level.removeEntity(pal.getId(), Entity.RemovalReason.DISCARDED);
        }
        pal = null;
    }

    /** One press of the pick-block (middle mouse) binding, as the user would. */
    private static void middleClick() {
        KeyMapping.click(H.mc().options.keyPickItem.getKey());
    }

    private static float markCharge(int entityId) {
        IWorldMarks m = Services.visuals().marks();
        for (int i = 0; i < IWorldMarks.SLOTS; i++) {
            if (m.occupied(i) && m.kind(i) == MarkKind.PRIMARY_TARGET && m.entityId(i) == entityId) {
                return m.charge(i);
            }
        }
        return Float.NaN;
    }

    private static ReadState readState() {
        return (ReadState) CombatDepthScenario.field(CombatBootstrap.readRing(), "read");
    }

    private static boolean on(String id) {
        return Services.modules().byId(id).map(m -> m.isEnabled()).orElse(false);
    }

    // ---- 1. Ally Roster (+ the player blip cue) ---------------------------------------------------------------------

    private static void allies(Scenario s) {
        AllyRosterModule allies = CombatBootstrap.allies();
        CombatAssistant ca = CombatBootstrap.assistant();
        HoloAudioCues cues = H.module(HoloAudioCues.ID);
        int[] n = new int[4];
        Zombie[] z = new Zombie[1];
        s.run("allies: cues on", () -> {
            s.check("ally roster on by default, middle-click enabled", allies.isEnabled()
                    && allies.middleClickEnabled() && !allies.roster().contains(PAL), "enabled=" + allies.isEnabled()
                    + " middleClick=" + allies.middleClickEnabled() + " entries=" + allies.roster().entries().size());
            H.enable(HoloAudioCues.ID, true);
        });
        s.waitTicks(45); // the blip's warm-up: players already around when it turns on are not announced
        s.run("allies: a player arrives", () -> {
            n[0] = cues.played(CueScheduler.Cue.PLAYER_BLIP);
            n[1] = WorldProbe.sounds("block.note_block.pling");
            spawnPal(2.5);
        });
        s.waitUpTo("blip", 20, () -> cues.played(CueScheduler.Cue.PLAYER_BLIP) > n[0]);
        s.run("blip result", () -> s.check("holo cues: blip when a non-ally player comes into range",
                cues.played(CueScheduler.Cue.PLAYER_BLIP) > n[0], "blips " + n[0] + " -> "
                        + cues.played(CueScheduler.Cue.PLAYER_BLIP) + ", pling sounds " + n[1] + " -> "
                        + WorldProbe.sounds("block.note_block.pling") + ", pal id " + pal.getId()));
        s.waitTicks(5);
        s.run("allies: middle-click the player", () -> {
            boolean aimed = H.mc().hitResult instanceof EntityHitResult eh && eh.getEntity() == pal;
            s.note("crosshair on the pal before the middle-click: " + aimed + " (" + H.mc().hitResult + ")");
            middleClick();
        });
        s.waitUpTo("ally added", 10, () -> allies.roster().contains(PAL));
        s.run("ally added result", () -> s.check("ally roster: middle-click on a player adds them (never-target)",
                allies.roster().contains(PAL) && allies.roster().isNeverTarget(PAL)
                        && Services.allies().isAlly(PAL),
                "contains=" + allies.roster().contains(PAL) + " neverTarget=" + allies.roster().isNeverTarget(PAL)
                        + " entries=" + allies.roster().entries().size()));
        s.run("allies: the ally leaves", () -> removePal());
        s.waitTicks(5);
        s.run("allies: the ally comes back", () -> {
            n[2] = cues.played(CueScheduler.Cue.PLAYER_BLIP);
            spawnPal(2.5);
        });
        s.waitTicks(30);
        s.run("ally blip result", () -> s.check("holo cues: no blip for a player on the ally roster",
                cues.played(CueScheduler.Cue.PLAYER_BLIP) == n[2],
                "blips " + n[2] + " -> " + cues.played(CueScheduler.Cue.PLAYER_BLIP)));
        s.run("allies: middle-click again", CombatExtraScenario::middleClick);
        s.waitUpTo("ally removed", 10, () -> !allies.roster().contains(PAL));
        s.run("ally removed result", () -> s.check("ally roster: a second middle-click removes them",
                !allies.roster().contains(PAL), "contains=" + allies.roster().contains(PAL)));
        s.run("allies: shortcut off", () -> {
            H.setting(AllyRosterModule.ID, AllyRosterModule.MIDDLE_CLICK, false);
            middleClick();
        });
        s.waitTicks(6);
        s.run("shortcut off result", () -> {
            s.check("ally roster: with the middle-click setting off the click changes nothing",
                    !allies.roster().contains(PAL), "contains=" + allies.roster().contains(PAL));
            H.setting(AllyRosterModule.ID, AllyRosterModule.MIDDLE_CLICK, true);
            removePal();
        });
        // the roster's never-target rule against a hostile marked as an ally (the roster is keyed by UUID)
        s.cmd("give @s minecraft:diamond_sword");
        s.cmd(() -> zombie(0.5, 2.5, false, 500));
        s.waitFor("ally zombie visible", 60, () -> CombatScenario.nearestZombie() != null);
        s.run("allies: mark the zombie and hit it", () -> {
            z[0] = CombatScenario.nearestZombie();
            allies.togglePlayer(z[0].getUUID(), "PlaytestZombie");
            H.enable(CombatAssistant.ID, true);
            n[3] = ca.engagements();
            hit(z[0]);
        });
        s.waitTicks(25);
        s.run("ally zombie result", () -> s.check("ally roster: hitting a never-target ally does not engage",
                ca.state() == CombatState.DORMANT && ca.engagements() == n[3] && ca.lastNote().contains("ally"),
                "state=" + ca.state() + " engagements " + n[3] + " -> " + ca.engagements() + ", note '"
                        + ca.lastNote() + "'"));
        s.run("allies: unmark and hit again", () -> {
            allies.togglePlayer(z[0].getUUID(), "PlaytestZombie");
            hit(z[0]);
        });
        s.waitUpTo("engaged after unmark", 40, () -> ca.state() == CombatState.ENGAGED);
        s.run("unmarked result", () -> {
            s.check("ally roster: the same hit engages once it is off the roster",
                    ca.state() == CombatState.ENGAGED && ca.targetId() == z[0].getId(),
                    "state=" + ca.state() + " target=" + ca.targetId() + " zombie=" + z[0].getId());
            H.enable(CombatAssistant.ID, false);
            H.enable(HoloAudioCues.ID, false);
        });
        reset(s);
    }

    // ---- 2. The Read, the weapon-charged tick and the lease clicks --------------------------------------------------

    private static void fight(Scenario s) {
        CombatAssistant ca = CombatBootstrap.assistant();
        HoloAudioCues cues = H.module(HoloAudioCues.ID);
        int[] n = new int[6];
        Zombie[] z = new Zombie[1];
        // min/max read charge on the mark, ticks with a read on the mark, ticks engaged
        float[] charge = {2f, -1f};
        int[] seen = new int[3];
        s.cmd("give @s minecraft:diamond_sword");
        s.run("fight: modules on", () -> {
            for (String id : new String[] {CombatAssistant.ID, TARGET_HUD, ReadRingModule.ID, FIGHT_COACH,
                    HoloAudioCues.ID}) {
                H.enable(id, true);
            }
            n[0] = cues.played(CueScheduler.Cue.LEASE_CLICK);
            n[1] = cues.played(CueScheduler.Cue.SWING_READY);
        });
        s.cmd(() -> zombie(0.5, 3.5, true, 500));
        s.waitFor("fight zombie visible", 60, () -> CombatScenario.nearestZombie() != null);
        s.waitUpTo("fight zombie close", 60, () -> CombatScenario.nearestZombie().distanceTo(H.p()) < 2.8);
        s.run("fight: first hit", () -> {
            z[0] = CombatScenario.nearestZombie();
            hit(z[0]);
        });
        s.waitFor("fight engaged", 40, () -> ca.state() == CombatState.ENGAGED);
        s.run("lease click result", () -> s.check("holo cues: click when automation takes a lease",
                cues.played(CueScheduler.Cue.LEASE_CLICK) > n[0]
                        && Math.abs(cues.lastPitch(CueScheduler.Cue.LEASE_CLICK) - 1.8f) < 0.01f,
                "clicks " + n[0] + " -> " + cues.played(CueScheduler.Cue.LEASE_CLICK) + ", pitch "
                        + cues.lastPitch(CueScheduler.Cue.LEASE_CLICK) + ", button sounds "
                        + WorldProbe.sounds("ui.button.click")));
        s.step("fight: watch the read", t -> {
            ReadState r = readState();
            if (ca.state() == CombatState.ENGAGED) {
                seen[2]++;
            }
            float c = markCharge(z[0].getId());
            if (r.active && r.targetId == z[0].getId() && c == c && c >= 0f) {
                seen[0]++;
                charge[0] = Math.min(charge[0], c);
                charge[1] = Math.max(charge[1], c);
            }
            if (t == 30) {
                Shots.take(s, "combatextra-read");
            }
            boolean done = seen[0] > 40 && charge[0] < 0.6f && charge[1] > 0.95f
                    && cues.played(CueScheduler.Cue.SWING_READY) > n[1];
            return done || t > 20 * 15 || !z[0].isAlive();
        });
        s.run("read result", () -> {
            ReadState r = readState();
            s.check("the read: the target's attack charge rides on the reticle mark", seen[0] > 40,
                    "ticks with the read on the mark " + seen[0] + " of " + seen[2] + " engaged, read active="
                            + r.active + " target=" + r.targetId + " (zombie " + z[0].getId() + ")");
            s.check("the read: the charge empties after the zombie swings and refills", charge[0] < 0.6f
                    && charge[1] > 0.95f, Harness.fmt("charge min %.2f max %.2f, readyIn %d, player hp %.1f",
                    charge[0], charge[1], r.readyIn, H.p().getHealth()));
            s.check("holo cues: weapon-charged tick during the fight", cues.played(CueScheduler.Cue.SWING_READY) > n[1],
                    "ticks " + n[1] + " -> " + cues.played(CueScheduler.Cue.SWING_READY) + ", last pitch "
                            + cues.lastPitch(CueScheduler.Cue.SWING_READY) + ", hat sounds "
                            + WorldProbe.sounds("block.note_block.hat") + ", assistant swings " + ca.swings());
            H.setting(ReadRingModule.ID, ReadRingModule.K_RING, true);
        });
        s.waitTicks(5);
        s.run("read ring style", () -> {
            float c = markCharge(z[0].getId());
            s.check("the read: the ring style takes the read off the reticle mark",
                    readState().active && !(c >= 0f), "read active=" + readState().active + " mark charge=" + c);
            H.setting(ReadRingModule.ID, ReadRingModule.K_RING, false);
        });
        s.waitTicks(5);
        s.run("read off", () -> {
            n[2] = markCharge(z[0].getId()) >= 0f ? 1 : 0;
            H.enable(ReadRingModule.ID, false);
        });
        s.waitTicks(5);
        s.run("read off result", () -> {
            float c = markCharge(z[0].getId());
            s.check("the read: back on the reticle with the ring off, and gone with the module off",
                    n[2] == 1 && !(c >= 0f) && c == c, "reticle back=" + (n[2] == 1) + ", mark charge after off=" + c);
            n[3] = cues.played(CueScheduler.Cue.LEASE_CLICK);
            Services.arbiter().stopAll("playtest: combat extras");
        });
        s.waitTicks(5);
        s.run("stop-all click result", () -> {
            s.check("holo cues: lower click when Stop-All ends the fight's lease",
                    cues.played(CueScheduler.Cue.LEASE_CLICK) > n[3]
                            && Math.abs(cues.lastPitch(CueScheduler.Cue.LEASE_CLICK) - 1.2f) < 0.01f
                            && ca.state() == CombatState.DORMANT,
                    "clicks " + n[3] + " -> " + cues.played(CueScheduler.Cue.LEASE_CLICK) + ", pitch "
                            + cues.lastPitch(CueScheduler.Cue.LEASE_CLICK) + ", assistant " + ca.state());
            Services.arbiter().resumeAutomation();
            for (String id : new String[] {CombatAssistant.ID, TARGET_HUD, ReadRingModule.ID, FIGHT_COACH,
                    HoloAudioCues.ID}) {
                H.enable(id, false);
            }
        });
        reset(s);
    }

    // ---- 3. Combat Choreography ------------------------------------------------------------------------------------

    private static void choreography(Scenario s) {
        ChoreographyModule ch = H.module(ChoreographyModule.ID);
        Zombie[] z = new Zombie[1];
        s.cmd("give @s minecraft:diamond_sword");
        s.cmd(() -> zombie(0.5, 2.5, false, 500));
        s.waitFor("choreo zombie visible", 60, () -> CombatScenario.nearestZombie() != null);
        s.run("choreography on, user hits (assistant off)", () -> {
            H.enable(ChoreographyModule.ID, true);
            z[0] = CombatScenario.nearestZombie();
            WorldProbe.arm();
            hit(z[0]); // the hit makes it the assistant's recent opponent: the overlay shows it with the assistant off
        });
        s.waitUpTo("choreography shows it", 20, () -> ch.shownTarget() == z[0].getId());
        s.waitTicks(10);
        s.run("in reach result", () -> s.check("choreography: in-reach ring (ALLY), knockback arrow, afterimages",
                ch.shownTarget() == z[0].getId() && ch.inReach() && ch.hasLanding() && !ch.ledge()
                        && ch.afterimageCount() >= 2 && WorldProbe.calls("ring:ALLY") > 0
                        && WorldProbe.calls("ribbon:ACCENT") > 0 && WorldProbe.calls("boxEdges:CAT_COMBAT") > 0,
                "shown=" + ch.shownTarget() + " inReach=" + ch.inReach() + " landing=" + ch.hasLanding() + " ledge="
                        + ch.ledge() + " afterimages=" + ch.afterimageCount() + " dist="
                        + Harness.fmt("%.2f", z[0].distanceTo(H.p())) + "; kit " + WorldProbe.summary()));
        s.cmd(() -> Arena.tp(0.5, -4.5, 0f));
        s.waitTicks(8);
        s.run("probe re-arm (out of reach)", WorldProbe::arm);
        s.waitTicks(6);
        s.run("out of reach result", () -> s.check("choreography: the ring turns WARN out of melee distance",
                !ch.inReach() && WorldProbe.calls("ring:WARN") > 0 && WorldProbe.calls("ring:ALLY") == 0,
                "inReach=" + ch.inReach() + " dist=" + Harness.fmt("%.2f", z[0].distanceTo(H.p())) + "; kit "
                        + WorldProbe.summary()));
        // at the south edge (the deck ends at z = cz + 13, open air below): the next hit would knock it off
        s.cmd(() -> "tp @e[type=minecraft:zombie] " + (Arena.cx + 0.5) + " " + (Arena.FLOOR + 1) + " "
                + (Arena.cz + 12.3));
        s.cmd(() -> Arena.tp(0.5, 10.3, 0f));
        s.waitTicks(8);
        s.run("probe re-arm (ledge)", WorldProbe::arm);
        s.waitTicks(6);
        s.run("ledge result", () -> s.check("choreography: the knockback arrow warns (DANGER) over the drop",
                ch.ledge() && ch.landZ() > z[0].getZ() + 0.3 && WorldProbe.calls("ribbon:DANGER") > 0
                        && WorldProbe.calls("groundMark:DANGER") > 0,
                Harness.fmt("ledge=%s landing (%.2f, %.2f, %.2f), zombie z %.2f; kit %s", ch.ledge(),
                        ch.landX() - Arena.cx, ch.landY() - Arena.FLOOR, ch.landZ() - Arena.cz,
                        z[0].getZ() - Arena.cz, WorldProbe.summary())));
        s.cmd("kill @e[type=minecraft:zombie]");
        s.waitTicks(5);
        s.run("probe re-arm (no target)", WorldProbe::arm);
        s.waitTicks(6);
        s.run("no target result", () -> {
            s.check("choreography: nothing drawn without a target", ch.shownTarget() < 0
                    && WorldProbe.calls("ring") == 0 && WorldProbe.calls("ribbon") == 0,
                    "shown=" + ch.shownTarget() + "; kit " + WorldProbe.summary());
            WorldProbe.disarm();
            H.enable(ChoreographyModule.ID, false);
        });
        reset(s);
    }

    // ---- 4. Kinetic Chain (+ a real wind-charge launch, the mace cues) ---------------------------------------------

    private static void kinetic(Scenario s) {
        KineticChainModule kc = H.module(KineticChainModule.ID);
        HoloAudioCues cues = H.module(HoloAudioCues.ID);
        double[] launch = new double[4]; // predicted apex, start y, max y, left ground
        int[] n = new int[2];
        List<?>[] texts = new List<?>[1];
        s.cmd("item replace entity @s weapon.mainhand with minecraft:wind_charge 8");
        s.run("mace in another hotbar slot", () -> Harness.command("item replace entity @s hotbar."
                + ((H.p().getInventory().selected + 1) % 9) + " with minecraft:mace"));
        s.cmd(() -> tpLook(0.5, 1, 0.5, 0f, 90f));
        s.run("kinetic on", () -> H.enable(KineticChainModule.ID, true));
        s.waitTicks(12);
        s.run("kinetic readout", () -> {
            texts[0] = WorldProbe.hudTexts(kc);
            String hud = String.valueOf(texts[0]);
            s.check("kinetic chain: launch spots, apex from the floor under the crosshair, mace damage",
                    kc.spotCount() > 0 && kc.apexTenths() > 0 && kc.damageTenths() > 0 && kc.wantsDraw()
                            && hud.contains("APEX") && hud.contains("MACE") && kc.bestApex() * 10 >= kc.apexTenths() - 1,
                    "spots " + kc.spotCount() + ", apex " + kc.apexTenths() / 10.0 + " (best " + Harness.fmt("%.2f",
                            kc.bestApex()) + "), mace " + kc.damageTenths() / 10.0 + ", HUD " + hud + ", aim "
                            + H.mc().hitResult.getType());
            launch[0] = kc.apexTenths() / 10.0;
        });
        s.cmd("item replace entity @s weapon.mainhand with minecraft:stick");
        s.waitTicks(3);
        s.run("no charge result", () -> {
            s.check("kinetic chain: nothing without a wind charge in hand", !kc.wantsDraw() && kc.apexTenths() < 0
                    && kc.spotCount() == 0, "wantsDraw=" + kc.wantsDraw() + " apex=" + kc.apexTenths() + " spots="
                    + kc.spotCount());
            Harness.command("item replace entity @s weapon.mainhand with minecraft:wind_charge 8");
        });
        s.waitTicks(6);
        s.run("throw the charge at the feet", () -> {
            launch[0] = kc.apexTenths() / 10.0;
            launch[1] = H.p().getY();
            launch[2] = H.p().getY();
            ControlLease l = takeLease("playtest_thrower", ControlChannel.WORLD_INTERACT);
            Services.input().scheduleKeyTap(l, SimKey.USE, 1, 1);
        });
        s.step("launch flight", t -> {
            launch[2] = Math.max(launch[2], H.p().getY());
            if (!H.p().onGround()) {
                launch[3] = 1;
            }
            return (launch[3] > 0 && H.p().onGround() && t > 5) || t > 100;
        });
        s.run("launch result", () -> {
            closeLease();
            double rise = launch[2] - launch[1];
            s.check("kinetic chain: predicted apex matches a real wind-charge launch from the floor",
                    launch[3] > 0 && launch[0] > 0 && Math.abs(rise - launch[0]) <= Math.max(0.6, 0.2 * rise),
                    Harness.fmt("predicted %.1f, measured %.2f (start y %.2f, max %.2f), charges left %d", launch[0],
                            rise, launch[1], launch[2], H.count("minecraft:wind_charge")));
            H.enable(KineticChainModule.ID, false);
        });
        // the mace cues: a 13-block fall with the mace onto an idle zombie, looking down at it
        s.cmd("item replace entity @s weapon.mainhand with minecraft:mace");
        s.cmd(() -> zombie(-4.5, 0.5, false, 20));
        s.waitFor("mace zombie visible", 60, () -> CombatScenario.nearestZombie() != null);
        s.run("mace cues on", () -> {
            H.enable(HoloAudioCues.ID, true);
            n[0] = cues.played(CueScheduler.Cue.MACE_RISE);
            n[1] = cues.played(CueScheduler.Cue.MACE_LETHAL);
        });
        s.cmd(() -> tpLook(-4.5, 14, 0.5, 0f, 90f));
        s.waitUpTo("mace fall", 80, () -> H.p().onGround() && H.p().getY() < Arena.FLOOR + 4);
        s.run("mace cue result", () -> {
            s.check("holo cues: rising tone during a mace fall and the lethal chime over the target",
                    cues.played(CueScheduler.Cue.MACE_RISE) > n[0] && cues.played(CueScheduler.Cue.MACE_LETHAL) > n[1],
                    "rise " + n[0] + " -> " + cues.played(CueScheduler.Cue.MACE_RISE) + ", lethal " + n[1] + " -> "
                            + cues.played(CueScheduler.Cue.MACE_LETHAL) + ", bit/chime sounds "
                            + WorldProbe.sounds("block.note_block.bit") + "/"
                            + WorldProbe.sounds("block.note_block.chime") + ", landed at " + H.posStr());
            H.enable(HoloAudioCues.ID, false);
        });
        reset(s);
    }

    // ---- 5. Ghost Line ---------------------------------------------------------------------------------------------

    /** Progress along the L route (-8,6) -> (6,6) -> (6,-2) (arena-relative cell centres), and distance off it. */
    private static double[] onRoute(double x, double z) {
        double rx = x - Arena.cx;
        double rz = z - Arena.cz;
        // leg 1: z = 6.5, x from -7.5 to 6.5; leg 2: x = 6.5, z from 6.5 to -1.5
        double t1 = Math.max(-7.5, Math.min(6.5, rx));
        double d1 = Math.hypot(rx - t1, rz - 6.5);
        double t2 = Math.max(-1.5, Math.min(6.5, rz));
        double d2 = Math.hypot(rx - 6.5, rz - t2);
        return d1 <= d2 ? new double[] {t1 + 7.5, d1} : new double[] {14 + (6.5 - t2), d2};
    }

    private static void ghostLine(Scenario s) {
        GhostLineModule gm = H.module(GhostLineModule.ID);
        GhostLine g = gm.ghost();
        // route: samples, ahead samples, on-route samples; jump: JUMP-mode samples, GOOD samples, MISS, airborne
        int[] r = new int[8];
        // route: max distance off the route, max lead, min lead, player progress at the min lead
        double[] dev = {0, 0, 99, 0};
        s.cmd(() -> Arena.tp(-7.5, 6.5, -90f));
        s.waitTicks(10);
        s.run("ghost line on, route run starts", () -> {
            H.enable(GhostLineModule.ID, true);
            WorldProbe.arm();
            long[] cells = new long[23];
            int k = 0;
            for (int x = -8; x <= 6; x++) {
                cells[k++] = BlockPos.asLong(Arena.cx + x, Arena.FLOOR + 1, Arena.cz + 6);
            }
            for (int zc = 5; zc >= -2; zc--) {
                cells[k++] = BlockPos.asLong(Arena.cx + 6, Arena.FLOOR + 1, Arena.cz + zc);
            }
            ControlLease l = takeLease("playtest_route", ControlChannel.MOVEMENT, ControlChannel.CAMERA);
            boolean started = Services.routeRunnerOrNull().follow(l, cells, k, true);
            s.check("ghost line: route run started", started, "state " + Services.routeRunnerOrNull().state());
        });
        s.step("route run", t -> {
            MinecraftRouteRunner rr = (MinecraftRouteRunner) Services.routeRunnerOrNull();
            if (g.mode() == GhostLine.ROUTE) {
                double[] pp = onRoute(H.p().getX(), H.p().getZ());
                double[] gp = onRoute(g.ghostX(), g.ghostZ());
                if (pp[0] < 18) { // the ghost stops at the route's end: judge "ahead" before the last few blocks
                    r[0]++;
                    if (gp[0] - pp[0] >= 2.0) {
                        r[1]++;
                    }
                    if (gp[1] <= 0.6) {
                        r[2]++;
                    }
                    dev[0] = Math.max(dev[0], gp[1]);
                    dev[1] = Math.max(dev[1], gp[0] - pp[0]);
                    if (gp[0] - pp[0] < dev[2]) {
                        dev[2] = gp[0] - pp[0];
                        dev[3] = pp[0];
                    }
                }
            }
            if (t == 30) {
                Shots.take(s, "combatextra-ghost-route");
            }
            IRouteRunner.State st = rr.state();
            return (t > 10 && st != IRouteRunner.State.RUNNING && st != IRouteRunner.State.PLANNING) || t > 20 * 15;
        });
        s.waitTicks(5);
        s.run("route result", () -> {
            double[] pp = onRoute(H.p().getX(), H.p().getZ());
            s.check("ghost line: the ghost runs ahead of the player on the route",
                    r[0] >= 20 && r[1] >= r[0] * 0.85 && r[2] >= r[0] * 0.9 && dev[2] > 0.5
                            && WorldProbe.calls("boxEdges:LINE_STRONG") > 0 && WorldProbe.calls("polyline:ACCENT") > 0,
                    Harness.fmt("%d route samples, %d ahead >= 2.0 along the L (lead %.2f..%.2f, min at progress %.1f),"
                                    + " %d on the route (max off %.2f); runner %s, player at progress %.1f/22 %s; kit %s",
                            r[0], r[1], dev[2], dev[1], dev[3], r[2], dev[0], Services.routeRunnerOrNull().state(),
                            pp[0], H.posStr(), WorldProbe.summary()));
            s.check("ghost line: cleared once the route run ends", g.mode() == GhostLine.NONE,
                    "mode=" + g.mode());
            closeLease();
        });
        // jump mode: sprint along a flat lane with Movement Assist on; then walk at a void edge behind SafeWalk
        s.cmd(() -> Arena.tp(-10.5, 9.5, -90f));
        s.waitTicks(10);
        s.run("movement assist on, sprint", () -> {
            H.setting(MovementAssist.ID, "assist_automation", true);
            H.enable(MovementAssist.ID, true);
            WorldProbe.arm();
            ControlLease l = takeLease("playtest_mover", ControlChannel.MOVEMENT);
            Services.input().setKeyHeld(l, SimKey.FORWARD, true);
            Services.input().setKeyHeld(l, SimKey.SPRINT, true);
        });
        s.step("sprint lane", t -> {
            if (g.mode() == GhostLine.JUMP) {
                r[3]++;
                double ahead = g.ghostX() - H.p().getX();
                if (g.quality() == GhostLine.GOOD && ahead > 1.5 && ahead < 7 && Math.abs(g.ghostZ() - H.p().getZ()) < 1) {
                    r[4]++;
                }
            }
            return t >= 24 || H.p().getX() > Arena.cx + 6;
        });
        s.run("jump mode result", () -> {
            closeLease();
            s.check("ghost line: jump landing ahead on a flat run, GOOD mark",
                    r[3] >= 8 && r[4] >= r[3] * 0.7 && WorldProbe.calls("groundMark:ACCENT") > 0,
                    r[3] + " jump-mode samples, " + r[4] + " GOOD and ahead in the lane; kit " + WorldProbe.summary());
        });
        // a fall that would kill at this health: a 13-deep pit (x -11..-7, z -11..-7) walked into at a few HP (effects
        // cleared, so no saturation healing). Resistance keeps the real landing harmless; the prediction only knows the
        // health, so for it the landing is lethal.
        s.waitTicks(15);
        s.cmd(() -> Arena.fill(-11, -13, -11, -7, -13, -7, "stone"));
        s.cmd(() -> Arena.fill(-11, -12, -11, -7, 0, -7, "air"));
        s.cmd(() -> Arena.tp(-9.5, -4.5, 180f));
        s.cmd("effect clear @s");
        s.cmd("damage @s 18 minecraft:generic");
        s.cmd("effect give @s minecraft:resistance 600 4 true");
        s.waitTicks(10);
        s.run("walk into the pit at low health", () -> {
            s.note(Harness.fmt("health before the pit walk %.1f", H.p().getHealth()));
            WorldProbe.arm();
            ControlLease l = takeLease("playtest_mover", ControlChannel.MOVEMENT);
            Services.input().setKeyHeld(l, SimKey.FORWARD, true);
        });
        s.step("fall into the pit", t -> {
            if (!H.p().onGround() && g.mode() == GhostLine.JUMP && g.quality() == GhostLine.MISS) {
                r[5]++;
            }
            if (!H.p().onGround()) {
                r[6]++;
            }
            return (H.p().onGround() && H.p().getY() < Arena.FLOOR - 4) || t >= 80;
        });
        s.run("miss result", () -> {
            closeLease();
            s.check("ghost line: a fall that would kill is marked MISS (DANGER) while airborne",
                    r[6] > 0 && r[5] * 2 >= r[6] && WorldProbe.calls("groundMark:DANGER") > 0,
                    r[5] + " MISS samples of " + r[6] + " airborne ticks, player " + H.posStr() + Harness.fmt(
                            " hp %.1f; kit ", H.p().getHealth()) + WorldProbe.summary());
            WorldProbe.disarm();
            H.enable(MovementAssist.ID, false);
            H.setting(MovementAssist.ID, "assist_automation", false);
            H.enable(GhostLineModule.ID, false);
            Harness.command(Arena.tp(0.5, 0.5, 0f)); // out of the pit before it is filled back in
            restoreArena();
        });
        reset(s);
    }

    // ---- 6. Runner's Vision ----------------------------------------------------------------------------------------

    private static void runnersVision(Scenario s) {
        RunnersVisionModule rm = H.module(RunnersVisionModule.ID);
        RunnersVision v = rm.vision();
        // best line seen: points, jumps, end, last x, points past the gap, air points over the gap
        double[] best = new double[6];
        int[] samples = new int[1];
        // a 2-wide, 5-deep pit across the lane at x -2..-1 and a 3-high wall at x = 8 (lane z 1..5)
        s.cmd(() -> Arena.fill(-2, -5, 1, -1, -5, 5, "stone"));
        s.cmd(() -> Arena.fill(-2, -4, 1, -1, 0, 5, "air"));
        s.cmd(() -> Arena.fill(8, 1, 1, 8, 3, 5, "stone"));
        s.cmd(() -> Arena.tp(-11.5, 3.5, -90f));
        s.waitTicks(10);
        s.run("runner's vision on, sprint", () -> {
            H.enable(RunnersVisionModule.ID, true);
            WorldProbe.arm();
            ControlLease l = takeLease("playtest_mover", ControlChannel.MOVEMENT);
            Services.input().setKeyHeld(l, SimKey.FORWARD, true);
            Services.input().setKeyHeld(l, SimKey.SPRINT, true);
        });
        s.step("sprint toward the pit", t -> {
            if (H.p().isSprinting() && v.count() > 1) {
                samples[0]++;
                if (v.count() >= best[0]) {
                    int past = 0;
                    int air = 0;
                    for (int i = 0; i < v.count(); i++) {
                        double rx = v.x(i) - Arena.cx;
                        if (rx > 0.5) {
                            past++;
                        }
                        if (rx > -2 && rx < -1 && v.air(i)) {
                            air++;
                        }
                    }
                    best[0] = v.count();
                    best[1] = v.jumps();
                    best[2] = v.end();
                    best[3] = v.x(v.count() - 1) - Arena.cx;
                    best[4] = past;
                    best[5] = air;
                }
            }
            if (t == 14) {
                Shots.take(s, "combatextra-runners-vision");
            }
            return H.p().getX() > Arena.cx - 7 || t > 60;
        });
        s.run("release keys", CombatExtraScenario::closeLease);
        s.waitTicks(15);
        s.run("runner's vision result", () -> {
            s.check("runner's vision: the line jumps the pit and stops at the wall",
                    samples[0] > 3 && best[1] >= 1 && best[2] == RunnersVision.END_STOP && best[3] > 5.5
                            && best[3] < 8.0 && best[4] > 0 && best[5] > 0,
                    Harness.fmt("%d samples; best line %.0f points, %.0f jumps, end %s, last x=cx%+.2f, %.0f points"
                                    + " past the pit, %.0f airborne over it; player %s", samples[0], best[0], best[1],
                            best[2] == RunnersVision.END_STOP ? "STOP" : "RANGE", best[3], best[4], best[5],
                            H.posStr()));
            s.check("runner's vision: drawn as glow + flow with the stop mark",
                    WorldProbe.calls("polyline:ACCENT_GLOW") > 0 && WorldProbe.calls("polyline:ACCENT") > 0
                            && WorldProbe.calls("groundMark:WARN") > 0, "kit " + WorldProbe.summary());
            WorldProbe.arm();
        });
        s.waitTicks(6);
        s.run("not sprinting result", () -> {
            s.check("runner's vision: no line while not sprinting", v.count() == 0
                    && WorldProbe.calls("polyline") == 0, "points " + v.count() + ", sprinting " + H.p().isSprinting()
                    + "; kit " + WorldProbe.summary());
            WorldProbe.disarm();
            H.enable(RunnersVisionModule.ID, false);
            restoreArena();
        });
        reset(s);
    }

    // ---- 7. Crop Work ----------------------------------------------------------------------------------------------

    private static final int[] RIPE_WHEAT = {-7, -6, -3};

    private static int age(int dx) {
        BlockState st = H.block(Arena.cx + dx, Arena.FLOOR + 1, Arena.cz - 1);
        return st.getBlock() instanceof CropBlock c ? c.getAge(st) : -1;
    }

    private static String row() {
        StringBuilder sb = new StringBuilder();
        for (int dx = -7; dx <= -3; dx++) {
            sb.append(H.blockId(Arena.cx + dx, Arena.FLOOR + 1, Arena.cz - 1).replace("minecraft:", "")).append('/')
                    .append(age(dx)).append(' ');
        }
        return sb.toString().trim();
    }

    private static void crops(Scenario s) {
        int[] hoeSlot = {-1};
        boolean[] leased = new boolean[1];
        int[] wheatBefore = new int[1];
        // water row (z-2, stone under it), farmland row (z-1), crops on the farmland
        s.cmd(() -> Arena.fill(-7, -1, -2, -3, -1, -2, "stone"));
        s.cmd(() -> Arena.fill(-7, 0, -2, -3, 0, -2, "water"));
        s.cmd(() -> Arena.fill(-7, 0, -1, -3, 0, -1, "farmland"));
        s.cmd(() -> Arena.fill(-7, 1, -1, -6, 1, -1, "wheat[age=7]"));
        s.cmd(() -> Arena.fill(-5, 1, -1, -5, 1, -1, "wheat[age=2]"));
        s.cmd(() -> Arena.fill(-4, 1, -1, -4, 1, -1, "carrots[age=7]"));
        s.cmd(() -> Arena.fill(-3, 1, -1, -3, 1, -1, "wheat[age=7]"));
        s.cmd("item replace entity @s weapon.mainhand with minecraft:iron_hoe");
        s.cmd("item replace entity @s inventory.4 with minecraft:wheat_seeds 8");
        s.cmd(() -> Arena.tp(-4.5, 0.5, 180f));
        s.waitTicks(10);
        s.run("crop work on", () -> {
            hoeSlot[0] = H.p().getInventory().selected;
            wheatBefore[0] = H.count("minecraft:wheat");
            s.check("crop farm planted, hoe in hand, seeds only in the main inventory",
                    age(-7) == 7 && age(-5) == 2 && H.p().getMainHandItem().is(Items.IRON_HOE)
                            && H.count("minecraft:wheat_seeds") == 8,
                    "row " + row() + ", hand " + H.p().getMainHandItem() + ", seeds " + H.count("minecraft:wheat_seeds"));
            H.enable(CropWorkModule.ID, true);
        });
        s.step("harvest", t -> {
            for (ControlChannel c : ControlChannel.all()) {
                leased[0] |= Services.arbiter().isHeldBy(c, CropWorkModule.ID);
            }
            if (t == 40) {
                Shots.take(s, "combatextra-crops");
            }
            boolean done = true;
            for (int dx : RIPE_WHEAT) {
                int a = age(dx);
                done &= a >= 0 && a < 7;
            }
            return (done && t > 20) || t > 20 * 25;
        });
        s.waitTicks(30);
        s.run("harvest result", () -> {
            StringBuilder replanted = new StringBuilder();
            boolean all = true;
            for (int dx : RIPE_WHEAT) {
                int a = age(dx);
                all &= a >= 0 && a < 7;
                replanted.append(dx).append('=').append(a).append(' ');
            }
            s.check("crop work: ripe wheat harvested and replanted from the inventory", all,
                    "row " + row() + " (replanted ages " + replanted.toString().trim() + "), wheat items "
                            + wheatBefore[0] + " -> " + H.count("minecraft:wheat") + " in the inventory, left standing"
                            + " for hidden soil " + ((CropWorkModule) H.module(CropWorkModule.ID)).hiddenSkips());
            s.check("crop work: young wheat and an unreplantable crop left standing",
                    age(-5) >= 2 && age(-5) < 7 && "minecraft:carrots".equals(H.blockId(Arena.cx - 4,
                            Arena.FLOOR + 1, Arena.cz - 1)) && age(-4) == 7, "row " + row());
            boolean holdsHoe = H.p().getInventory().selected == hoeSlot[0]
                    && H.p().getMainHandItem().is(Items.IRON_HOE);
            boolean released = true;
            for (ControlChannel c : ControlChannel.all()) {
                released &= !Services.arbiter().isHeldBy(c, CropWorkModule.ID);
            }
            s.check("crop work: worked through its leases, hoe back in hand, leases released",
                    leased[0] && holdsHoe && released, "leased=" + leased[0] + " selected=" + H.p().getInventory()
                            .selected + " (hoe slot " + hoeSlot[0] + ") hand=" + H.p().getMainHandItem()
                            + " released=" + released + " seeds left " + H.count("minecraft:wheat_seeds") + "; "
                            + H.arbiterState());
            H.enable(CropWorkModule.ID, false);
            restoreArena();
            s.note("modules on after the crop section: " + (on(CropWorkModule.ID) ? "crop_work still on" : "none"));
        });
        reset(s);
    }
}
