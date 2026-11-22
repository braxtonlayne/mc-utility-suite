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
        H.setting(CombatAssistant.ID, CombatSettings.ENGAGE_RANGE, 24.0);
        H.setting(CombatAssistant.ID, CombatSettings.DUEL_MODE, true);
        H.enable(AutoRespawn.ID, true);
        configured = true;
        PlaytestMod.LOG.info("[arena-client] configured: combat assistant proactive on players, duel mode, auto"
                + " respawn; server {}", mc.getCurrentServer() == null ? "?" : mc.getCurrentServer().ip);
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
                    "[swing] kind=%s policy=%s ratio=%.2f gnd=%b fall=%.2f vy=%.2f sprint=%b str=%.2f W=%b",
                    ca.hitPlanner().kind(), ca.lastHitPolicy(), ca.gearRatio(), p.onGround(), p.fallDistance,
                    p.getDeltaMovement().y, p.isSprinting(), p.getAttackStrengthScale(0f),
                    mc.options.keyUp.isDown()));
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
                        + " blk=%b use=%b gnd=%b | %s broad=%s tac=%s sw+%d keys=%s%s%s%s%s%s note=%s",
                Math.sqrt(p.distanceToSqr(t)), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getHealth(),
                p.getFoodData().getFoodLevel(), p.getAttackStrengthScale(0f), p.onGround(), t.getX(), t.getZ(),
                t.getYHeadRot(), thp, blk, using, t.onGround(), ca.state(), ca.broadTactic(), ca.tactic(),
                sw - lastSwings, mc.options.keyUp.isDown() ? "W" : "", mc.options.keyDown.isDown() ? "S" : "",
                mc.options.keyLeft.isDown() ? "A" : "", mc.options.keyRight.isDown() ? "D" : "",
                mc.options.keySprint.isDown() ? "^" : "", mc.options.keyUse.isDown() ? "U" : "", ca.lastNote()));
        lastSwings = sw;
    }

    private void onChat(ClientChatReceivedEvent e) {
        String s = e.getMessage().getString();
        if (s.startsWith("[arena]") || s.contains(" was slain by ")) {
            PlaytestMod.LOG.info("[arena-client] {}", s);
        }
    }
}
