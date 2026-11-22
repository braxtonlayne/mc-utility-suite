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
const OUT = args.out || path.join(__dirname, "..", "results", `${SETUP}-${ENGINE}${(args.arena || "hazards") === "flat" ? "" : "-hazards"}.jsonl`);
const HOST = "127.0.0.1";
const PORT = 25570;
const ROUND_TIMEOUT_MS = 90_000;

// Arena, far from spawn. Both start 8 blocks apart on the x = AX + 0.5 line.
//   --arena flat     the original 21x21 stone floor with a glass ring (keeps old results comparable)
//   --arena hazards  (default) 41x41 field on a 10-deep stone base with terrain that punishes bad footing.
//                    Every hazard is mirror-symmetric in x and z about the field centre (cell c <-> cell -c), so
//                    neither start has a positional edge:
//                      lava hole   3x3, 1 deep, dead centre between the starts (knockback into it is a death)
//                      ravines     3 wide, 8 deep, 25 long on both flanks (x cells +-9..11), stairs out at both ends
//                      streams     2 wide, 1 deep still water along both side walls (x cells +-15..16): wading slows
const ARENA = args.arena || "hazards";
const AX = 1000, AY = 100, AZ = 1000;
const HALF = ARENA === "flat" ? 10 : 20;
const RAVINE_DEPTH = 8;
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
  // Fable decision 2026-11-22: the pass bar (>= 16/20). One clear gear step down, not a hopeless one.
  outgeared: {
    ours: ["iron_sword", "iron_helmet", "iron_chestplate", "iron_leggings", "iron_boots"],
    bot: ["diamond_sword{Enchantments:[{id:sharpness,lvl:2}]}", "diamond_helmet", "diamond_chestplate",
      "diamond_leggings", "diamond_boots", "shield"],
  },
  // stress case only since 2026-11-22 (one bot hit is worth about six of ours)
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

// Builds (or rebuilds) the arena; idempotent, so every round starts on unchanged terrain.
async function buildArena(c) {
  const H = HALF, x0 = AX - H, x1 = AX + H, z0 = AZ - H, z1 = AZ + H;
  await c(`forceload add ${x0 - 2} ${z0 - 2} ${x1 + 2} ${z1 + 2}`);
  // clear anything a bigger previous layout left (flat after hazards, or block litter)
  await c(`fill ${AX - 21} ${AY + 1} ${AZ - 21} ${AX + 21} ${AY + 6} ${AZ + 21} minecraft:air`);
  if (ARENA === "flat") {
    await c(`fill ${x0} ${AY} ${z0} ${x1} ${AY} ${z1} minecraft:stone`);
  } else {
    // a solid base so the cuts below have walls and a floor (fill volume 41*41*10 < the 32768 limit)
    await c(`fill ${x0} ${AY - 9} ${z0} ${x1} ${AY} ${z1} minecraft:stone`);
  }
  await c(`fill ${x0 - 1} ${AY + 1} ${z0 - 1} ${x1 + 1} ${AY + 5} ${z1 + 1} minecraft:glass hollow`);
  await c(`fill ${x0} ${AY + 1} ${z0} ${x1} ${AY + 5} ${z1} minecraft:air`);
  if (ARENA === "flat") return;
  // ravines on both flanks, with one-block stairs climbing out at both ends
  const D = RAVINE_DEPTH, RL = 12;
  for (const [a, b] of [[9, 11], [-11, -9]]) {
    await c(`fill ${AX + a} ${AY - D + 1} ${AZ - RL} ${AX + b} ${AY} ${AZ + RL} minecraft:air`);
    for (let j = 0; j < D - 1; j++) {
      // step j sits j cells in from each end, its top at AY - 1 - j (a 1-block climb per cell)
      for (const z of [AZ + RL - j, AZ - RL + j]) {
        await c(`fill ${AX + a} ${AY - D + 1} ${z} ${AX + b} ${AY - 1 - j} ${z} minecraft:stone`);
      }
    }
  }
  // lava hole, 1 deep, centre (contained: stone on every side at its level, nothing to flow into)
  await c(`fill ${AX - 1} ${AY} ${AZ - 1} ${AX + 1} ${AY} ${AZ + 1} minecraft:lava`);
  // streams along both side walls: 1-deep source water, contained by stone
  for (const [a, b] of [[15, 16], [-16, -15]]) {
    await c(`fill ${AX + a} ${AY} ${AZ - 18} ${AX + b} ${AY} ${AZ + 18} minecraft:water`);
  }
  await c(`kill @e[type=minecraft:falling_block]`);
}

