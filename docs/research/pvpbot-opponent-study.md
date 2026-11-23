# Opponent study: Vulgar's PvP Bot 4.0.0 (HARD / ADAPTIVE)

Read-only study of the arena opponent, from its disassembled classes (`javap -c -p`, intermediary names), plus the
arena kit in `tools/bot-arena/bots/arena.js` and the 3/20 run in `tools/bot-arena/results/outgeared-pvpbot-hard-*`.
Everything below is described in our own words; no code from the bot is reproduced (its licence is All Rights
Reserved). Numbers are game ticks (20 per second) and blocks unless noted.

## 0. Effective configuration in the arena

`pb diff hard` then `pb mode adaptive` run `applyDifficulty` and then `applyMode`. ADAPTIVE falls in the same branch
as COMBO, so it adds +10 crit chance (capped at 92), +5 sprint-reset chance (capped at 80) and -0.03 accuracy
reduction (floor 0.01). It does not change the cooldown, the preferred range or the strafe frequency.

| Field | HARD | after ADAPTIVE | What it actually does |
|---|---|---|---|
| attackCooldownTicks / jitter | 9 / 2 | 9 / 2 | Its own delay after a swing: 7 to 10 ticks. **Never the binding limit**, see 1.2 |
| critChancePercent | 72 | **82** | Chance that a queued crit jump actually happens |
| sprintResetChancePercent | 65 | **70** | Chance of a sprint-reset pause after a pattern ends |
| accuracyReduction | 0.05 | **0.02** | Per-tick chance to skip the attack check while **we** are airborne |
| preferredRange | 2.2 | 2.2 | Only the switch between sprint approach and walk approach (see 2.1) |
| attackReach | 3.2 | 3.2 | Feet-to-feet distance limit (see 1.1) |
| strafeFrequency | 0.3 | 0.3 | Used inverted: it strafes on about **70%** of ticks (see 2.2) |
| strafeChangeTicks | 12 | 12 | Strafe direction re-rolled every 12 movement ticks |
| gappleThreshold | 16 | 16 | **Retreat trigger** (see 4) |
| notchAppleThreshold | 10 | 10 | Enchanted apple threshold |
| hungerThreshold | 8 | 8 | Eats any food below food level 8 |
| potionCheckIntervalTicks | 160 | 160 | Potion check period |
| shieldHoldTicks / shieldLockoutTicks | 30 / 35 | 30 / 35 | Hold timer only matters in SMP/DEFENSIVE modes |
| allowSameTickAttacks | false | false | At most one attack per game tick |
| ledgeLatchEnabled | true | true | Mid-air pull back onto ledges (see 2.5) |
| pathMode | SAFE | SAFE | Avoids drops over 2 blocks (4 when chasing a target below) |

ADAPTIVE is neither "aggressive" (CRIT/AGGRESSIVE) nor "defensive" (SMP/DEFENSIVE), so: no predictive shield, no
crit-mode loop; combo patterns are drawn uniformly from SPRINT_HITS, DOUBLE_STRAFE, JUMP_RESET and SHIELD_BAIT
(BREACH_SWAP only with a breach mace).

The arena kit gives the bot only a Sharpness II diamond sword, full diamond armour and an offhand shield: **no axe, no
golden apples, no totem, no food, no potions.** So the axe logic, the healing, the totem and the potion logic all do
nothing in our arena, and the shield stays in the offhand (it would be displaced only by a totem).

## 1. Attack decision

### 1.1 Range test

Two checks, both on **feet positions, not eyes and not hitboxes**, and **no ray cast at all**:

1. Per-tick gate: horizontal (XZ) centre distance squared must be at least 0.81 (0.9 blocks) against a real player
   (0.64 against another fake player, 0 if the target stands in a cobweb) and at most 3.2 squared.
2. At the swing: the full 3D feet-to-feet distance must be at most 3.2 (3.55 if the target is in a cobweb and within
   2.75 vertically).

What that means in vanilla terms (our reach is 3.0 from the eye to the nearest point of the 0.6 x 1.8 box):

* Same level, along an axis: its 3.2 centre limit is 2.9 eye-to-box. **Our reach is about 3.3 centre to centre along
  an axis and about 3.42 on a diagonal.** We out-reach it by 0.1 to 0.2 blocks on flat ground.
