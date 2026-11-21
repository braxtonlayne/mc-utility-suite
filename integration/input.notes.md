# input package: core humanization (A7) and camera momentum release (A10)

## Done (in owned paths)

- `core/input/PrimitiveRandom`: SplitMix64 `RandomGenerator` in one `long` field, polar-method Gaussian with a cached
  spare. One per session, seeded from the clock in `CoreBootstrap`; the smoother's curve shape, click spacing,
  `nextGaussianDelayMs`/`nextGaussianTicks` and the humanizer all draw from it.
- `core/input/Humanizer` implements `IHumanizer`, registered with `Services.registerHumanizer` in `CoreBootstrap.init`.
  - Skill drawn once per session in [0.6, 0.9]; override in [0, 1] from settings.
  - `reactionTicks`: log-normal around the median, sigma 0.35 - 0.2 * skill, clamped.
  - `turnSeconds`: Fitts `a + b * log2(angle / width + 1)`, a = 0.10 - 0.06 s, b = 0.16 - 0.08 s (per skill), floored at
    `angle / 450`. Deterministic (no draw), so it is monotonic in angle.
  - `aimOffset`: triangular (sum of two uniforms) per axis, spread 0.35..0.75 of the half extent (tighter with skill).
  - Off: behaves like `IHumanizer.NEUTRAL` (except `nextUnit`).
- `InputSmoother`: a new turn from `setRotationTarget` whose target speed is exactly 1 and whose lease is not
  EMERGENCY gets its main-motion time from `turnSeconds(distance, 3 deg)`. It only lengthens a turn
  (`RotationCurve.start(..., minMainSeconds)` takes the max with the base-speed duration), and every frame/tick is still
  limited by `RotationLimits` (450 deg/s). Retargets inside the 15-degree blend window are unchanged.
- `MomentumRelease` (engine free) + smoother wiring: when the mouse preempts a VISIBLE camera lease (ManualInputHooks
  brackets `arbiter.onManualInput(MOUSE_LOOK)` with `smoother.setManualLookPreempting`), the turn's last per-frame
  velocity keeps going and decays exponentially (tau 60 ms, hard end 200 ms) on top of the user's mouse. Capped at
  450 deg/s; an axis the user moves against loses its momentum at once. `releaseAll` (Stop-All, world unload), a new
  automated turn, a lease closed by its module and the setting being off all stop it dead (old behaviour).
- Settings read in `CoreBootstrap.applyCoreSettings` from `global.json` -> `core` (keys in `core/input/InputSettings`):
  `humanization` (bool, default true), `humanization_skill` (double, default -1 = per-session), `camera_momentum_release`
  (bool, default true).
- Tests: `HumanizerTest`, `MomentumReleaseTest`, `HumanizedTurnTest` (no-snap regression with humanization at
  maxed speed, Fitts slows small turns, EMERGENCY and explicit speeds are not humanized, allocation tests for every
  `IHumanizer` call, the smoother tick/frame and the momentum step).

## Integrator: wiring outside the owned paths

1. Menu (ui/tabs/ToolsTab.java, `CORE_SPECS`): add the three settings so they show under the core settings:
   ```java
   SettingSpec.bool(InputSettings.KEY_HUMANIZATION, "Humanized timing", true, "",
           "Turn time by Fitts's law, varied reaction delays and aim points. Only ever slower, never past 450 deg/s."),
   SettingSpec.decimal(InputSettings.KEY_SKILL_OVERRIDE, "Humanized skill (-1 = per session)", -1.0, -1.0, 1.0, "",
           "Fixed skill from 0 (slow, loose) to 1 (quick, tight). -1 draws one per session between 0.6 and 0.9."),
   SettingSpec.bool(InputSettings.KEY_MOMENTUM_RELEASE, "Camera momentum on takeover", true, "",
           "When you move the mouse during an automated turn, the turn eases out over about 0.2 s instead of stopping dead.")
   ```
   (import `com.utilitysuite.core.input.InputSettings`). Changing them already re-applies through
   `CoreBootstrap.applyCoreSettings`.
2. Legit presets (if a preset file lists core settings): humanization stays on for every legit level; a "less legit"
   tuning may lower it only through the skill override, never by bypassing the 450 deg/s cap.
3. Modules that time reactions or aim: call `Services.humanizer()` (already the real one after core init).

No new mixins (the existing `MouseHandlerManualLookMixin` already feeds `ManualInputHooks`), no lang keys required
(labels above are literal strings like the other core specs).

## Not done / follow-ups

- Fitts target width is a fixed 3 degrees for plain `RotationTarget`s; the contract has no width field. A future
  `RotationTarget` width (or a per-lease hint) would let combat pass the real hitbox angular size.
- `nextGaussianDelayMs` keeps a normal (not log-normal) shape to keep its contract; modules wanting human reactions
  should call `Services.humanizer().reactionTicks`.
- Momentum release is VISIBLE-only by design (a SILENT rotation already leaves the camera to the user and eases the
  server angle back at the cap).
- Mouse direction for the "never against the user" check assumes vanilla axes (dx > 0 = yaw up, dy > 0 = pitch down);
  with "invert mouse" on, the pitch check reads the raw sign and may keep pitch momentum for up to 200 ms.
