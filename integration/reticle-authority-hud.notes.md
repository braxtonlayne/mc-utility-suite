# reticle-authority-hud

Screen-space reticles from `IWorldMarks`, the control-authority bar, and the combat HUD switched to publishing
marks (design `stem-ui-2026-11-22.md` 5.3, 5.4, 8.6, 8.8).

## Wiring the integrator must add

1. Bootstrap line, in `UtilitySuiteMod` right after `UiBootstrap.init(modBus);`:

   ```java
   com.utilitysuite.ui.holo.MarksBootstrap.init(modBus);
   ```

   It subscribes `ScreenProjector` to `RenderLevelStageEvent` (game bus), clears every mark on logout (claims are
   kept), and registers `ReticleLayer` (slot FREE) and `AuthorityIndicator` (slot CROSSHAIR) with
   `Services.visuals().hud()` on the first client tick after `Services.registerVisuals(...)` ran. Until the Shell
   registers its `IVisuals`, nothing is registered (cheap early-out each tick).

2. Threat marks need the tracker's positions. In `CombatBootstrap.init`, after `FightCoachHud hud = new FightCoachHud(coach);`:

   ```java
   hud.useThreats(threats);
   ```

   Without it `FightCoachHud` keeps drawing its old "!" arrow (no regression, no marks).

3. The `target_hud` GUI layer registered in `CombatBootstrap` (`targetHud::render`) is now a no-op and can be removed
   along with its `RegisterGuiLayersEvent` listener. `TargetLine` is no longer used by `TargetHud` (left in place, it
   is outside this package; delete it with its test if nothing else needs it).

4. The Shell host must call `AuthorityState.SHARED.tick()` once per client tick (contract says so; not done here).

## What changed

- `ui/holo/ScreenProjector`: in `AFTER_LEVEL` copies the model-view (camera rotation) and projection (bobbing, hurt
  shake and nausea already multiplied in by `GameRenderer.renderLevel` in 1.21.1, verified in the NeoForge 21.1.265
  sources) into reused `Matrix4f`s and projects every occupied slot into a `float[16 * 6]` (normalised rect,
  bearing, camera distance) plus a state byte (on screen / off screen / behind). Entities by network id via
  `level.getEntity(id)`, box interpolated with `getGameTimeDeltaPartialTick(false)` (the entity renderer's partial
  tick). The math is the static `projectBox`; `expandToMin` grows a pixel rect to a minimum size. A projected slot
  is valid only for the generation it was projected for.
- `ui/holo/ReticleLayer` (FREE): primary target brackets (min 14 px, lock-on 2.2x -> 1x over 180 ms with
  `easeOutBack`, restarted per generation), vertical 5-segment health bar coloured by `HoloTheme.healthToken`, charge
  arc under the box with a strafe chevron, leader line to a FRAMED tag (name, health % in MONO, distance, state word);
  `FLAG_NO_LOS` = dashed and dim (0.45). Threats: diamond (WARN, DANGER with `FLAG_LETHAL`), off screen an edge
  chevron on an ellipse around the crosshair with the distance. A primary off screen also gets an ACCENT chevron.
  Allies: small ALLY ticks. Landing / objective: small brackets (+ MICRO label with `FLAG_TAG`). At most two primaries
  get full detail per frame (bounds the budget at 16 marks).
- `ui/holo/AuthorityIndicator` (CROSSHAIR): 40x3 split bar 14 px under the crosshair, MANUAL left, ACCENT / SYNC
  right; during a hand-back the assist share is `min(reported share (or 1 when 0), left / total)` so it drains over
  exactly the ticks left, smoothed with `AnimStore.settle` (45 ms). Reason in MICRO, WARN outline pulse while
  `overrideTicks > 0` (DANGER outline and assist share when `planLost`), small lock while `latchedKeys != 0`. Draws
  only while `AuthorityState.active()`, fades out 200 ms after with the last values.
