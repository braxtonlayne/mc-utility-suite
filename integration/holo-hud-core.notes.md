# holo-hud-core: integration notes

Branch work: the sci-fi HUD core (stem-ui design sections 5.2, 7, 8.7, 8.9, 9).

## What landed

- `ui/holo/HoloKit` — `IHoloCanvas` over `GuiGraphics`. Fills for plates, frames (`HoloFrames`, fill = `GLASS_HUD`),
  brackets, hairlines and bars; one soft-ribbon primitive (`ribbon`, `arc`) through the `holo_gui` render type;
  glyphs through `holo_gui_glyph`; text without shadow; `number()` from a lazily filled string cache (-999..9999,
  digit-by-digit at a tabular advance outside it); items through `ItemIcons.forId`. HUD scale is applied once per
  frame to the existing pose matrix and restored from a saved copy (no `pushPose`). `hudOpacity` multiplies every
  alpha. `EffectLevel.OFF` = no ribbons/glow: axis-aligned ribbons become fills, others a hard 1 px ribbon.
  The engine-free half is `HoloKit.Geometry` (sinks, atlas layout, 256-entry sin/cos table) and is unit tested.
- `ui/holo/HoloRenderTypes` — `holo_gui` (linear filter) and `holo_gui_glyph` (nearest filter: the 7x7 glyphs must
  stay crisp at GUI scale 2-4, so the brief's "one render type" became two with identical state apart from the
  filter). Position-tex-colour, translucent, no depth test, colour-only write mask, no cull.
- `ui/holo/HoloAtlas` — 256x16 atlas drawn from code at first use (hard/soft/glow falloff rows, 16x16 radial glow,
  the 14 glyph bitmaps), registered as a `DynamicTexture` under `utilitysuite:holo/atlas`. Own art, no PNG.
- `ui/holo/HudHost` — `IHudHost`: early-out chain, slot placement and stacking by priority, appear fade + reflow via
  `AnimStore`, Clean-Screen exemption (`allowInCleanScreen`) and instant elements (`setInstant`, used by the stopped
  badge). Replaces `SuiteHud.topUsed`.
- `ui/holo/Visuals` — `IVisuals` (hud = the host, effects = `HoloTheme.effects`, shared marks/authority). Registered
  in `UiBootstrap` via `Services.registerVisuals`. `AuthorityState.SHARED.tick()` runs once per client tick (the
  `SuiteHud` `ClientTickEvent.Post` listener); marks + authority reset in `SuiteHud.onWorldUnload`.
- `ui/holo/VisualSettings` — module `appearance` ("Appearance"): effects, motion, accent preset, hud_opacity 40-100,
  hud_scale 75-150, written into `HoloTheme` on profile load and on change. Registered in `UiBootstrap`.
- Ported to `HudElement`s drawn only through the canvas: `HudPill` (status line) + `HudPill.Badge` (stopped / Clean),
  `InfoStrip`, `ModuleListHud`. Their private colour constants are gone. `HudStatusLine` gained `fraction()`.
- `UiBootstrap` now registers one HUD layer (`utilitysuite:status_line` = `SuiteHud`); the `info_strip` and
  `module_list` layers are gone (their elements live in the host). `tab_gui` is untouched.

## Wiring left for the integrator

1. **Appearance card in the menu.** `VisualSettings` reports `ModuleCategory.CORE` (always on, so no toggle and not
   in the module list), so the generic Tools loop does not render it. In `ui/tabs/ToolsTab.build`, under
   `p.section("Suite")`, add:
   ```java
   Services.modules().byId(VisualSettings.ID).ifPresent(m -> SettingsRenderer.module(p, m, false));
   ```
   (import `com.utilitysuite.ui.holo.VisualSettings`). Optionally add a description entry in
   `SettingsRenderer` next to `Map.entry(SuiteHud.ID, ...)`: `Map.entry(VisualSettings.ID, "Look of the menu and HUD")`.
2. **Lang.** Merge `integration/holo-hud-core.lang.json` into `assets/utilitysuite/lang/en_us.json`. HUD strings are
   still literals in code (as before); the `hud.utilitysuite.*` keys are there for when the HUD switches to
   translated text.
