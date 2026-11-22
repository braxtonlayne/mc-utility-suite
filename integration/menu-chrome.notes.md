# menu-chrome (STEM re-skin of the suite menu, 2026-11-22)

Re-skin plus motion of `SuiteMenuScreen` and `AutopilotBar` per docs/design/stem-ui-2026-11-22.md 8.1, 8.2, 8.4.
Layout rects are unchanged: every coordinate is derived from the existing `wx/wy/ww/wh`, `sidebarW`, `listX/listW`,
`viewX/viewY/viewW/viewH`, `TOP_BAR_H`, `AutopilotBar.H`. No node counts, click paths, the autopilot bar rectangle,
open/close behaviour or `UiBootstrap`'s reopen debounce changed. Nothing was visually verified in game (no game
window was opened): see the owner checks at the end.

## What changed

- **Backdrop**: `render()` calls `FrameClock.sample()`, flushes, then `Screen#renderBlurredBackground(partialTick)`,
  then `renderBackground` draws `VOID` at 55 % (fading in with the frame). Without the player's blur the window gets
  an extra `VOID` base (62 %) under the glass so HUD text cannot ghost through.
- **Window**: one `HoloFrames.frame(HERO, chamfer)` on `GLASS` + `HoloFrames.scanlines`; columns, top bar and the
  autopilot strip are separated by `LINE_FAINT` hairlines on the one glass surface (the `SIDEBAR`/`LIST_BG`/`WINDOW`
  fills are gone). A 24 px sweep at 5 % accent travels down on open and every 8 s (effects FULL + motion FULL).
- **Title bar**: 2 px accent bar, `UTILITY SUITE`, version and profile as bracketed readouts (hairline `[ ]`, no
  fill), a `FIELD` plate under the goal field. Stop all is untouched (still the kit's DANGER pill).
- **Sidebar**: quiet rows; the selected row is `GLASS_RAISED` with `LINE_STRONG` brackets; the 2 px accent bar is
  drawn by the screen and travels to the new row (`AnimStore.settle`, TAU_NORMAL). Counts are micro text (accent on
  the selected row). Footer status uses glyphs: accent dot (armed) or a DANGER cross (stopped).
- **Module list**: category chips replaced by a 2 px category-hue bar (`Tab.token()` / `CAT_*`; dimmed when the
  module is off). Right-edge state mark: accent dot with halo (on), `TEXT_FAINT` dot (off), `SYNC` diamond while the
  module holds a lease. Captions are accent micro caps with a hairline to the row edge. Legit level control: one
  `FIELD` inset with hairline separators; the lit segment is `GLASS_RAISED` with a travelling 2 px accent underline.
- **Tooltip**: framed `GLASS_RAISED` plate after 350 ms with a 90 ms fade. Placement is the pure function
  `SuiteMenuScreen.TooltipPlacement.place` (below the anchor, x just right of the pointer; above when clipped; then
  right/left of the anchor; always clamped on screen). Unit tested: never overlaps the anchor, always on screen.
- **Scrollbars**: 2 px `LINE` thumb on a `LINE_FAINT` rail, `LINE_STRONG` when hot. Hit zones unchanged.
- **Toast**: framed `GLASS_RAISED` with an accent side bar.
- **Motion**: open = frame unfolds from the centre over 110 ms, content fades 60-220 ms (the old 0.96 scale-in is
  gone, which also removes the scissor/pose mismatch during open). Category / module / autopilot-page switch = the
  content column fades in and slides 6 px over 140 ms. Search edits reflow list rows (`AnimStore.settle` keyed per
  module id; new rows enter 10 px low); any other rebuild snaps rows. Motion OFF: all instant. REDUCED: fades only
  (no unfold geometry, slide, reflow, sweep or boot). Input is never gated by an animation.
- **AutopilotBar = STEM strip**: OFF = quiet (muted label, faint hairline chips, faint left tick). ON = 2 px accent bar
  at the left, label and lit chips in accent, `[ON]` readout in a hairline frame, no solid green. A chip whose reflex
  acted in the last 10 s is `SYNC` with a diamond on its top edge. Turning it on plays a 300 ms unfold (bar grows from
  the centre, faint accent wash, chips revealed left to right by scissor). Label stays `AUTOPILOT`.
- **ReflexChips**: text logic unchanged; added `indexOf`, `noteActive`, `actedRecently` (`ACT_WINDOW_MS` = 10 s).
- **BootSequence** (`ui/holo/BootSequence`, engine free, unit tested): first open per session, motion FULL only,
  900 ms: centre line 0-120, frame unfold + bracket snap + 2-frame flicker 120-300, columns revealed by scissor 40 ms
  apart with a bright caret 300-520, `SYNC n % · N MODULES · LINK OK` counting up 520-760 then fading to faint. Module
  count is the real registry count, computed once at open; the percent strings and widths are a static table. Any
  click, scroll or key skips it (except the key-repeat of the open key); input is never blocked.
- **No per-frame allocation added**: text widths (title, version, profile, clean label, Enter, badges, captions,
  autopilot label/state/settings/key hint, toast, legit labels and status line) are cached at build/refresh or on
  first draw; the legit-level labels are a static upper-case table (the old code upper-cased and ellipsized per frame).

## APIs verified in source (NeoForge 21.1.265 sources jar)

- `Screen#renderBlurredBackground(float)` (protected): NeoForge-patched to `RenderSystem.disableDepthTest()`, then
  `gameRenderer.processBlurEffect(partialTick)`, then re-binds the main target. `GameRenderer#processBlurEffect` reads
  `options.getMenuBackgroundBlurriness()` and does nothing below 1, so the accessibility setting is respected.
  `Options#getMenuBackgroundBlurriness()` returns an int (used once in `init` to pick the no-blur base).
- `GuiGraphics#fillGradient(x0, y0, x1, y1, from, to)`, `GuiGraphics#flush()`, scissor stack intersection via
  `UiRenderUtils.clip` (MenuScale-aware).
- `IActionArbiter#holder(ControlChannel)` / `ControlLease#moduleId()` for the lease diamond.

## Deviations and not done

- **Lease diamond** is sampled with `arbiter.holder(channel)` for the 4 channels at the menu's refresh cadence (every
  10 ticks), not from an event: no lease event exists on the arbiter contract. Not per frame.
