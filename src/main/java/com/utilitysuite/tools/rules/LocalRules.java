package com.utilitysuite.tools.rules;

import com.google.gson.JsonObject;
import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ParseResult;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.tools.inventory.SmartInventory;
import com.utilitysuite.tools.player.QuickPearl;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Local Rules: a small per-profile rule engine (condition → action), evaluated at most every {@code eval_interval}
 * ticks. Rules are stored in the active profile under {@code local_rules.rules}. Actions go through the goal engine,
 * the module registry and SmartInventory; nothing here touches input directly except the deposit run's lease-based
 * container opening.
 */
public final class LocalRules implements IModule, IConfigurable, RuleContext {

    public static final String ID = "local_rules";
    public static final String K_INTERVAL = "eval_interval_ticks";
    public static final String K_RULES = "rules";

    private final RuleEngine engine = new RuleEngine();
    private final List<Rule> rules = new ArrayList<>();
    private final DepositRun deposit;
    /** Cached so the per-tick call allocates no method reference. */
    private final java.util.function.Consumer<Rule> fireRef = this::fire;
    private final Map<String, Item> itemCache = new HashMap<>();
    private boolean enabled = true;
    private String lastMessage = "";
    private int nextId = 1;
    private final Supplier<QuickPearl> quickPearl;
    /** Level game time of the local player's last totem pop, or {@link Long#MIN_VALUE} for none this world. */
    private long lastTotemPopGameTime = Long.MIN_VALUE;

    public LocalRules(Supplier<SmartInventory> inventory, Supplier<QuickPearl> quickPearl) {
        this.deposit = new DepositRun(inventory, this::message);
        this.quickPearl = quickPearl;
    }