async function resetRound(rcon) {
  const c = (cmd) => rcon.send(cmd);
  await buildArena(c);
  await c(`kill @e[type=item]`);
  await c(`kill @e[type=arrow]`);
  for (const who of [PLAYER, BOT]) {
    await c(`gamemode survival ${who}`);
    await c(`clear ${who}`);
    await c(`effect clear ${who}`);
    await c(`effect give ${who} minecraft:instant_health 1 10 true`);
    // no saturation at round start (Fable decision): hunger is full but the saturation buffer is the vanilla one
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
  const logAt = serverLogSize();
  let result = null;
  const onMsg = (msg) => {
    const s = msg.toString();
    if (result) return;
    if (s.startsWith(BOT + " ") && DEATH.test(s)) result = { winner: "ours", line: s, cause: causeOf(s) };
    else if (s.startsWith(PLAYER + " ") && DEATH.test(s)) result = { winner: "bot", line: s, cause: causeOf(s) };
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
    arena: ARENA, grim: grimFlags(logAt), winner: result ? result.winner : "timeout", line: result ? result.line : null,
    cause: result ? result.cause : null,
    minHp: { ours: Math.min(...hp.ours, 20), bot: Math.min(...hp.bot, 20) },
    lastHp: { ours: hp.ours.at(-1) ?? null, bot: hp.bot.at(-1) ?? null }, hpSeries: hp, distSeries: dist };
  // the dead side respawns via its own auto-respawn (bot) or the client's Auto-Respawn
  if (bot.health <= 0) { try { bot.respawn(); } catch (_) { /* */ } }
  await sleep(2500);
  return rec;
}

// vanilla death messages a fight here can end in (melee, knocked into lava, a ravine fall, drowning)
const DEATH = /was slain|died|was killed|lava|burn|went up in flames|hit the ground too hard|fell |drowned|doomed to fall|experienced kinetic/;
function causeOf(s) {
  if (/lava|burn|flames/.test(s)) return "lava";
  if (/hit the ground|fell |doomed to fall/.test(s)) return "fall";
  if (/drowned/.test(s)) return "drowned";
  return "melee";
}

// Grim alerts land in the server log; count the ones against our client during the round.
const SERVER_LOG = path.join(__dirname, "..", "server", "logs", "latest.log");
function serverLogSize() {
  try { return fs.statSync(SERVER_LOG).size; } catch (_) { return 0; }
}
function grimFlags(from) {
  let text = "";
  try {
    const fd = fs.openSync(SERVER_LOG, "r");
    const n = Math.max(0, fs.fstatSync(fd).size - from);
    const buf = Buffer.alloc(n);
    fs.readSync(fd, buf, 0, n, from);
    fs.closeSync(fd);
    text = buf.toString("utf8");
  } catch (_) { return null; }
  const checks = {};
  let total = 0;
  for (const line of text.split(String.fromCharCode(10))) {
    if (!/grim/i.test(line) || !line.includes(` ${PLAYER} `)) continue;
    const m = line.match(new RegExp(`${PLAYER} failed ([A-Za-z0-9]+)`));
    const k = m ? m[1] : "other";
    checks[k] = (checks[k] || 0) + 1;
    total++;
  }
  return { total, checks };
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  const rcon = await Rcon.connect({ host: HOST, port: 25575, password: rconPassword() });
  await rcon.send("gamerule doImmediateRespawn true");
  await rcon.send("gamerule keepInventory false");
  await rcon.send("gamerule naturalRegeneration true");
  await rcon.send("gamerule showDeathMessages true");
  // Grim runs during fights: only the opponent bot is op (permissions.yml gives ops grim.exempt), never our client
  await rcon.send(`deop ${PLAYER}`);
  await rcon.send(`op ${BOT}`);
  if (args.dry) {
    console.log("rcon ok:", await rcon.send("list"));
    if (args.build) {
      // --dry --build: build the arena and spot-check the hazards over RCON (no client or bot needed)
      await buildArena((cmd) => rcon.send(cmd));
      const probe = async (dx, dy, dz, block) => {
        const r = await rcon.send(`execute if block ${AX + dx} ${AY + dy} ${AZ + dz} minecraft:${block}`);
        console.log(`  ${dx},${dy},${dz} ${block}: ${/passed/i.test(r) ? "ok" : "NO (" + r + ")"}`);
      };
      await probe(0, 0, 0, "lava"); await probe(0, 0, -4, "stone"); await probe(0, 1, -4, "air");
      await probe(10, 0, 0, "air"); await probe(10, -RAVINE_DEPTH, 0, "stone"); await probe(-10, -7, 0, "air");
      await probe(10, -1, 12, "stone"); await probe(10, -1, 11, "air"); await probe(10, -2, 11, "stone");
      await probe(15, 0, 0, "water"); await probe(-16, 0, 18, "water"); await probe(21, 3, 0, "glass");
    }
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
    console.log(`round ${n}: ${rec.winner}${rec.cause ? " (" + rec.cause + ")" : ""}, grim ${rec.grim ? rec.grim.total : "?"} in ${(rec.ms / 1000).toFixed(1)} s  (${wins}-${losses}, ${timeouts} timeouts)` +
      `  min hp ours ${rec.minHp.ours} bot ${rec.minHp.bot}, last ours ${rec.lastHp.ours} bot ${rec.lastHp.bot}`);
  }
  console.log(`RESULT ${SETUP} vs ${ENGINE}: ours ${wins}/${ROUNDS}, bot ${losses}, timeouts ${timeouts}, ` +
    `pass ${wins >= Math.ceil(ROUNDS * 0.8) ? "YES" : "NO"} (bar 8/10)`);
  bot.quit();
  await rcon.end();
})().catch((e) => { console.error(e); process.exit(1); });
