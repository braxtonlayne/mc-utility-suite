# Run note: Hand-over key (2026-11-22)

Proposal 1 in `docs/design/feature-proposals-2026-11-22.md`. Branch `worktree-agent-ae18d609c94cdcb37`, from main
at `ddbed04`.

## What was built

One held key gives the autopilot the whole body. It raises authority only: no rotation cap, reach, line-of-sight
rule or legit level is touched anywhere in this change.

- **Contract** (`core.api.IAutopilot`): `handOverHeld()` and `handOverStartTick()`, with defaults so fakes and an
  absent autopilot keep working.
- **State** (`core/autopilot/HandOverState`, engine free): hold, Stop-All revoke (the key must be let go and
  pressed again), optional double-tap latch.
- **Key** (`KeybindManager.KeyId.HAND_OVER`, `KeybindManager.isHeld`): unbound by default.
- **Autopilot** (`core/autopilot/Autopilot`): while held, `isOn()` is true, `allows()` is true for every reflex and
  `reach()` is ASSIST, whatever the toggle and the per-reflex settings say. The key is ticked from the
  every-tick listener, so it works with the toggle off. `handOverHeld()` asks the key again, so a release counts
  in the same tick. On release with the toggle off, the scheduler's turn and the reflex chain's lease are dropped.
  `onStopAll` revokes it.
- **Movement** (`MovementAssist`): the core key counts as the existing parkour hold-to-take-over key (CARRY, same
  thresholds), with the module on or off. With the module on it also arms fall clutch and parkour blocks.
- **Combat** (`CombatAssistant.handOverTick`, called from the combat bootstrap's client tick): while held and
  dormant it engages the entity under the crosshair if the target filter accepts it (a proactive pick:
  single-player and allow-listed servers only), else whoever attacked you (reactive: everywhere). The fight is the
  ordinary engagement and lease. On release, an engagement that started under the key ends that tick with
  `DisengageReason.HAND_OVER_RELEASED`; a fight the module started itself is left alone. A fresh movement press
  still ends the fight, and the key then waits out the usual pause before it picks again.
- **HUD** (`AutopilotHud`, `SuiteHud`): the takeover brackets stay in while held, one status line
  `STEM - FULL CONTROL` (a reflex acting shows its own line instead), notices `STEM HAS CONTROL` and
  `CONTROL RETURNED`. Nothing is drawn when idle; Clean-Screen hides the line as before. The key alone no longer
  reads as "AUTOPILOT ONLINE": that notice follows the toggle.
- **Menu**: Tools tab, "Hotkeys" card, row "Hand over (hold: the autopilot takes the whole body)". The latch
  setting is in the Autopilot bar's settings, group "Hand-over key", "Double-tap to latch" (off by default).

## How to use it

1. Options > Controls > Key Binds > Utility Suite > "Hand over: hold to give the autopilot control". Bind it (a
   mouse side button works well).
2. Hold it in game. Let go to take control back. Stop-All (Backspace) ends it even while it is held.

## Tests

- `core/autopilot/HandOverTest` (10): press and release, release within the tick, Stop-All, latch, every reflex
  armed with the toggle off, an unreadable key is never held, no allocation per tick.
- `combat/HandOverCombatTest` (6, real arbiter, smoother and registry): engages the crosshair threat, all leases
  gone one tick after release, no rotation step over 450 deg/s, never an ally or a non-threat, attacker engaged
  where extras are not allowed, Stop-All, the module's own fight survives the key.
- `ui/holo/HandOverHudTest` (5): nothing drawn when idle, brackets and line while held, fade then zero draw calls,
  no allocation.

## Open

- Not played in game. The feel of the hand-back across movement, combat and reflexes at once is unverified.
- Lease end reason: the combat lease ends as a normal release, and the reason is recorded as a
  `DisengageReason`. There is no hand-over value in `LeaseEndReason` (a `core.api` change left for a decision).
- The camera hand-back uses whatever each module's lease release already does. `MomentumRelease` was not wired
  specially for the key.
- The combat pick is the vanilla crosshair entity, so it needs the threat inside pick range. A wider "in front of
  you" cone would need an angle in `TargetCandidates`.
- The last attacker has no time limit while it stays alive and inside engage range.
- With the module on and a CARRY jump in flight, releasing the key lets that jump finish in CARRY, as the
  takeover design says for its own key.
- No MovementAssist unit test for the core key (the module needs the game); covered only by the shared code path
  with the parkour key.
- No parkour or combat playtest scenario uses the key yet.
