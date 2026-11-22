# combat-heal-windows

Healing in windows as a `FightStage` (design 2.4), plus the Auto-Eat swap-back fix (run note item 11).

## Wiring (integrator)

In `CombatBootstrap`, after the assistant is built (any order among the other stages is fine; it is the only
`Slot.SUSTAIN` stage so far):

```java
assistant.addStage(new HealWindowStage(new McHealFacts()));
```

Imports: `com.utilitysuite.combat.assistant.stages.HealWindowStage`, `com.utilitysuite.combat.assistant.mc.McHealFacts`.
Lang keys: `integration/combat-heal-windows.lang.json` (module `combat_assistant`, group "Emergency").

## What it does

- `combat/tactics/HealWindow` (engine-free planner):
  - `wantHeal`: effective health <= `heal_hits_left` (default 2.5, 1.5..4) x measured damage per hit; while no hit is
    measured, the fixed low-health threshold. Never later than that threshold (tested over a grid).
  - `safeTicks`: ticks the opponent cannot land a full hit = max(time to close the gap, `attackReadyIn`), plus 12 when
    line of sight is broken. A target knocked by our hit (hurt time + separating speed, airborne) keeps flying with
    air drag 0.91 for up to 9 ticks before it can turn back. Approach 0.33 b/t vs players, 0.25 vs mobs.
  - `plan`: EAT when safe >= 32 - 8; else FIGHT_FOR_KNOCKBACK when in reach and our charge >= 0.9; else RETREAT.
  - `topUp`: hunger < 18, no waste, the window covers the whole eat.
- `combat/assistant/stages/HealWindowStage` (`Slot.SUSTAIN`, settings `heal_windows` on, `heal_hits_left` 2.5,
  `heal_top_up` on):
  - Steps aside (returns false) whenever `SustainPlanner.decide` asks for anything: the built-in emergency (totem,
    lethal, low-health apple, low-health retreat) runs exactly as before.
  - EAT: `ctx.startEating(s, s.gappleSlot)` and returns false, so the built-in layer continues the eat on the same tick
    (backs off facing the target, finishes, restores the weapon).
  - FIGHT_FOR_KNOCKBACK: returns false (the built-in loop swings).
  - RETREAT: `return ctx.retreatRoute(s)`; with no route (setting off, no runner, refused) the fight goes on instead of
    straight backing-up.
  - Damage per hit: its own smoothed average (alpha 0.4) of effective-health drops >= 0.5 seen by the stage, reset on
    engage.
  - Facts (`HealFacts`, `McHealFacts`): refreshed every 10 ticks; one 36-slot scan, no allocation. Hunger, saturation,
    best hotbar food (FoodSelector.score, golden apples and Auto-Eat's unsafe foods excluded), eat ticks, splash
    healing slot, golden apple count.
- `SustainExtras.shouldStagePull`: the staged pull decision (first 40 engaged ticks, whatever the health, once per
  engagement). `HealWindowStage.stagePullWanted()` computes it every tick; nothing clicks yet (seam request 1).
- `FoodSelector.score` extracted from `choose` (same numbers). `FoodSelector.UNSAFE` now also lists
  `minecraft:chicken`: the old `minecraft:raw_chicken` entry is not a real item id, so raw chicken was eligible.
- `FoodSelector.SwapBack` + `SurvivalAutopilot`: the pull / eat / swap-back cycle is a small state machine. After the
  pull lands it records source slot, hotbar slot, the food's item id and the displaced item's id. Any early lease end
  (preempted, lease expired, Stop-All) keeps the restore owed. While owed and no eat runs, every 5 ticks it decides:
  WAIT while a screen or non-inventory container is open, clicks are pending, another module holds HOTBAR, the user
  is on the hotbar (manual override) or automation is stopped; DROP when the pack slot no longer holds the displaced
  item or the hotbar slot holds something other than the food (the user moved a stack, or the swap-back already
  landed); RESTORE otherwise (also when the hotbar slot is empty because the last food was eaten and the displaced
  item is still in the pack). RESTORE takes a short SURVIVAL HOTBAR lease, one SWAP click, and forgets once the click
  went out. World unload and disabling the module forget it. Swap-back setting off: nothing is recorded.

## Seam requests (not edited: not my files)

1. **Staged pull click** (`CombatAssistant`): expose the existing pull through `FightContext`, e.g.
   `boolean pullConsumable(CombatSnapshot s)` = the body of `maybePullConsumable` without the
   `s.effectiveHealth() > settings.lowHealth` gate (keeps `pulledInvSlot`/`pulledHotbarSlot` so `stopEating` /
   disengage swap it back). Then in `HealWindowStage.step`, after computing `stagePullWanted`:
   `if (stagePullWanted && ctx.pullConsumable(s)) { notePulled(); }`. One line in the stage once the seam exists.
   Pass `clicker.pending() > 0` as `pullInFlight` (the stage passes false today, as it cannot see the clicker).
2. **Measured hit in the context** (optional): `double theirHitPerHit()` on `FightContext` returning `gear.theirHit()`
   would let the stage share the gear read instead of its own average.
3. **Eat duration**: `startEating` uses `MAX_EAT_TICKS` and `usingItem` for every consumable; fine for 32-tick food,
   apples and potions. Dried kelp (16) also works through `usingItem`. No change needed, noted for the playtest.

## Not done (listed, out of scope)

- Splash healing (look down with the smoothed `AIM_DOWN`, throw, look back): `HealFacts.splashHealingSlot()` is read
  but unused.
- Pre-fight absorption (eating a golden apple before engaging).
- Top-up "after a kill": the stage resets with the fight, so top-ups only happen while a far target is still engaged
  (pursuit, duel mode). After a kill the regular Auto-Eat takes over.
- The staged pull click (seam request 1).
- The deferred swap-back path in `SurvivalAutopilot` is not unit-tested end to end (it is engine code); the decision
  is covered by `EatSwapBackTest`.

## Left for the playtest

- Low-health fights against a zombie and a player: no dying mid-apple; backing off around cover; the eat starts right
  after a sprint hit sends the target flying.
- The window constants (`KNOCK_AIR_TICKS` 9, `NO_SIGHT_BONUS` 12, approach 0.33/0.25, margin 8): tune by feel.
- Eat-from-pack interrupted by a fight or Stop-All: the food goes back once the fight ends (or automation resumes),
  and stays put when you moved it yourself.
