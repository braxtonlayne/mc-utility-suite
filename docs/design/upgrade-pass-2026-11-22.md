# Upgrade pass: combat and general features, 2026-11-22

Review after the owner's hands-on test of the merged next-layer build ("its combat could also improve. Same for the
other features generally"). Scope: combat, plus the weakest spots in jobs, survival, tools, world and travel. Parkour
control and UI visuals are out of scope here (other designs own them).

Method: read the combat package (`CombatAssistant`, `SwingGate`, `SustainPlanner`, `SustainExtras`,
`ShieldBreakSequencer`, `AerialPlanner`, `SpacingController`, `CombatSnapshot`, `CombatSettings`, `TradeInput`,
`HitTradeSolver` model, `ComboKeeper` header, `TacticUtility`, `ShieldDecision`, `MinecraftCombatWorld.bestMeleeSlot`),
the roadmap, the three run notes and the research notes, and skimmed the class contracts of the other modules. Nothing
was run in a game client. Claims marked **(read)** come from the code; **(roadmap)** come from open roadmap items;
**(verify)** are vanilla-rule assumptions to confirm in a test world before building on them.

## 1. Where combat stands

The last two volleys built the hard, clever parts: a forward-simulating trade solver, a cooldown read, a combo keeper,
anticipatory aim, an arena read, feints and pearl chases. What a player feels in a fight is decided by simpler things
underneath, and several of those are still first-version:

| Area | Today | Why it feels weak |
|---|---|---|
| Which hit to throw | `SwingGate` hops on every swing cycle when Critical-hit timing is on (the default) **(read)**. `CombatSnapshot.fallingForCrit()` does not look at sprint **(read)**. | In 1.21.1 a hit made while sprinting is never a critical hit, and a critical hit never has sprint knockback. The solver and the keeper hold sprint while stepping in, so many "crit hops" are plain airborne sprint hits: no crit damage, and the assistant is in the air (more knockback taken, no ground control) for nothing. Nothing decides knockback hit versus damage hit. |
| Shields (theirs) | Axe swap only when a hotbar axe exists; otherwise `SwingGate` keeps swinging into the raised shield **(read)**. | A blocked hit deals nothing, burns the cooldown and pushes the attacker back. No flank, no wait for the shield to drop, no pressing the 5-second window after an axe disables it. |
| Shields (ours) | A reflex in `combat_reflexes` raises against a threat already in the front cone; it does not share intent with the swing or the solver **(roadmap)**. The totem keeper fills the offhand by default, which leaves no room for a shield. | Blocking their charged hit and answering inside their cooldown is the strongest gear-neutral tool in 1.9+ combat. It is not part of the fight plan. |
| Healing | A golden apple at a fixed 8 half-hearts, eaten while walking backward facing the opponent; consumables are pulled from the inventory only once health is already low **(read)**. Plain food is never eaten in a fight; Auto-Eat is hotbar-only **(read)**. | Eating takes 32 ticks at a crawl. Against a stronger opponent that is two or three free hits at the moment the assistant can least afford them. A good player eats in a window they created. |
| Gear disadvantage | The solver counts hits, with a constant 1.2 weight on hits taken (1.8 when kiting) **(read)**. | One of their hits can be worth three of ours. The solver should know that and refuse even trades. |
| Mob packs | While engaged the target is only re-picked when it is lost, and only in Proactive mode **(read)**. The dodge and the creeper back-off are switched off while the assistant is engaged (`CombatReflexes` line 191) **(read, roadmap)**. | Single-player is where the owner plays most. Fighting a zombie while a creeper walks up, or standing still in a skeleton's line of fire, is the most visible failure. |
| Ranged | None in the assistant. `ThreatMath` has the ballistics **(roadmap)**. | No opener while closing, no answer to a target across a gap except waiting. |
| Weapon pick | Highest damage x speed from attribute modifiers, once per engagement **(read)**. | Ignores enchantments (Sharpness, Smite, Bane), the target's armour, and a weapon that breaks mid-fight. |
| Getting comboed | Jump reset exists, off by default, limited to single-player or allow-listed servers, and it reacts on the hit tick **(read)**. | There is no plan for the moment the opponent has the assistant in a combo: it keeps running the solver from the air. |
| Block-hitting | Not present. | Correctly absent: sword blocking was removed in 1.9. The 1.21.1 equivalent is the shield tap between swings (section 2.2). No work item. |

