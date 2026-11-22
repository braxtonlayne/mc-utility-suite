# Opponent reads: FeintPlanner + SkillEstimator (volley 2026-11-22)

Both classes live in `com.utilitysuite.combat.tactics`, are engine free, hold up to 8 opponents in fixed primitive
arrays (least recently seen/touched slot reused) and allocate nothing after construction
(`OpponentReadsAllocationTest`: 50k ticks of observe+plan and 50k note calls, under 1 KB each). Public signatures are
unchanged from the contracts commit; only constants were added.

## FeintPlanner

### Reads (per opponent, kept by `reset()`, dropped by `clear()`)

- **Aim lead** (`aimLeadTicks`): `delta = wrap(oppYaw - bearing)` where `bearing = atan2(-dx, dz)` in degrees
  (Minecraft yaw, d = us - it), divided by the bearing rate `(dx*rvz - dz*rvx) / r^2` (deg/tick, from the *relative*
  velocity, so "as seen from it"). Positive = it aims ahead of us in the direction we move; negative = it lags.
  Skipped while the bearing turns slower than 0.5 deg/tick (no lateral motion to lead). Each sample is clamped to
  +-6 ticks; running mean for the first 5 samples, then EMA 0.15.
- **Pre-aim** (`preAim`): EMA 0.1 of "lead within [0.5, 3] ticks", sampled only while our strafe has been held
  8+ ticks (our strafe run is shared by all opponents, counted once per tick from `observe`/`plan`).
- **Bait rate** (`baitRate`): when our velocity away from it exceeds 0.06 b/t for 4 ticks, a trial opens for
  10 ticks. Success = it sprints (`oppSprinting`) with closing speed > 0.1 b/t before the window ends. One trial per
  backpedal run. Mean for the first trials, then EMA 0.25.

### Plan state machine

```
NONE --(preAim > 0.6, held strafe 8+, unit < 0.25)--> BREAK_PATTERN (6..10 ticks, strafe = -ourStrafe) --> NONE
NONE --(bait test passes, unit < chance)------------> BAIT_RETREAT (5..8 ticks, forward -1, no sprint)
BAIT_RETREAT --(it sprinted in this/last tick AND (oppReadyIn > 3 OR (dist < 3.2 AND ownReadyIn == 0)))--> PUNISH
BAIT_RETREAT --(canBackUp false)--> NONE (abort the same tick, overrides KEEP)
BAIT_RETREAT --(time out)--> NONE
PUNISH (5 ticks, forward +1, sprint allowed) --> NONE
```

- Bait test: `canBackUp`, 2.5 <= distance <= 4.0, `oppReadyIn == 0`, and `ownReadyIn <=` the bait length.
  Chance per eligible tick: 0.25 when `baitRate > 0.5`, 0.02 while untested (one probe, then the rate decides),
  0 otherwise. A mob never sprints in, so after its first failed probe it is never baited again.
- Break has priority over bait when both are eligible.
- Pacing: any feint end sets `nextAllowed = end + 40 + unit * 40`. A start also needs `unit < START_CHANCE`, and
  the duration comes from the same unit rescaled, so starts are geometric-delayed and never periodic.
  Start-to-start is always >= 40 + feint length.
- Starting a break halves that opponent's pre-aim so the next break needs fresh evidence.
- `feints()` counts started BREAK_PATTERN and BAIT_RETREAT (PUNISH is part of its bait). `reset()` ends the running
  feint, clears the cooldown, our strafe run, backpedal/trial/sprint-in state; habits stay.
- Overrides are `KEEP` (and `allowSprint()` true) whenever `plan` returns NONE.

## SkillEstimator

Components (each 0..1):

| component | raw | score | weight |
|---|---|---|---|
| accuracy | `(hits + 0.5) / (swingsInReach + 1)` | `(a - 0.15) / 0.75` | 0.25 |
| combo | `0.7 * EMA(finished combo) + 0.3 * min(best, 5)`, an ongoing longer run counts | `(c - 1) / 2.5` | 0.30 |
| reaction | EMA 0.2 of reaction ticks (mean for first samples) | `(13 - r) / 9`, 0.5 unmeasured | 0.25 |
| evasion | `(ownMisses + 0.25) / (ownSwings + 1)` | `e / 0.35` | 0.20 |

`raw = 0.08 + 0.92 * blend`. Combos: consecutive `noteOpponentHit` without a `noteOwnHit` between; the combo is
finished (and fed to the EMA, alpha max(0.3, 1/n)) on our hit.

Every note* is one sample. `skill()` is 0.5 (`UNKNOWN_SKILL`) until 8 samples; from then on each event moves the
reported value a quarter of the way to `raw`, at most 0.02 per event (gaps under 0.001 snap), starting from 0.5.

Test profiles: a mob (lands half its in-reach swings, no combos, never dodges, reacts in 12) settles at about 0.21;
a player landing 70% in 3-hit combos, reacting in 5 and dodging a third of our swings at about 0.85.

## Left for playtest

- Whether breaks and baits read as player-like or as glitches (break length 6..10, bait 5..8, punish 5).
- Bait success against real players; whether 2.5..4 blocks is the right bait band and 3.2 the commit distance.
- Whether the dial settles one notch above (too easy / too hard) and how fast it gets there (0.02/event).
- Behaviour against mobs: should stay quiet (zombies aim straight at us, never sprint in).
- Pre-aim from real yaw data: server yaw is quantised to 360/256 deg and interpolated, which adds noise at
  0.5..3 tick leads; the EMA may need a slower alpha or a deadband.
