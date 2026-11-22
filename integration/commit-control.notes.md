# commit-control (parkour control model, design 2026-11-22 sections 2-5 and 10)

## Built

- `SupportRegion` (engine-free, allocation free): union of the landing top's boxes (read through the
  `Voxels` box queries) grown by 0.3; Chebyshev `margin`, exact `containsSquare` by coordinate compression,
  `computeStop` (same order and constants as the replica; ground friction, sneak input scale), `wide`
  (contains a 1.6 x 1.6 square around the stop point, i.e. half side 0.8 in body-centre space), `stable`
  (speed < 0.03 and the no-key stop point inside with 0.05 margin), `topOf` / `cellTopBound` (a block's own
  real top: fence 1.5, pane 1.0). Adjacent tops in a column merge, so a strip or platform stays a few rects.
- `LandingHold` (every mode; replaces `STICK_TICKS` / `shouldStick`, which are gone): sneak guard from the last
  air tick of a predicted narrow landing, kept on the ground; stale-key latch per key at touchdown (keys down on
  the last air tick), no timer, no budget gate; release on stable + fresh press or jump (FRESH_INPUT) or a
  tick count (TIMER); `hold_limit_ticks` (12) after stable lifts the sneak, latch stays; safety lift after 60
  unstable ground ticks. Never on wide landings, never while the user sneaks or holds jump.
- `OverrideClassifier`: NOISE / TRIM / AXIS_OVERRIDE / HARD_OVERRIDE per design 4.1, sensitivity LOW/NORMAL/HIGH
  (x1.5 / 1.0 / 0.6), RELAXED axis rule at 2 ticks.
