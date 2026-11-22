# anticipatory-aim (review section 2.4)

Goal: the assistant's aim looks calmer than the opponent's and never loses it. Public signatures are unchanged from
contracts commit bf4c67d. All aim still goes through the core smoother at the 450 deg/s cap; this task only changes
where the aim point is and which way we strafe.

## StrafePhaseModel (combat/tactics)

- 8 slots, least recently seen slot reused (as in OpponentModel). Primitive arrays only, no allocation after
  construction (StrafePhaseModelAllocationTest: observe + predictOffset + queries, 50k calls under 1 KB).
- Lateral sign uses the same convention as `OpponentModel.observe` (+ = the opponent's own right while it faces the
  observer).
- Reversals: |lateral| < `DEAD_ZONE` (0.03 b/t) has no side. A new side has to last `MIN_HOLD` (3) samples, so 1-2
  tick taps are ignored, and the reversal is dated back to its first sample. Dead-zone samples neither confirm nor break
  a candidate. `STAND_TICKS` (8) samples with no side mean the opponent is standing: sign 0, confidence 0, and the phase
  restarts. A gap of more than `GAP_TICKS` (20) between samples also restarts the phase but keeps the holds.
- Period = median of the last 8 hold durations. Confidence = (1 - CV/0.4) * min(1, holds/4), and 0 below 3 reversals
  (2 holds). It fades to 0 when the next reversal is late by more than one period past 1.5x the period, which covers
  circle-strafers and broken rhythms.
- `predictOffset` splits the velocity into a radial part and a lateral part along the line to the observer seen last.
  The radial part is the plain lead. The lateral part is integrated with its sign flipping at each expected reversal,
  then blended with the plain lead by a weight rescaled from the confidence (0 below 0.3, full at 0.9). The result is
  capped at the plain lead's lateral size. Below 0.3 confidence the output is exactly `velocity * leadTicks`.
  Returns the weight used.
- Known simplification: one period for both sides. An opponent who holds right 10 and left 14 gets a period of about 12
  and a lower confidence. Per-side medians are a possible follow-up if playtest shows asymmetric rhythms.

## StrafePlanner 6-argument update (combat/assistant)

- `withDir == NONE` or `withStrength <= 0.3` runs the 4-argument path unchanged. This is checked by
  StrafeWithTest.noPreferenceReproducesTheFourArgumentPlanner over 3000 random ticks, and StrafeBiasTest stays green.
- Above the threshold, already moving with the opponent: an interval boundary extends the run (interval shaped by
  the bias, then x(1 + 0.5 * strength)) instead of reversing. Moving against it: switch at the boundary or once the
  current direction has been held `WITH_MIN_HOLD` (6) ticks. The preference never changes direction faster than that.
- Hazards win. A hazard on the preferred side drops to the 4-argument path for that tick, and both sides blocked gives
  NONE.
- Simulation (StrafeWithTest): against an A-D opponent with period 12 at 3 blocks, the mean needed yaw rate with the
  preference is less than half the rate when strafing against it. In practice it is close to 0 against about 7.6
  deg/tick.

## AimSolver

- New `neededYawRateDegPerTick(CombatSnapshot s, double ownLateralPerTick)`: |target lateral - own lateral| / distance,
  in degrees per tick. `ownLateralPerTick` is + to our right while facing the target, so `StrafePlanner.RIGHT * speed`
  maps directly. For tests and for the wiring to log.
- The offset overload `solve(s, ox, oy, oz, out)` gives exactly the old lead for offset = velocity * lead
  (AimSolverLeadTest).

## Wiring sketch (for combat-wiring, not done here)

Per engaged tick: `phase.observe(targetId, tick, tx, tz, tvx, tvz, px, pz)`. Then
`c = phase.predictOffset(targetId, tick, lead, tvx, tvz, xz)` and `AimSolver.solve(s, xz[0], tvy * lead, xz[1], out)`.
For strafing: `withDir = StrafePlanner.mirrorOf(phase.lateralSignAt(targetId, tick, 2))` with
`withStrength = phase.confidence(targetId)` (or the strafe bias's magnitude when there is no phase yet).

## Left for playtest

- Whether the lead feels calm or twitchy against real players who vary their rhythm.
- Mirroring against a circle-strafer: the preference should simply hold one side.
- Crosshair-on-target percentage before and after (log `neededYawRateDegPerTick` and `crosshairOnTarget`).
