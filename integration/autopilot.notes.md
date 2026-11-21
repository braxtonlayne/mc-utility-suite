# autopilot package: integration notes

Owned: `core/autopilot/**` (+ tests). No edits outside it; no core.api changes. No new mixins.

## What exists now

- `DangerModel` (IDangerModel): one slot per HazardKind, primitive arrays, absolute ticks of a `TickClock`.
  A report lives `STALE_TICKS` (10) after it was made, or until one tick past impact. The nearer report wins.
  The default clock counts **wall-clock** 50 ms ticks, so reports age even while the autopilot module is off
  (ModuleRegistry only ticks enabled modules). `Autopilot.danger()` returns it, so `Services.danger()` works.
- `ReflexScheduler`: `Autopilot.mayAct(reflex, ticksNeeded, damageAverted)`. Bids live 2 ticks (reflexes ticked
  earlier in the frame still see later ones). Rank: can finish before its hazard's `ticksToImpact` > higher
  `damageAverted / max(1, ticksNeeded)` > lower ordinal. An acting reflex (`noteActed`) holds the turn until
  `noteDone` (200-tick safety timeout, refreshed when it calls `mayAct`). Stop-All, world unload and turning the
  autopilot off clear it. `allows()` is unchanged, so callers that only use it work as before.
  Reflex -> hazard deadlines: FALL_CLUTCH/ELYTRA_SWAP: FALL, VOID; SHIELD: PROJECTILE, EXPLOSION, MELEE;
  DODGE: PROJECTILE; THREAT_ESCAPE: EXPLOSION, LAVA, FIRE; TOTEM: LOW_HEALTH; OFFHAND: PROJECTILE, LOW_HEALTH;
  PARKOUR*: none.
- Settings (group "Reflexes"): `reflex_shield`, `reflex_dodge`, `reflex_threat_escape`, `reflex_elytra_swap`,
  `reflex_offhand`, all default true and allowed under both reaches (DANGER_ONLY covers them). Group "Black box":
  `black_box` (default true), `black_box_hit` (half hearts in one tick, default 6, 2..40). `summary()` lists them.
- `ReflexChain` (A3): `chainLease(reflex, moduleId)` returns one EMERGENCY lease owned by `"autopilot"` covering
  all four channels, shared by FALL_CLUTCH, SHIELD, DODGE, THREAT_ESCAPE, ELYTRA_SWAP. Released 5 ticks after the
  last member's `noteDone`, on manual input on any of its channels (detected as a new manual-override pause, since
  the arbiter does not preempt EMERGENCY leases on manual input), on preemption/Stop-All (listener), world unload,
  autopilot off, or the 200-tick lease timeout. PARKOUR, PARKOUR_BLOCKS, TOTEM, OFFHAND get null.
- `BlackBox` + `BlackBoxWriter` (A10): 512-row primitive ring (a sample row per autopilot tick when health changed
  or a hazard is live, plus one row per noteActed/noteDeclined/noteDone; repeated identical declines within 20 ticks
  are folded). Reasons interned by identity in a 64-entry table. Dumps the last 200 ticks on death (vitals feed, and
  `SuiteEvent.PlayerDied` if anyone posts it) or a hit of at least `black_box_hit`, debounced 100 ticks, JSON
  written on a daemon thread to `<game dir>/utilitysuite/blackbox/blackbox-<millis>.json` (newest 20 kept), and one
  `SuiteEvent.Diagnostic` line is posted.
- `VitalsMonitor` (engine free) + `mc/MinecraftVitals` (the only engine class): health, air, fire each autopilot
  tick -> LOW_HEALTH (<= 6 hp, 20 ticks, damage = health left), DROWNING (air <= 60, ticks = air + 20, 2 damage),
  FIRE (burning without fire resistance/creative, 0 ticks, damage = seconds left capped at 8).

## Notes for the packages that call this

- **Count turn time yourself** in `ticksNeeded`: at most `IInputSmoother.MAX_TURN_DEG_PER_SEC` = 22.5 deg/tick, so
  `ceil(angle / 22.5)` ticks of turning plus the act.
- `mayAct` answers false while the autopilot is off (it is `allows()` plus scheduling). A module acting under its
  own enabled setting (autopilot off) should keep using its own checks, not `mayAct`.
- Call `noteActed` when the reflex starts and `noteDone` when it ends or gives up: the acting reflex blocks the
  others until then (200-tick timeout). Reasons must be constant strings.
- A chain lease is owned by `"autopilot"`, so `arbiter.isHeldBy(channel, yourModuleId)` is false for it; check
  `lease.isActive()` / `lease.covers(channel)` instead. Do not close the chain lease yourself; call `noteDone`.
- The black box and the vitals feed only run while the autopilot is on (its onTick); the danger model itself works
  either way.

## Integrator

- Merge `autopilot.lang.json` (HUD status line keys per reflex, `hud.utilitysuite.autopilot.reflex.<name lowercase>`,
  for the ui package's active-reflex line, plus the black-box message) into en_us.json.
- No bootstrap change: CoreBootstrap already registers `new Autopilot()` with the same ID and constructor.
- Optional: post `SuiteEvent.PlayerDied` from the core's death handling; the black box subscribes to it (the vitals
  feed already catches deaths on its own, the debounce merges both).

## Not done

- No preemption of an acting reflex by a much better late bid (the acting reflex always holds the turn).
- The black box now records vitals while the autopilot is off (setting `black_box_always`, default on; rows carry
  `"off":true`). Driven by its own `ClientTickEvent.Post` listener; unit-tested, not yet seen in a client.
- Not playtested in game (no runClient in this run); unit tests only.