- `JumpCommit`: IDLE, ARMED, TAKEOFF, FLIGHT, TOUCHDOWN (same-tick), SETTLE, CHAIN (stub, unused: falls
  through to SETTLE/RELEASE), RELEASE (6 GUIDE / 8 CARRY / 2 after a hard override, smoothstep authority ramp,
  edge-guard horizon `2 + 4a`), ABORT. Modes NUDGE / GUIDE / CARRY with the axis rules (GUIDE fills free axes,
  withholds only stale keys, never replaces a fresh key; CARRY: fresh key = trim for 2 ticks, then the user's).
  Key-change limiter (per-level max changes, min hold ticks, reaction delay) and GUIDE's 2-tick choice keep.
  Aborts: sneak, back against a forward plan 2 ticks, > 25 deg off the commit line or > 20 deg/tick, two axes
  taken, plan lost in flight, external (Stop-All, screen, EMERGENCY+ MOVEMENT lease, module off; teleport and
  world change reset).
- `FlightController` (air brake only): family "keep keys until air tick k, then release forward / hold back"
  with the brake covering the last 1, 3, 5, 7 air ticks; one verifying roll each (wall contact, UNMODELLED /
  STUCK cells, HARM landing fail it); picks the most stop-point margin; falls back to `JumpSearch.steer`.
  GUIDE never holds back against a held W (release only); CARRY may.
- `MovementAssistBrain`: `mode`, `landingHold`, `setKeyChangeLimits`, `noteUserSneak`, `abortCommitment`,
  `commit()`, `hold()`, `flight()`, `targetRegion()`, `lastCommitSimTicks()`. The latch is applied before the
  ground/air decisions (a latched W cannot trigger an edge jump) and outranks every fill. The intent target's
  true top height and top extent come from the boxes (`JumpSearch.setTargetTop`). Fail-safe: no commitment onto a
  target whose cell is HARM / STUCK / UNMODELLED; flight rolls reject those cells (NUDGE + the hold still run).
  Edge jumps in GUIDE / CARRY are not budget-gated.
  A bare brain defaults to NUDGE with the hold off so the scripted always-W drivers (`ParkourBenchmarkTest`,
  the legacy parity test) keep their meaning; the module sets CO_PILOT and the hold on from the profile.
- `MovementAssist`: settings `control_mode` (CORRECTIONS / CO_PILOT / TAKEOVER, default CO_PILOT, absent key
  reads CO_PILOT), `override_sensitivity`, `landing_hold` (migration: absent key reads `stick_landing`, so a
  profile with `stick_landing=false` reads the hold off), `hold_release`, `hold_limit_ticks`; the old "Stick the
  landing" setting row is replaced by "Landing hold". Switched from `LevelVoxels` to `LevelShapes`.
  Publishes `AuthorityState` every tick of a commitment or hold (share, CARRY takeover flag, reason JUMP /
  LANDING / EDGE_HOLD, hand-back left/total, latched mask by `SimKey.ordinal()`), `noteOverride(6, ...)` on
  overrides, `clear` when idle; claims one `IWorldMarks` slot and publishes the locked target's real top as a
  LANDING box, cleared when idle. Landing overlay: the full green box is replaced by four corner brackets on the
  real top (via `WorldRenderKit.line` on the existing line batch; Clean-Screen and idle draw nothing, as before).
  Status: `parkourStatus()` returns the constants "Parkour: locked" / "Parkour: hold (release W to move)" /
  "Parkour: yours" or null when idle (also written to `status()`). Autopilot PARKOUR reflex: the selected mode,
  at least GUIDE; `noteActed(PARKOUR, "parkour")` at TAKEOFF, `noteDone` when the commitment returns to IDLE
  (or is aborted / the world unloads).
- `LegitPresets`: knobs `movement_assist.assist_key_changes` (3 / 6 / 0 = unlimited),
  `assist_key_hold_ticks` (3 / 2 / 1), `assist_reaction_ticks` (3 / 0 / 0; LEGIT jitters 2-4 per jump).

## Measured

- Commitment + hold simulated ticks per client tick (pillar run, sloppy W held, `MovementAssistBrainTest`):
  NUDGE max 1, GUIDE max 56, CARRY max 139. Bound asserted: 1,200 (`FlightController.MAX_SIM_TICKS`,
  `FlightControllerTest`, `MovementAssistBrainTest`), on top of the existing 1,800 search bound (also asserted).
- Allocation tests (`AllocationProbe`, < 1 KB over the run): `LandingHold`, `JumpCommit` (all modes),
  `FlightController.plan`, `SupportRegion` build/margin/wide/stop, brain `decide` in each mode with the hold on.

## Integration step

- Merge `commit-control.lang.json` into `en_us.json`; the old `setting.utilitysuite.movement_assist.stick_landing`
  entries (if any) can go.
- Shell: show `MovementAssist.parkourStatus()` as the one status line while it is non-null (SuiteHud polls only
  the goal job and autopilot reflex today; I did not edit `ui/`). The authority bar reads `AuthorityState.SHARED`.
- After merging shape-physics: run `LandingJointTest` (`@Tag("needs-shape-physics")`; it skips on a cube-only
  replica through a probe that drops a body on a fence post). Fence post, wall and pane must stay on top 40 ticks
  in all three modes; the corner-onto-fence case asserts only "a landing that was made holds" (landing a corner
  is the technique library's job).
- Verify in game (design 5.2 note): the first sneak tick set in `MovementInputUpdateEvent` behaves like a key press
  (edge back-off on, the 0.3 input scale one tick later).

## Left for later volleys

- Co-steer (yaw) in any mode: keys only this volley (owner decision D1 open); turn gain unchanged.
- Technique templates (corner, neo, headhitter, 45-degree), takeoff-tick search in the last 4 supported ticks.
- Momentum chains (CHAIN is a stub; a jump held through the landing simply skips the hold).
- Intent v2 (real-top candidates, simulated reach, gaze average, lock / deferred choice). The target is not frozen
  at TAKEOFF yet: the brain keeps reading the estimator each tick.
- The hold-to-take-over key; greying `authorship_budget` / `tick_phase` / `takeoff_shaping` in the menu for
  Co-pilot / Takeover; the `CommitView` record and the arc / stop-point pip.
- Wiring onto `ISharedControl` (keys are still written in `MovementInputUpdateEvent`, key staleness tracked in
  the brain from the Input booleans). The existing `p.setSprinting(true)` in `MovementAssist` (takeoff shaping's
  sprint start) is unchanged and should move to `SharedGrant.startSprint()`.
- CARRY on the ground does not yet drive the run-up (existing takeoff shaping only); a CARRY commitment arms when
  a movement key is held, like GUIDE.

## Hands-on checks for the owner

1. Sprint-jump onto a fence post and onto a stone wall holding W, in each mode (Corrections, Co-pilot,
   Takeover): you stay on top.
2. On the post, release W and press it again: you walk off at once.
3. Press sneak at any point of a jump or the hold: everything is yours immediately.
4. The HUD authority bar shows the hold and drains over the hand-back (6 ticks Co-pilot, 8 Takeover); with W still
   down after landing the status reads "Parkour: hold (release W to move)".
5. A wide platform landing: nothing held, you keep running.