## 2. Combat upgrades, ranked by feel per effort

### 2.1 Hit planner: choose the hit, then make it happen (highest leverage)

One small engine-free unit, `combat/tactics/HitPlanner`, decides the **kind** of the next hit a few ticks before the
attack is charged, and the swing, hop and sprint keys all follow that one decision.

Kinds and what each needs on the swing tick:

| Kind | Needs | Gives |
|---|---|---|
| `SPRINT_KB` | on the ground (or rising), sprinting, full charge | +1 knockback level, starts or continues a combo, makes room |
| `CRIT` | airborne, falling, **not sprinting**, charge above 0.9 | 1.5x damage, normal knockback |
| `SWEEP` | on the ground, not sprinting, sword, full charge | hits everything within about a block of the target |
| `PLAIN` | full charge | fallback when none of the above can be set up in time |

Execution details that matter:

- **Crit means dropping sprint in the air.** Client sprint persists while W is held **(verify)**, so a crit needs a
  1-tick W release after takeoff and before the swing. That release is also the sprint reset for the next hit, so a
  crit followed by a sprint hit costs nothing extra.
- **Low crits.** Swing on the first falling tick (any fall distance above zero counts), not later in the fall. With a
  sword the cadence closes: 12.5-tick cooldown, 12 ticks airborne. Time the hop so the first falling tick is the tick
  the attack is charged (hop when the attack is ready in about 6 ticks; fewer under a low ceiling). `SwingGate.PRE_JUMP`
  at 60% charge is a fixed stand-in for this and is wrong for axes and maces.
- **No hop when being airborne is a liability:** a drop or lava behind, low headroom (already checked), the opponent's
  attack charged and in reach with no shield up, or the trade solver wanting a ground step this tick. Today only
  `HOLD_GAP` and headroom stop the hop.

Default policy (pro-player defaults, every rule a setting under an advanced "Hit choice" group, with a fixed override
`AUTO / KNOCKBACK / CRITS / PLAIN`):

1. Opener and every re-engage after a gap: `SPRINT_KB`.
2. While `ComboKeeper` has a live plan that reaches the next hit: `SPRINT_KB`.
3. `CRIT` when knockback is worth little or damage is worth a lot: the target is against a wall or cornered, its
   shield is disabled or it is eating, the keeper has no follow-up, the gear read (2.3) says we are out-damaged, or it
   is a mob with a lot of health and no knockback threat.
4. `SWEEP` when two or more hostiles stand in the sweep box. **Never sweep** when a roster ally, a pet, a villager or
   a neutral mob (wolf, iron golem, zombified piglin) is in the box: pick `SPRINT_KB` or `CRIT`, neither of which
   sweeps. Today nothing prevents a sweep from angering a piglin crowd.
5. Arena read active toward lava or a drop: `SPRINT_KB`, always.

Budget: a handful of comparisons per tick, no allocation. Tests: one behaviour test per rule, an allocation test, and a
physics test that the planned crit really has `sprinting == false` and `fallDistance > 0` on the swing tick (reuse the
body sim).

Feel: very high (damage output visibly rises, the pointless hopping stops). Effort: medium. Tier: opus.

### 2.2 Shield duel: stop feeding shields, start using one

Three parts, one unit (`combat/tactics/ShieldDuel`), sharing swing intent with the existing reflex.

- **Their shield, no axe in the hotbar.** Never swing into a frontal raised shield. A shield covers the half-space in
  front of its holder: circle to get past 90 degrees off their facing (the strafe planner already circles), or hold
  just outside reach and hit on the tick they lower it to attack (they must lower to swing, and a re-raised shield
  needs 5 ticks before it blocks again).
