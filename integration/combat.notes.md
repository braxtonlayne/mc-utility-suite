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
- A8 integration: `combat/tactics/TacticUtility` and `OpponentModel` are written and tested (engine free) but see the
  status in the commit log / below for how far they are wired into `CombatAssistant`.
- Shield vs. assistant SwingGate: the reflex lowers the shield when the weapon is charged and a living entity is under
  the crosshair; the assistant swings on its own next tick. No shared "swing intent" flag yet.
- Dodge uses a conservative 0.12 blocks/tick strafe speed; fast close arrows are often not fully reachable (it still
  taps; `DodgePlanner.reachable()` reports it). Needs in-game tuning.
- Not playtested in a client (no runClient in this run).
