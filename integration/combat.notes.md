# combat package: integration notes

## Merge these fragments
- `combat.lang.json` -> `en_us.json` (visual range status message).
- `combat.mixins.txt` -> `utilitysuite.mixins.json` `client` list: `combat.ClientPacketListenerTotemPopMixin`
  (entity event 35 = totem pop, forwarded to `TargetHud.onTotemPop`; no NeoForge client event exposes entity events).

## Wiring outside the package (integrator)
- `core/LegitPresets.KNOBS`, suggested rows (module `combat_reflexes`):
  - `knob("combat_reflexes", "shield_turn", false, false, true)` (turning to block is the less-legit option)
  - `knob("combat_reflexes", "shield_front_cone", 70.0, 80.0, 90.0)`
- Nothing else: all new modules and listeners are registered in `CombatBootstrap`.

## New modules (all IModule + IConfigurable, category COMBAT)
- `combat_reflexes` (on by default; acts only while the Combat Assistant is on, or the autopilot allows the reflex):
  shield reflex (A5), projectile dodge and creeper/TNT back-off (A4), danger-model feed. Runs from the client tick
  in `CombatBootstrap`, not the module tick, so it works while the assistant is off.
- `trigger_assist` (off by default): swings at a valid target already under the crosshair at full charge, after a
  humanizer reaction delay; ATTACK tap under a WORLD_INTERACT lease; stands aside while the assistant holds that
  channel; manual clicks win via the arbiter's manual override.
- `target_hud` (off by default): one cached line for the current target (health, armour wear, held item, totem pops)
  plus a visual range status message for non-ally players. Hidden by Clean-Screen / F1 / screens.

## Danger feed
- `ThreatTracker.evaluate()` now fills per-kind fields and `publish(IDangerModel)` reports PROJECTILE, EXPLOSION and
  MELEE (clears when gone). Published only while an autopilot is registered and the autopilot or the assistant is on.
- `bearingYawDeg` is the WORLD yaw (Minecraft convention, 0 = +Z) from the player toward the hazard's current position.
- Damage estimates: arrows `ceil(speed * baseDamage)`, fireballs 6, other projectiles 0.5 (ignored by the shield);
  blasts use vanilla's unarmoured formula (`ThreatMath.blastDamage`); melee 6 (player) / 4 (mob), ticks 2 / 4 when a
  hostile within 3.6 blocks faces the player.
- One evaluation per player tick is shared between the coach and the reflexes.

## Hand-off
- `CombatAssistant` totem keeper skips while module `offhand_manager` is registered and enabled (lookup cached; the
  bootstrap binds the registry with `assistant.bindModuleRegistry`). The in-fight EQUIP_TOTEM sustain click is
  unchanged (it only runs while engaged).

## Not done / follow-ups
- A8 status: `TacticUtility` + `OpponentModel` (engine free, tested) are wired into `CombatAssistant` behind the
  "Read the fight" setting (`tactic_utility`, default on; off = previous behaviour). FLEE backs off (aim kept on the
  target, like the existing sustain retreat), KITE widens the spacing band by 0.5, RETREAT_HEAL and FIGHT leave the
  existing sustain/spacing loop in charge. `StrafePlanner` intervals follow the opponent's strafe bias. Own DPS is
  measured from the target's health drops (`CombatSnapshot.targetHealth`, new), opponent DPS from damage taken while
  engaged; floors 4 / 1.5 before any hit. Not done: shield-use rate is recorded but not yet used (e.g. to time the
  axe shield break), and the existing `TacticsPlanner` (HOLD_GAP / EDGE_KNOCK) does not read the broad tactic.
- Shield vs. assistant SwingGate: the reflex lowers the shield when the weapon is charged and a living entity is under
  the crosshair; the assistant swings on its own next tick. No shared "swing intent" flag yet.
- Dodge and fuse back-off stand aside while the assistant is engaged (it owns movement in a fight); the shield
  reflex still works then, on the assistant's lease. Merging dodge into the assistant's own movement is a follow-up.
- Dodge uses a conservative 0.12 blocks/tick strafe speed; fast close arrows are often not fully reachable (it still
  taps; `DodgePlanner.reachable()` reports it). Needs in-game tuning.
- Not playtested in a client (no runClient in this run).