- **Their shield, axe available.** Keep the sequencer, then use the result: an axe hit disables the shield for 100
  ticks. Record `shieldDisabledUntil` in `OpponentModel`, and for that window switch the hit planner to `CRIT`, lower
  the taken-weight, and suppress feints and retreats. Today the assistant swaps back and carries on as if nothing
  happened. Also re-check `MAX_WAIT = 10`: a turtling opponent makes it give up and try again in a loop.
- **Our shield.** A block-and-answer loop against a sword or bare-hand opponent: raise 5 ticks (plus reaction margin)
  before their attack is charged and they are in reach (the cooldown read already gives `attackReadyIn`), take the hit
  on the shield, lower, hit inside their cooldown (our attack keeps charging while blocking), raise again. Never raise
  into an axe holder's swing (it disables the shield for 5 seconds): against axes use the long 20-tick cooldown window
  instead, which the cooldown read already exposes. Under Legit the shield only goes up when the opponent is in the
  front cone, as `ShieldDecision` does now.
- **Offhand policy.** With the keeper on, the totem occupies the offhand. In a fight against a player or a ranged mob,
  prefer the shield while effective health is above the lethal margin and swap the totem in below it (one SWAP click,
  already implemented for the totem). This belongs in `OffhandRules` as a new rule ahead of "preferred item", so the
  Offhand Manager and the combat keeper agree.

The solver needs two fields to cooperate: `TradeInput.sBlocking` (their hit counts as zero from the front) and
`oShieldUp` (our hit counts as zero and costs a pushback). Without them the solver will walk out of range of a hit the
shield would have eaten.

Feel: very high against players and skeletons; it is the main way a low-tier kit beats a stacked one. Effort: medium
to high. Tier: opus.

### 2.3 Gear read: weigh trades by damage, not by count

`combat/tactics/GearRead`, engine-free, fed by numbers the assistant already measures (`lastHitDamage`, the dealt
model, both health values):

- `takenWeight = clamp((their damage per hit / our effective health) / (our damage per hit / their effective health),
  0.8, 3.0)`, smoothed over the last few hits, replacing `BASE_TAKEN_WEIGHT` and `KITE_TAKEN_WEIGHT`. Before any hit
  has landed, estimate from visible armour and the held weapon (the Target HUD already reads both).
- The same ratio feeds `TacticUtility` (it uses observed DPS, which lags by seconds) and the hit planner's rule 3.
- A **duel mode** setting ("Never disengage"): FLEE and RETREAT are replaced by "reset" (make distance, heal, come
  back). The benchmark arena needs this, and it is also the right behaviour in a closed arena or a boss room.

Feel: high from a disadvantage, invisible when even. Effort: low. Tier: sonnet.

### 2.4 Healing in windows, staged before the fight

Rework `SustainPlanner` around two ideas: heal when it is **safe**, and decide in **hits left**, not half-hearts.

