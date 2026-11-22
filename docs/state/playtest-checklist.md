# In-game playtest checklist

One ordered list of every in-game check still owed after the 2026-11-21 parallel run and second volley. Nothing here has
been marked done; the run-note check results are cited where they exist. Sources are noted in parentheses.

Date: 2026-11-21. Works through the next session in order, most dangerous first inside each section.

## 0. Parkour control model (hands-on, 2026-11-22)

Not run yet. Source: docs/design/parkour-control-model-2026-11-22.md (sections 3, 5, 10). Set Movement Assist
`control_mode` in the menu for each pass. Landing hold on, Release hold on = Fresh input.

- [ ] Fence walk-off, the reported failure. Sprint-jump across a 2-block gap onto a single oak fence post, hold W through
      the flight and keep holding it after touchdown. Expect to stay on the post. Release W, press it again: you walk
      off at once. Do it once in each mode: Corrections, Co-pilot (default), Takeover.
- [ ] Stone (cobblestone) wall post, 3-block gap, same hold-W test, each of the three modes.
- [ ] Glass pane, 1-block gap, same test, each of the three modes.
- [ ] Corner jump onto a fence: the landing sits behind the corner of a block beside the gap, so the straight line clips
      it. Hold W, one strafe tap, keep W held through the landing. Expect to land on the post and stay on it. Repeat
      onto a wall top. Each mode.
- [ ] Override gestures during a committed jump, one at a time: sneak, back, a fresh press of a movement key, a big
      mouse turn. Each must hand control back at once; nothing the assist wrote may remain after the gesture
      (design 4.1).
- [ ] Hand-back is visible: the authority bar under the crosshair drains smoothly over the release ticks (about 6 in
      Co-pilot, 8 in Takeover, 2 after a hard override) and the target bracket opens with it.
- [ ] Legit level LEGIT / BALANCED / RELAXED: LEGIT adds no camera help (keys only), BALANCED and RELAXED allow the
      co-steer; the walk-off protection (stale-key latch) works on all three.
