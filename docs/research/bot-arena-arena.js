// Local bot benchmark arena for mc-utility-suite (private, localhost only).
// Runs N rounds of one gear setup: resets both fighters via RCON, gives kits, starts the bot on the
// client player, and scores each round from the death that ends it. The opposing bot never
// disengages: if its attack loop stops while both are alive, a watchdog restarts it at once.
//
// usage: node arena.js --setup fists|mixed|stacked [--rounds 10] [--engine nxg|basic] [--player Dev]
//        [--bot ArenaBot] [--out results.jsonl] [--dry]
"use strict";
const fs = require("fs");
const path = require("path");
const mineflayer = require("mineflayer");
const { Rcon } = require("rcon-client");

const args = parseArgs(process.argv.slice(2));
const SETUP = args.setup || "fists";
const ROUNDS = Number(args.rounds || 10);
const ENGINE = args.engine || "nxg";
const PLAYER = args.player || "Dev";
const BOT = args.bot || "ArenaBot";
const OUT = args.out || path.join(__dirname, "..", "results", `${SETUP}-${ENGINE}.jsonl`);
const HOST = "127.0.0.1";
const PORT = 25570;
const ROUND_TIMEOUT_MS = 90_000;

// Arena: a flat 21x21 stone floor with a glass wall ring, far from spawn. Both start 8 blocks apart.
const AX = 1000, AY = 100, AZ = 1000;
const START_PLAYER = [AX + 0.5, AY + 1, AZ - 3.5];
const START_BOT = [AX + 0.5, AY + 1, AZ + 4.5];

// Gear kits. "ours" is the client player (the side under test), "bot" is the opponent.
// The pass bar is about winning from a DISADVANTAGE, so mixed and stacked favour the bot.
const KITS = {
  fists: { ours: [], bot: [] },
  mixed: {
    ours: ["iron_sword", "chainmail_helmet", "iron_chestplate", "leather_leggings", "chainmail_boots"],
    bot: ["iron_sword", "iron_helmet", "iron_chestplate", "chainmail_leggings", "iron_boots", "shield"],
  },
  stacked: {
    ours: ["stone_sword", "leather_helmet", "leather_chestplate", "leather_leggings", "leather_boots"],
    bot: ["diamond_sword{Enchantments:[{id:sharpness,lvl:2}]}", "diamond_helmet", "diamond_chestplate",
      "diamond_leggings", "diamond_boots", "shield"],
  },
};

function parseArgs(a) {
  const o = {};
  for (let i = 0; i < a.length; i++) {
    if (a[i].startsWith("--")) {
      const k = a[i].slice(2);
      o[k] = a[i + 1] && !a[i + 1].startsWith("--") ? a[++i] : true;
    }
  }
  return o;
}

function rconPassword() {
  const props = fs.readFileSync(path.join(__dirname, "..", "server", "server.properties"), "utf8");
  const m = props.match(/^rcon\.password=(.*)$/m);
  if (!m) throw new Error("rcon.password missing in server.properties");
  return m[1].trim();
}

const SLOT_FOR = (item) => {
  const n = item.split("{")[0];
  if (n.endsWith("_helmet")) return "armor.head";
  if (n.endsWith("_chestplate")) return "armor.chest";
  if (n.endsWith("_leggings")) return "armor.legs";
  if (n.endsWith("_boots")) return "armor.feet";
  if (n === "shield") return "weapon.offhand";
  return "hotbar.0";
};

// 1.21.1 item components: enchantments as [enchantments={levels:{...}}]; convert the short form above.
function itemArg(item) {
  const m = item.match(/^([a-z_]+)\{Enchantments:\[\{id:([a-z_]+),lvl:(\d+)\}\]\}$/);
  if (m) return `minecraft:${m[1]}[enchantments={levels:{"minecraft:${m[2]}":${m[3]}}}]`;
  return `minecraft:${item}`;
}