3. **Order of registration.** `Services.visuals()` is registered from `UiBootstrap.init`, which runs last. A module
   that registers a `HudElement` during its own bootstrap must do it lazily (first tick) or `UiBootstrap` must move
   the `registerVisuals` line earlier (it only needs `new SuiteHud()`, which has no side effects).
4. **UiPreview** (`ui/UiPreview.HudPreviewScreen`) still calls `SuiteHud.renderOverlay(g, font, w, false, true)`;
   that signature is kept and now draws the new HUD.
5. Devtest `MenuScenario` keeps rendering `UiBootstrap.hud()` as a layer through `CountingGraphics`; idle and
   Clean-Screen still return before any `GuiGraphics` call (the host gate runs before `drawManaged`).
6. Not wired here (out of scope): notices/toasts, world ribbons (`IHoloWorld`), the suite font (line heights already
   go through `HoloTheme.lineHeight`), the menu, the "one-shot bright bar" and the STEM header pulse in the module
   list, the 120 ms cross-fade is a fade-in of the new text only (no fade-out of the old string).

## APIs not verified by running (checked against the mapped 21.1.265 sources only)

- `RenderType.create(String, VertexFormat, Mode, int, CompositeState)`, `CompositeState.builder()` setters,
  `RenderStateShard.ShaderStateShard(GameRenderer::getPositionTexColorShader)`, `TextureStateShard(id, blur, false)`,
  `TRANSLUCENT_TRANSPARENCY`, `NO_DEPTH_TEST`, `COLOR_WRITE`, `NO_CULL`, `DefaultVertexFormat.POSITION_TEX_COLOR`:
  names verified in source; runtime look not seen.
- `VertexConsumer.addVertex(Matrix4f, x, y, z).setUv(u, v).setColor(argb)` (1.21 builder chain).
- `TextureManager.register(ResourceLocation, AbstractTexture)` with a `DynamicTexture(NativeImage)` created on the
  render thread; `NativeImage.setPixelRGBA` takes ABGR (white texels, so only alpha matters).
- `GuiGraphics.drawManaged(Runnable)` (deprecated in NeoForge but present) batches the whole HUD frame;
  `GuiGraphics.drawString(Font, String, float, float, int, boolean)`; `renderItem` inside the managed block.
- Modifying `g.pose().last().pose()` in place for the HUD scale and restoring it after the frame.
- Font alpha: `Font` turns an alpha byte below 4 into opaque, so `HoloKit` skips text whose faded alpha is < 4.
- Allocation: `HoloKit` itself allocates nothing per call, but the game's `drawString`, `Font.width`, `renderItem`
  (pushPose inside) and `BufferSource.getBuffer` (new `BufferBuilder` per batch) allocate internally. The allocation
  tests cover the host, the elements and the kit emitters through the recording canvas and sinks.
- `holo_gui` batches interleaved with vanilla fills end the previous batch on each switch (expected; a HUD frame
  uses fills, glyph quads and text). Ribbons are not used by the ported elements yet.

## In-game checks (owner or harness; not run here)

- HUD idle = 0 draw calls in the playtest counter (MenuScenario "HUD draws nothing when idle", Clean-Screen check).
- Readability of the status line, badges, info strip and module list over snow, sky and lava, at hud_opacity 40 and
  100.
- GUI scale 1 to 4 and hud_scale 75 / 100 / 150: glyphs crisp (nearest filter), brackets on whole pixels, text not
  blurred, no drift after the pose restore (other HUD layers drawn after ours keep their size).
- Sodium on, and a shader pack on (Iris/Oculus): the custom GUI render types must still draw (GUI pass, not world).
- `/spark profiler` with the full HUD on (status line + stopped badge + info strip + module list with ~30 rows).
- Effects Off + Motion Off look: flat, instant, still readable.
- The module list: toggle modules from the menu and watch rows slide in, fade out and the gap close; take a control
  lease (any job) and check the acting glyph and the sync bar on that row.
- The atlas glyphs (running, acting, waiting, failed, done, dot) look like their shapes at GUI scale 2.
