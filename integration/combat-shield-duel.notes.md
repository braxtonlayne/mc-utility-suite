# Shield duel (volley 2026-11-22, task combat-shield-duel)

Design: `docs/design/upgrade-pass-2026-11-22.md` section 2.2. One `FightStage` in `Slot.PRE_SWING` that shapes the
built-in loop with one-tick requests and returns false, so the aim and the movement stay the built-in ones.

## Units

- `combat.tactics.ShieldDuel` (engine free, allocation free). Static rules: `frontal(holderYaw, hx, hz, ax, az)`
  (the blocked half-space), `blocksHit(useTicks, leadTicks)` (5-tick raise delay), `openSide(...)` (shorter way round,
  hazards respected), `inFrontCone(...)`, `offAxisDeg` / `signedOffAxisDeg`. One instance per fight holds the
  block-and-answer loop (plain input fields, `step()`, `raised()`, `vetoSwing()`, `lowered()`).
- `combat.assistant.stages.ShieldFacts`: the stage's own source interface (own shield ready / raised ticks, target head
  yaw, target shield raised ticks, target holds an axe, observed shield disable). `ShieldFacts.NONE` = no facts.
- `combat.assistant.mc.McShieldFacts`: Minecraft implementation. One `level.getEntity` per tick. Registers its own
  NeoForge listener for `PlayLevelSoundEvent.AtPosition` in the constructor (see "Shield disable" below).
- `combat.assistant.stages.ShieldDuelStage`: the glue. Settings `shield_duel` (master) and `shield_duel_block` (our
  shield), both default on, group "Shield & weapons".
- `ShieldBreakSequencer`: give-up back-off (below). `OpponentModel`: `noteShieldDisabled / shieldDisabledUntil /
  shieldDisabled / shieldDisabledFor`, `SHIELD_DISABLE_TICKS = 100`. `TradeInput.sBlocking / oShieldUp` honoured by
  `HitTradeSolver` (default false: every existing solver test keeps its numbers). `TradeDriver.ShieldFeed`.

## Vanilla rules (1.21.1 sources, NeoForge 21.1.265, cited in the code)

- `LivingEntity.isDamageSourceBlocked`: blocked when `isBlocking()` and `sourcePos.vectorTo(position)` (horizontal,
  normalised) dot `calculateViewVector(0, getYHeadRot())` < 0: the attacker within (strictly) 90 degrees of the
  holder's **head** yaw. The snapshot's `targetYaw` is the body yaw, so the stage prefers `ShieldFacts.targetHeadYaw`.
- `LivingEntity.isBlocking`: `getUseDuration - useItemRemaining >= 5`.
- `Player.blockUsingShield` -> `disableShield`: cooldown 100 on the shield, `stopUsingItem`, entity event 30.
  `LivingEntity.canDisableShield` = main hand `canDisableShield` (NeoForge; an axe).
- `LivingEntity.blockUsingShield` -> `attacker.blockedByShield(holder)` -> `holder.knockback(0.5, holder.x -
  attacker.x, ...)`. Note: with `knockback` subtracting the ratio this moves the **holder toward the attacker**, not the
  attacker back. The solver models exactly that (the brief said "costs a pushback"; the real cost of hitting a shield is
  the burnt cooldown and the holder ending up closer, i.e. in its reach).
- `Minecraft.handleKeybinds`: while `isUsingItem()`, attack clicks are consumed without an attack. So the stage
  vetoes the swing on the tick it lowers our shield and one more tick (`ShieldDuel.LOWER_GAP`).

## Behaviour

1. **Their shield (no axe, or the sequencer backing off).** While their shield is up, frontal and will block by the
   time our tap arrives (`blocksHit(useTicks, HIT_LEAD = 1)`): `vetoSwing`, `vetoJump`, `forceSide(openSide)`. A raise
   younger than 4 ticks is not vetoed (the hit lands). On the tick it is no longer up, if our attack is ready
   (`SwingGate.ready`), in reach and on the crosshair, the stage taps itself and vetoes the planner's swing (one tap).
   Past 90 degrees nothing is vetoed: the hit planner swings as normal.