async function resetRound(rcon) {
  const c = (cmd) => rcon.send(cmd);
  // arena floor + wall ring (idempotent)
  await c(`forceload add ${AX - 12} ${AZ - 12} ${AX + 12} ${AZ + 12}`);
  await c(`fill ${AX - 10} ${AY} ${AZ - 10} ${AX + 10} ${AY} ${AZ + 10} minecraft:stone`);
  await c(`fill ${AX - 10} ${AY + 1} ${AZ - 10} ${AX + 10} ${AY + 6} ${AZ + 10} minecraft:air`);
  await c(`fill ${AX - 11} ${AY + 1} ${AZ - 11} ${AX + 11} ${AY + 4} ${AZ + 11} minecraft:glass hollow`);
  await c(`fill ${AX - 10} ${AY + 1} ${AZ - 10} ${AX + 10} ${AY + 4} ${AZ + 10} minecraft:air`);
  await c(`kill @e[type=item]`);
  await c(`kill @e[type=arrow]`);
  for (const who of [PLAYER, BOT]) {
    await c(`gamemode survival ${who}`);
    await c(`clear ${who}`);
    await c(`effect clear ${who}`);
    await c(`effect give ${who} minecraft:instant_health 1 10 true`);
    await c(`effect give ${who} minecraft:saturation 1 10 true`);
  }
  const kit = KITS[SETUP];
  for (const [who, items] of [[PLAYER, kit.ours], [BOT, kit.bot]]) {
    for (const it of items) await c(`item replace entity ${who} ${SLOT_FOR(it)} with ${itemArg(it)}`);
  }
  await c(`tp ${PLAYER} ${START_PLAYER.join(" ")} 0 0`);
  await c(`tp ${BOT} ${START_BOT.join(" ")} 180 0`);
}

function createArenaBot() {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username: BOT, version: "1.21.1", auth: "offline" });
  bot.once("spawn", () => {
    if (ENGINE === "nxg") {
      const custom = require("@nxg-org/mineflayer-custom-pvp").default;
      const { pathfinder, Movements } = require("mineflayer-pathfinder");
      bot.loadPlugin(pathfinder);
      bot.loadPlugin(custom);
      const moves = new Movements(bot);
      moves.allowSprinting = true;
      moves.allowParkour = true;
      bot.pathfinder.setMovements(moves);
      const o = bot.swordpvp.options;
      // legit, strong settings: hop crits with reaction timing, w-tap, intelligent strafe, legit shield,
      // smoothed legit rotation, full-cooldown swings, no packet crits, no blatant modes, no wall hits
      o.critConfig = { enabled: true, mode: "hop", attemptRange: 2,
        reaction: { enabled: true, maxPreemptiveTicks: 1, maxWaitTicks: 5, maxWaitDistance: 5 } };
      o.rotateConfig = { enabled: true, smooth: true, lookAtHidden: false, mode: "legit" };
      o.shieldConfig = { enabled: true, mode: "legit" };
      o.swingConfig = { mode: "fullswing" };
      o.genericConfig.hitThroughWalls = false;
      o.cps = 12;
    } else {
      const { pathfinder } = require("mineflayer-pathfinder");
      bot.loadPlugin(pathfinder);
      bot.loadPlugin(require("mineflayer-pvp").plugin);
    }
  });
  bot.on("kicked", (r) => console.log("[bot] kicked", r));
  bot.on("error", (e) => console.log("[bot] error", e.message));
  return bot;
}

function startAttack(bot, entity) {
  if (ENGINE === "nxg") bot.swordpvp.attack(entity);
  else bot.pvp.attack(entity);
}

function stopAttack(bot) {
  try {
    if (ENGINE === "nxg") bot.swordpvp.stop();
    else bot.pvp.stop();
  } catch (_) { /* not started */ }
}

function attacking(bot) {
  return ENGINE === "nxg" ? !!bot.swordpvp.target : !!bot.pvp.target;
}

async function equipKit(bot) {
  // armour and the offhand shield were placed by /item replace; hold the hotbar-0 weapon
  try { bot.setQuickBarSlot(0); } catch (_) { /* fine */ }
}

