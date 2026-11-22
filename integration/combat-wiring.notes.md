# Combat wiring: hit-trade solver, cooldown reading, route pursuit and flight

Volley 2026-11-22, task combat-wiring. Code: `combat/assistant/CombatAssistant`, new `combat/assistant/TradeDriver`,
`CombatSettings`. Contracts used as on main at 68af800: `HitTradeSolver`, `TradeInput`, `OpponentModel.noteSwing /
attackReadyIn / cooldownTicks`, the new `CombatSnapshot` fields, `Services.bodySimsOrNull()`,
`Services.routeRunnerOrNull()` / `IRouteRunner`. No `travel.*` import in combat main code.

## Decision order in `drive()` (engaged tick)

1. Route retry countdown, pending weapon restore.
2. Emergency sustain (unchanged): totem click, eating (leaves any route first, backpedals facing the opponent), then the
   sustain RETREAT: **turn and run along a route when the opponent keeps closing** (closing speed > 0.05 b/t, or a
   flight already running), else the old aim + backpedal.
3. Broad tactic FLEE: **route flight** (`runner.flee(lease, target, 24, lookAlongRoute=true)`), else aim + backpedal.
4. **Pursuit**: target farther than spacing max + 6 (hand back inside spacing max + 5, 1-block hysteresis) or line of
   sight lost for more than 10 ticks -> `runner.pursue(lease, target, spacingMax, true)` every tick; otherwise the run
   is stopped (`runner.stop(lease)`) and the melee code below runs.
5. Wind-charge launch, aim, shield break, swing / crit sync, terrain tactics (unchanged).
6. Movement (`movement(s)`): within spacing max + 2.5 with `hit_trade` on and a solver available, the **TradeDriver**
   answer drives forward / strafe / sprint (and a jump tap) instead of the SpacingController band and the
   StrafePlanner. Then, in order: hazard veto (never forward into hazardFront, back into hazardBack, strafe into
   hazardLeft / hazardRight, whatever the solver says), sprint only with forward, then the sprint-reset taps
   (W_RELEASE / S_TAP) override while `sprintResetTicks > 0`. HOLD_GAP / EDGE_KNOCK still own the keys when active
   (movement() is not called then).

Cooldown reading: `observeOpponent` calls `opponents.noteSwing(targetId, tacticClock, s.targetAttackSpeed)` on the tick
`s.targetSwingStarted` is set.

## TradeDriver (engine-free, allocation-free)

- Fills one reused `TradeInput`: own position / velocity / onGround / yaw / sprinting / reach; `sReadyIn` from
  `attackStrength` and the own cooldown period (learned from the per-tick rise of attackStrength, EMA, default 12.5);
  opponent position / velocity / onGround; `oReadyIn = opponents.attackReadyIn` (0 when `cooldown_read` is off),
  `oCooldownTicks = opponents.cooldownTicks`, `oUsesCooldown` = target is a player (cached at engage);
  `oForward` / `oStrafe` from the target velocity against the bearing toward us (dead zone 0.03 b/t; its left is
  `(fz, -fx)` for facing `f`); `oSprinting` above 0.22 b/t; the four hazard flags; horizon 6..8; `takenWeight` 1.2
  (1.8 while KITE, which is how KITE widens on the solver path); `allowJump` false at HOLD_GAP and while crit sync is
  waiting on or riding a hop.
- Dwell: an answer is held at least 3 ticks unless the new best beats the held combination's current score by 0.25
  hits or the held one is rejected (score <= -1e6). A jump is only reported with the answer that was taken.
- Seam: `TradeDriver.Step` (the shape of HitTradeSolver). Production `TradeDriver.of(new HitTradeSolver(provider))`,
  built lazily once `Services.bodySimsOrNull()` is non-null. `CombatAssistant.useTradeDriver(driver)` lets tests script
  answers (HitTradeSolver is final).

## Route hand-over rules

- Taking over: release our movement keys (`move(HOLD, NONE, false)`), `clearKeyCache()` (keys + aim cache), then call the
  runner. No `aimAtTarget` while the runner runs (it looks along the route through the same lease).
- Handing back: `runner.stop(lease)` then `clearKeyCache()` so the melee code re-presses its keys and re-sends its aim.
- Switching pursue <-> flee: `runner.stop(lease)` first.
- The runner is stopped on disengage (every reason), target switch (`engage` with a new target), Stop-All / lease loss
  (`onPreempted` stops it for the ended lease), module disable (via disengageNow) and world unload (mode reset).

## Fallbacks

| Situation | Behaviour |
|---|---|
| `hit_trade` off, no body-sim provider, `valid() == false` (the stub), or target beyond spacing max + 2.5 | old band + strafe code, unchanged (driver dwell reset) |
| `cooldown_read` off | solver sees the opponent as always ready (`oReadyIn = 0`) |
| `route_pursuit` off or no runner registered | old W/sprint chase and backpedal |
| runner returns false, reports FAILED, ARRIVED or IDLE | hand back this tick, backpedal / band; next try after 20 ticks (no key flip-flop) |
| eating | never routed: backpedal facing the opponent |

## Settings added (CombatSettings, labels inline)

- `hit_trade` (bool, true, group Spacing, "Trade solver", advanced)
- `hit_trade_horizon` (int 7, 6..8, group Spacing, "Trade look-ahead (ticks)", advanced)
- `cooldown_read` (bool, true, group Cooldown, "Read their cooldown", advanced)
- `route_pursuit` (bool, true, group Tactics, "Run routes when chasing or fleeing")

## Tests

- `combat/assistant/TradeDriverTest`: oForward / oStrafe inference and dead zones, copied fields and clamps, sReadyIn and
  the learned own period, cooldown_read off, invalid answer, dwell (hold, clear lead, rejected held), sprint / jump
  rules, allocation of `decide`.
- `combat/assistant/CombatRouteHandoverTest` (fake runner sharing the lease): pursue beyond range with hysteresis and
  stop inside it, keys re-pressed after hand-back (cache cleared), our keys released at hand-over and the camera follows
  the runner, LOS-loss pursuit, setting off / no runner, flee on FLEE, refusal fallback + retry wait, FAILED fallback,
  sustain retreat runs only while the opponent closes, stop on disengage / Stop-All / disarm.
- `CombatAssistantBehaviourTest`: invalid scripted answer and the production stub keep the exact band test passing,
  valid answer drives keys, range / setting gate, hazard veto, sprint-reset priority, solver jump tap, swing feed,
  allocation of the engaged tick on the scripted solver path and on the production solver path.
- Full suite: 941 tests green.

## Leave for the in-game playtest

- Feel of the spacing dance against a player and against mobs: a better player or a jittering bot? (dwell 3 ticks,
  margin 0.25 hits are first guesses.)
- Step-in timing right after a real opponent swing (needs the real OpponentModel and MinecraftCombatWorld swing fields).
- Interplay with crit hops (solver jumps are blocked while crit sync waits or rides a hop), shield break, sprint resets.
- Pursuit / flee hand-over and the camera during it (turn to run, turn back to the target on hand-back, all under the
  450 deg/s cap); the 6 / 5 block pursuit thresholds and the 10-tick LOS threshold.
- Legit / Balanced / Relaxed differences.
- Spark profile of the engaged tick with the real solver (9 combos x 6-8 ticks x 2 bodies).

## Notes for the other tasks

- With the stub HitTradeSolver (always invalid) the assistant behaves exactly as before.
- `OpponentModel` stubs answer "ready"; the wiring reads `attackReadyIn` / `cooldownTicks` every engaged tick and does not
  assume more than the contract.