- [ ] Clean-Screen hides every parkour element (authority bar, bracket, landing marker, status line, "Hold: release W
      to move" text).
- [ ] Devtest: run the 8b-parkour scenario (`-PplaytestOnly=Parkour`) and read the control-model lanes and the replica
      parity notes in the report (integration/parkour-devtest.notes.md lists the lanes).

## 1. Startup

- [ ] Run `./gradlew runClient` with the mixin audit on. Expect all 20 mixins applied with no failures, including
      `KeyboardHandlerTabGuiMixin` (run-2026-11-21-parallel.md, "Second volley"; ROADMAP "Open").
- [ ] Confirm the 7 mixins from the first run still apply: totem pop, velocity, and the 5 render filters
      (run-2026-11-21-parallel.md, "Needs an in-game playtest" item 1; render.notes.md, "New mixins").
- [ ] Note: the run note's 17:00 check already records the audit as clean on main. Re-confirm it in a fresh
      `runClient` before relying on it (run-2026-11-21-parallel.md, "Second volley").

## 2. Shell / UI

- [ ] Tab-GUI arrows and Enter, with a key mapping bound to an arrow key. Expect Tab-GUI to take the keys and the bound
      mapping not to fire (run-2026-11-21-parallel.md, "Second volley"; ui.notes.md, "Tab-GUI key consumption").
- [ ] F1, F3 and Clean-Screen hide Tab-GUI. Expect it to hide while each is active (run-2026-11-21-parallel.md,
      "Second volley").
- [ ] Tab-GUI overlap with the top-left HUD (run-2026-11-21-parallel.md, "Needs an in-game playtest" item 2).
- [ ] Menu at GUI scale 3 and 4: column fit, text caret, tooltips at the edges, scissor edges, mouse hit boxes.
      Expect no clipping or misaligned hit boxes (run-2026-11-21-parallel.md, "Needs an in-game playtest" item 2;
      ui.notes.md, "Not verified in a running client").
- [ ] Timeline screen at GUI scale 3 and 4. Expect it to lay out in menu-scale pixels and clicks and hover to land
      on the right rows (run-2026-11-21-parallel.md, "Second volley"; ROADMAP "Open", TimelineScreen item).
- [ ] World and Travel tabs: "Go back" rows and the World header pill. Expect the shared label, title and description
      and a 68 px pill (run-2026-11-21-parallel.md, "Second volley", row 6).
- [ ] Freecam switch row on the World tab. Expect it to behave as a switch (run-2026-11-21-parallel.md, "Second volley").

## 3. Input

- [ ] Camera momentum on takeover with invert-mouse off. Expect momentum to ease in the user's mouse direction and never
      against it (run-2026-11-21-parallel.md, "Second volley"; ROADMAP "Open").
- [ ] Camera momentum on takeover with invert-mouse on. Watch for up to 200 ms of pitch momentum against the mouse,
      which the notes say the raw-sign pitch check may allow (run-2026-11-21-parallel.md, "Second volley";
      input.notes.md, "Not done / follow-ups").
- [ ] Humanized turn timing feel. Expect small turns to be slower, and the no-snap cap (450 deg/s) to still hold
      visibly (run-2026-11-21-parallel.md, "Needs an in-game playtest" item 4).

## 4. Jobs

- [ ] Lit creeper next to a running strip-mine job. Expect the job to pause, the player to back off, and the job to
      resume (run-2026-11-21-parallel.md, "Second volley" and "Next session, first things" item 1; ROADMAP "Open").
- [ ] Same test: watch whether the resumed path walks straight back into the blast spot. `JobExecutor` was not changed
      for this (run-2026-11-21-parallel.md, "Second volley").
- [ ] Goal playtest with hands off the game window. Expect the job to run without a real mouse press pausing it
      (run-2026-11-21-parallel.md, "In-game checks after the merge"; ROADMAP "Open").
- [ ] Auto-Torch placement and slot restore during a job. Expect floor placement only, since wall placement is not
      implemented (run-2026-11-21-parallel.md, "Needs an in-game playtest" item 7; jobs.notes.md).
- [ ] Lava Plug: check the support-face choice when the lava is below the player (jobs.notes.md, "Not play-tested").
- [ ] Vein Work order and stop conditions. Expect it to mine only blocks within reach, and to stop when the inventory is
      full (`getFreeSlot() < 0`) (run-2026-11-21-parallel.md, "Needs an in-game playtest" item 7; jobs.notes.md).
- [ ] Vein Work start after the user's own break. Expect the start to be delayed by the configured manual-override
      pause (jobs.notes.md, "Not play-tested").
- [ ] Crop Work replant. Expect crops to be replanted; cocoa is not replanted (jobs.notes.md).
- [ ] Strip route turning away from lava (run-2026-11-21-parallel.md, "Needs an in-game playtest" item 7).

## 5. Combat and reflexes

- [ ] Shield raise and lower timing with the job-escape active at the same time. Expect the two to work together, but the
      notes say one lease per module means they can replace each other (run-2026-11-21-parallel.md, "Known leftovers";
      combat.notes.md, "Shield vs. assistant SwingGate").
- [ ] Dodge strafe speed. The 0.12 blocks/tick value is a guess; check it against close arrows and tune
      (run-2026-11-21-parallel.md, "Next session" item 3; combat.notes.md, "Dodge uses a conservative").
- [ ] Dodge and fuse back-off while the Combat Assistant is engaged. Expect them to stand aside, since the assistant
      owns movement in a fight (combat.notes.md, "Not done / follow-ups").
- [ ] Danger ranking with several hazards at once. Expect the ranking to pick the most dangerous hazard first
      (ROADMAP "Open"; run-2026-11-21-parallel.md, "Second volley" and "In-game checks").
- [ ] Tactic utility: flee and kite behaviour in a real fight (run-2026-11-21-parallel.md, "Needs an in-game
      playtest" item 5).
- [ ] Trigger Assist, Target HUD totem-pop count, and the visual range message (run-2026-11-21-parallel.md,
      "Needs an in-game playtest" item 5).

## 6. Autopilot

- [ ] Black-box rows with the autopilot off. Expect rows marked `"off":true` while `black_box_always` is on (default),
      and no actions taken. Check that the tick listener registers in the real game (run-2026-11-21-parallel.md,
      "Second volley"; autopilot.notes.md, "Not done").
- [ ] Death dump with the autopilot off. Expect the dump to be written on death (run-2026-11-21-parallel.md,
      "Second volley"; ROADMAP "Open").
- [ ] Autopilot scheduler picks under real hazards, and chain lease release on manual input (run-2026-11-21-parallel.md,
      "Needs an in-game playtest" item 3).
- [ ] "Autopilot: <reflex>" status line, and the black-box dump on a big hit (run-2026-11-21-parallel.md,
      "Needs an in-game playtest" item 3).
- [ ] Late, much better bid. Expect it not to preempt an acting reflex, which holds the turn
      (autopilot.notes.md, "Not done"; ROADMAP "Open").

## 7. Anything else in the sources

### Movement

- [ ] Early staging and slot restore: the clutch item moves into an empty hotbar slot and the previous slot comes back
      if unchanged. A full hotbar is left alone (movement.notes.md, "Done, not verified in game").
- [ ] Elytra deploy, Elytra Swap click plan, water steer and glide guard (run-2026-11-21-parallel.md, "Needs an
      in-game playtest" item 6; movement.notes.md).
- [ ] Sprint and sneak toggles at a ledge. Expect a sneak not to drop at a ledge, and the toggle key to release sprint
      (movement.notes.md, "Toggle Sprint" and "Toggle Sneak").
- [ ] Mount jump release point (run-2026-11-21-parallel.md, "Needs an in-game playtest" item 6).
- [ ] Fly on the integrated server without allow-flight. Expect it to work or be corrected or kicked; test in
      single-player (movement.notes.md, "Fly"; run-2026-11-21-parallel.md, "Next session" item 3).
- [ ] Velocity Scale on entity-motion packets and explosion knockback. Re-apply Relaxed first
      (run-2026-11-21-parallel.md, "Next session" item 3; movement.notes.md, "Velocity scale").

### Render

- [ ] Light overlay rescans, including slabs, carpet and snow layers (run-2026-11-21-parallel.md, "Needs an in-game
      playtest" item 8; render.notes.md).
- [ ] Blast overlay labels for swelling creepers, primed TNT and end crystals. Beds, anchors, wither skulls and
      fireballs are not covered yet (render.notes.md; run-2026-11-21-parallel.md, "Needs an in-game playtest" item 8).
- [ ] Slime chunks against a known seed (run-2026-11-21-parallel.md, "Needs an in-game playtest" item 8).
- [ ] Freelook in first and third person. Third person turns in place rather than orbiting, and the key state is sampled
      on the client tick (render.notes.md, "Freelook"; run-2026-11-21-parallel.md, "Needs an in-game playtest" item 8).
- [ ] Each render filter. "Hide rain and snow" also suppresses rain splash sounds (run-2026-11-21-parallel.md,
      "Needs an in-game playtest" item 8; render.notes.md).
- [ ] Tracer start point at the crosshair with view bobbing on (render.notes.md, "Unverified in game").

### Player

- [ ] Auto-Fish bite detection by splash sound and by the hook dip. Also check with the sound volume at 0
      (player.notes.md, "Unverified in game"; run-2026-11-21-parallel.md, "Needs an in-game playtest" item 9).
- [ ] Offhand Manager swaps, including a totem to the offhand at 8 HP (run-2026-11-21-parallel.md, "Needs an in-game
      playtest" item 9; player.notes.md).
- [ ] Quick Pearl restore after the throw (run-2026-11-21-parallel.md, "Needs an in-game playtest" item 9). Note: if its
      lease is preempted mid-swap, the moved stack stays in the hotbar slot (player.notes.md, "Known limitations").
- [ ] Auto-Respawn delay (about 30 ticks), the coordinates chat line, and the automatic respawn press
      (run-2026-11-21-parallel.md, "In-game checks after the merge"; player.notes.md).
- [ ] Auto-Reconnect: the manual-disconnect heuristic, and resume of the job from its goal label. Expect "Could not
      resume" if the label does not parse (player.notes.md, "Unverified in game"; run-2026-11-21-parallel.md,
      "Needs an in-game playtest" item 9).

### Integration and cleanup

- [ ] Spark profile with the new overlays and scans turned on (run-2026-11-21-parallel.md, "Needs an in-game playtest"
      item 10; ROADMAP "Open").
- [ ] Remove the empty `TabGui.onKey` registration in `UiBootstrap`, after the Tab-GUI check passes (ROADMAP "Open";
      run-2026-11-21-parallel.md, "Known leftovers").
- [ ] Remove the 16 kept worktrees under `.claude/worktrees/` once nothing on them is needed
      (run-2026-11-21-parallel.md, "Next session" item 4).
