# Checkpoint 2026-11-22, evening

## Landed today (main)
- **Regressions fixed:**
  - the assist writes under automation leases again (benchmark 19/19)
  - Menu idle wait
  - Coach check
  - `runArena` dev run
- **Parkour landing lanes** (merge 223d0be): the intent estimator arms on lone panes and on posts behind a column;
  the target is frozen at takeoff; new corner-cut technique; the `CoSteerModel` flies scripts with the camera
  follower's real dynamics. Co-pilot and Takeover land fence, wall, pane and the rebuilt corner lanes. Corrections
  lanes check its design promise: no walk-off after a landing it made. Details in run-2026-11-22-open-items.md,
  "Parkour lanes".
- **Combat** (7ac5172, merge 6259269):
  - duel mode no longer resets when a reset cannot heal
  - fights on at low health unless a reset pays
  - every hit is a crit when heavily out-geared
- **Bot arena** (docs/research/bot-arena-2026-11-22.md):
  - Grim gave our client zero flags across about 40 rounds.
  - Fair results: mixed 10/10, fists 9/10 then 5/5. Stacked was 1 of 10, still a fail; one of the bot's hits is
    worth six of ours.
- **Feature proposals:** docs/design/feature-proposals-2026-11-22.md (11 proposals). The top 3 are the hand-over
  key, course memory with a background jump solver, and the debrief card.

## Waiting on the owner
1. The two lane decisions from the parkour run. The first-built deep corner lanes are now a note, not a check
   (frame-perfect only). The Corrections lanes check "no walk-off" rather than a landing.
2. The stacked pass bar. Options: a less extreme kit, no saturation at round start, or a stress case rather than
   a pass bar.
3. Which proposals go into the next volley.
4. Feel the new corner-cut camera turn in game (up to 25 degrees in Co-pilot).
5. Font downloads (Rajdhani + JetBrains Mono, SIL OFL): still unapproved.

## Next fix (found this evening, not done)
**Parkour-blocks gap lane** (5 air, "a block extends the far side"): no block is placed and the runner falls.
Traced in game:
- The runner presses jump on its last supported tick.
- `MovementAssistBrain.pressHeldBack` (technique-first) holds the press while the next simulated tick is still on
  the ground.
- The takeoff comes a tick late, past the edge, and without the sprint-jump boost. vz is 0.262 at the first airborne
  tick; a sprint jump has about 0.44.
- The trajectory then passes the cell in front of the far side below its top, so `BlockAssistPlanner` finds no
  placement (best -inf with 30 candidates).
- The replica with a proper sprint jump plans it fine.

Fix idea: never hold back a press at an edge where nothing lands, or where the held tick loses the sprint boost.
Check why the boost was lost: sprint was still true on the last ground tick. Lane diagnostics stay in place: the
takeoff log in the lane note, and `MovementAssist.blockNote()`.

Also open from the agents:
- **Mutual freeze in the arena:** both sides stand still for about 72 s. Cause not found; telemetry is now in
  `ArenaClient`.
- **Older technique families:** they still plan with a view model the client cannot fly.
- **`corner-wall-3gap` in Co-pilot:** depends on the tick phase (one of four phases finds no script).

## Environment
- **Paper arena server:** started by the combat agent (detached java, Grim off; the Grim jar is in
  server/plugins-off). It stops when the laptop shuts down, and the world is saved on stop.
- **Worktrees** of both agents are left under .claude/worktrees (their branches are merged).

## Last verification (17:00)
- `./gradlew test`: green.
- **Playtests:**
  - Menu: PASS.
  - Combat: PASS alone. It failed once in the combined Menu, Parkour and Combat run: right after the parkour
    scenario's player death, the client ran from the zombie, 0 swings. Treat it as a knock-on of the gap-lane death
    (state after the death). Re-check once the gap lane is fixed.
  - Parkour: the benchmark is 19/19 and every landing lane passes. Still failing:
    - parkour blocks gap lane, both variants (see "Next fix")
    - player stays alive (the gap-lane fall)
    - HAY clutch: it steers into the arena's water pool instead of placing hay, unhurt (20 to 20). The test
      position is contaminated by the pool; move the lane or accept water as a safe outcome.