- **"Acted in the last 10 s"** is sampled from `IAutopilot.activeReflex()` in `AutopilotBar.refresh` (every 10 ticks
  while the menu is open). A reflex that acts while the menu is closed, or for under half a second, is not seen. A
  HUD-side feed (SuiteHud already tracks `lastReflex`) could stamp `ACTED_AT` if the owner wants it complete.
- **Close animation (110 ms)** not done: `onClose` must hand input back on the first frame, and once the screen is
  replaced nothing in this task's files draws; it needs a HUD-layer ghost outside this task's scope.
- **Content cross-fade** is a fade-in of the new page (the old page is already rebuilt away); no out-fade.
- **Section headings in content cards** (accent micro caps + hairline to the card edge) are drawn by `Panel` / kit
  nodes, which this task may not edit. Column-2 captions got that treatment.
- **Goal field / Stop all / toggles / pills** are kit widgets restyled by the parallel kit task; only a `FIELD` plate
  under the goal field was added here.
- **`smallText` 0.75 pose scaling kept**: replacing it needs the suite font (design 4.2).
- **Pulse trace** on the STEM strip (8.4) not done.
- Lang strings: code uses literals like the rest of the menu; keys for the new boot readout are in
  `integration/menu-chrome.lang.json` for when the menu moves to translation keys.

## In-game checks for the owner

1. Menu blur on (Options > Accessibility > Menu Background Blur > 0) and off (0): the world behind is blurred with a
   dark tint; with blur off the window still hides HUD/chat text behind it.
2. GUI scale 1, 2, 3, 4 and Auto, and a small window below the three-column width (two-column layout): frame,
   chamfer corners, hairlines, sidebar bar, list rows and scrollbars line up; scissor reveals line up at scale 3-4.
3. Every tab (Jobs, World, Travel, Combat, Tools), a module page and the autopilot page: text readable on the glass,
   category bars visible, state marks clear (turn a module on/off by right click; start a job to see the SYNC diamond).
4. Open/close feel: first open of the session plays the 900 ms boot (click during it: skips at once, the click lands);
   later opens unfold in 220 ms; tab switches slide 6 px; typing in search reflows the list.
5. Autopilot strip: off vs on; turning it on unfolds over 300 ms; after a reflex acts (e.g. a fall clutch with the
   menu open) its chip shows SYNC for 10 s.
6. Tooltip: hover a control near each screen edge; it never covers the control and stays on screen.
7. Appearance settings: effects Off (no glow, scanlines, sweep), motion Off (everything instant, no boot), motion
   Reduced (fades only, no boot).
