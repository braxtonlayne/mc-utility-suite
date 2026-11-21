# ui package notes (Menu polish and Tab-GUI)

## Done

- **B1 effective scale.** `ui/kit/MenuScale` (engine-free, `MenuScaleTest`): at window GUI scale >= 3 the
  `SuiteMenuScreen` lays out against window px / 2, scales the pose by `2 / guiScale`, converts click, release, drag,
  scroll and hover coordinates, and converts scissor rectangles through `UiRenderUtils.scissor` (rounded outward).
  Three columns fit at GUI scales 1-4 for 1760x990 and 1920x1080.
  - `SuiteMenuScreen.autopilotBarRect()` now returns **GUI px** (converted back), so `devtest/MenuScenario`, which
    clicks through `mouseClicked`, keeps working unchanged at every GUI scale.
- **B3** "CLEAN" caption on the Clean-Screen switch (always shown), Stop pill always reads "Stop all".
- **B4** `AutopilotBar` shows reflex chips (lit = `Services.autopilotOrNull().allows(r)` while on). The four original
  reflexes first; SHIELD, DODGE, THREAT_ESCAPE, ELYTRA_SWAP, OFFHAND only when the width allows. Logic in
  `ui/ReflexChips` (`ReflexChipsTest`).
- **B5** Autopilot page: sidebar selection cleared, the card's duplicate switch removed, "< Back" control added.
- **B6** Empty states use `paragraph` (wrap); card subtitles show whole or are hidden; Combat's "Off" chip removed
  (the "Armed" chip remains while on).
- **B7** World presets: all outlined (`PillButton.Style.OUTLINE`, new), only the applied one filled. "Applied" = last
  preset applied from the menu while its module switches still match.
- **B8** Water Walk is a switch (`QuickActions.switchRow`); "Take Me Back" button reads "Go back".
- **B9** Tools: Quick Deposit help is one line with More/Less, "Pinned slots" label (full text in its tooltip),
  paragraph text uses `TEXT_DIM`.
- **A10 (UI half)** `SuiteHud` reads `IAutopilot.activeReflex()` each tick (no allocation) and shows a constant
  "Autopilot: <reflex>" line (`ReflexChips.STATUS_LINE`, indexed by ordinal) ahead of the job line; Clean-Screen and the
  one-line rule apply (`AutopilotStatusLineTest`).
- **Tab-GUI** `ui/TabGui` (IModule + IConfigurable, TOOLS, id `tab_gui`, off by default), registered in `UiBootstrap`
  together with its GUI layer and an `InputEvent.Key` listener. Navigation state in `ui/TabGuiNav` (`TabGuiNavTest`).
- **Item 7** The menu's column 2 and search list modules generically from `Services.modules().byCategory(...)`, so new
  IModule + IConfigurable instances from other packages appear without ui changes (the list scrolls). No sub-grouping
  was added.

## Integrator

- Merge `ui.lang.json` (`module.utilitysuite.tab_gui`).
- No mixins, no bootstrap lines outside `ui/`.

## Not done / caveats

- **Tab-GUI key consumption (resolved 2026-11-21 by `mixin/ui/KeyboardHandlerTabGuiMixin`, not yet loaded in a client; original note follows).** NeoForge 21.1's `InputEvent.Key` is not cancellable and fires after vanilla has
  processed the key, so consumed arrow keys still reach other `InputEvent.Key` listeners and any key mapping bound to an
  arrow key (vanilla binds none by default). Truly swallowing them needs a small client mixin on
  `KeyboardHandler.keyPress` (HEAD, cancellable) that asks `TabGui` whether it consumed the key; that mixin belongs to the
  `mixin/` package and was not added.
- `TimelineScreen` keeps its own layout (not scaled); it fits at GUI scale 4 but stays small.
- The new reflex chips' "lit" state for SHIELD..OFFHAND follows `Autopilot.allows`, which is still a stub (true while
  on) until the autopilot package adds per-reflex settings. **Resolved 2026-11-21:** per-reflex settings are merged, so
  the chips now follow them.
- Not verified in a running client (runClient is off limits for this run): check the menu at GUI scale 3 and 4
  (text-field caret, tooltips near the edges, scissor edges) in the next playtest.