async function runRound(rcon, bot, n) {
  await resetRound(rcon);
  await sleep(1500); // let both bodies settle and the client see the kit
  await equipKit(bot);
  const target = bot.players[PLAYER] && bot.players[PLAYER].entity;
  if (!target) throw new Error(`player ${PLAYER} not visible to the bot`);
  const t0 = Date.now();
  let result = null;
  const onMsg = (msg) => {
    const s = msg.toString();
    if (result) return;
    if (s.startsWith(BOT + " ") && /was slain|died|was killed/.test(s)) result = { winner: "ours", line: s };
    else if (s.startsWith(PLAYER + " ") && /was slain|died|was killed/.test(s)) result = { winner: "bot", line: s };
  };
  bot.on("messagestr", onMsg);
  // go: tell the client (its devtest arena driver listens for this line), then the bot attacks
  await rcon.send(`tellraw ${PLAYER} {"text":"[arena] go ${BOT} round ${n}"}`);
  startAttack(bot, target);
  // watchdog: the opponent may not disengage; restart its attack whenever it stops
  const wd = setInterval(() => {
    if (!result && !attacking(bot)) {
      const t = bot.players[PLAYER] && bot.players[PLAYER].entity;
      if (t) startAttack(bot, t);
    }
  }, 100);
  // health telemetry: both sides every 2 s via RCON (Health is a float NBT tag)
  const hp = { ours: [], bot: [] };
  const dist = [];
  const sampler = setInterval(async () => {
    try {
      for (const [k, who] of [["ours", PLAYER], ["bot", BOT]]) {
        const r = await rcon.send(`data get entity ${who} Health`);
        const m = r.match(/([0-9.]+)f/);
        if (m) hp[k].push(Number(m[1]));
      }
      { const t = bot.players[PLAYER] && bot.players[PLAYER].entity;
        dist.push(t ? Number(bot.entity.position.distanceTo(t.position).toFixed(2)) : null); }
      if (args.trace) {
        const me = bot.entity.position, t = bot.players[PLAYER] && bot.players[PLAYER].entity;
        const d = t ? me.distanceTo(t.position).toFixed(2) : "?";
        console.log(`  t+${((Date.now() - t0) / 1000).toFixed(0)}s dist ${d} botBlocking ${!!bot.usingHeldItem}` +
          ` botHeld ${bot.heldItem ? bot.heldItem.name : "none"} attacking ${attacking(bot)} hp ${hp.ours.at(-1)}/${hp.bot.at(-1)}`);
      }
    } catch (_) { /* best effort */ }
  }, 2000);
  while (!result && Date.now() - t0 < ROUND_TIMEOUT_MS) await sleep(50);
  clearInterval(wd);
  clearInterval(sampler);
  stopAttack(bot);
  bot.removeListener("messagestr", onMsg);
  await rcon.send(`tellraw ${PLAYER} {"text":"[arena] stop"}`);
  const rec = { setup: SETUP, engine: ENGINE, round: n, ms: Date.now() - t0,
    winner: result ? result.winner : "timeout", line: result ? result.line : null,
    minHp: { ours: Math.min(...hp.ours, 20), bot: Math.min(...hp.bot, 20) },
    lastHp: { ours: hp.ours.at(-1) ?? null, bot: hp.bot.at(-1) ?? null }, hpSeries: hp, distSeries: dist };
  // the dead side respawns via its own auto-respawn (bot) or the client's Auto-Respawn
  if (bot.health <= 0) { try { bot.respawn(); } catch (_) { /* */ } }
  await sleep(2500);
  return rec;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const rcon = await Rcon.connect({ host: HOST, port: 25575, password: rconPassword() });
  await rcon.send("gamerule doImmediateRespawn true");
  await rcon.send("gamerule keepInventory false");
  await rcon.send("gamerule naturalRegeneration true");
  await rcon.send("gamerule showDeathMessages true");
  if (args.dry) {
    console.log("rcon ok:", await rcon.send("list"));
    await rcon.end();
    return;
  }
  // a fresh bot connection every round: after deaths and respawns mineflayer's state goes stale (its swings
  // stopped landing at 1-3 blocks), which turned rounds into no-damage timeouts that measured the harness, not us
  let bot = null;
  fs.mkdirSync(path.dirname(OUT), { recursive: true });
  let wins = 0, losses = 0, timeouts = 0;
  for (let n = 1; n <= ROUNDS; n++) {
    if (bot) { bot.quit(); await sleep(1500); }
    bot = createArenaBot();
    await new Promise((r) => bot.once("spawn", r));
    await sleep(2000);
    const rec = await runRound(rcon, bot, n);
    fs.appendFileSync(OUT, JSON.stringify(rec) + "\n");
    if (rec.winner === "ours") wins++; else if (rec.winner === "bot") losses++; else timeouts++;
    console.log(`round ${n}: ${rec.winner} in ${(rec.ms / 1000).toFixed(1)} s  (${wins}-${losses}, ${timeouts} timeouts)` +
      `  min hp ours ${rec.minHp.ours} bot ${rec.minHp.bot}, last ours ${rec.lastHp.ours} bot ${rec.lastHp.bot}`);
  }
  console.log(`RESULT ${SETUP} vs ${ENGINE}: ours ${wins}/${ROUNDS}, bot ${losses}, timeouts ${timeouts}, ` +
    `pass ${wins >= Math.ceil(ROUNDS * 0.8) ? "YES" : "NO"} (bar 8/10)`);
  bot.quit();
  await rcon.end();
})().catch((e) => { console.error(e); process.exit(1); });
