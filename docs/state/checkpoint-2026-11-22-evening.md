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

## Next fix (found this evening; resolved at 17:19, see the end of this note)
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

## Gap lane resolved (17:19)
- My 5-air change was wrong. A far-side block turns a 5-air gap into the 4-air maximum flat sprint jump. The body
  then reaches the block a hair below its top (in game and in the planner's own sim), so no block can save it, and
  the planner was right to refuse.
- Replica sweep with a last-tick press: a far-side block saves only the 4-air sprint jump. A well-timed press clears
  4 air with no block. Walking, no gap is saved by one block.
- I also corrected my earlier note: the takeoff kept the sprint-jump boost. The vz of 0.262 was read after the jump
  tick's ground friction.
- **Lane now:** 4 air with a sloppy early press. It passes when the body crosses, by a re-timed press or a placed
  block, and the detail says which. Today the assist crossed by re-timing the press. The planner's far-side block
  stays covered by `BlockAssistPlannerTest`, and in-game placement by the wall lane.
- **HAY lane:** an unhurt water landing now counts and is named in the detail. Failing runs had steered off the 5x5
  platform to sea water about 87 blocks down; this run used hay.
- **Parkour and Combat playtests: PASS.** The Combat failure was the knock-on of the gap-lane death, as suspected.

## Hand-over key landed (merge f149dbf, 17:37)
- Hold one key: the autopilot takes the whole body (parkour Takeover, combat on the crosshair target or your last
  attacker, every reflex armed). Release hands back the same tick, and Stop-All revokes it. It is unbound by
  default: Controls > Key Binds > Utility Suite, "Hand over", or menu Tools > Hotkeys. The double-tap latch is off
  by default (Autopilot bar settings). Run note: docs/state/run-2026-11-22-handover-key.md.
- Verified: `./gradlew test` green (1851 tests). Menu and Combat playtests PASS. Not yet played by hand.

## First item after work: held-back press walks off the edge (corner-fence-2gap, Co-pilot)
- In the 17:37 run this lane failed. The scripted press at tick 39 was held back by
  `MovementAssistBrain.pressHeldBack` (technique-first: "the jump waits for the last supported tick"). The body then
  walked off at z 11.51 with no jump: jump false, max y 1.00, commitment IDLE all flight.
- It had passed 5 runs in a row before, so it depends on the tick phase. Owner-visible risk: in play, a press the
  assist holds back can turn into a walk-off.
- **Fix direction:**
  - Never let a held press outlive the support: release it on any tick where the next simulated tick would leave
    the ground under the real collision box.
  - Add a lane-replica test for all four tick phases of this run-up.
- Every other landing lane passed: Co-pilot and Takeover for fence, wall, pane and corner wall; Takeover corner
  fence. The Corrections lanes pass their "no walk-off" promise.

## Fonts and hazard arena (17:55)
- **Suite fonts landed (16aa4d8):** Rajdhani Medium/SemiBold and JetBrains Mono, SIL OFL. This is the one approved
  licence exception: font files only, with their licence texts. Asset names are lowercase, because Minecraft rejects
  uppercase pack paths. The load probe passes, and HUD text (`HoloKit.drawText`) renders in Rajdhani. **Not done:**
  the menu widgets still draw through vanilla `drawString`, so the menu is still in the pixel font. Route them
  through `HoloKit.drawText` / `SuiteFonts.Client.style(role)`.
- **Hazard arena (owner request):** `tools/bot-arena/bots/arena.js`, copied to docs/research/bot-arena-arena.js.
  - `--arena hazards` is now the default: a 41x41 field on a 10-deep stone base. Every hazard is mirror-symmetric
    about the centre, so neither start has an edge:
    - a 3x3 lava hole dead centre between the starts
    - 3-wide, 8-deep ravines on both flanks, with one-block stairs out at both ends
    - 2-wide wading streams along both side walls
  - `--arena flat` keeps the old 21x21 floor, so earlier results stay comparable. Hazard results go to
    `<setup>-<engine>-hazards.jsonl`.
  - New `outgeared` kit (Fable's pass bar, at least 16/20): ours is iron sword and full iron; the bot has full
    diamond, a Sharpness II sword and a shield. No saturation at round start for any kit.
  - Deaths now count lava, falls and drowning, and each record carries a `cause`.
  - Verified: `node arena.js --dry --build` built it on the server, and all 12 block probes pass.
  - **Not run yet:** no rounds on the new arena. Two things to watch, since neither bot nor client has met lava or
    ravines yet: does the mineflayer pathfinder route around the lava, and does our client.
  - To run after work: start the server (`java -Xmx2G -jar paper-1.21.1-133.jar --nogui` in tools/bot-arena/server),
    then `./gradlew runArena`, then `node arena.js --setup outgeared --rounds 20`. The server is stopped now.

## Menu fonts and Grim in the arena (18:05)
- **Menu text in the suite face (b707120):**
  - `UiRenderUtils.text` and the new `UiRenderUtils.width` draw and measure with the UI face, and every menu
    width call now uses `width`, so layouts follow the narrower face.
  - Small caps (`SuiteMenuScreen.smallText`) draw natively in the MICRO face instead of a 0.75 pose scale, which
    had dropped their thin strokes.
  - Tooltips stay vanilla, because their lines come pre-wrapped by vanilla's splitter.
  - **Known defect:** some flat-topped capitals lose their top row in the menu ("Tools" reads "Iools", "Fight"
    reads "Hight"). A shift of 0 did not fix it, so I reverted that. Next suspects: oversample vs the menu's
    fractional scale, and nearest sampling of the glyph atlas. Vanilla text is one setting away (visual settings,
    font).
- **Grim runs during arena fights again** (owner request):
  - The Grim jar is back in server/plugins.
  - `server/permissions.yml` (copy: docs/research/bot-arena-permissions.yml) gives ops `grim.exempt`.
  - arena.js ops only ArenaBot and deops Dev at start. Grim checks our client, but doesn't set back the bot's
    non-vanilla movement, which earlier measured Grim against the bot instead of us.
  - Each round record carries `grim: {total, checks}`, counted from server/logs/latest.log lines "Dev failed
    <Check>", and the round line prints the Grim count and the death cause.
  - Verified: Grim 2.3.75 loads with no permission errors, and ops.json lists only ArenaBot.
  - **Unverified:** the exact Grim alert wording in the log. If the first rounds show `grim 0` while Grim's own
    `/grim` alerts say otherwise, fix the regex in `grimFlags`. Check too that the bot isn't being set back (the
    `--trace` distance should close).
- After work: run `node arena.js --setup outgeared --rounds 20` on the hazard arena with Grim on (the server is
  stopped). The parkour held-back-press fix stays first.

## Volley at 18:05, both merged (18:16). Stopping point for the night
- **Held-back press walk-off fixed** (merge 581f9c4):
  - A held press was lost when its search or script went away mid-hold. That matches "commitment IDLE all flight".
  - It now fires at once in that case, holds only while the next tick is still supported, and the script path
    tracks its hold.
  - New test `ParkourLaneReplicaTest.aHeldBackPressNeverTurnsIntoAWalkOff`: 320 replica runs (5 lanes, 2 modes,
    16 run-up phases, 2 target modes). It reproduced the walk-off before the fix.
  - **Unverified in game:** run the corner-fence-2gap Co-pilot lane
    (`./gradlew runPlaytest -PplaytestOnly=...parkour`).
- **Clipped menu capitals fixed** (merge 4bfb68b):
  - Root cause: glyphs were rasterised at oversample 4 but drawn at 2 screen px per GUI px. Nearest sampling then
    dropped every other texel row, which took out the thin bars. The HUD had it too: "Y=-54" showed as "Y=-b4".
  - Each face now has variants `_x1`..`_x6`, and the style picks the one matching GUI scale x menu scale x HUD
    scale.
  - The menu preview reads clean.
  - **Unverified:** the other preview screens, GUI scales 1/3/5/6, Sodium, and the extra reload cost (18 font faces
    instead of 3).
- Full `./gradlew test` is green on main after both merges.
- **Next after work:**
  1. The parkour playtest on the lane above.
  2. The arena: `node arena.js --setup outgeared --rounds 20` on the hazard arena with Grim on.
  3. A look at the menu in game.
- 18:21 check on main: the Jobs tab, Combat tab and HUD previews read clean. Still in the pixel font: the search box
  and goal bar (vanilla `EditBox` text fields) and tooltips. That's a small follow-up.