- **Trigger:** effective health at or below `k` of their measured hits (default 2.5), instead of a fixed 8.
- **Window:** start eating only when the opponent cannot hit for most of the 32 ticks: right after a sprint knockback
  hit that sent it away, after knocking it off a ledge, while its shield cooldown or a long axe cooldown runs, or
  after breaking line of sight. If there is no window, **make one**: a sprint hit for distance, then the existing
  route flee around cover, then eat, then re-engage (the roadmap's "retreat-and-heal loop"). Backing up in a straight
  line while eating stays only as the last resort.
- **Staging at engage time:** pull the best consumable into the hotbar when the fight starts (the click costs nothing
  then), not when health is already low. `SustainExtras.shouldPull` is gated on low health today.
- **Top-ups between exchanges:** natural regeneration needs hunger 18+, and saturation drains fast in a fight. When
  the opponent is away (pursuit, a pearl chase, after a kill) eat normal food down to "no waste". This is one call
  into the Auto-Eat logic with the fight's window test.
- **Pre-fight absorption** against a player or a boss-class mob when golden apples are plentiful (setting, default
  off: it spends items).
- **Splash healing** when present: look down (the wind-charge sequence already has a smoothed `AIM_DOWN`), throw at
  the feet, look back. About 4 ticks of turn at the 450 deg/s cap, far cheaper than 32 ticks of eating.

Feel: high (it stops dying mid-apple). Effort: medium. Tier: opus for the planner and the window logic, sonnet for the
snapshot fields.

### 2.5 Threat-aware fights against mob packs

- **Re-pick while engaged.** Every 5 ticks (the existing dormant scan budget), in both engagement modes, score the
  current target against nearby hostiles that are already attacking, with hysteresis so it does not flap. A swelling
  creeper, a skeleton drawing a bow at close range or a mob about to hit from behind outranks a zombie at half
  health. Reactive mode stays reactive: only mobs that are already hostile to the player qualify.
- **Reflexes inside the fight.** Give the assistant the dodge and fuse back-off through its own lease instead of
  switching them off: `DodgePlanner`'s strafe tap becomes a forced strafe for the solver (one more veto alongside the
  hazard flags), and a fuse back-off overrides spacing until the blast radius is cleared.
- **Mob playbook.** A small static table keyed by entity type id (string ids, so it stays testable and works for
  modded mobs with a default row): preferred hit kind, spacing band, and one special rule each. Starter rows: creeper
  (sprint hit, back out past the blast radius while it swells, return), skeleton and stray (close in on a strafe,
  shield up while closing), spider (no crit hops into its leap), enderman (fight under a 2-high roof or stand in
  water when the arena read finds one), witch (rush, never kite), baby zombie (aim at its real box, no hops), blaze
  and ghast (ranged answer from 2.6), warden and wither (never engage proactively).
- **Pack handling.** With three or more melee mobs in range, back away along a hazard-free line so they string out,
  and let the hit planner sweep.

Feel: very high in single-player. Effort: medium. Tier: opus for the wiring in `CombatAssistant` and
`CombatReflexes`, sonnet for the playbook table and the re-pick scoring.

### 2.6 Ranged opener and gap answers

- Bow or crossbow while closing from beyond about 8 blocks, at a target across a gap during `HOLD_GAP`, and at a
  runner; switch to melee inside 6. Lead from `ThreatMath`, with the charge released at full draw. The smoothed
  rotation cap already bounds how fast the aim can settle, so the shot waits for the crosshair.
- Loaded crossbow as a pre-fight item: fire it as the opener, then draw the weapon.
- Rod, snowball and egg knockback on players has changed between versions **(verify in 1.21.1 before designing
  around it)**. If it works, use it only with the arena read: a projectile hit that pushes a target standing next to
  a drop.

Feel: medium to high (very visible, and it adds free damage before the melee starts). Effort: medium to high.
Tier: opus.

### 2.7 Getting out of a combo

- **Predicted jump reset.** Press jump on the tick the opponent's hit is expected (`attackReadyIn` plus reach), which
  is how players do it, instead of reacting on the damage tick. That makes it a timing skill that can miss, so it can
  be considered for Balanced tuning without the single-player gate. **Owner decision:** keep the gate or relax it for
  the predicted variant.
- **Combo breaker.** Two hits taken without landing one: stop running the solver from the air. In order of
  preference: shield up (if 2.2 allows), strafe out of the line perpendicular to the knockback, turn the next landing
  into a hit only if the attack is charged; never backpedal in a straight line (that is the line the combo runs on).
- **Pinned against a wall:** slide out along the wall toward the side with more open columns in the arena read.

Feel: medium to high against players. Effort: low to medium. Tier: sonnet.

### 2.8 Smaller fixes

