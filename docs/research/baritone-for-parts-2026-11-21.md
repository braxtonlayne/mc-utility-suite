# Baritone for parts: pro-level combat and movement (ideas, 2026-11-21)

Read-only Fable review, made at the owner's request. The goal: fighting the bot should feel like fighting a player who
outranks you in pure skill, and movement assist should turn the worst parkour player into a pro while following their
intent.

These are ideas only. Plug-in points were read at signature level, so confirm them, and check the Baritone API names
against 1.11.3. They build on the dodge, humanized turns and danger ranking, which are still unplaytested.

## 1. Baritone for parts

Baritone is a great route calculator but a poor athlete: it walks block centres, stops to turn, and has no idea a fight
is happening. Use it in three modes.

**Baritone drives (keep as is)**
- Covers mining, `clearArea`, long unobserved travel, break/place costing and chunk-cache scans.
- Seam: `jobs/baritone/PathBackend` and `BaritoneAdapter`. Turns are already replayed through
  `InputSmoother.captureForeignRotation`.

**Baritone plans, we drive (new, and the main change)**
- `PathBackend.planTo(x,y,z)` computes a route without executing it. Proposed mechanism: a custom `IBaritoneProcess`
  that returns `SET_GOAL_AND_PAUSE` (verify).
- `copyPath(long[] out)` returns the route as packed positions.
- A new engine-free `travel/movement/RouteFollower` treats the path as a corridor, not a rail:
  - runs `JumpSearch` plus the multi-jump A* along it
  - cuts corners and sprint-jumps flats
  - takes parkour shortcuts Baritone would walk around
- Use it for pursuit, fleeing and anything a person is watching.

**We plan and drive (within about 12 blocks)**
- `EscapePlanner`, `JumpSearch` and `TacticsPlanner` do the work. Baritone is never consulted inside melee range.

**Replace outright**
- Baritone's movement executor during combat and pursuit.
- Its parkour moves.
- Its fall handling (`FallClutch` and `ClutchLadder` take over).
- Its look behaviour (our rotation curve owns the head).

## 2. Combat that feels like someone who outranks you

Everything here is keys and timing, so the 450 deg/s rotation cap is never strained. A better player wins on spacing and
reads, not flicks.

1. **Hit-trade solver (M-L).**
   - Each tick, simulate both players 6-8 ticks ahead with `MovementPhysics`, knockback included, for all 9 key combos.
   - Pick the keys that maximise hits landed minus hits taken. The result is the "always 0.1 blocks out of your reach"
     dance.
   - Plug: `combat/assistant`, between `TacticsPlanner` and `StrafePlanner`. It replaces the fixed distances in
     `SpacingController`.
2. **Cooldown reading (S-M).**
   - Add `OpponentModel.attackReadyIn(id)`, built from the opponent's swing events and held-item attack speed.
   - Step in right after they swing, while their next hit would be weak; step out as their cooldown refills.
   - Time our own swings to the end of their hurt window (`SwingGate`).
3. **Combo keeper (M).** After a hit, predict the knockback arc and pick the sprint reset (W-release or S-tap) and the
   step that lands the next full-cooldown hit as they come down.
4. **Anticipatory aim (S-M).**
   - `AimSolver` leads by the opponent's learned strafe phase, not just their velocity.
   - `StrafePlanner` prefers strafing with the opponent, which lowers the turn rate needed.
   - Result: the bot looks calmer than the opponent but never loses track of them.
5. **Knockback vectoring (M).**
   - Generalise `EDGE_KNOCK` into a continuous field: pick the approach angle so knockback sends the target toward a
     drop, lava or cobweb, or pins it against a wall.
   - Plug: `TacticsPlanner`, plus a budgeted scan in `MinecraftCombatWorld`.
6. **Arena reading (M-L).**
   - A budgeted 16-block scan builds a cost field with:
     - high ground (crits and downhill knockback)
     - low ceilings, which break a crit-hopper's hops (detected via `hopRate`)
     - slow blocks
   - Adds two new tactics in `TacticUtility`: HIGH_GROUND and CEILING_DENY.