- `combat/hud/TargetHud`: publishes the target as `PRIMARY_TARGET` (health incl. absorption / max, distance, name with
  "POP n" when totems popped, `FLAG_TAG`, `FLAG_NO_LOS` from the assistant's own `CombatWorld.sample` when the target
  is not under the crosshair, the tactic word from `CombatAssistant.tactic()` while engaged, e.g. "HOLD GAP"). Its own
  `g.fill`/`drawString` pill and the colour constants are gone. Clears the mark on no target, module off, setting off
  and world unload.
- `combat/hud/ReadRingModule`: new setting `ring` ("Ring", default off). Off = reticle style: each tick it offers
  charge and the predicted strafe side (`ReadRingGeometry.screenSide`, from the tilt and the player's yaw, 0 below
  0.2 confidence) to `TargetHud.offerRead`, used when it is the same target (valid 2 ticks). On = the old world ring.
- `combat/coach/FightCoachHud`: with the tracker wired, publishes the most imminent projectile (THREAT, LETHAL when its
  damage >= health + absorption) and blast (THREAT, LETHAL) as box marks instead of the text arrow; range bar, swing
  pip, AXE and BACK unchanged (that part is still `g.fill`/`drawString`, slated for the coach-pips item of 8.8).
  Marks cleared whenever the layer draws nothing.

## Verified in source / tests

- `./gradlew compileJava` and `./gradlew test` pass (all suites, incl. `AcceptanceScenario4Test`).
- `ScreenProjectorTest`: hand-computed points in front, a box's extent, behind (bearing 90), off screen (bearings 90,
  0, -90), near-plane straddle clamped, rotated view, minimum size, instance with an unknown entity and a stale
  generation.
- `ReticleLayerTest`: zero calls for an empty table and for unprojected marks; exact element counts for a tagged
  target (16 calls, 6 text runs); dashed NO_LOS; lethal off-screen threat (2 ribbons + distance, DANGER, right side);
  on-screen diamond; ally/landing/objective; lock-on contraction and restart on re-acquire; 16 tagged NO_LOS primaries
  and a realistic 16-mark mix under 250 quads.
- `AuthorityIndicatorTest`: zero calls inactive, shares + reason, hand-back drain math, SYNC takeover, override and
  plan-lost outline, latched lock, 200 ms fade.
- `ReticleAllocationTest`: projecting 16 marks, drawing the reticles and the authority bar allocate < 1 KiB over
  50 000 iterations (i.e. nothing per frame).
- Input-rule grep: no hits in the touched files.

## In-game checks (not run here: no game window in this task)

- Reticle alignment on a zombie with view bobbing on and off, while walking and sprinting, and at FOV 30, 70, 110
  (and with the FOV effect of sprinting / speed). If the brackets lag or swing with bobbing, the projection the event
  exposes is not the one the world was drawn with.
- GUI scale 1, 2, 3, 4 and HUD scale 0.75 / 1.5: brackets still hug the target (the projector stores normalised
  coordinates; the layer scales by the canvas size).
- Fight a zombie and a skeleton with the Combat Assistant: lock-on contraction on acquire, health bar colours
  (accent > 50 %, warn > 25 %, danger), charge arc and strafe chevron with The Read on, tag word while engaged, dashed
  brackets when the skeleton goes behind a wall, arrow threat diamond / edge chevron with distance, creeper fuse as a
  DANGER threat.
- Authority bar during a parkour landing hold: assist share drains over the hand-back, LANDING label, amber flash on a
  manual override, lock mark while keys are latched; fades 200 ms after.
- Clean-Screen and F1 hide everything (host gates); with nothing tracked the host makes zero HUD draw calls
  (`wantsDraw` false for both elements).
- Target very close (inside the near plane): rect is clamped to the screen, no flicker.

## Not done

- Coach pips as a segmented cooldown arc and CRIT / BACK OFF glyph cues (8.8 "Coach pips") are not converted; the
  coach's range bar, swing pip, AXE and BACK still use `g.fill` / `drawString` with their private colours.
- Melee wind-up threats (`ThreatTracker.meleeEntityId`) are not published as marks.
- Ally marks are drawn if published, but no module publishes ALLY marks yet.
