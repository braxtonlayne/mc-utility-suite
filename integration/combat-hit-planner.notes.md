# combat-hit-planner

Upgrade pass 2026-11-22, items 2.1 (hit planner) and 2.3 (gear read, duel mode). Owner: "Its combat could also
improve." New units: `combat/tactics/HitPlanner`, `combat/tactics/GearRead` (both engine free, allocation free).
Changed: `CombatAssistant`, `CombatSettings`, `CombatSnapshot`, `mc/MinecraftCombatWorld`, `TacticUtility`.

## Vanilla rules verified (NeoForge 21.1.265 sources jar, `build/moddev/artifacts/neoforge-21.1.265-sources.jar`)

| Rule | Source | Result |
|---|---|---|
| A sprint hit is never critical | `net.minecraft.world.entity.player.Player#attack(Entity)`: `flag1 = flag4 && fallDistance > 0 && !onGround() && !onClimbable() && !isInWater() && !hasEffect(BLINDNESS) && !isPassenger() && target instanceof LivingEntity && !isSprinting()` | Confirmed. |
| Sprint knockback | same method: `flag = isSprinting() && flag4` (`flag4 = charge > 0.9`), no ground check; +1 knockback, then `setSprinting(false)` and x0.6 horizontal speed | Confirmed ("or rising" in the design is right: airborne sprint hits also knock back). |
| Crit needs | charge > 0.9, `fallDistance > 0`, not on ground, not climbing, not in water, not blind, not riding, not sprinting | Confirmed. Any fall above 0 counts, so the first falling tick crits. |
| Sweep | same method: `flag4 && !critBlocksSweep && !flag && onGround() && walkDist - walkDistO < getSpeed()` and the main hand `canPerformAction(ItemAbilities.SWORD_SWEEP)` | **Differs from the design**: besides sword, ground, no sprint, full charge, vanilla also needs the player to be moving slowly: walk distance this tick (0.6 x horizontal move) below the movement speed attribute (0.1 walking), i.e. under about 0.167 blocks per tick. Walking forward at full speed never sweeps. The planner follows the source: a SWEEP releases forward to slow down, and the "never sweep" guard treats a fast ground hit as safe. |
| Sweep targets | `getEntitiesOfClass(LivingEntity, getSweepHitBox)`; NeoForge default `IItemExtension#getSweepHitBox` = target box `inflate(1.0, 0.25, 1.0)`; skips self, the target, `isAlliedTo` (team allies), marker armour stands; needs `distanceToSqr < entityInteractionRange^2` | Confirmed: neutral mobs, villagers and pets are hit (only teams protect). |
| Client sprint in the air | `net.minecraft.client.player.LocalPlayer#aiStep`: sprint stops only on `!input.hasForwardImpulse()` (or a wall, water, hunger); the sprint-key path `keySprint.isDown() && forwardImpulse >= 0.8` restarts sprint with no ground check | Confirmed, plus a detail the design did not have: with the sprint key held, sprint comes back in the air the tick forward is pressed again. So the planner releases forward and sprint together from the apex until the swing. |
| Order of a tick | `Minecraft#tick` handles key binds (the attack packet) before `LocalPlayer#tick` sends the movement and sprint state | The server sees the sprint state from the previous tick's movement packet on the swing tick: forward must be up the tick before the swing; the planner swings only once the sampled `sprinting` is false. |
| Armour | `CombatRules#getDamageAfterAbsorb` (`GearRead.afterArmor`), ARMOR and ARMOR_TOUGHNESS are `setSyncable(true)` in `Attributes` and sent to trackers by `ServerEntity` | Confirmed; target armour is read from the synced attributes. ATTACK_DAMAGE is not syncable: the target's weapon is read from its held item's modifiers. |

## HitPlanner (combat/tactics)

Kinds `SPRINT_KB`, `CRIT`, `SWEEP`, `PLAIN`; policy `AUTO / KNOCKBACK / CRITS / PLAIN` (setting "Hit choice",
advanced). AUTO, in order: arena read toward lava or a drop -> SPRINT_KB; opener and re-engage (20 ticks more than a
block out of reach) -> SPRINT_KB; live combo plan -> SPRINT_KB; sword + the target and at least one more hostile in
the sweep box + nothing protected there -> SWEEP; otherwise CRIT when worth it (walled, eating, no combo follow-up,
gear ratio >= 1.15, or a mob with 20+ health that does not launch) and a hop is allowed, else SPRINT_KB. SWEEP is
checked before the crit rules because "no combo follow-up" is true for almost every non-combo hit and would make
sweeps unreachable.

- The kind is chosen freely until the attack is 8 ticks from charged, then latched until the swing. A latched CRIT
  still on the ground is re-planned when a hop becomes a liability.
- Hop: `ticksUntilReady(charge, attack speed) <= hopLead(headroom)` (6 for open sky, 5 under a 3-block ceiling),
  within reach + 0.6, once per hit. Works for axes and maces (the 60% `SwingGate.PRE_JUMP` is no longer used by the
  assistant; `SwingGate.decide` stays for its tests and the coach).
- No hop: drop or lava behind (`hazardBack`), headroom <= 2, gap ambush (HOLD_GAP), in water / climbing / using an
  item, the opponent player's attack charged (cooldown read says ready) within 3.6 blocks, or Critical-hit timing off.
- CRIT: forward and sprint released while `pvy < 0.12` (apex and fall); swing when falling, not sprinting, charged;
  plain swing after 8 ticks at full charge without a fall.
- SPRINT_KB: holds sprint while stepping forward; waits at most 2 ticks for sprint, and only while forward and sprint
  are held and sprint is on its way (so a fresh engage in reach swings at once, as before).
