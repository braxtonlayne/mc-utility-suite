# kit-widgets notes

## What changed per widget
- UiTheme: every colour is now an alias of a HoloToken, filled by `UiTheme.refresh()` (called from each new `Panel`). Fields are plain
  `static int` (not compile-time constants) because the accent is user-chosen; no constant is used as a switch label or annotation, so none
  had to stay a literal. Mapping extras: TEXT_DIM = mix(TEXT_SECONDARY, TEXT_MUTED, .45); ACCENT_HOVER = mix(ACCENT, TEXT, .35);
  CONTROL_HOVER = GLASS_RAISED + 10% accent; TRACK_OFF = TEXT_FAINT. TOGGLE_MS is now 140. `Panel.WHITE/GRAY/DIM/...` became non-final
  and are refreshed with the theme. Widgets read the tokens directly, so only code holding the old ints can go stale.
- UiRenderUtils: signatures kept. roundedRect / roundedOutline / panel / dot draw square fills and 1 px edges (radius and corner mask ignored),
  shadow() draws nothing, new `bracketFrame` helpers delegate to HoloFrames.
- UiNode: stable animation key (`animId`, `ensureAnimId`, hover and press helpers on AnimStore.SHARED). New `WidgetKeys` (setting, module,
  named, positional). Panel gives unkeyed nodes a kind+position key; SettingsRenderer / SettingWidgetMapper.widgetKey give settings a module id
  + setting key hash, so state survives `Panel.rebuild()`.
- ToggleSwitch: layout stays 28x14, 22x10 bracketed rail centred; OFF = LINE edge + dim bar left, ON = ACCENT_DEEP fill + bright bar right
  (`warn(true)` makes it WARN; nothing calls it yet). The static RECENT map is gone: AnimStore progress, 140 ms, eased out.
- PillButton: rectangular framed; primary = accent outline/text/faint fill; danger solid only for DANGER style; success is now outline style;
  `pill()` is ignored. CycleSelector: one frame, hairline separators, sliding GLASS_RAISED block + 2 px accent underline (settle).
  ModernSlider: 2 px rail, 2 px fill, 2x10 thumb bar with mini brackets when focused/dragged, tick at the default (`Model.defaultPosition()`,
  default method; SpecModel implements it). StyledTextField: FIELD inset, bottom edge LINE to LINE_STRONG on focus (settled).
  ChipNode: hairline frame + square marker. CardNode: FRAMED frame (HERO tone adds bright brackets, DANGER tone danger brackets/edge), hover +6%.
  GoalHeroCard: HERO frame with chamfer when a job is active (FRAMED when idle), 2 px progress bar. CollapsibleSection, ListRowNode,
  LabelNode, BoxNode: quiet level, tokens.
- TabGui: two framed plates (GLASS_HUD), selected row = bracketed row with 2 px accent side bar, ON = small accent bar at the row end, module
  column unfolds over 120 ms. Navigation and TabGuiNav untouched; no draw calls while off; no allocation in render.

## Not done / caveats
- Callers (tabs/, SuiteMenuScreen) still pass old literal ARGB values to BoxNode and a few widgets; they render as given until restyled.
- The HERO glow of GoalHeroCard extends 3 px outside the box and may be clipped by the content scissor.
- Not looked at in game (no runClient allowed).

## Look at in game
- Every widget at GUI scale 1 to 4; hover and toggle motion; press flash; focus brackets on keyboard focus.
- Contrast of TEXT_MUTED / TEXT_DIM on glass (labels, captions, hints).
- Effects Off (no hero glow) and Motion Off (everything instant; toggle jumps).
- The slider default tick for settings with a range; rebuilding after a toggle should not restart the slide.