- **Weapon choice.** Add enchantment damage for the target's type, prefer damage per hit over DPS when the target is
  armoured, re-pick when the weapon breaks or the target changes, and prefer the axe as the main weapon when the
  opponent keeps a shield up (`OpponentModel` shield-use rate is an open roadmap item). Tier: sonnet.
- **Solver fidelity.** Knockback resistance (netherite), Knockback and Punch levels, an airborne victim, and a
  latency offset: `SwingGate.HURT_HOLD` is a constant 2 ticks; derive it from the measured ping (the player
  list entry carries it). Tier: sonnet.
- **Mace and wind charge** stay opt-in; fold `shouldSwapToMace` into the hit planner as a fifth kind later.

### 2.9 What the benchmark needs before it is worth running

The pass bar is 8 of 10 from a gear disadvantage against the strongest open-source bot. Against a stacked opponent an
even hit count loses, so the wins must come from: hits that do not trade (2.1 timing plus the existing solver, 2.3
weighting), blocked hits (2.2), environmental kills (arena read, already built), and healing that is never
interrupted (2.4). Open-source bots mostly walk straight in and swing on cooldown, which is exactly what a
block-and-answer loop and a knockback-then-reset cadence punish.

Two cheap pieces make every later tuning step objective:

- **Duel mode** (2.3), so a flee cannot end a round.
- **Fight log.** An engine-free ring of per-fight counters (hits landed and taken, hit kinds, shield blocks both
  ways, damage both ways, time in the air, heals started and interrupted, cause of the end), written as one compact
  JSON line per fight off-thread. The black box already does this for deaths; this is the per-fight version. With it,
  "does the hit planner help" is a number, in the arena and in normal play.

## 3. Other features: top weak spots

Ranked by how much a player would notice. Parkour and UI are excluded.

| # | Area | Today | Upgrade | Feel | Effort |
|---|---|---|---|---|---|
| G1 | Auto-Eat (`jobs/survival`) | Hotbar food only; the best food in the main inventory is ignored and the player starves with a full pack **(read)**. | Pull food from the inventory (same SWAP path the combat pull uses), prefer high-saturation food for long jobs, eat before a long sprint or a fight rather than at the threshold, never interrupt a block mid-break. | High | Low |
| G2 | Hotbar refill (`tools/inventory`) | Refills a slot only with the identical item **(read)**. A pickaxe that breaks leaves an empty slot. | Class-aware refill: a broken tool or weapon is replaced by the next best of the same class, a used totem by the next totem, an emptied food slot by the next best food. Pinned slots keep their rule. | High | Low |
| G3 | Vein Work (`jobs/fieldwork`) | Leaves blocks that are out of reach **(read)**; a tall tree is half felled. | Walk to them with the local route plan (already used by combat), pillar for the last logs when blocks are available, replant a sapling, wait for and collect the drops. | High | Medium |
| G4 | Armour in a fight (`tools/inventory`, `travel/mobility`) | Auto-armour never touches an elytra slot **(read)**. | When a fight starts on the ground, swap the elytra for the best chestplate and back afterwards; equip a looted upgrade only in a safe window (no hostile attacking). | Medium to high | Low |
| G5 | Build Assist (`jobs/schematic`) | Stuck at 9 of 10 with a "hidden" cell **(roadmap)**; block states not enforced **(roadmap)**. | Fix the regression first, then the shared placement ladder (reposition, pillar one block, dig a pocket) and facing/half/axis states. A build that finishes is the feature. | High | Medium to high |
| G6 | Strip mining (`jobs/goal`, `jobs/planner`) | Torch cells and wall glances are planned but not acted on; no hazard-averse hint for SAFEST **(roadmap)**. | Place the planned torches, plug lava the tunnel opens (Lava Plug exists as a module), pass the hazard hint to the miner. | Medium | Low to medium |
| G7 | Cross-dimension travel (`travel/autopilot`, `world/waypoints`) | Fails with a message **(roadmap)**. | Portal routing through registered portal waypoints with the 8:1 conversion that already exists. | Medium | Medium to high |
| G8 | Goal engine crafting (`jobs/goal`) | Waits when a recipe is locked **(roadmap)**. | Fill the grid by clicks so a locked recipe still crafts. | Medium | Low to medium |
| G9 | Detours (`jobs/goal`) | Walks past exposed ore and dropped items it needs **(roadmap)**. | Bounded detours from the block tracker. This is the "it is smart" moment for jobs. | High | High |
| G10 | Jobs and threats (`combat/reflex`, `jobs/goal`) | Creeper back-off during jobs is unit-tested only; the job can walk back into the blast spot **(roadmap)**. | A no-go zone around the hazard position for the resume, with the distance-aware hold that is already stubbed. | Medium | Low |

