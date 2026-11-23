# PvP bot technique survey (companion to `pvpbot-opponent-study.md`)

Read-only survey of open or available Minecraft PvP bots, for ideas our client-side Combat Assistant can use to beat a
strong bot while outgeared. Everything is described in our own words; no code is reproduced (PracticeBotPvP is All
Rights Reserved; the others are open source but we only take ideas). Ticks are game ticks (20 per second), distances
are blocks. "Eye-to-box" is vanilla reach (eye to the nearest point of the target's box, 3.0 for players);
"centre" or "feet" distance is position to position, which is 0.3 to 0.42 longer than eye-to-box on flat ground.

Sources read:

| Bot | What | Read from |
|---|---|---|
| PracticeBotPvP 2.1.0 | Paper plugin, zombie entity driven by velocity writes | disassembled classes (`javap -c -p`) and its bundled `config.yml` / `difficulties.yml` under `tools/bot-arena/downloads/practicebotpvp-2.1.0/x/` |
| @nxg-org/mineflayer-custom-pvp | mineflayer plugin, a real client (keys, look, attack packets) | `tools/bot-arena/bots/node_modules/@nxg-org/mineflayer-custom-pvp/lib/sword/*` |
| mineflayer-pvp | mineflayer plugin, the simple baseline | `.../node_modules/mineflayer-pvp/lib/*` |
| Stepan1411 pvp-bot-fabric | Fabric server-side fake player (Carpet `/player` commands plus direct entity writes) | GitHub source via `gh api` (`BotCombat`, `BotNavigation`, `BotUtils`, `BotSettings`) |
| Sentinel (mcmonkey) | Citizens NPC guard plugin | GitHub source (`SentinelAttackHelper`, `SentinelTrait` fields) |
| Meinbot (Meindo, MIT, the most-starred "minecraft pvp bot" repo on GitHub) | a thin wrapper around mineflayer-pvp plus auto-eat | GitHub source; nothing new beyond mineflayer-pvp |

Vulgar's PvP Bot (the current arena opponent) is covered in `pvpbot-opponent-study.md` and is not repeated here.

---

## 1. PracticeBotPvP 2.1.0 (Paper, zombie)

Difficulty profiles (bundled defaults): crit chance / combo chance / strafe frequency / reaction multiplier / shield
raise ticks / movement randomness:

| | crit | combo | strafe | reaction x | shield raise | randomness |
|---|---|---|---|---|---|---|
| EASY | 10 | 10 | 30 | 1.5 | 10 | 0.8 |
| MEDIUM | 40 | 50 | 60 | 1.0 | 5 | 0.5 |
| HARD | 80 | 90 | 85 | 0.5 | 2 | 0.2 |
| HACKER | 100 | 100 | 100 | 0.3 | 1 | 0.0 |

**Attack decision.** Reach is `config.yml` `reach: 3.5`, measured feet to feet (3D centre distance, no ray cast, no
look check): about 3.1 to 3.2 eye-to-box, slightly longer than a player. Its own attack timer per difficulty is 20 /
15 / 10 / 6 ticks (EASY to HACKER), not the player attack-strength rule (it is a zombie), so HACKER swings every 6
ticks. It cannot attack while in its BLOCKING state or with the shield up. Each attack rolls a type: CRITICAL with
the crit chance, else COMBO with the combo chance, else NORMAL.

* **CRITICAL**: sets its vertical speed to 0.42 (a jump), then exactly 7 ticks later attacks only if it is falling
  (vertical speed below 0); otherwise the attack is silently dropped. Either way its attack timer has already been
  set, so a missed crit wastes a whole cooldown.
* **COMBO**: sprint flag on, attack next tick, sprint flag off one tick later, done after 3 ticks (a scripted
  sprint-knockback hit with an immediate sprint reset).
* **NORMAL**: attack, back to engaging after 5 ticks.

**Spacing (ReachBehavior).** Optimal distance = reach x 0.8 = 2.8 feet; minimum = max(reach x 0.6, 2.0) = 2.1.
Beyond reach: move in at 0.25; under 2.1: back off at 0.2; under 2.3: back off at 0.1; over 3.3: move in at 0.1. So it
holds a 2.3 to 3.3 feet band centred on 2.8, i.e. **just inside its own reach**, and sits still in the band.

**Strafe.** Three patterns: CIRCLE (pure sideways 0.2, plus a 0.1 radial correction when off a 3.0 radius by more
than 0.5), ZIGZAG (sideways 0.2 plus 0.1 toward), RANDOM (coin flip between the two each tick). Direction flips about
every 20 ticks (randomised by the randomness value), with a 20% chance to switch pattern at each flip. Strafe is
applied on "strafe frequency" percent of ticks.

**Shield.** Raised when we swing (a swing seen within the last 500 ms) after a reaction delay of shield-raise ticks
plus up to randomness x 5 ticks (HARD: 2 to 3 ticks), and **immediately when it is hit**. Held at most 30 ticks.
While blocking it backs away if we are within 3. Dropped when shield-stunned (axe).

**Dodge.** Starts a 15-tick dodge when health is at or below 30% (30% chance per check) or when we are within 2
blocks (10% per check). Within 2 blocks it jump-backs (0.4 back, 0.35 up); otherwise 50% straight back at 0.3, 25%
left, 25% right at 0.25.

**Not vanilla:** velocity written directly (full air control), 3.5 feet reach without a ray, attack rate not tied to
attack strength (6 ticks on HACKER), crit damage applied by the plugin.

**Exploitable.**
1. **Swing bait freezes it.** It raises its shield on our swing animation, cannot attack while blocking, and holds up
   to 30 ticks. An air swing right after our own landed hit (our charge is already spent) buys a 2-to-3-tick-late
   raise that blocks its offence; with an axe the raise becomes a 5-second shield disable.
2. **Hitting it raises its shield**, which also stops its attacks: every landed hit buys some ticks of no offence.
3. **Crit commitment**: on 80% of attacks (HARD) it jumps and swings exactly 7 ticks later, only if falling and only
   if we are within 3.5 feet. Being out of range at take-off + 7 wastes its full cooldown.
4. Its spacing is static inside 2.3 to 3.3: it does not hover outside our reach, so we can always trade at our 3.0.

## 2. @nxg-org/mineflayer-custom-pvp (a real client)

This is the most transferable bot, because it plays through the same inputs we have: movement keys, look, an attack
packet and the vanilla attack-strength rule. Defaults (`swordconfigs`):

* attackRange 3.0 **eye-to-box** (vanilla-correct), tooCloseRange 2.5, enemyReach 3.0, hitThroughWalls off,
  missChancePerTick 0.
* Attack cadence: the cooldown table (sword listed as speed 1.7, so 11 ticks), counted so the swing fires on the 12th
  tick, at about 0.96 charge. It swings only when: the counter has run out, the target is in range **and** a ray from
  its eye along its **current** look direction hits the target's box within reach (so it never swings at air).
* W-tap ("wtap" mode): on the swing tick, on the ground, it releases and re-presses forward and sprint, so every swing
  is preceded by a sprint restart (sprint knockback on every ground hit). An "stap" mode backpedals until it is
  0.1 beyond its range or the target stops moving at it.
* **Crit hop** ("hop" mode): when the target is within 2.0 eye-to-box and exactly 8 ticks are left on the cooldown
  (or it is the first hit), it taps jump. The fall then lines up with the charge. On the swing tick, if its vertical
  speed is at or below -0.25 it releases sprint first, so the hit is a real crit.
* **Reactionary crit**: after it is hurt and the knockback arrives, it waits up to 12 ticks while airborne; it swings
  as soon as it is falling at -0.25 or faster and the charge is ready (allowing 1 tick early), or after charge + 5
  ticks, aborting if it lands or the target is 5+ away. A knockback launch is turned into a crit.
* **Knockback cancel "jump"** (default): when hurt by an attacker whose look ray hits its box within 3.0, it presses
  forward + sprint + jump at once: a **jump reset** (the sprint-jump boost along the facing cancels part of the
  incoming push). There are also "velocity" (scales its own velocity: an anti-knockback cheat) and "shift" modes.
* **On-hit backoff**: it stops approaching for 5 ticks after being hurt, and approaches only while beyond tooClose
  (2.5 eye-to-box). In range and not too close it holds forward + sprint; inside 2.5 it stops pressing forward.
* **Strafe "intelligent"** (default): strafes only while the target faces it (the target's yaw is within 90 degrees
  of the bearing to the bot) **and** it was hit within the last 40 ticks; direction re-rolled every 5 to 24 ticks;
  only within range + 3. There is also "circle" (strafe toward the side the target is turning away from).
* **Shield, "legit"**: on the swing tick it lowers its offhand shield, swings, and raises it again 3 ticks later
  (block-hitting).
* **Shield disable**: when the target's shield is raised and the target has not swung for 3+ ticks (and 3+ ticks since
  the last weapon switch), it equips an axe, waits 3 ticks, and hits; it switches back to the sword when the shield
  drops.
