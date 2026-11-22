# combat-threat-fights: integration notes

Task: threat-aware fights against mob packs, the combo escape, and the FightStage seam (seam owner). Base 7b7d078.

## CombatBootstrap lines (integrator)

In `CombatBootstrap.init`, after the `assistant.useReadState(readState);` line and **before**
`Services.modules().register(assistant);` (so the stage settings are in the module's list when the menu and the
profile load read it). `threats` is already the coach's `ThreatTracker` there, hence `nearby`:

```java
import com.utilitysuite.combat.assistant.mc.McNearbyThreats;
import com.utilitysuite.combat.assistant.stages.ComboEscapeStage;
import com.utilitysuite.combat.assistant.stages.ThreatFightStage;
...
        McNearbyThreats nearby = new McNearbyThreats();
        assistant.addStage(new ThreatFightStage(nearby));   // Slot.PRE_AIM
        assistant.addStage(new ComboEscapeStage());         // Slot.MOVEMENT
```

Order against the other volley stages: `ThreatFightStage` only sends one-tick requests and returns false, so its
position in PRE_AIM does not matter. `ComboEscapeStage` returns true while it escapes; register it before any other
MOVEMENT stage that should yield to it (or after one that must keep the movement).

Lang: merge `integration/combat-threat-fights.lang.json` into `en_us.json` (three settings, group "Tactics").

## Seam changes (all additive; no change without a registered stage)

- `FightContext`: new default reads `lastHitTakenAt()`, `lastHitLandedAt()`, `hitsTakenUnanswered()`,
  `lastAttackerId()`, `lastAttackerAt()` (defaults -1 / 0, so other implementations keep compiling).
  `CombatAssistant.StageContext` implements them: a hit taken is a health drop matched to the target's damage event
  (the gear read's existing attribution); a landed hit is a target health drop; the unanswered count resets on a
  landed hit and on any engage; the last attacker is the cause of the last entity-attack damage to the player
  (any mob, not only the target), cleared when the fight ends.
- `CombatAssistant.lastHitPolicy()`: the policy the hit planner used last (tests).
- **Seam fix 1**: `requestTarget` never switches to a roster ally (`neverTarget`). Before, the switch happened and the
  next tick disengaged the whole fight with `TARGET_IS_ALLY`.
- **Seam fix 2**: `widenSpacing` was ignored while the arena read's HIGH_GROUND / CEILING_DENY positional move drove
  the keys (`positionalMove` ran before `movement`). A widened band now outranks the positional move (a fuse back-off
  beats holding the high ground). Not changed: the gap-ambush tactic keys (`tac != NONE`) still win over a stage's
  side and spacing; a forced side is still vetoed by the hazard flags (tested).
- Checked and fine as is: requests are cleared at the start of every `drive`; `hitPolicy` lasts one tick; a stage
  returning true skips later stages and slots (each slot tested); `reset()` runs on disengage, Stop-All and world
  unload; stage settings are listed and read on profile load and on a setting change; two idle stages allocate
  nothing and leave a scripted 120-tick fight's key / aim / swing trace identical.

## What the stages do

`ThreatFightStage` (PRE_AIM, settings `threat_fights`, `mob_playbook`, both default on):
- Re-pick every 5 fight ticks via `combat/tactics/ThreatRank` (hostile base, swelling +600, bow drawn within 10
  +300, melee from behind +250, -5 per block, +2 per missing half-heart; current target +80 and a 15-tick dwell,
  skipped only by a swelling creeper within 5 blocks). Reactive rule: only rows flagged HOSTILE (or the entity that
  last hit the player, for 200 fight ticks); never warden / wither / ender dragon; LOS required while
  `engage_requires_los` is on. No re-pick away from a player opponent (the threat answers still run).
- Fuse back-off: a swelling creeper within 7.5 blocks (past vanilla's 7-block swell give-up): if it is the target,
  the band moves to 7.5 (`widenSpacing(7.5 - spacing_min)`), aim stays on it; if it is not and stands in front, the
  band widens by what is missing; if it is behind, a forced strafe away from it (backing off would walk into it).
  Applies to any swelling row (safety, not targeting).
- Projectile answer: a bow / loaded crossbow pointed at the player (hostile rows within 24) or a projectile on a hit
  course: forced strafe away from the shooter for 12 ticks, sticky, the other side on a hazard.
- Pack: 3+ hostile melee rows within 4 blocks (target included): `widenSpacing(1.5)` and `hitPolicy(AUTO)`.
- Playbook row of the current target each tick (`combat/tactics/MobPlaybook`): creeper KNOCKBACK; skeleton / stray /
  bogged strafe in while out of reach; spider / cave spider / baby zombie family `vetoJump`; witch rush (no pack
  widening); enderman default row on purpose (no special move yet: the design's 2-high roof / water idea needs the
  arena read); unknown and modded ids the default row.

`ComboEscapeStage` (MOVEMENT, setting `combo_escape`, default on; players only): two target hits taken without
landing one, the last within 2 ticks: for 10 ticks it replaces the built-in movement with a strafe out of the
knockback line toward the hazard-free side (alternating the opening side between escapes), sliding along a wall
when pinned (hazard behind and on one side), stepping in if both sides are closed, never backing up, never sprinting.
Hands back as soon as a hit of ours lands or the window ends.

`McNearbyThreats` (mc): one bounded entity query (16 blocks, 48 candidates, nearest 16 kept) plus one projectile query
(24 blocks) per refresh, only while a threat stage steps (engaged). HOSTILE from synced state: aggressive flag (or a
staring enderman) and facing the player or within 2.5 blocks, a creeper swelling within 7.5, or a bow / loaded
crossbow pointed at the player. Players, armour stands and owned pets are never rows. Type ids are cached per type;
LOS by the shared voxel walk. Per refresh it allocates the two query boxes, like the proactive scan.

## Not built / owner decisions

- **Predicted jump reset** (design 2.7): not built, owner decision (keep the single-player gate or relax it for the
  predicted variant).
- The combo escape runs in MOVEMENT, after the swing: a crit hop the hit planner already scheduled on the trigger
  tick still happens. A PRE_SWING companion that vetoes hops during the escape is a small follow-up if the playtest
  shows it.
- "Shield up while closing" on skeletons and the blaze / ghast ranged answer wait for 2.2 and 2.6.
- Witch "never kite": the stage cannot switch off the broad tactic's KITE extra spacing (no seam request for it);
  it only skips its own widening.
- The reflex dodge and fuse back-off in `CombatReflexes` still stand down while the assistant holds movement (not my
  file); these stages are their in-fight replacement.

## Tests

`FightStageSeamTest` (11), `stages/ThreatFightStageTest` (8), `stages/ComboEscapeStageTest` (5),
`stages/ThreatStagesAllocationTest` (2: a busy engaged tick with every answer live, and the engine-free units, both
under 1 KiB over 50k / 100k steps), `tactics/ThreatRankTest` (5), `tactics/MobPlaybookTest` (3),
`tactics/ComboEscapeTest` (5). Full suite: 1585 tests, 0 failures.

## Left for the playtest

- A zombie pack with a creeper and a skeleton in survival: switch timing, back-off distance, whether the pack
  widening strings them out or just walks backward into terrain.
- The combo escape against a second client: trigger rate, side choice, wall slide.
- Dodge distances against a drawing skeleton (12-tick strafe), and `McNearbyThreats` HOSTILE heuristics on real
  mobs (facing threshold 0.5, aggressive flag timing). Spark check of the 5-tick refresh with a large mob farm nearby.