2. **Axe.** `ShieldBreakSequencer` is kept (the assistant still runs it before PRE_SWING). Its give-up loop is fixed:
   after `MAX_WAIT` with the shield still up it backs off 10, 20, 40, 80 (cap) ticks before the next try; the back-off
   is not `active()`, so the stage circles and holds the swing meanwhile. A lowered shield or a landed swing clears it.
   When the disable is observed, the stage records `noteShieldDisabled(target, clock + ticksLeft)` and for the window
   calls `hitPolicy(CRITS)` and `pressAdvantage()` every tick.
3. **Our shield.** Needs `ownShieldReady` (offhand shield, not on cooldown, main hand without its own use action).
   Raise when the opponent is in its reach + 0.8, not holding its own shield up, and `attackReadyIn <= 5 + 2`. Their
   swing restarts the cooldown read (`noteSwing`) and the shield comes down inside their cooldown; the planner answers
   from the second tick after the lowering. Never against `targetHoldsAxe` or `targetHeavyHitter`, never while
   `ctx.eating()`, only with the opponent within 70 degrees of our view (legit front cone, as `ShieldDecision`). A raise
   that never meets a hit drops after 40 ticks for a 10-tick rest. USE is released through `ctx.hold(USE, false)` when
   the stage stops blocking (setting off, threat gone); on disengage / Stop-All the assistant's lease release frees the
   key and `reset()` forgets it (tests cover both).
4. **Solver.** The stage writes `TradeDriver.shieldFeed().set(clock, ourShieldUp, theirShieldUp)` each tick; `fill()`
   reads it only when the stamp equals that tick's fight clock (PRE_SWING runs before the movement in the same
   `drive`). No `CombatAssistant` change needed. Instance setters `setSelfBlocking / setOppShieldUp` exist for a later
   direct wiring.

## Wiring for the integrator (CombatBootstrap)

```java
import com.utilitysuite.combat.assistant.mc.McShieldFacts;
import com.utilitysuite.combat.assistant.stages.ShieldDuelStage;
// after the assistant is built, before the module is registered (start-up, client thread):
assistant.addStage(new ShieldDuelStage(new McShieldFacts()));
```

`McShieldFacts` registers its sound listener on `NeoForge.EVENT_BUS` itself; nothing else to subscribe. Lang keys in
`integration/combat-shield-duel.lang.json` (merge into `assets/utilitysuite/lang/en_us.json`).

## Seam requests (not edited; not mine)

- **Shield disable without the sound.** A silent target (`isSilent`) plays no shield-break sound, so the disable is
  not observed. A cleaner signal would be the sequencer's own result: `CombatAssistant` step 4 could call
  `opponents.noteShieldDisabled(targetId, tacticClock + OpponentModel.SHIELD_DISABLE_TICKS)` on the tick after
  `ShieldBreakSequencer.Action.SWING` when `s.targetBlocking` dropped, or expose `FightContext.lastShieldBreakSwing()`
  (fight-clock tick of the sequencer's last swing, Long.MIN_VALUE for none).
- **Lower taken-weight in the disabled window** (design 2.2): `FightContext` has no taken-weight request. Proposed:
  `void takenWeightScale(double factor)` (one tick, multiplied into `gear.takenWeight(kiting)` in `movement`).
- **Suppress retreats in the disabled window** (design 2.2): `pressAdvantage()` only stops feints. Proposed: let
  `pressAdvantage` also skip the `TacticUtility` FLEE/RETREAT_HEAL branch for that tick, or a separate
  `void holdGround()`.
- **Legit / less-legit tuning** has no setting on `CombatSettings`; the stage always uses the legit front cone.
- The `combat_reflexes` shield reflex (`ShieldDecision`) can also hold USE. If both run at once they agree on the cone
  but not on timing; the integrator should let the reflex stand down while the Combat Assistant holds its lease.

## Not done

- Offhand totem/shield policy (`OffhandRules`), per the brief.
- Inferring the disable from the swing when the sound is not heard (see seam request).

## Left for the playtest

Against a shielding player or a second client: no swings into the shield, circling to the open side, block-and-answer
timing under ping (the 2-tick margin), the axe disable window (sound match radius 1.5 blocks), and that the sequencer's
back-off feels like a person waiting for an opening rather than a stall.