* **Follow**: beyond range it paths to the target's position led by velocity x round(4 x sqrt(distance)) ticks
  (about 12 ticks of lead at 9 blocks).
* Rotation default: "constant", **instant** look at the target's head top every tick (non-smooth). A smooth option
  exists.

**Not vanilla:** instant rotation by default; the "packet" and "shorthop" crit modes (fake position packets); the
"velocity" knockback cancel; "silent" rotation. The rest (key presses, the ray-gated swing, the hop timing, the jump
reset, block-hitting) is what a real player does.

**Exploitable.**
1. **Predictable crit hop**: it only hops inside 2.0 eye-to-box, 3 ticks after its previous swing; it then swings
   about 9 ticks after take-off. Back out to beyond 3.0 during that fall and the crit is wasted (it swings at nothing
   only if the ray hits; otherwise it lands and resets).
2. The 5-tick backoff after every hit taken is free spacing for us: it does not re-close until we are inside 3.0 +
   1.
3. "Intelligent" strafe switches off after 40 ticks without hitting it; when we are winning it stops strafing.
4. Instant aim means strafing against it is useless (as with Vulgar); only distance helps.

## 3. mineflayer-pvp (baseline)

Follows to within 2 blocks (pathfinder), attackRange 3.5 feet. Attack timer = weapon cooldown with a random -2 to +2
ticks (so some swings come at about 0.8 charge). On each attack: lowers the shield, waits 100 ms, looks at the head
instantly, attacks, waits 150 ms, raises the shield. No crits, no W-tap, no strafe, no knockback handling. Raises the
shield at a creeper about to explode. **Exploitable:** fixed 2-block approach, no spacing, no evasion; the -2 tick
early swings cost it damage; its shield lowers for about 250 ms around each swing on a fixed rhythm.

