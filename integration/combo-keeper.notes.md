# combo-keeper

`combat/tactics/ComboKeeper` (signatures frozen by contracts commit bf4c67d). Engine free: bodies come from
`IBodySimProvider`, input is the shared `TradeInput`. Four scratch bodies are allocated in the constructor;
`begin` and `step` allocate nothing (`ComboKeeperAllocationTest`: 50k mixed begin/step calls under 1 KB).
`begin` measured about 55-65 us on the dev machine (36 candidates, each rolled until its hit tick).

## Model

1. **Knockback.** `begin(in, sprintHit, allowBackTap)` gets both fighters BEFORE the hit. A scratch opponent gets
   `knockback(0.4)` toward-source, plus `knockback(0.5)` along our yaw for a sprint hit; our scratch body is slowed
   with `scaleHorizontal(0.6)` after a sprint hit. Same constants as `HitTradeSolver` (it references them).
2. **Opponent arc.** No keys while knocked up (airborne or still rising); once grounded it holds `oForward`/`oStrafe`
   toward us, sprinting when `oSprinting && oForward > 0` (the solver's assumption). `airTicks` = first tick it is on
   the ground again (rolled past the hit tick if needed); `land*` = its predicted position at the planned hit tick.
3. **Candidates.** Reset {none, W-release 1/2/3, S-tap 1/2} x strafe {0, left, right} x hold {0, 2 ticks of no
   forward key after the reset} = 36 rollouts. S-tap is skipped when `allowBackTap` is false or `hazardBack` is set;
   a strafe toward `hazardLeft`/`hazardRight` is skipped; `hazardFront` means no plan at all (every follow-up closes
   in). Our body faces the opponent every tick (the aim does that in game).
4. **Fresh sprint.** After a sprint hit the sprint only comes back after W was released or S tapped and W pressed
   again; with RESET_NONE it stays off for the whole plan (slower approach, no sprint knockback). After a plain hit
   sprint is available at once and resets only cost time.
5. **Hit tick.** First tick t with: our charge ready (`max(sReadyIn, sCooldownTicks - 0.5)` ticks after the hit:
   the hit just sent restarted the cooldown), `t >= 10 - SwingGate.HURT_HOLD` (the swing is sent 2 ticks before
   immunity ends), and the opponent in reach (eye to box, `HitTradeSolver.reachSq`). None within `MAX_PLAN_TICKS`
   (20) rejects the candidate; no candidate left means `begin` returns false.
6. **Score.** `-hitTick + 4.0 if the hit is a sprint hit - 0.6 per tick inside the opponent's reach while its attack is
   charged (before our hit, after our own hurt ticks) - 1.0 if we end overrunning it (centers < 0.8) - 0.05 per reset
   tick - 0.04 per hold tick - 0.03 for a strafe - 0.5 when we bump a wall - 1.0 for a drop over 1 block`.
   Rejected: a drop over 3 blocks, or still falling more than 1 block below the start at the hit (unknown bottom:
   pit or ledge). Ties keep the earlier candidate (none, W1, S1, W2, S2, W3; no strafe first; no hold first).
7. **step(in).** Plays the stored keys of the chosen rollout. `begin` sets the keys for plan tick 1; the k-th `step`
   checks the live opponent against the arc at tick k and sets the keys for tick k+1. `hitInTicks` = hitTick - k
   (0 = swing now if the swing gate agrees; the keys stay on the closing keys). The next `step` ends the plan, as
   does a deviation over 0.6 blocks (3D) or passing `MAX_PLAN_TICKS`. Ending clears the keys.

## Constants and why

| constant | value | why |
|---|---|---|
| DEVIATION | 0.6 | about one player width; under it the plan's timing still holds |
| SPRINT_HIT_VALUE | 4.0 ticks | a sprint follow-up (full knockback) keeps the combo; worth a few ticks of waiting, not a whole cooldown |
| EXPOSED_COST | 0.6 per tick | standing in a charged opponent's reach invites the trade back; below one tick of delay so a hit we can take now still wins |
| TOO_CLOSE | 0.8 | centers closer than this means running through the opponent (aim swings, push) |
| RESET/HOLD/STRAFE costs | 0.05 / 0.04 / 0.03 | tie-breaks only: fewer key changes when nothing else differs |
| FALL_REJECT | 3.0 | vanilla fall damage starts above 3 blocks (same as the solver) |
| HOLDS | {0, 2} | lets the keeper wait out a charged opponent without longer resets; more values cost begin time |

## Observed in the tests (flat floor, opponent 2.8 ahead, we sprint in at 0.28 b/t, sword)

- Sprint hit, opponent attack ready in 4: S-tap 1 tick, hit at tick 12, opponent lands at tick 11 about 6 blocks
  out; the executed plan reaches it sprinting at full charge.
- Same with its attack far from ready: W-release 1 tick (the S-tap's extra space buys nothing).
- Plain hit: no reset, keep sprinting.
- Hop from a 1-block step: air 13 vs 11, hit at 18 vs 12. A hop from level ground does NOT lengthen the arc: vanilla
  knockback adds no lift to an airborne target (vy is kept, only the grounded case sets 0.4).
- Wall behind the opponent: arc stops at the wall (4.6 vs 6.6), reset stays at the shortest (W1) when its attack is
  not charged. When its attack IS charged, the keeper deliberately takes a longer reset (S-tap 2 + strafe) to stay
  out of its reach until our charge is back; that is the trade model, not a bug.
- With back taps allowed, S-tap was chosen in 24 of 40 sample scenarios (mostly when the opponent's attack is charged).

## Left for playtest

- Whether the chosen resets actually keep sprint knockback on a real server with ping (server-side sprint state
  vs the client's re-sprint after the W press; 1-tick resets may be eaten by packet timing).
- Feel of W-release vs S-tap (S-tap opens space but reads as backing off; may want a bias toward W-release).
- Combos against players who jump-reset (jump as they are hit: airborne knockback keeps their vy and halves the
  horizontal part, so the arc is shorter than predicted and the plan ends on the deviation check).
- The opponent rule (no keys while airborne, walks straight at us after landing) against strafing opponents.
- Whether 0.6 is the right deviation tolerance with ping jitter on the opponent's position.
