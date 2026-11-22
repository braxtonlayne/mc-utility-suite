# Hit-trade solver (volley 2026-11-22)

`com.utilitysuite.combat.tactics.HitTradeSolver` picks the movement keys for the next tick of a melee duel by
simulating both fighters a few ticks ahead. It implements footsies: stay just outside reach while the opponent's attack
is charged, step in right after it swings. Keys only. It never asks for a rotation; the aim keeps us facing the target.

Public surface (frozen for the Combat Assistant): `HitTradeSolver(IBodySimProvider)`, `solve(TradeInput)`, `valid()`,
`forward()`, `strafe()`, `jump()`, `sprint()`, `score()`, `scoreOf(combo)`, `combo(f, s)`, `COMBOS`. Added:
`reset()` (call on a new target or engagement to drop hysteresis) and the constant `REJECTED`.

`TradeInput` gained fields with safe defaults: `sHurtTicks`, `oHurtTicks` (remaining hurt immunity, default 0) and
`resprintTicks` (default 2).

## Model

For each of the 9 key combinations (forward -1/0/+1 x strafe -1/0/+1), plus a jump-held variant when `allowJump`,
both bodies are set from the input and rolled forward `horizon` ticks (clamped 1..12; 6-8 intended). Worst case is
18 rollouts x 8 ticks x 2 bodies = 288 body ticks.

Per simulated tick:
1. Both fighters face each other (yaw = atan2(-dx, dz) from positions; Minecraft yaw 0 = +Z, 90 = -X).
2. We hold the combination, sprinting when forward and not in a re-sprint wait. The opponent holds
   `oForward`/`oStrafe` relative to facing us, sprinting per `oSprinting` (mobs never sprint-hit).
3. Cooldown counters count down. After an attack a counter resets to `cooldownTicks - 0.5` (vanilla reaches full
   strength half a tick early); a mob (`oUsesCooldown` false) resets to 20.
4. A fighter attacks when charged, the victim is in reach (attacker eye at y + 1.62 to the nearest point of the
   0.6 x 1.8 box, the same as `CombatSnapshot.reachDistanceSq`) and the victim is out of its 10-tick hurt immunity.
5. **Simultaneous ticks:** when both could hit on the same tick, ours resolves first and counts **half** (in the
   real game it is a coin flip on packet order). The opponent's swing is not consumed: it stays charged and is
   checked again next tick, after our knockback had its effect, so it lands only if it still reaches.
6. A landed hit: `victim.knockback(0.4, attacker - victim)`. For a sprint hit, also
   `victim.knockback(0.5, sin(yaw), -cos(yaw))` with the attacker yaw, then `attacker.scaleHorizontal(0.6)`, and the
   attacker cannot sprint for `resprintTicks`. As in vanilla, the 0.6 slowdown applies only to sprint hits.

Score per combination:
- hits landed - `takenWeight` x hits taken, each hit weighted 0.95^tick, so later hits count less;
- plus 0.15 x an end-position term in [-1, 1]:
  - our window (ours ready within 2 ticks, theirs not): +1 inside our reach, or within the distance we can still
    sprint (0.25 blocks/tick x the ticks until their attack returns, at most 2 blocks); otherwise negative by the
    excess;
  - otherwise (their attack charged, or neither): +1 in the band just outside their reach (reach .. reach + 0.6);
    -1 inside their reach while theirs is charged (-0.25 if not); falling off with distance beyond the band;
- -0.2 if we walked into a wall (horizontal collision before taking a hit; being knocked into one is already priced
  by the hits);
- -1.0 if we end more than 1 block below the start (off an edge);
- **rejected** (`REJECTED` = -1e6) if the combination moves toward a flagged hazard (`hazardFront/Back/Left/Right`,
  relative to the direction toward the opponent), or the rollout drops more than 3 blocks (fall damage or void).