## 4. Stepan1411 pvp-bot-fabric (server-side fake player)

Defaults (`BotSettings`): meleeRange 3.5 (centre distance `distanceTo`), attackCooldown 10 ticks (x1.5 below 50%
health), criticalsEnabled on, criticalFallTicks 6, shieldRaiseTicks 12, shieldHoldTicks 60, shieldBreakChance 40,
retreatHealthPercent 0.3, criticalHealthPercent 0.15, aimSpeed 90 degrees per tick, bhop on, cobwebs on, auto totem
on, auto potion on, missChance 0, mistakeChance 0.

**Attack decision.** In range (3.5 centre), its own counter at 0, **attack strength exactly 1.0**, and not holding
the shield for a prediction. With criticals on (default) **it never swings on the ground**: on the ground it jumps;
in the air it counts falling ticks and on the 6th falling tick sets its fall distance and attacks (a jump crit about
12 ticks after take-off, i.e. near landing). Every attack queues a one-tick W-tap (sprint off, forward 0.5 on the next
ground tick). Miss chance swings without attacking; mistake chance adds up to +-30 degrees of yaw error.

**Spacing.** Beyond 3.5: walk/sprint in (bhopping when chasing at full speed, jump every 10 ticks). Under 1.5: back
off at 0.3. Otherwise no spacing control: it stands where it is while jumping for crits.

