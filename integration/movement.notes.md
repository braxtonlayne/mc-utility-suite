# movement package: integration notes

## Merge

- `movement.lang.json`: two key names (Toggle Sprint, Toggle Sneak) for `en_us.json`.
- `movement.mixins.txt`: `travel.ClientPacketListenerVelocityMixin` goes in the `client` list of
  `utilitysuite.mixins.json` (RETURN of `ClientPacketListener.handleSetEntityMotion`; the dev-run mixin audit will
  check the target).
- Registration is done inside the package (`TravelBootstrap`, `TravelKeys`): modules `elytra_swap`, `move_toggles`,
  `mount_jump`, `fly`, `velocity_scale` (all TRAVEL, all off by default). No other bootstrap line needed.
- No core.api change.

## Autopilot / danger model wiring (consumer side done here)

- Danger feed: `FallClutch.feedDanger` reports `FALL` (ticks to landing, predicted fall damage) and `VOID` (below the
  world, or no landing within the 120-tick horizon over an open column); cleared on landing, in water, flying or
  gliding. `TerrainAssist.feedLava` reports `LAVA` (at the feet = 0 ticks, or up to 3 blocks along the motion, with a
  bearing). Feeds only run while their module (or the autopilot clutch reflex) runs; LAVA only while Terrain Assist's
  hazard overlay is on.
- `FallClutch` asks `mayAct(FALL_CLUTCH, ticksNeeded, damageAverted)` when the autopilot drives the reflex, takes
  `chainLease(FALL_CLUTCH, "fall_clutch")` when it covers the channels (else its own EMERGENCY lease), calls
  `noteActed` / `noteDeclined` / `noteDone` with constant strings, and never closes a chained lease (noteDone ends it).
- `ElytraSwap.tick()` runs from the travel bootstrap every tick so `Reflex.ELYTRA_SWAP` works while the module is off;
  it uses `mayAct` / `noteActed` / `noteDone` too.
- Suggestion for the autopilot package: an `ELYTRA_SWAP` setting in `core/autopilot/Autopilot.allows()` (stub returns
  true while on).

## Legit tuning

- Fly and Velocity scale require `LegitPresets.current(...) == RELAXED` (a mixed "Custom" level is refused) plus
  single-player / allow-list (Fly also where the server already granted flight). They re-check every 40 ticks.
- Nothing added to `LegitPresets.KNOBS` (the gate reads the level; no knob needed).

## Done, not verified in game (no runClient in this run)

- Early staging (A2): `FallRiskEstimator` + `FallStaging` (setting "Stage the clutch early"). Moves the clutch item
  from the main inventory into an EMPTY hotbar slot only (a full hotbar is left alone) and preselects it; restores the
  previous slot if the user did not change it. Note: a fight on a ledge also preselects the clutch slot, as the brief
  asked; if that feels wrong mid-fight, gate the fight trigger behind a setting.
- Clutch ladder (A6): `ClutchLadder` ranks block clutch, elytra deploy (jump tap), steering into water within 4 blocks
  of the landing (keys only), and the pearl (turn at <= 450 deg/s + throw + flight must fit). In game: block, elytra
  and water steering are wired; settings "Deploy a worn elytra", "Steer into water", "Glide pull-up".
- Glide guard: while gliding and sinking toward the ground fast enough to hurt, pull the nose up to -25 deg pitch
  through the capped smoother (skipped while anything else holds the camera, e.g. the travel autopilot's elytra
  driver).
- Elytra Swap: SWAP click for a hotbar elytra, three PICKUP clicks for a main-inventory one; same plan swaps back.
- Toggle Sprint: held through `setKeyHeld(SPRINT)` under an IDLE-priority MOVEMENT lease; a movement-key press pauses
  it (arbiter manual override) and it resumes when the channel frees. MovementAssist ignores this lease when deciding
  "automation drives movement". Releasing on the REAL sprint key is not possible without reading `options.keySprint`
  (forbidden outside core/input), so the toggle key releases it.
- Toggle Sneak: applied as `input.shiftKeyDown = true` in `MovementInputUpdateEvent` (like ScoutCameraModule), NOT via a
  held key: a manual-override pause must never drop a sneak at a ledge. A fresh real sneak press releases it.
- Mount Jump Charge: tap jump on a jumpable mount; the automation holds JUMP and releases at the target bar
  (default 95%). A long manual press is left alone; a jump press during the automated charge preempts the lease.
- Fly: sets `abilities.mayfly` client-side (no mixin). Unverified: whether the integrated server tolerates it without
  `allow-flight` (it may correct or kick for flying). Test in single-player before shipping.
- Velocity scale: entity-motion packets (`handleSetEntityMotion`) and explosion knockback (`handleExplosion`), both
  hooked at RETURN in the one mixin (targets checked against the 21.1.265 sources).

## Not done

- Pearl clutch in game: needs a ledge search, a smoothed turn and a throw; only the ranking (`ClutchLadder.offerPearl`,
  tested) exists.
- Staging does not skip the water bucket in the Nether (the clutch itself does).
- No playtest scenarios added for the new behaviors.