    /** Called from the totem-pop packet hook when the local player's totem was used. Client thread. */
    public void onLocalTotemPop() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        lastTotemPopGameTime = mc.level.getGameTime();
        engine.evaluateNextTick(); // react on the next tick, not at the end of the evaluation interval
    }

    public void subscribe(ISuiteEventBus bus) {
        bus.subscribe(SuiteEvent.JobChanged.class, deposit::onJobChanged);
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
        this.enabled = enabled;
        if (!enabled) {
            deposit.abort(null);
        }
    }

    @Override
    public void onTick() {
        if (Minecraft.getInstance().player == null) {
            return;
        }
        deposit.tick();
        if (Services.arbiter().isAutomationStopped()) {
            return;
        }
        engine.tick(this, fireRef);
    }

    @Override
    public void onStopAll() {
        deposit.abort("Deposit run stopped (Stop-All)");
    }

    @Override
    public void onWorldUnload() {
        deposit.abort(null);
        itemCache.clear();
        lastTotemPopGameTime = Long.MIN_VALUE;
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        engine.setInterval(profile.getInt(ID, K_INTERVAL, 20));
        rules.clear();
        rules.addAll(RuleCodec.fromJson(profile.module(ID).get(K_RULES)));
        nextId = 1;
        for (Rule r : rules) {
            if (r.id().startsWith("r")) {
                try {
                    nextId = Math.max(nextId, Integer.parseInt(r.id().substring(1)) + 1);
                } catch (NumberFormatException ignored) {
                    // custom id
                }
            }
        }
        engine.setRules(rules);
    }

    // ---- rule management (UI) ---------------------------------------------------------------------------------------

    public List<Rule> rules() {
        return List.copyOf(rules);
    }

    public String newRuleId() {
        return "r" + nextId++;
    }

    public void addRule(Rule rule) {
        rules.add(rule);
        save();
    }

    /**
     * Replaces the rule with {@code id} by {@code edited}'s condition, action and cooldown, keeping id, enabled flag
     * and list position.
     *
     * @return false when no rule has that id
     */
    public boolean replaceRule(String id, Rule edited) {
        List<Rule> next = RuleEdits.replace(rules, id, edited);
        if (next == null) {
            return false;
        }
        rules.clear();
        rules.addAll(next);
        save();
        return true;
    }

    public void removeRule(String id) {
        rules.removeIf(r -> r.id().equals(id));
        save();
    }

    public void setRuleEnabled(String id, boolean value) {
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id().equals(id)) {
                rules.set(i, rules.get(i).withEnabled(value));
            }
        }
        save();
    }

    private void save() {
        JsonObject o = Services.modState().profile().module(ID);
        o.add(K_RULES, RuleCodec.toJson(rules));
        Services.modState().markDirty();
        engine.setRules(rules);
    }

    public String lastMessage() {
        return lastMessage;
    }

    public boolean depositRunning() {
        return deposit.isRunning();
    }

    // ---- actions ----------------------------------------------------------------------------------------------------

    private void fire(Rule rule) {
        IGoalEngine goals = Services.goalsOrNull();
        Rule.Action a = rule.action();
        switch (a.type()) {
            case SUBMIT_GOAL -> {
                if (goals == null) {
                    message("Rule: goal engine unavailable");
                    return;
                }
                ParseResult pr = goals.parse(a.arg());
                if (!pr.isOk()) {
                    message("Rule '" + a.arg() + "': " + pr.error());
                    return;
                }
                goals.submit(pr.request());
                message("Rule fired: " + rule.describe());
            }
            case DEPOSIT_AT -> {
                if (deposit.isRunning()) {
                    return;
                }
                message("Rule fired: " + rule.describe());
                deposit.start(a.arg(), a.flag());
            }
            case PAUSE_JOB, RESUME_JOB -> {
                Optional<JobSnapshot> job = goals == null ? Optional.empty() : goals.activeJob();
                if (job.isEmpty()) {
                    return;
                }
                if (a.type() == Rule.ActionType.PAUSE_JOB) {
                    goals.pause(job.get().jobId());
                } else {
                    goals.resume(job.get().jobId());
                }
                message("Rule fired: " + rule.describe());
            }
            case TOGGLE_MODULE -> {
                if (CoreBootstrap.modules().setModuleEnabled(a.arg(), a.flag(), Services.modState().profile())) {
                    Services.modState().markDirty();
                    message("Rule fired: " + rule.describe());
                } else {
                    message("Rule: unknown module '" + a.arg() + "'");
                }
            }
            case STOP_JOB -> {
                Optional<JobSnapshot> job = goals == null ? Optional.empty() : goals.activeJob();
                if (job.isEmpty()) {
                    return;
                }
                goals.cancel(job.get().jobId());
                deposit.abort(null);
                alert("Rule: " + rule.describe());
            }
            case STOP_ALL -> {
                alert("Rule: " + rule.describe());
                Services.arbiter().stopAll("Local rule: " + rule.condition().describe());
            }
            case QUICK_PEARL -> {
                QuickPearl qp = quickPearl.get();
                if (qp == null || !qp.requestThrow()) {
                    alert("Rule: pearl escape unavailable (" + rule.condition().describe() + ")");
                } else {
                    alert("Rule: " + rule.describe());
                }
            }
            case ALERT -> alert(a.arg());
        }
    }

    private void message(String text) {
        post(com.utilitysuite.core.api.visual.NoticeKind.INFO, text);
    }

    /** Safety actions: a warning notice, so it stands out from routine rule firings. */
    private void alert(String text) {
        post(com.utilitysuite.core.api.visual.NoticeKind.WARN, text);
    }

    private void post(com.utilitysuite.core.api.visual.NoticeKind kind, String text) {
        lastMessage = text;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(kind, text, null);
        }
    }

    // ---- RuleContext (Minecraft adapter) ----------------------------------------------------------------------------

    @Override
    public double inventoryFullPct() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return 0;
        }
        Inventory inv = p.getInventory();
        int used = 0;
        for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
            if (!inv.getNonEquipmentItems().get(i).isEmpty()) {
                used++;
            }
        }
        return used * 100.0 / inv.getNonEquipmentItems().size();
    }

    @Override
    public double health() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 0 : p.getHealth();
    }

    @Override
    public double hunger() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 0 : p.getFoodData().getFoodLevel();
    }

    @Override
    public long timeOfDay() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? 0 : Math.floorMod(mc.level.getOverworldClockTime(), 24000L); // was getDayTime()
    }

    @Override
    public String jobStatus() {
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            return "NONE";
        }
        Optional<JobSnapshot> j = goals.activeJob();
        return j.isEmpty() ? "NONE" : j.get().status().name();
    }

    @Override
    public int itemCount(String itemId) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return 0;
        }
        Item item = itemCache.computeIfAbsent(itemId, LocalRules::resolve);
        if (item == Items.AIR) {
            return 0;
        }
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (st.getItem() == item) {
                n += st.getCount();
            }
        }
        return n;
    }

    @Override
    public int mainHandDurability() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return -1;
        }
        ItemStack st = p.getMainHandItem();
        return st.isDamageableItem() ? st.getMaxDamage() - st.getDamageValue() : -1;
    }

    @Override
    public int mainHandMaxDurability() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return 0;
        }
        ItemStack st = p.getMainHandItem();
        return st.isDamageableItem() ? st.getMaxDamage() : 0;
    }

    /** Loaded players only (entity tracking range); allies from {@link Services#allies()} are skipped. */
    @Override
    public double strangerDistance() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        if (self == null || mc.level == null) {
            return Double.POSITIVE_INFINITY;
        }
        double best = Double.POSITIVE_INFINITY;
        List<? extends Player> players = mc.level.players();
        for (int i = 0; i < players.size(); i++) {
            Player other = players.get(i);
            if (other == self || other.isSpectator() || !other.isAlive()
                    || Services.allies().isAlly(other.getUUID())) {
                continue;
            }
            double d = self.distanceToSqr(other);
            if (d < best) {
                best = d;
            }
        }
        return Double.isInfinite(best) ? best : Math.sqrt(best);
    }

    @Override
    public double lowestArmorDurabilityPct() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return -1;
        }
        double low = -1;
        for (int i = 0; i < ARMOR_SLOTS.length; i++) {
            ItemStack st = p.getItemBySlot(ARMOR_SLOTS[i]);
            if (st.isEmpty() || !st.isDamageableItem() || st.getMaxDamage() <= 0) {
                continue;
            }
            double pct = (st.getMaxDamage() - st.getDamageValue()) * 100.0 / st.getMaxDamage();
            if (low < 0 || pct < low) {
                low = pct;
            }
        }
        return low;
    }

    @Override
    public double secondsSinceTotemPop() {
        Minecraft mc = Minecraft.getInstance();
        if (lastTotemPopGameTime == Long.MIN_VALUE || mc.level == null) {
            return Double.POSITIVE_INFINITY;
        }
        return Math.max(0, mc.level.getGameTime() - lastTotemPopGameTime) / 20.0;
    }

    private static final EquipmentSlot[] ARMOR_SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
            EquipmentSlot.FEET};

    private static Item resolve(String id) {
        Identifier rl = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.getValue(rl);
    }

    // ---- settings ---------------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Local Rules";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(SettingSpec.integer(K_INTERVAL, "Check every (ticks)", 20, 5, 200, "",
                "Rules are evaluated at most this often."));
    }

    @Override
    public void onSettingChanged(String key) {
        engine.setInterval(Services.modState().profile().getInt(ID, K_INTERVAL, 20));
    }
}