**Strafe.** In combat movement the heading is bent about 22 degrees sideways (a 0.4 lateral mix) plus a 0.3 sideways
input; direction flips every 8 to 18 ticks.

**Knockback.** If its horizontal speed is above 0.35 it drops all inputs for up to 10 ticks (lets knockback play
out; no jump reset).

**Aim.** A per-tick rate limit: each tick the yaw moves toward the target's eyes by at most aimSpeed degrees (default
90, so effectively instant), in melee it sets yaw directly. No easing, no restart: a simple clamped slew.

**Shield (two layers).**
* *Prediction*: if we are sprinting, closing (distance decreasing) and within 4.5, it raises the shield for 12 ticks
  and holds at least 6, then will not predict again for 15 to 24 ticks.
* *Auto shield*: if we are within 4.0 and **our arm is swinging**, it blocks and holds for 60 ticks after the last
  such swing, with a 30-tick cooldown between raises.
* Below 50% health it holds the shield whenever it is not about to attack; below 80% it flicks the shield up at random
  (0.4% per tick, 3 to 5 ticks).
* **While a prediction or hold is active and it is blocking, it does not attack.**

**Shield break.** While the target blocks, every second tick a 40% roll: swap the axe into the hotbar (written
straight into the inventory) and attack. Marks "shield broken" for 5 seconds (which suppresses its own low-health
retreat during that time).

**Mace defence.** A target holding a mace, airborne and falling (vertical speed below -0.08): raise the shield and
hold for 20 ticks.

**Retreat and healing.** Eats at 50% health (golden apples first) or below food 14; while eating it retreats facing
away (move away at 1.2). At 30% health with food (or 15% always), and not within 5 s of breaking our shield: shield up,
cobweb toward us if within 8, **turn its back to us** and run (1.5). With no food under 50% and a bow: back off and
shoot. Cobwebs are also thrown at a moving target 2 to 6 away during melee.

**Not vanilla:** forced fall distance for the crit, 3.5 centre reach without a ray, instant yaw in melee, axe/shield/
totem moved by writing the inventory directly, velocity added directly in some moves.

**Exploitable.**
1. **Crit-only attacker**: it can attack only while falling, about 12 ticks after its take-off, and only if we are in
   3.5 centre at that tick. Out of range on that tick means no attack at all until the next jump (about 12+ ticks).
2. **Swing to freeze it**: any swing of ours within 4.0 raises its shield and keeps it up for 60 ticks after our last
   swing, and it does not attack while that hold runs. With an axe, a hit on that shield disables it for 5 seconds;
   without one, it is a stall we control (useful to wait for our own regeneration or to reset spacing).
3. **Retreat with its back turned**: at 30% health it faces away; a shield only blocks hits from the front half-space
   of the holder's view, so **our hits from behind land through its raised shield**, and it runs at sprint speed in a
   straight line (walls stop it).
4. Knockback handling is passive (10 ticks of no input), so sprint-knockback combos are not escaped.
5. Below 50% health it turtles behind the shield and its swing rate drops (cooldown x1.5): the moment to bring the axe.

## 5. Sentinel (Citizens NPC)

