package com.utilitysuite.devtest;

import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.core.ServerAllowList;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.tools.player.AutoRespawn;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev-only client side of the local bot benchmark arena ({@code ./gradlew runArena}, inert unless
 * {@code -Dutilitysuite.arena=true}). The run quick-plays into the arena server (tools/bot-arena, 127.0.0.1:25570);
 * once in the world this sets the arena profile the way a player would in the menu: Combat Assistant armed,
 * Proactive engagement of players, duel mode (never disengage), Auto-Respawn on. Everything else stays at the
 * defaults, so the benchmark measures the shipped behaviour. The orchestrator (tools/bot-arena/bots/arena.js) resets
 * rounds over RCON and scores them from death messages; this class only logs what it sees.
 */
final class ArenaClient {

    private int readyTicks = -1;
    private boolean configured;

    void register(IEventBus bus) {
        bus.addListener(this::onTick);
        bus.addListener(this::onChat);
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null || mc.hasSingleplayerServer()) {
            readyTicks = -1;
            configured = false;
            return;
        }
        if (configured) {
            gateTelemetry();
            swingTelemetry(mc);
            telemetry(mc);
            return;
        }
        if (readyTicks < 0) {
            readyTicks = 0;
        }
        if (++readyTicks < 40) {
            return; // let the server profile load first
        }
        String allow = Services.modState().profile().getString(ServerAllowList.SOURCE_MODULE_ID,
                ServerAllowList.SETTING_KEY, ServerAllowList.DEFAULT_LIST);
        if (!allow.contains("127.0.0.1")) {
            H.setting(ServerAllowList.SOURCE_MODULE_ID, ServerAllowList.SETTING_KEY, allow + ",127.0.0.1");
        }
        H.enable(CombatAssistant.ID, true);
        H.setting(CombatAssistant.ID, CombatSettings.ENGAGE_MODE, "PROACTIVE");
        H.setting(CombatAssistant.ID, CombatSettings.TARGETS, "PLAYERS");
        // Owner, 2026-11-23: legit play, but free to move. Both fighters are boxed in, so the assistant may run, kite,
        // reset and re-engage anywhere in the box; its goal is the kill. So the legit level stays BALANCED (the
        // defaults; applied explicitly so nothing left over loosens it), engage and disengage reach cover the whole
        // 41x41 field, duel mode keeps it from giving the fight up, and jump resets (a vanilla technique) are on.
        applyLevel(com.utilitysuite.core.LegitPresets.Level.BALANCED);
        H.setting(CombatAssistant.ID, CombatSettings.DISENGAGE_RADIUS, 64.0);
        H.setting(CombatAssistant.ID, CombatSettings.ENGAGE_RANGE, 60.0);
        H.setting(CombatAssistant.ID, CombatSettings.JUMP_RESET, true);
        H.setting(CombatAssistant.ID, CombatSettings.DUEL_MODE, true);
        // experiments only: -Dutilitysuite.arena.aimSpeed=1.875 (240 deg/s x 1.875 = the 450 deg/s hard cap)
        String aimSpeed = System.getProperty("utilitysuite.arena.aimSpeed");
        if (aimSpeed != null) {
            H.setting(CombatAssistant.ID, CombatSettings.AIM_SPEED, Double.parseDouble(aimSpeed));
            PlaytestMod.LOG.info("[arena-client] experiment: aim speed x{}", aimSpeed);
        }
        // experiments only: -Dutilitysuite.arena.settings=velocity_lead=false,lead_ticks=1 (combat assistant keys)
        String extra = System.getProperty("utilitysuite.arena.settings");
        if (extra != null && !extra.isBlank()) {
            for (String kv : extra.split(",")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String k = kv.substring(0, eq).trim();
                String v = kv.substring(eq + 1).trim();
                Object val = v.equals("true") || v.equals("false") ? (Object) Boolean.parseBoolean(v)
                        : v.matches("-?[0-9.]+") ? (Object) Double.parseDouble(v) : v;
                H.setting(CombatAssistant.ID, k, val);
                PlaytestMod.LOG.info("[arena-client] experiment: {} = {}", k, val);
            }
        }
        H.enable(AutoRespawn.ID, true);
        configured = true;
        PlaytestMod.LOG.info("[arena-client] configured: combat assistant proactive on players, duel mode, auto"
                + " respawn; server {}", mc.getCurrentServer() == null ? "?" : mc.getCurrentServer().ip);
    }

    /** A legit level, applied the way the menu applies it (knobs written, modules notified, core reloaded). */
    private static void applyLevel(com.utilitysuite.core.LegitPresets.Level level) {
        var ms = Services.modState();
        boolean core = false;
        for (var k : com.utilitysuite.core.LegitPresets.apply(level, ms.profile(), ms.global())) {
            if (k.global()) {
                core = true;
                continue;
            }
            Services.modules().byId(k.moduleId()).ifPresent(m -> {
                if (m instanceof com.utilitysuite.core.api.IConfigurable c) {
                    c.onSettingChanged(k.key());
                }
            });
        }
        if (core) {
            com.utilitysuite.core.CoreBootstrap.applyCoreSettings();
        }
    }

    /**
     * Swing-gate diagnostics: one line per tick in which the attack is charged and the target is within 3.6 blocks
     * (eye to box) but no swing went out, naming each gate. Counts ready-but-held ticks against swings for the
     * per-round summary ("why does it hold its swings at close range").
     */
    private int heldTicks;
    private int gateSwingSeen;
    private final float[] aimNow = new float[2];

    private void gateTelemetry() {
        CombatAssistant ca = com.utilitysuite.combat.CombatBootstrap.assistant();
        if (ca == null || ca.targetId() < 0) {
            return;
        }
        var s = ca.snapshotForDiagnostics();
        boolean swung = ca.swings() != gateSwingSeen;
        gateSwingSeen = ca.swings();
        double reach = Math.sqrt(s.reachDistanceSq());
        if (swung || s.attackStrength < 0.95f || reach > 3.6) {
            return;
        }
        heldTicks++;
        var hp = ca.hitPlanner();
        // aim error against the target's centre now (no lead), and the box's half-width in degrees at this range
        com.utilitysuite.combat.assistant.AimSolver.solve(s, 0.0, aimNow);
        double yawErr = com.utilitysuite.combat.assistant.AimSolver.yawDelta(aimNow[0], s.yaw);
        double halfDeg = Math.toDegrees(Math.atan2(0.3, Math.max(0.5, Math.sqrt(s.distanceSq()))));
        PlaytestMod.LOG.info(String.format(java.util.Locale.ROOT,
                "[gate] reach=%.2f inReach=%b xhair=%b yawErr=%.1f pitchErr=%.1f half=%.1f hurt=%d str=%.2f kind=%s gnd=%b"
                        + " sprint=%b blk=%b broad=%s held=%d",
                reach, s.inReach(), s.crosshairOnTarget, yawErr, aimNow[1] - s.pitch, halfDeg, s.targetHurtTime,
                s.attackStrength, hp.kind(), s.onGround, s.sprinting, s.targetBlocking, ca.broadTactic(), heldTicks));
    }

    private int swingSeen;

    /** One line per swing: the planned hit kind and the crit conditions on the tick it was sent. */
    private void swingTelemetry(Minecraft mc) {
        CombatAssistant ca = com.utilitysuite.combat.CombatBootstrap.assistant();
        if (ca == null || mc.player == null) {
            return;
        }
        int sw = ca.swings();
        if (sw != swingSeen) {
            swingSeen = sw;
            net.minecraft.client.player.LocalPlayer p = mc.player;
            PlaytestMod.LOG.info(String.format(java.util.Locale.ROOT,
                    "[swing] kind=%s policy=%s ratio=%.2f gnd=%b fall=%.2f vy=%.2f sprint=%b str=%.2f W=%b appr=%d close=%.2f",
                    ca.hitPlanner().kind(), ca.lastHitPolicy(), ca.gearRatio(), p.onGround(), p.fallDistance,
                    p.getDeltaMovement().y, p.isSprinting(), p.getAttackStrengthScale(0f),
                    mc.options.keyUp.isDown(), ca.hitPlanner().approachHops(), ca.hitPlanner().closing()));
        }
    }

    private int teleTick;
    private int lastSwings;

    /** One line every 10 ticks while a fight runs: what the assistant sees and does (dev diagnostics only). */
    private void telemetry(Minecraft mc) {
        if (++teleTick % 10 != 0) {
            return;
        }
        CombatAssistant ca = com.utilitysuite.combat.CombatBootstrap.assistant();
        if (ca == null || ca.targetId() < 0 || mc.level == null) {
            return;
        }
        net.minecraft.world.entity.Entity t = mc.level.getEntity(ca.targetId());
        net.minecraft.client.player.LocalPlayer p = mc.player;
        if (t == null) {
            return;
        }
        boolean blk = t instanceof net.minecraft.world.entity.LivingEntity le && le.isBlocking();
        boolean using = t instanceof net.minecraft.world.entity.LivingEntity le2 && le2.isUsingItem();
        float thp = t instanceof net.minecraft.world.entity.LivingEntity le3 ? le3.getHealth() : -1f;
        int sw = ca.swings();
        PlaytestMod.LOG.info(String.format(java.util.Locale.ROOT,
                "[tele] d=%.2f me=(%.1f,%.1f,%.1f) yaw=%.0f hp=%.1f food=%d str=%.2f gnd=%b | opp=(%.1f,%.1f) hy=%.0f hp=%.1f"
                        + " blk=%b use=%b gnd=%b | %s broad=%s tac=%s sw+%d keys=%s%s%s%s%s%s note=%s | %s",
                Math.sqrt(p.distanceToSqr(t)), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getHealth(),
                p.getFoodData().getFoodLevel(), p.getAttackStrengthScale(0f), p.onGround(), t.getX(), t.getZ(),
                t.getYHeadRot(), thp, blk, using, t.onGround(), ca.state(), ca.broadTactic(), ca.tactic(),
                sw - lastSwings, mc.options.keyUp.isDown() ? "W" : "", mc.options.keyDown.isDown() ? "S" : "",
                mc.options.keyLeft.isDown() ? "A" : "", mc.options.keyRight.isDown() ? "D" : "",
                mc.options.keySprint.isDown() ? "^" : "", mc.options.keyUse.isDown() ? "U" : "", ca.lastNote(), H.arbiterState()));
        lastSwings = sw;
    }

    private void onChat(ClientChatReceivedEvent e) {
        String s = e.getMessage().getString();
        if (s.startsWith("[arena]") || s.contains(" was slain by ")) {
            PlaytestMod.LOG.info("[arena-client] {}", s);
        }
    }
}