- SWEEP: 2 ticks before charged, sprint off and forward released while faster than 0.15 b/t; swing when slow on the
  ground, or after 3 ticks.
- Guard for every kind: no ground swing that would sweep (sword, ground, no sprint, slower than 0.17 b/t) while
  anything protected is in the sweep box; sprint is asked for instead.

## Snapshot and world sampling

- `CombatSnapshot.fallingForCrit()` now also requires `!sprinting` (the coach's crit-ready readout gets this fix too).
- New fields: own attack speed, attack damage, armour, toughness, `sweepWeapon`; target armour, toughness, estimated
  attack damage, `targetUsingItem`, `targetHeavyHitter`; `sweepHostiles`, `sweepProtected`.
- `MinecraftCombatWorld.sampleHitChoice`: attribute reads per tick (no allocation); the target's weapon damage is
  cached by its modifier component like the attack speed; the sweep-box census runs every 5 samples (the proactive
  scan's cadence, reusing its `scanBuffer`, max 16 entities), never per tick. It allocates one AABB and the level's
  section walk per run, like the proactive scan. Hostile = `Enemy` and not `NeutralMob`, not a piglin, not a player;
  everything else is protected (players of any kind, pets, villagers, golems, passive animals, armour stands). That is
  stricter than "roster ally" for players: a sweep never touches a bystander.

## What changed in CombatAssistant.drive

- Step 5 (swing) now feeds `HitPlanner` (policy, crit timing, player target, `feel.comboDrives`, HOLD_GAP, opponent
  charged from the cooldown read, gear ratio, sprint pending) and follows it: jump tap through the smoother, swing via
  `tapAttack` (mace swap kept), and three per-tick overrides applied in `move()`: hold sprint while stepping forward
  (SPRINT_KB), release forward + sprint (CRIT apex, SWEEP slow-down), drop sprint. Every key still goes through the
  lease and `IInputSmoother`; no new key writes outside `core.input`; the static grep rule is unchanged.
- `tapAttack` tells the planner about every swing (shield break and gap-ambush swings too).
- `tradeAllowJump` also stays off while the planner owns a crit hop.
- The trade solver's taken weight is `GearRead.takenWeight(kiting)` (1.2 even, up to 3.0; x1.5 while kiting) instead
  of 1.2 / 1.8. The combo keeper's trade input keeps the even 1.2 (`BASE_TAKEN_WEIGHT`, now `GearRead.EVEN_BIAS`).
- Gear read inputs: estimates from the snapshot every tick; observed hits from the health drop that follows the
  target's own hit event (5-tick window) and from the target's health drops.
- `TacticUtility` gets `gearRatio` (advantage = mean of the DPS read and `1 / (1 + ratio)`) and `duelMode`.
- Duel mode ("Never disengage (duel mode)", Tactics, default off): `TacticUtility` never returns FLEE (its score goes
  to RETREAT_HEAL); the assistant runs RETREAT_HEAL as a reset (eat a hotbar golden apple once 6+ blocks away, else
  run the flee route / back off) and returns to the fight when FIGHT wins again. OUT_OF_RANGE and LINE_OF_SIGHT_LOST
  never fire while the target is alive and loaded; TARGET_LOST still does. Sustain RETREAT already backs off until
  recovered; with the range check suppressed it now comes back instead of dropping the fight.
- HUD: unchanged (no new status line text or overlay).

## Tests

`HitPlannerTest` (24: one per rule, plus two body-sim runs on `PhysicsBodySim` with the `LocalPlayer.aiStep` sprint
rule modelled in the test: every planned crit swing tick has `sprinting == false`, `fallDistance > 0`, airborne, at
least 6 crits in 120 ticks; the opener sprint-knockback hit is on the ground and sprinting), `GearReadTest` (even gear
1.2, 3:1 clamped to 3.0, before the first hit from visible gear, smoothing, vanilla armour formula),
`HitPlannerAllocationTest` (`decide` and `GearRead.update`, 50k calls under 1 KB), `assistant/DuelModeTest` (never
FLEE over a grid of fights; mid-flight switch; gear ratio in the advantage; end to end: a target 40 blocks away and out
of sight stays engaged in duel mode, is dropped without it). Existing combat tests, solver allocation tests and
`AcceptanceScenario4Test` pass unchanged.

## Left from the design

Shield duel (2.2), healing in windows and pre-staging (2.4), threat-aware mob-pack fights (2.5), ranged opener and gap
answers (2.6), combo escape (2.7), weapon choice by enchantments / target armour / durability (2.8), fight log for
the benchmark (2.9). Also not done here: the "shield disabled" crit trigger (needs the shield duel's state), a
SPRINT_KB that waits for the solver to step in when the engage starts in reach (today it swings at once), protection
enchantments and difficulty scaling in the gear estimate.

## In-game checks for the owner

1. Zombie, sword, flat ground: the first hit is a sprint knockback hit (knockback sound, zombie flies further), then
   one hop per hit, each hit shows the crit particles and crit sound; no hops between hits.
2. A player or a husk at range: the opener is a sprint knockback hit.
3. Two zombies close together with a sword: sweep particles on hits. Then a tamed wolf or a villager next to the
   target: no sweep particles ever, the wolf and villager take nothing.
4. Stronger kit (they have netherite and Sharpness, you iron): the assistant stops stepping into even trades, backs off
   more often and goes for crits.
5. Duel mode on in a closed arena: when losing it backs off, eats, then comes back; walking 20 blocks away or breaking
   line of sight does not end the fight.
6. Hit choice set to Knockback / Crits / Plain: every hit is that kind.
7. `/spark profiler start`, fight a pack for a minute, `/spark profiler stop`: `CombatAssistant.tickEngaged` and
   `MinecraftCombatWorld.sample` stay small; the sweep census shows only every fifth tick.