Melee reach 3 (eye to eye), attackRate 30 ticks by default, chases while the target is out of reach. Raises a shield
when it "sees a threat": the threat looks toward the NPC within 60 degrees and is within 5 blocks, or holds a ranged
weapon. Keeps chasing a target it lost sight of for a configurable number of "clever ticks". **Nothing new for
melee**; the one idea is "shield only when the opponent is facing you", which our `ShieldDecision` front-cone rule
already does.

---

## 6. Cross-bot patterns

* **Every bot swings on charge whenever in range.** None withholds a swing for a better hit type. Custom-pvp gates on
  "my current look ray hits the box within reach", not on an aim-error threshold.
* **Crits are always a scheduled hop** whose fall is timed to the charge (custom-pvp, Vulgar) or a fixed delay
  (PracticeBot 7 ticks, pvp-bot 6 falling ticks). All of them commit to an airborne swing tick we can predict from
  their take-off.
* **Shields react to our swing animation** (PracticeBot, pvp-bot, Vulgar's SMP mode) and suppress their own attacks
  while up. A swing is a cheap, legit lever on their offence.
* **Spacing**: the real-client bot holds 2.5 to 3.0 eye-to-box and backs off for 5 ticks after a hit taken;
  PracticeBot holds 2.3 to 3.3 feet. Nobody hovers outside their own reach, which is what our solver does.
* **Knockback**: the only legit counter seen is the jump reset (custom-pvp), followed by a crit on the fall
  (reactionary crit). Server bots just stop input.
* **Aim**: all of them track every tick with no easing (instant or a clamped slew).

## 7. What our assistant lacks or does worse (ranked by expected effect in the outgeared arena)

Legit = vanilla reach (3.0 eye-to-box), rotations smoothed and speed-capped by the core (450 degrees per second,
22.5 per tick), keys and clicks only, no packet tricks.

| # | Technique (source) | Our gap | Class(es) to change | Legit? |
|---|---|---|---|---|
| 1 | **Continuous tracking aim**: a per-tick rate-limited slew toward the aim point with the target's angular velocity fed forward (pvp-bot's clamped slew; custom-pvp's per-tick look) | `CombatAssistant.aimAt` issues a new `RotationTarget` whenever the aim point moves more than 0.5 degrees, and `RotationCurve` plays each one as an eased Bezier with overshoot and correction. At 1 to 2 blocks against a strafing opponent the point moves several degrees per tick, so the camera keeps restarting an ease-in and trails the box. This is the likely cause of the close-range tracking weakness (check against the swing-gate aim-error telemetry). Note the needed rate at 1 block with 0.26 lateral speed is about 15 degrees per tick, inside the 22.5 cap. | `core.input.InputSmoother` + `RotationCurve` (a TRACK mode: velocity-continuous, capped, no overshoot, keeps humanizer micro-noise), `core.api.RotationMode`/`RotationTarget` (with care: orchestrator-owned), `assistant/CombatAssistant.aimAt` (use TRACK while engaged inside about 4 blocks, feed `AimSolver.neededYawRateDegPerTick`) | Yes: same cap, no snap; the "no head snapping" rule holds |
| 2 | **Swing when the current look ray hits the box in reach** (custom-pvp) | The gate should be "crosshair entity is the target and in reach" at charge, not "aim error under a threshold around the aim point". At close range the box is 20+ degrees wide; any ray inside it is a hit. Together with dropping crit-or-nothing (study item 2), this is what turns held charged swings into hits. | `assistant/SwingGate`, `tactics/HitPlanner.decide` (`canHit` from `crosshairOnTarget` + reach), `assistant/CombatAssistant` swing path | Yes |
| 3 | **Jump reset on the hurt tick, then a crit on the fall** (custom-pvp kbCancel "jump" + reactionary crit) | `jumpReset` exists but is off by default, taps jump with a 1-tick delay (after the knockback has lifted us, `onGround` is false, so the tap may do nothing), does not hold forward + sprint for the sprint-jump boost, and nothing schedules a crit from the resulting air time. | `assistant/SustainExtras.shouldJumpReset` + `CombatAssistant.onDamageEvent` (tap on the same tick the knockback velocity arrives, with forward + sprint held; on in duel mode or when outgeared; only when the attacker faces us), `tactics/HitPlanner` (treat the jump-reset or knockback launch as a planned CRIT: release sprint at the apex, swing on the first falling tick in reach once charged), `tactics/ComboEscape` (jump reset on the first hit of a combo, before the 2-hit trigger) | Yes: a jump key press; no velocity edits |
| 4 | **Swing bait against shield-reactive opponents** (exploits PracticeBot, pvp-bot, Vulgar SMP mode) | `FeintPlanner` feints with movement only. A swing at air resets our own charge, so the bait belongs in the dead ticks right after our own landed hit (charge already near 0). Learn per opponent whether a swing within about 4 blocks raises its shield, the delay, and whether it stops attacking while blocking. Then: bait after each hit; if we carry an axe, hit the raised shield (after its 5-tick raise) for a 100-tick disable; if not, use the freeze to reposition or to wait. | `tactics/FeintPlanner` (new swing feint), `tactics/OpponentModel` (shield-on-swing reaction read: raise latency, hold length, attacks while blocking), `assistant/ShieldBreakSequencer` (start the axe swap right after a sword hit, since a weapon switch resets the charge anyway and the disable does not need charge), `tactics/ShieldDuel` | Yes: real clicks; respects the 5-tick raise and the front half-space rule |
| 5 | **Range out the opponent's committed crit swing** (all crit-hop bots: custom-pvp 9 ticks after a hop inside 2.0, PracticeBot exactly 7 ticks, pvp-bot 6th falling tick about 12 after take-off) | Generalise the study's Vulgar "crit lock" into a learned read: from `OpponentModel` take-offs and the damage-taken timestamps, learn the take-off-to-swing delay and whether it ever swings on the ground. Then avoid being in its reach on the predicted tick and step in on its landing (pvp-bot and PracticeBot cannot hit on the ground at all while in crit mode). | `tactics/OpponentModel` (take-off to hit delay histogram, "airborne-only attacker" flag), `tactics/HitTradeSolver.rollout` (opponent threat only on its predicted fall tick), `tactics/ComboKeeper` (back-out cost during that tick) | Yes |
| 6 | **Hold spacing inside our own reach; back off only briefly after a hit taken** (custom-pvp: approach beyond 2.5, no approach for 5 ticks after being hurt; PracticeBot 2.3 to 3.3 feet) | Our solver hovers at 3.0 to 3.6, outside our own reach (study items 3 and 6). The bots' rule is simpler: 2.5 to 3.0 eye-to-box, and a 5-tick pause after each hit taken (the knockback carries us out anyway). | `tactics/HitTradeSolver.positionTerm`, `assistant/SpacingController` defaults (2.7 to 3.1 centre is close; the solver overrides it), `tactics/TradeInput` (learned `oReach`) | Yes |
| 7 | **Strafe only when it pays** (custom-pvp "intelligent": only while the opponent faces us and only while trading; never against instant-turn aim) | Our strafe adds our own lateral speed to the angle we must track (`neededYawRateDegPerTick` subtracts it), so against an instant-aim bot it costs our aim and our approach and buys nothing. Gate it: opponent faces us, its observed turn rate is limited (it misses when we strafe), and our needed yaw rate stays under the cap. | `assistant/StrafePlanner`, `assistant/ProFeelLayer` (strafeWith), `tactics/OpponentModel` (opponent turn-rate read: facing error to us over time; near 0 = instant aim) | Yes |
| 8 | **Punish the turned-back retreat** (pvp-bot at 30% health; Vulgar at 16 HP; PracticeBot jump-back dodge at 30%) | A shield only blocks from the front half-space of the holder's view, so a retreating opponent that faces away is unshielded from behind. The study's retreat read should also set "shield irrelevant" for `ShieldDuel` and let `HitPlanner` choose CRIT (a fleeing target's knockback does not matter). Watch for its cobweb throw (pvp-bot) when chasing inside 8. | `tactics/OpponentModel` (retreat read, see the study), `tactics/ShieldDuel.blocks` already models the half-space: feed it the opponent's real facing, `tactics/HitPlanner`, pursuit in `assistant/CombatAssistant` | Yes |
| 9 | **Block-hit with our own shield** (custom-pvp legit mode: down on the swing tick, back up 3 ticks later; mineflayer-pvp 100 ms / 150 ms) | Only relevant if the kit gives us a shield (the current arena kit does not). `ShieldDecision` already lowers for a wanted swing; add the immediate re-raise and the 5-tick raise-delay accounting so the shield is active before the opponent's next charge. | `reflex/ShieldDecision`, `reflex/CombatReflexes` | Yes |
| 10 | **Mace fall read** (pvp-bot: target with a mace, airborne, falling faster than 0.08: shield for 20 ticks) | `ThreatTracker`/`ShieldDecision` would benefit from the same simple trigger for mace smashes (also as a "step out from under it" dodge when no shield). | `coach/ThreatTracker`, `reflex/ShieldDecision`, `reflex/DodgePlanner` | Yes |

Already matched or better (no change needed): crit hop timed so the fall meets the charge (`HitPlanner` hops at
`readyIn <= hopLead`, as custom-pvp's 8-ticks-left hop does); sprint reset per hit (`ComboKeeper` W-release / S-tap);
shield front-cone rule (`ShieldDecision`, Sentinel's 60-degree "sees threat" is cruder); axe shield disable with
back-off (`ShieldBreakSequencer`); predictive pursuit (`routePursuit`); hazard-aware strafing.

Not to copy (cheats or server-only): instant rotation, ray-free reach checks, 3.5 centre reach, forced fall distance,
fake position packets for crits ("packet", "shorthop"), scaling our own knockback velocity, silent rotation, direct
inventory writes, velocity writes with full air control.

## 8. Top five, with expected effect

1. **Tracking aim mode** (`InputSmoother`/`RotationCurve` TRACK mode, `CombatAssistant.aimAt`). The crosshair stays
   on the box at 1 to 2 blocks against a strafing or knocked-back opponent instead of trailing behind restarting
   eases. Expected: most of the "held charged swing near the target" cases become hits.
2. **Ray-hit swing gate plus swing on charge** (`SwingGate`, `HitPlanner`, together with the study's crit-or-nothing
   change). Expected: 3 to 4 swings in the first 3 seconds instead of 1 against Vulgar; matches what every bot does.
3. **Same-tick jump reset with sprint held, then a falling crit** (`SustainExtras`, `CombatAssistant.onDamageEvent`,
   `HitPlanner`, `ComboEscape`). Expected: shorter knockback after each hit taken (we stay in reach to answer) and a
   1.5x crit (about 2.6 instead of 1.6 through diamond) on the answer; breaks sprint-knockback combos earlier.
4. **Opponent crit-timing read and range-out** (`OpponentModel`, `HitTradeSolver`, `ComboKeeper`). Expected: denies
   the 8-damage crits that kill us in two, and against crit-only attackers (pvp-bot-fabric, PracticeBot crit rolls,
   Vulgar JUMP_RESET) creates ground windows where they cannot hit at all.
5. **Swing bait plus axe on shield-reactive opponents** (`FeintPlanner`, `OpponentModel`, `ShieldBreakSequencer`).
   Expected: against PracticeBot and pvp-bot-fabric a bait after each landed hit freezes their offence for 2 to 60
   ticks; with an axe, a 5-second shield disable. No effect on Vulgar in ADAPTIVE mode (it ignores our swings), so it
   ranks below the first four for the current arena.