* While the bot is airborne its 3D check shrinks its horizontal reach: at the apex of a jump (about 1.25 up) it can
  only swing out to about 2.95 horizontally, on its crit fall (about 1.0 to 1.2 up) about 2.97 to 3.04. Our reach
  against an airborne bot is still about 3.3.
* It never checks line of sight or where it is looking: it **can hit through corners and walls** within that radius.

### 1.2 When it swings

Per tick, in order (any failure returns for the tick):

1. Cobweb escape, then the **retreat trigger** (health at or below 16 and not already retreating: switch to RETREAT,
   see 4).
2. **Knockback stun**: if it is in its 6-tick knockback window (see 2.4) it only turns to face us, counts a "hit taken"
   for that tick and returns. No attack, no movement input.
3. Movement (`combatMoveTo`, see 2), shield bookkeeping.
4. **Sprint-reset pause**: while its sprint-reset timer runs (3 to 7 ticks after a pattern ends with the 70% roll,
   5 to 8 ticks in DOUBLE_STRAFE's middle step) it does nothing else.
5. Combo-break check (see 3.3), unless it is waiting for a crit or shielding.
6. Its own 7-to-10-tick delay after the previous swing.
7. Horizontal range gate (1.1).
8. **Attack strength at least 0.9** (sword: 12 ticks after the previous swing, strength 0.96). This is the binding
   limit, so its real cadence is one swing per 12 to 13 ticks at best.
9. If we are airborne, a 2% chance to skip the tick.
10. If it is waiting for a crit: it swings **only on a tick when its own vertical speed is below -0.08** (falling), and
    otherwise does nothing. Standing on the ground gives -0.078, so it never fires on the ground.
11. Otherwise the current combo pattern's step.

A swing is a direct server-side `attack` call followed by `swing(MAIN_HAND)` (the swing animation is broadcast, so our
cooldown read can see it). Every swing re-rolls the 7-to-10 delay, marks a hit for its chain counter and starts a short
strafe "burst" in the dead strafe controller (never applied, see 2.2).

### 1.3 Combo patterns (ADAPTIVE draws one of four, 25% each)

* **SPRINT_HITS**: three swings, each preceded by forcing the sprint flag on (so each is a sprint-knockback hit), then
  a jump (82%) and the pattern ends. The end clears the crit wait, so this jump is not a planned crit.
* **DOUBLE_STRAFE**: two swings, then a sprint reset (sprint off, horizontal speed x0.4, 5 to 8 idle ticks, new
  cooldown), then a jump (82%) and the pattern ends.
* **JUMP_RESET**: on the first tick all gates pass (in range, charged) it jumps (82%) and waits for the crit fall, swings
  on the first falling tick in range, then two forced-sprint swings, then the pattern ends.
* **SHIELD_BAIT**: on the first tick all gates pass it raises the offhand shield and sets a 12-to-19-tick cooldown; on
  the next tick all gates pass again (in range, charged) it lowers the shield, tries to equip an axe (none in the kit),
  and swings **on that same tick**.

After a pattern ends: 70% chance of a sprint reset (sprint off, speed x0.4, 3 to 7 idle ticks), then a new pattern.

### 1.4 Crits and sprint resets

* Crit timing: a real jump from the ground (vanilla jump, 0.42 up). The swing fires on the first falling tick with
  vertical speed below -0.08, which is the 7th or 8th tick after take-off. A crit only counts in vanilla if the bot's
  sprint flag is off; its flag is off whenever its last movement input that tick was a walk (within 2.2) or a strafe,
  which is most ticks in close range, so most of these are real crits (we saw 8 to 8.7 damage taken per crit through
  iron armour, about 4.85 per plain hit).
* "Sprint reset" = sprint flag off and horizontal speed multiplied by 0.4, then an idle window. It is not a W-tap in
  the vanilla sense: the sprint flag is forced back on before the next forced-sprint swing regardless.

### 1.5 Vanilla legality

Within vanilla: attack strength gate 0.9 (no spam), one attack per tick, normal damage, crit and invulnerability
handling (it calls the real attack; its cadence is always at least 12 ticks, so it never needs an invulnerability
bypass), shield raise delay (5 ticks) and shield cooldown, its effective reach (2.9 eye-to-box flat) is slightly
**shorter** than ours.

Not vanilla (flag for the benchmark write-up):

1. **Instant rotation**: every tick it sets yaw, head yaw, body yaw and pitch straight at our eyes (no turn speed); in
   retreat it snaps 180 degrees to face away.
2. **No ray cast / line of sight** for attacks: hits through walls and around corners inside the 3.2 radius.
3. **Velocity written directly** every tick (sprint 0.26, walk 0.15, strafe 0.12, back-off 0.15): instant direction
   reversals, **full air control** (it steers mid-jump and mid-knockback), no slow-down while blocking or eating.
4. **Sprint flag set while strafing sideways or backing up** at times, so some forced-sprint swings carry sprint
   knockback a real client could not have.
5. **Knockback erased while it is "sprinting"**: if our hit lands while its sprint flag is on, the knockback stun
   (2.4) does not start and its next movement tick overwrites the knockback velocity; only the vertical lift remains.
6. **Ledge latch**: while falling faster than 0.35 per tick next to a ledge it pulls itself horizontally toward the
   ledge (0.18) and caps its fall speed at -0.22. Impossible for a player.
7. Hotbar and offhand changes written straight into the inventory (an inventory item jumps to the hotbar without an
   open screen). Unused with the arena kit.
8. ULTRA_HARD only: permanent Strength and Resistance effects (not active on HARD; they are removed every 60 ticks).

## 2. Movement and spacing

### 2.1 Approach (every engaged tick, before the attack logic)

* It faces us (instant).
* Knockback detection: if its horizontal speed is above 0.3 and its sprint flag is off, it starts a **6-tick stun**
  (2.4) and does nothing else this tick.
* Distance bands (centre to centre, 3D feet distance):
  * below 0.9: walk straight away from us (0.15) with a small sideways offset;
  * above 6.4 (twice its reach): sprint straight at us (0.26), no strafe;
  * 2.2 to 6.4: sprint at us (0.26), then the strafe may overwrite it;
  * 0.9 to 2.2: **walk at us (0.15)**, then the strafe may overwrite it.
* So "preferredRange 2.2" is not a spacing target: **it keeps walking in until 0.9**. It fights at very close range.

### 2.2 Strafe

* The strafe that runs: a direction from {left, right, none} (one third each), re-rolled every **12 engaged movement
  ticks** (the counter pauses during stuns); on each tick, with probability 0.7 (1 - 0.3), it **replaces** the approach
  velocity with a pure sideways 0.12. With "none" the approach is kept.
* Net effect inside 6.4: it closes on about 30% of ticks at 0.26/0.15 and jitters sideways at 0.12 the rest of the
  time; the approach averages about 0.08 per tick in the sprint band.
* The separate StrafeController (random 8-to-22-tick flips, a 28-degree "burst" after each attack) is **never used** by
  the movement. Dead code.
* It aims at our current eye position with no lead, but aim does not matter to its attack (no ray cast). **Strafing has
  no defensive value against it; only distance does.**

### 2.3 Pathing

Velocity is written through a navigation helper: in SAFE mode it refuses steps over drops deeper than 2 blocks (4
while chasing a target that is lower), tries a downhill path, then the best detour direction; if all fail its speed
bleeds to 20% and its sprint stops. It auto-jumps over 1-block steps. Stuck for 18 ticks: a random 0.12 nudge;
blocked 8 ticks on the ground: a jump.

### 2.4 Knockback handling

Our hit lands, its next movement tick sees speed above 0.3 with sprint off and it stops writing velocity for 6 ticks,
so vanilla knockback plays out and it **cannot attack during those 6 ticks**. Each stunned tick also counts as a "hit
taken" for its combo-break logic (so one hit counts as six). After the stun it steers again at once, in the air too.
Rough travel during the stun: about 1.9 blocks for a plain hit, more for a sprint hit. If its sprint flag was on when
we hit, there is no stun and the knockback is mostly erased on the next tick.

### 2.5 Retreat path

In RETREAT it runs straight away from us at 0.26 (just under our 0.28 sprint), facing away with pitch 0, using the same
SAFE navigation. Into a wall it slides or gets blocked (speed bleeds to 20%). It does not use ledge-latch differently.
Ledge latch (1.5, item 6) makes knocking it into the ravine unreliable when it is within about 1.5 blocks of an edge.

## 3. Shield, axe, combo break

### 3.1 Shield (ADAPTIVE: only in SHIELD_BAIT)

* Raised from the offhand on the first tick of SHIELD_BAIT when we are in its 0.9-to-3.2 horizontal band and its
  attack is charged. Blocking starts 5 ticks later (vanilla raise delay).
* It stays up until the cooldown (12 to 19 ticks) has passed **and** we are inside 3.2 **and** its strength is at least
  0.9; then it drops it and swings on the same tick. Outside 3.2 the shield stays up indefinitely while it keeps walking
  in (no speed loss).
* It always faces us (instant turn), so circling to the open side never works.
* No reaction to our swings at all in ADAPTIVE (the predictive "raise when the target's attack is charged, 30%" logic
  runs only in SMP/DEFENSIVE).

### 3.2 Axe

Would disable our shield: after it sees our raised shield, it waits a reaction time (HARD: 2 to 4 ticks), then for a
12-tick window it swaps to its best axe and hits if its own strength is at least 0.9, then swaps back after 3 ticks.
SHIELD_BAIT's drop-swing also tries the axe. **The arena kit has no axe, so none of this happens.** We carry no shield
either.

### 3.3 Combo break

When it counts at least 2 "hits taken" within 25 ticks (one stunned hit already counts 6), it is on the ground, not
waiting for a crit and not shielding: each tick a 65% roll (HARD) to jump; after a jump it does not try again for 40
ticks. In practice: **the first ground tick after our hit lands it usually hops.** That hop is not a planned crit, but
its pattern can still swing while it falls (an incidental crit if its sprint flag is off).

## 4. Healing, items, retreat

* **Retreat**: health at or below **16** (gappleThreshold on HARD) and no totem just popped: drop the shield, reset the
  pattern state, state RETREAT for 60 ticks. While in RETREAT the combat tick does not run at all: **no attacks, no
  shield, no combo break**. It only runs away (2.5) and tries to heal. After 60 ticks: if health is at least max - 2
  (18) it returns to ATTACK, otherwise 20 more ticks of retreat, repeated.
* Healing tries: enchanted apple at 10 or below, golden apple then enchanted apple at 16 or below, any food below food
  level 8. **The arena kit has none of these**, so it only heals by natural regeneration.
* In our run data the bot sat at 12.71 health for about 20 seconds (round 18) and at 14.27 for about 15 seconds
  (round 16) without regenerating, so its food level is most likely below 18 (the fake player persists between rounds;
  the round reset clears effects and heals but does not feed it). **Inference, not verified**: check with
  `data get entity arenabot foodLevel` before relying on it. If true, **once it drops to 16 it never fights again that
  round**: the round becomes a chase.
* Totem: moved into the offhand whenever one is in the inventory (none in the kit).
* Potions: every 160 ticks it drinks or throws any potion whose effect it lacks or has under 200 ticks (none in the
  kit).

## 5. Weaknesses we can exploit

Ranked by expected value in the outgeared arena (our hit through diamond: about 1.56, crit about 2.61; its hit through
iron: about 4.85, crit about 8.35).

1. **The 16-health retreat.** Two crits or three plain hits put it at 16 or below and it stops fighting for at least 3
   seconds, in the arena probably for the rest of the round. The whole fight is "land 4 damage before taking 4 hits,
   then hunt". Our 3 wins were exactly the rounds where it fell under 16 (smoke round 2, rounds 6, 16, 18).
2. **It never attacks while retreating, and it runs slower than we sprint** (0.26 vs 0.28, and sprint-jumping is faster
   still). It faces straight away from us, a clear read from its yaw. It runs in a straight line into walls and
   corners, where its speed bleeds to 20%.
3. **Out-range.** Our reach is 0.1 to 0.2 blocks longer than its 3.2 centre gate on flat ground, and about 0.3 longer
   while it is airborne. It never holds spacing; it walks in on a predictable schedule. A hit that lands while its sprint
   flag is off stuns it for 6 ticks and pushes it out; it then needs roughly 6 to 15 more ticks to come back into 3.2,
   by which time our attack is charged again.
4. **Crit lock.** In JUMP_RESET (about 20% of patterns) it jumps only when we are already in range, then swings only on
   a falling tick within 3.2 feet-to-feet (about 3.0 horizontal at that height). If we are beyond about 3.05 horizontal
   during its ticks 7 to 10 after take-off, it lands **still waiting for a crit and cannot attack at all on the
   ground**; it stays disarmed until it is airborne and falling again (our knockback, a step-up auto-jump, or a ledge).
   While it is locked we can hit it on the ground freely; after each hit, stay beyond about 3.05 while it falls.
5. **Fixed cadence and pauses.** At most one swing per 12 to 13 ticks; no swing during its 6-tick stun, during the 3-to-8
   tick sprint-reset pauses after a pattern, while its shield is up, or while it waits for a crit fall. Its swing tick
   is visible (broadcast swing).
6. **Shield bait is a stall, not a threat** if we stay out of 3.2: it cannot drop the shield without us inside 3.2, and
   the drop and its swing are the same tick. The first 5 ticks after the raise do not block. After its drop-swing it is
   empty for about 12 ticks.
7. **Strafe is noise**: 12-tick direction phases (one third "none"), 70% of ticks sideways 0.12. Dodging is useless
   against it (no ray cast); aiming at it is easy if we do not over-lead its random per-tick flips.
8. **Combo-break hop on landing** (65% per ground tick after a hit): it hops right as it lands, predictable.
9. Hazards: SAFE pathing and ledge latch make "knock it into the ravine" weak; its forced sprint-knockback hits make the
   ravine dangerous for **us** (one loss was a fall).

## 6. Why our client withholds swings (telemetry: 1 swing in about 3 s at 2.4 to 4 blocks, strength 1.0; KITE or
RETREAT_HEAL at 11 to 13 HP)