7. **Conditioning and feints (M).**
   - Measure the opponent's aim lead.
   - Show a strafe pattern, then break it once they start pre-aiming it.
   - Bait retreat: backpedal to draw a sprint-in, then step in on the cooldown read.
8. **Pearl intercept (S-M).** Simulate where a thrown pearl lands and start moving there before it does.
9. **Rank dial (M).**
   - Estimate the opponent's skill live from hit rate, combo length and reaction delay.
   - Set the `Humanizer` skill one notch above it, so the bot wins like a slightly better player, not a machine.
   - The default for the hunter bot.

## 3. Movement that makes a bad parkour player a pro

The player supplies direction and commitment; the assist supplies tick precision. It corrects only when the simulation
says the jump will miss.

1. **Intent estimator (M).** A new `travel/movement/IntentEstimator` scores candidate landing cells by:
   - the look ray
   - held keys
   - velocity
   - the line of recent jumps

   `JumpSearch` then aims for "the block they were looking at", not just progress along the heading.
2. **Turn gain, not turn injection (M).**
   - Scale the player's own mouse motion: a little more gain toward the ideal takeoff yaw, a little less away from it,
     and none when the mouse is still.
   - Every degree comes from their hand, so it can't snap (still clamped to 450 deg/s).
   - Hooks where `ManualInputHooks` sees mouse deltas.
   - Balanced level only; Legit stays keys-only. No client has this.
3. **Jump buffering (S).** A jump pressed 1-3 ticks early is held and fired on the last ground tick. This delays a user
   key by up to 150 ms, so it must be an explicit setting.
4. **Takeoff shaping (M).** When the takeoff speed is short of what the landing needs:
   - add the 45° strafe key, with turn gain supplying the small yaw offset
   - time sprint so the boost peaks at takeoff

   To the player it looks like they've learned strafe jumps.
5. **Stick the landing (S).** Tap sneak at touchdown on 1-block targets so the player can't slide off the far edge. Needs
   a new `sneak()` output on `MovementAssistBrain`.
6. **Tick-phase check (S).** Evaluate takeoff on tick N and N+1, using yaw extrapolated from mouse velocity, and jump on
   the better one.
7. **Technique library (L).** Multi-jump planner edges for:
   - neo jumps
   - head-hitter chains
   - ladder and vine grabs
   - slime and ice
8. **Authorship budget (S).** Cap corrected ticks per jump, scale help by the predicted miss distance, and fade it as the
   player's unassisted success rate rises.

## 4. The wow list

1. **Ghost Line:** a translucent copy of you runs one second ahead along the planned line, and you follow yourself
   through a course you could never clear alone.
2. **The Read:** a ring on the opponent fills with their attack cooldown and tilts toward their next strafe; the bot
   steps in the instant it empties.
3. **Pearl intercept:** the runner throws a pearl, and the hunter is already sprinting to where it will land.
4. **Unbroken takeover:** knocked off a ledge mid-fight, the bot clutches, scoops the water, turns, climbs back and
   crits, all in one continuous motion.
5. **Shadow match:** the bot learns your habits from black-box recordings and fights as you, one rank better.
6. **Ceiling trap:** it backs under a low roof, your crit hops bonk against it, and it punishes each one.
7. **Annotated replay:** after a fight, the timeline shows every decision with its reason ("stepped out: your cooldown
   90%").
8. **Corridor run:** the body cuts corners and takes parkour shortcuts while Baritone's path stays drawn, so people can
   see it ignoring the "safe" route.

## 5. Build first

1. **Plan-only Baritone with RouteFollower and the multi-jump planner.** It's the seam everything else needs: pursuit,
   fleeing, the hunter bot, Ghost Line and the intent estimator's jump graph.
2. **Hit-trade solver with cooldown reading.** The biggest change in how a fight feels. It's keys only, so there's no
   rotation risk, and it's unit-testable.
3. **Intent estimator, turn gain and stick the landing.** Together these are the "worst player becomes a pro" feature.
   Turn gain needs the most playtesting for feel.