The answer is the best combination. Exact ties break in a fixed order (no strafe first, standing still before
stepping). The jump variant replaces the walking one only when it is 0.1 better. Hysteresis: the previous answer is
kept while it scores within 0.08 of the best. `valid()` is false only when every combination is rejected (for
example when we are already falling into a void); then the caller falls back to its spacing band.
`sprint()` = forward.

## Simplifications (not modelled)

- No armour, Knockback enchantment or knockback-resistance attribute: every hit is the bare 0.4 (+0.5 sprint).
- No shields, blocking or axe shield-disable.
- No critical hits, sweep attacks or damage amounts: a hit is a hit.
- Ping and server-side reach slack are ignored: positions are the client's, and reach is checked exactly.
- The opponent holds constant keys over the horizon and always faces us perfectly. It does not react to our moves.
- Hurt immunity ignores the "bigger hit overrides" rule.
- Knockback with a zero horizontal direction keeps the velocity (vanilla picks a random direction).
- The mob attack model is a 20-tick interval with the same eye-to-box reach (vanilla mob reach is box-based).
- Our real hurt immunity and sprint state on entry are inputs (`sHurtTicks`, `oHurtTicks`); `sSprinting` is unused
  (sprint follows the forward key).

## Tunables (constants in `HitTradeSolver`)

`HIT_DISCOUNT` 0.95, `SIMULTANEOUS_CREDIT` 0.5, `POSITION_WEIGHT` 0.15, `SPACING_BAND` 0.6, `NEARLY_READY` 2,
`CLOSE_SPEED` 0.25, `MAX_CLOSE` 2.0, `WALL_PENALTY` 0.2, `FALL_PENALTY` 1.0, `FALL_REJECT` 3.0, `JUMP_MARGIN` 0.1,
`HYSTERESIS` 0.08, `HURT_IMMUNITY` 10, `MOB_ATTACK_INTERVAL` 20. Per call through `TradeInput`: `horizon`,
`takenWeight`, `allowJump`, `resprintTicks`, reach and cooldown periods.

## Cost

`HitTradeSolverAllocationTest`: 50,000 `solve()` calls allocate nothing (under the 1 KiB probe budget). Measured
**about 10 us per solve** (horizon 8, jumps on, 288 body ticks) on the dev laptop in the JUnit JVM, which is well
under the 0.1 ms target. Scratch bodies come from `IBodySimProvider.newBody()` in the constructor only.

## Tests

- `KnockbackSimTest`: 0.4 on a standing body (0.4 away, vy 0.4), direction normalisation, the sprint hit adds 0.5 along
  the attacker's facing after halving, an airborne victim keeps vy, distance over 10 ticks (plain 0.6-2 blocks,
  sprint hit at least 0.5 further), zero direction keeps velocity.
- `HitTradeSolverTest`: backs off from a charged, charging opponent; steps in after the opponent swung; both ready
  and closing gives a positive score; hazardBack never backs off (back combos rejected); only the flagged
  direction is rejected; a wall behind makes strafing beat backing (on open ground backing wins); a fall into the
  void gives `valid()` false; mirrored inputs give mirrored scores and answers; hysteresis holds under input noise;
  `reset()` drops it; horizons 6/7/8; a mob punishes standing in reach; reach and yaw conventions.
- `HitTradeSolverAllocationTest`: zero allocation and the time per solve.

## Playtest list (in game)

- Does the spacing dance look human, or does it jitter? Watch the keys over a few seconds of footsies, and tune
  `HYSTERESIS` and `POSITION_WEIGHT`.
- Reach calibration: compare predicted in-reach ticks with real hits at real ping (singleplayer and the whitelisted
  server).
- Knockback distances in real fights: the Knockback enchantment, sprint resets (w-tap timing vs `resprintTicks`) and
  armour knockback resistance.
- Behaviour against mobs (zombies, skeletons up close) compared with players.
- CPU cost in a Spark profile while engaged.
- The simultaneous-tick half credit: does the bot step into even trades too eagerly? Raise `takenWeight` or lower
  `SIMULTANEOUS_CREDIT` if so.