Traced through `combat/assistant/CombatAssistant`, `TradeDriver`, `tactics/HitTradeSolver`, `HitPlanner`, `GearRead`,
`TacticUtility`:

* `GearRead.ratio()` against this kit is about 3 at full health and hits the 4.0 clamp once we are hurt, so
  `takenWeight` is at its 3.0 cap: the solver needs three hits landed to accept one taken.
* The solver models the bot as hitting whenever its read cooldown is ready and we are within `oReach = 3.0` eye-to-box,
  with no knockback stun, no pauses and no crit-only rule. Its end-position term then "hovers just outside their reach"
  (3.0 to 3.6 eye-to-box) whenever the opponent is read as charged. Because the bot's reach is really 2.9 and ours 3.0,
  hovering outside its modelled reach also keeps us outside ours. While the bot does not swing (stunned, pausing,
  shielding, crit-locked, **or retreating**), the cooldown read says "charged" and we hover forever. That is the 20-second
  stall at 12.71 health in round 18.
* `HitPlanner.critOrNothing()` is on at ratio >= 2.5: after the opener every hit must be a crit, which needs a hop
  timed so that we are in reach on the falling tick; the solver keeps pulling us out of reach, so the crit window rarely
  lines up and `want` stays false. The rule's premise (plain hits deal less than the opponent regenerates) is false
  here: the bot regenerates slowly or not at all, and plain hits push it toward the 16 retreat line just as well.