Common thread: the modules do the first thing a player would do and stop at the first obstacle. The cheapest wins
(G1, G2, G4) are all "look in the whole inventory and pick the next best thing", and can share one small engine-free
helper for "best item of class X in slots 0..35".

## 4. Order of work

Feel per effort, combat and general together:

1. Hit planner (2.1)
2. Gear read and duel mode (2.3), fight log (2.9)
3. Auto-Eat from the inventory (G1) and class-aware hotbar refill (G2)
4. Shield duel (2.2)
5. Threat-aware fights (2.5)
6. Healing in windows (2.4)
7. Getting out of a combo (2.7)
8. Vein Work reach (G3), fight-time armour (G4)
9. Ranged opener (2.6)
10. Weapon choice and solver fidelity (2.8)
11. Build Assist (G5), strip mining (G6), then G7 to G10

Items 1, 2 and 3 touch disjoint files and can run in parallel. 4, 5, 6 and 7 all edit `CombatAssistant.drive`:
either run them in sequence, or first extract section 1 (sustain) and section 5 (swing) of `drive` behind seams the
way `ProFeelLayer` did, and then build in parallel.

## 5. Rules every item keeps

- Engine-free decision units (snapshot in, keys and a hit kind out), string registry ids, no `net.minecraft` imports
  in `combat/tactics`. Minecraft access stays in `combat/assistant/mc` and `combat/reflex`.
- All keys, hotbar selections, rotations and clicks through the lease, `IInputSmoother` and `IContainerClicker`. No
  new sprint or key writes outside `core.input`; if the crit needs a guaranteed 1-tick W release, it is a scheduled
  key release through the smoother.
- Every rotation smoothed and capped at 450 deg/s; turn time is planned in (shield raise, splash throw, bow aim).
  Looks stay along the movement direction except while aiming at the target.
- Legit by default: shield only in the front cone, line of sight for every target pick and shot, humanised delays.
  Anything that reacts faster than a person could is a less-legit setting or is gated to single-player and
  allow-listed servers, as today.
- Manual input wins: any movement key or mouse move ends the automation as it does now; none of these items adds a
  lease that outlives that.
- Zero allocation in every per-tick path, scans budgeted (the engaged re-pick reuses the 5-tick scan), an allocation
  test and a behaviour test per unit, and a Spark check listed in the playtest section of the run note.
- HUD: at most the existing one status line ("Shield up", "Healing behind cover"). No new overlays in this pass.
- No code from GPL/LGPL/AGPL or unverified projects. Everything above is designed from vanilla mechanics.

## 6. Vanilla rules to confirm in a test world first

A 15-minute creative-world check before item 1 starts; each is load-bearing for a design above.

1. A hit while sprinting is never critical; sprint stays on in the air until W is released (2.1).
2. A crit registers on the first falling tick after the apex (2.1).
3. A hit on a raised frontal shield pushes the attacker back and deals nothing; an axe hit always disables for 100
   ticks; a raised shield blocks only after 5 ticks (2.2).
4. Attack charge keeps rising while the shield is up (2.2).
5. Snowball, egg and rod knockback on players (2.6).
6. A sweep needs a sword, the ground, no sprint and full charge, and damages entities near the target, including
   neutral ones (2.1 rule 4).