* `TacticUtility`: with a high gear ratio the advantage is about 0.1 to 0.2, and the bot's crit hops and combo-break hops
  give a high `oppHopRate`, so KITE outscores FIGHT and widens the band by 0.5. At 11 to 13 HP with food 20 and some
  saturation, `canRegenerate` makes RETREAT_HEAL (in duel mode) win; the reset heals us about 3 HP at most while the bot
  chases at almost our speed, and it costs the hits we need.
* `ShieldDuelStage` circles toward the open side of a raised shield; against an instant-turning bot there is no open
  side, so those ticks are lost.

## 7. Mapping to our code

| Finding | Class to change | Change |
|---|---|---|
| Retreat read (5.1, 5.2) | `tactics/OpponentModel` | New per-opponent read "retreating": its yaw points away from the bearing to us (within about 30 degrees) and it moves away at 0.2+ per tick for 5+ ticks, no swing seen. Also "health under its observed disengage line" (learned: the health at which it turned away). |
| Retreat read | `assistant/TradeDriver.fill` | While retreating: `oReadyIn` = horizon + 1 (no threat), `takenWeight` at its 0.8 floor, so the solver steps in. |
| Retreat read | `assistant/CombatAssistant` (pursuit, 1d) | Enter pursuit as soon as the target retreats and is beyond our reach (not only beyond spacingMax + 6); sprint-jump on open ground; drive it toward walls/corners (the arena field already rates walls). |
| Retreat read | `tactics/TacticUtility` | Input "opponent retreating": FIGHT wins outright, KITE and RETREAT_HEAL score 0. |
| Crit-or-nothing is wrong here (6) | `tactics/HitPlanner.critOrNothing / choose` | Require evidence that the opponent out-regenerates plain hits (observed health recovery, not the gear ratio alone). Against this bot prefer SPRINT_KB in the fight phase (knockback buys the stun loop) and CRIT only while it is walled or retreating into a wall. |
| Bot range and stun (5.3) | `tactics/TradeInput`, `assistant/TradeDriver.fill` | `oReach` per opponent from observed hit distances (`OpponentModel` records our distance at each hit taken, keeps a high percentile); about 2.9 for this bot. |
| Bot range and stun | `tactics/HitTradeSolver.rollout / hit` | After our landed hit on a non-sprinting opponent, push its charge to at least 7 ticks (stun); opponent airborne: shrink its reach by the 3D feet-distance rule (or learn it). Optional: an "opponent idle after combo" pause of 3 ticks with low weight. |
| Crit lock (5.4) | `tactics/OpponentModel` + `tactics/ComboKeeper` | Track "pending crit": it hopped inside our reach while charged and has not swung; predict its falling ticks (7 to 10 after take-off) and keep beyond 3.05 horizontal during them (keeper candidate cost). After it lands unswung, mark it "locked": no threat while it is on the ground; after each of our hits on a locked bot, back out during its fall. |
| Swing cadence (5.5) | `tactics/HitTradeSolver.positionTerm` | Hover band relative to the learned `oReach` (2.9 + 0.1 to 0.3, i.e. inside our 3.0), not 3.0 + 0.6. |
| Shield bait (5.6) | `assistant/stages/ShieldDuelStage`, `tactics/ShieldDuel` | Detect instant-turn opponents (its facing error to us is always near 0); then do not circle; hold just beyond 3.2 centre while its shield is up, swing in the first 5 ticks of a raise, and punish right after its drop-swing. |
| Strafe noise (5.7) | `tactics/StrafePhaseModel`, aim lead | Lower velocity lead when the opponent's lateral velocity flips sign often (per-tick 0.12 sideways jitter); the 12-tick phase is learnable but low value. |
| Our own resets (6) | `tactics/TacticUtility` | KITE's hop-pressure term should not count hops that are combo-break hops or crit hops we can range out; RETREAT_HEAL on natural regeneration only when the opponent cannot keep up (its observed chase speed below ours) or is itself disengaged. |
| Ravine (5.9) | `tactics/ArenaField` | Lower the knock-to-drop value against a target that has shown mid-air recovery; keep our back off the ravine against sprint-knockback combos (SPRINT_HITS: three hits). |

## 8. Top five changes most likely to flip the win rate

1. **Chase a retreating opponent and treat it as harmless** (`OpponentModel` retreat read, `TradeDriver.fill`,
   `TacticUtility`, pursuit in `CombatAssistant`). Expected: every fight that reaches the bot's 16-health line becomes a
   win instead of a stall or a timeout; removes the 20-second hovering seen in rounds 16 and 18.
2. **Drop crit-or-nothing against this opponent; swing every charged tick in reach in the fight phase** (`HitPlanner`).
   Expected: 3 to 4 swings in the first 3 seconds instead of 1, enough to reach the retreat line before we take the 4
   hits that kill us.
3. **Model its real threat: reach about 2.9 eye-to-box, 6-tick stun after our landed hit, no swing while it waits for a
   crit fall** (`HitTradeSolver`, `TradeInput`, `OpponentModel`). Expected: the solver steps in on our out-range edge and
   after each landed hit instead of hovering outside our own reach; first strike on most of its approaches.
4. **Range out its crit jump and exploit the crit lock** (`OpponentModel` pending-crit state, `ComboKeeper` cost).
   Expected: denies the 8-damage crits (two of them kill us from 20 with one plain hit) and creates ground windows where
   it cannot attack at all.
5. **Stop resetting and kiting against it** (`TacticUtility` KITE pressure and RETREAT_HEAL rules,
   `ShieldDuelStage` no circling vs instant turn). Expected: no more surrendering tempo at 11 to 13 HP; the fight stays on
   our terms long enough for changes 1 to 3 to land the 4 damage that ends its offence.
