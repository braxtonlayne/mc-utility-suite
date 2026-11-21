# Utility Suite roadmap

Status as of 2026-11-20 evening. Phase 2 directive ("Capability Expansion, Performance & UI Polish") plus the user's
additions. Items gated "SP/allow-list" run only in single-player or on servers listed in the allow list (Tools tab,
shared with the Latency Simulator), same as today.

## Phase 2: Milestone 1 (done 2026-11-20, merged on main)

- [x] Every Baritone run (waypoint Go, Take Me Back, schematic walk-to-site, autopilot overland) holds a lease; Stop-All
      cancels every Baritone process.
- [x] OreSim: per-cell height cache (worker allocation ~680 → ~115 MB/s); large ore veins (deepslate iron recall
      ~30% → ~100% in playtest; overall recall 0.97, precision 0.68).
- [x] Cost profiles produce distinct plans; typed goals use the World tab routing source unless `source=`; `travel to
      <waypoint>` converts 8:1; cross-dimension travel fails with a clear message.
- [x] UI: title/goal-bar gap, two-line waypoint rows, opaque window (chat bled through = the "ghost text"), menu
      auto-close fix + open/close logging, direct controls for Water Walk, Freecam, Step, NoSlowdown, Take Me Back,
      Combat arm/retreat, overlays, Add waypoint here.
- [x] Goal engine: replan budget actually caps repairs; waits for recipe unlocks; any wood type for planks; wider
      crafting-table placement search. `gather 4 coal` from an empty inventory is now the playtest.

## Phase 2: Milestone 1 leftovers

- [ ] Schematic playtest regression after the merge: Build Assist stuck at 9/10 ("hidden" cell, empty main hand).
- [x] `gather 4 coal` from nothing: full chain (wood → planks → table → sticks → pick → coal) passes in playtest.
- [ ] World tab Quick actions: "Start freecam" button text is truncated ("Start f…").
- [ ] Portal routing for cross-dimension waypoints (travel goals + autopilot).
- [ ] SAFEST: pass a hazard-averse hint to the miner (MiningDriver has no hook yet).
- [ ] Duplicate Water Walk / Step / NoSlowdown controls (Quick actions + Terrain card) can show stale values until
      the tab refreshes.
- [ ] Schematic walk-to-site is refused while a TRAVEL run is active and isn't retried.
- [ ] Craft fallback when a recipe is locked: place ingredients in the grid by clicks instead of the recipe book.

## Phase 2: Milestone 2 (combat, network tools)

- [x] Combat engagement mode: REACTIVE / PROACTIVE with target priority CLOSEST, LOWEST_HEALTH, THREAT (SP/allow-list). Done 2026-11-21, see "Combat tactics and the hunter direction".
- [ ] Silent (decoupled) aim through InputSmoother SILENT rotation for combat (SP/allow-list).
- [ ] Auto-mace swap when falling > 1.5 blocks; wind-charge fall clutch and optional mid-fight boost.
- [ ] Jump-reset on knockback; totems, golden apples and potions from the whole inventory; offhand totem replenish.
- [ ] Ranged assist: bow/crossbow charge timing and velocity-compensated aim using Fight Coach's ballistics.
- [ ] FastUse / FastPlace (configurable right-click cooldown) and direct container access within reach (SP/allow-list).
- Not building: Blink (outgoing packet hold), Backtrack (inbound entity delay), Packet NoFall.

## Phase 2: Milestone 3 (movement, world, schematics, planner)

- [ ] Travel tab: Step 1.0–2.0 slider, NoSlowdown modes (eating, bow, cobweb, soul sand), Omni-Sprint, FastLadder,
      Anti-Cobweb (SP/allow-list).
- [ ] Scaffold mode for Bridge Assist: forward, diagonal and stair-step at walk/sprint speed, silent placement,
      automatic block switching, pitch and delay settings.
- [ ] Water Walk modes SOLID / BOBBING / PACKET, separate water and lava toggles, configurable rubber-band guard.
- [ ] Auto-Mend with XP bottles during elytra/Baritone travel when durability < 15%.
- [ ] Enhanced boat traversal; portal routing (shared with Milestone 1 leftovers).
- [ ] Overlay readability: single-layer focus, opacity and line-width sliders, dimming of background layers.
- [ ] Schematic printer: enforce block states (facing, half, axis, waterlogged); optional break-and-replace of wrong
      blocks; Baritone walks around the build to reach hidden or out-of-range cells; pull staged materials from
      project chests.
- [ ] Planner sources: crops/farming, mob drops, villager trading, stonecutting, smithing.
- [ ] Smart Inventory container looter (gaussian-timed take-all for chests/shulkers).
- Not building: seed deduction from observed terrain; stash/base scanner that logs storage-heavy chunks.

## User additions (2026-11-20)

### Full progression planner

- [ ] `brew <potion> [qty]` (e.g. `brew fire_resistance 3`, `brew strength II`): brewing stand (find/craft), blaze
      powder fuel, glass bottles, fill at nearest water, nether wart + base ingredient + modifier
      (redstone/glowstone/gunpowder), load, wait, collect.
- [ ] `enchant <item>` with an XP-aware planner: route to a registered enchanting table, bring lapis, check levels;
      if short, queue an XP sub-goal (quartz/coal mining or mob hunting) until level 30.
- [ ] Multi-furnace array: when StorageMemory knows several furnaces at a base, split ore and fuel evenly across them,
      do a nearby task meanwhile, then come back and collect.

### Opportunistic detours ("while I'm passing by")

- [ ] While Baritone walks toward objective A, take a bounded detour (configurable radius, 8–12 blocks) for: items on
      the current or queued shopping list (exposed coal/iron, sugar cane, a needed tree), exposed high-value ores
      (diamond, ancient debris, emerald), dropped items matching needed materials. Update surplus accounting, resume.
- [ ] Opportunistic chest check: optionally peek into unindexed or stale chests/barrels passed on the way (villages,
      dungeons) to update the storage index.

### Visual, dimension and HUD presets

- [ ] One-click overlay presets: Cave Run (exposed ores + fluid hazard warning + fullbright), Ancient Debris Run
      (Y=15 debris filter + lava pocket warning), Builder Vision (schematic ghost + fullbright + fog/weather off),
      Clean (all overlays off).
- [ ] Dimension auto-switch: swap the active preset on entering Overworld / Nether / End.
- [ ] Optional info HUD strip (hidden in Clean-Screen): coordinates with live Nether/Overworld conversion, biome, TPS,
      ping, armor/tool durability bars.
- [ ] Visual rule editor for Local Rules plus starter presets: "Inventory ≥ 90% full → deposit at base and resume",
      "Tool durability < 10 → pause and mend/swap", "Health < 6 hearts → retreat / pause job".
- [ ] Spotlight-style top bar: typing a word ("water", "mace") filters and highlights matching settings across all
      tabs; typing a goal verb (gather, craft, go) runs a goal.

## Autopilot and the classic-client feel (user direction 2026-11-21)

- [x] Autopilot (`core/autopilot/Autopilot`, contract `IAutopilot`): opt-in, off by default, its own bar pinned across
      the menu under the title bar (click = on/off with a green ON badge, "Settings" end = its page) and an unbound
      "Toggle Autopilot" key. Settings: how far it may go (DANGER_ONLY / ASSIST) and which reflexes it may use (fall
      clutch, parkour timing, parkour blocks). It runs those reflexes even while Movement Assist is off, draws nothing.
- [x] Classic enabled-module list on the HUD (HUD status line > "Enabled module list", off by default): top-right,
      longest name first, category colours, Autopilot on top while on.
- [ ] More reflexes for the autopilot as they land: threat escape (walk the escape path), self-defence (Combat
      Assistant), auto-eat / totem in danger, ladder/vine clutch.
- [ ] Classic look beyond the bar and the list (keep the three-column layout below): optional "Classic" theme with
      square panels, per-category accent headers and bracketed key hints; decide with the user how far to go.

## Next session, first: UI restyle to the three-column layout (user request 2026-11-20 18:00)

Reference screenshot: `docs/ui/reference/style-target.webp`. Copy its layout and look only (not its feature set).

- **Window:** large centered panel (~75% of the screen width), near-black (#141416-ish) with 1 px #2A2A2E hairline borders,
  square or 4 px corners, uppercase bold title top-left ("UTILITY SUITE") with a small version chip, and icon buttons
  top-right (settings, profile, close). Small, dense, crisp text; muted grey secondary text; white primary.
- **Column 1, category sidebar (~150 px):** icon + label rows (Jobs, World, Travel, Combat, Tools, plus HUD, Profiles,
  Keybinds, Rules), a count badge on the right showing how many modules in that category are on, active row
  highlighted with a lighter fill; a divider then secondary groups (Profiles >, Binds >, Overlays >).
- **Column 2, module list (~230 px):** search field at the top ("Search modules, settings…" + magnifier), a PRESET
  segmented control (e.g. Off / Light / Balanced / Full, or the visual presets) with "none applied" status, then
  small sub-group captions (dim uppercase) and one row per module: a tag chip on the left (e.g. "AUTO"/"ASSIST"/
  "VISUAL", or a risk chip), name, kebab menu at the right; the selected module row highlighted; enabled modules in
  white, disabled in grey.
- **Column 3, detail panel:** module name + enable switch at the top right, a one-paragraph description in grey,
  "SETTINGS" caption, then compact rows: label left; control right-aligned (small pill dropdown for enums, tiny
  switch for booleans, full-width thin slider with value text at the right for numbers, range sliders "40 ↔ 80" for
  min/max pairs, "0 selected >" pickers for lists). Collapsible sub-sections (e.g. TARGETS / TYPES).
- **Search:** filters modules and settings across all categories and highlights matches (merges with the
  Spotlight-style top bar idea above).
- **HUD Studio side panel** (later): a library of HUD elements (minimap, info strip, armor status, clock, active-modules
  list), each with a preview card, on/off and a favourite star; tabs Library / Layouts / Style; "Place overlays" enters
  the drag/resize HUD edit mode (built for the minimap tonight).
- Keep the existing generic SettingSpec → widget mapping; this is a new renderer for the same data. Keep the HUD pill.

## Direction: altoclef-style autonomy + full mod compatibility (user request 2026-11-20 18:26)

The user's target is the tool to work "basically like altoclef": you give it any goal, it plans and runs the whole
chain, and it works with modded content and JEI. The research and design notes are in
`docs/research/altoclef-and-mod-compat.md`.

- [ ] Survival task chains that interrupt a goal and resume it: food, mob defense, MLG bucket, lava/fire/drowning
      escape, death recovery. They sit on top of ActionArbiter priorities.
- [ ] Re-decide each tick with stable subtasks (altoclef's `isEqual` rule) instead of only planning up front and
      repairing the plan.
- [ ] Block tracker: remember block positions by type within a scan budget. It feeds detours and modded ores
      (`c:ores/*`).
- [ ] Planner recipe sources from the live RecipeManager for every type: add stonecutting, smithing, campfire, and
      generic modded types. Use tags for substitution and choose tools by `mineable/*` and `incorrect_for_*` tags.
- [ ] Learn drops: record what blocks and mobs drop (loot tables aren't on the client) so "kill/break X for Y" can
      be planned.
- [ ] Optional JEI integration (`compat.jei`, compileOnly, gated by ModList):
      - recipe lookup across all categories as a planner source or as manual steps;
      - recipe transfer for modded crafting stations;
      - a "plan this" keybind on the hovered item;
      - JEI bookmarks become a shopping list.
- [ ] EMI adapter behind the same `IRecipeIndex` contract.
- [ ] Compatibility:
      - Sodium/Embeddium + Iris overlay check;
      - Xaero/JourneyMap waypoint import/export;
      - modded storage indexing.
- [ ] Compatibility playtest: JEI plus one content mod; `gather <modded item>` plans and runs.
- [ ] Stretch: "beat the game" chain (nether → blaze rods/pearls → stronghold → dragon).
- Optional: natural-language goals turned into GoalParser commands by the local LFM3 1.7B tool-calling server.

## Legit tuning and proactive automation (user direction 2026-11-21)

- Automation repositions and retries on its own when something is out of reach or not visible (walk to a spot that
  works, try again, with a cap and a clear status) instead of skipping or giving up.
- Line of sight is respected by default (no placing or hitting through walls). Each system has a "Require line of
  sight" setting; turning it off is an explicit, less-legit choice.
- [x] Build Assist: aims at a visible point on any support face (it used to aim at the face centre, which its own
      placed blocks could cover), skips a cell only when no face is visible, and the reach walker then moves to a
      spot that sees it. Setting "Require line of sight" (on).
- [x] Global "legit level" preset (Legit / Balanced / Relaxed) at the top of column 2: one click tunes camera speed,
      Build Assist (line of sight, silent rotation, delays), Bridge Assist (rotation, delay, walk pace and disguise) and
      combat aim. Balanced = the defaults; "custom" when hand-tuned. Table in `core/LegitPresets`.
- [ ] Extend the legit level to new knobs as they appear (combat engagement mode, silent aim, reach margin).
- [x] Combat Assistant: "Require line of sight" for proactive target picks (Relaxed level turns it off).
- [x] Goal engine station placement is a strategy ladder, like a player (2026-11-21, after a table failed inside a
      tree canopy): 1) place against any visible clickable face in reach (floor, then sides, then above; leaves and
      log sides count; blocks that open a screen do not); 2) break a leaf block hemming you in, then look again (up to
      3); 3) walk to open ground, farther each try (up to 3). Line of sight always respected.
- [ ] Same ladder for every placement and interaction (Build Assist, bridging, chests): add "pillar up one block"
      and "dig a pocket" rungs, and log which rung worked so the planner learns what the terrain needs.
- [x] Mining style "Like a player" (default; "Known ores" = walk straight to known/predicted ore): the job strip-mines
      a 1x2 tunnel from under the player's feet, choosing the level and direction (`jobs/planner/StripRoutePlanner`)
      whose walls, floor and ceiling would uncover the most known ore, whole veins counted. It only mines ore the
      tunnel actually exposes, follows veins, then digs on in 4-block segments. Playtest: 4 coal, no replans.
- [ ] Caving and quarry route variants (walk an open cave that passes the most ore; a small quarry over a dense spot).
- [x] Baritone no longer snaps the head (2026-11-21): its rotations are captured right before the movement packet,
      undone, and replayed as a smoothed turn (cap 450 deg/s); the movement keys are re-aimed to its heading.
- [x] Playtest evidence: a screenshot on every failed check (run/playtest/shots/fail/) and a rolling trace of long
      steps every 5 s (run/playtest/shots/trace/<scenario>/, last 12 kept).

## Bridging: walk mode and packet options (user request 2026-11-21)

Bridge Assist has a Mode setting: LINE (the automated line bridge) or WALK (you walk or run with your own keys and
camera; blocks are placed under you). Walk-mode settings combine freely:

- Pace: LEGIT (one block as your feet reach the edge, human spacing, never lets you drop) or INSTANT (every block
  along the next ticks of travel, up to 3 a tick, at sprint speed).
- Server look: REAL (your camera), BRIDGER (the server and other players see you facing back down at the deck like a
  backward bridger while you walk forward), FACE (aimed at each block). Packet-only: your camera never moves.
- Server sneak: OFF, AT_EDGE (a short crouch at each block, like ninja bridging), ALWAYS. Local movement stays at full
  speed (`LocalPlayerServerPoseMixin` + `IInputSmoother.setServerPose`, owned by a lease).
- Hide sprint from server; Tower (jump in place to pillar up); look-ahead ticks.
- INSTANT pace and any server look/sneak/sprint option need single-player or an allow-listed server.

Next for bridging:
- [ ] Diagonal and stair-step (ascending/descending) walk bridging; the planner already handles diagonal corners.
- [ ] Silent hotbar swap (the server sees the block slot while the client keeps your item in hand).
- [ ] Tower while moving ("tower-move"), and a jump-bridge/god-bridge timing look.
- [ ] HUD hint while walk mode is armed (mode, pace, blocks left).

## Combat tactics and the hunter direction (user direction 2026-11-21)

Goal: combat that looks and plays like a skilled player, using terrain and knockback, built and polished in this UI
first. Later the same stack drives an in-game **manhunt hunter agent** that plays the game while it hunts. Keep every
decision engine-free (snapshot in, keys/aim out) so an autonomous driver can reuse it.

Layers: `TargetSelector` (who) -> `TacticsPlanner` (where to stand, when to hit) -> spacing/strafe/swing/sustain (how).

Done:
- [x] Engagement mode REACTIVE / PROACTIVE (single-player or allow-listed servers only). A budgeted scan every 5 ticks
      while dormant; filter HOSTILE / HOSTILE_AND_PLAYERS / PLAYERS; priority THREAT / CLOSEST / LOWEST_HEALTH; engage
      range; line of sight required by default (Relaxed legit level turns it off). Neutral mobs only once angry; roster
      allies never. Retargets on kill, escape or lost sight. Pauses after the user takes over (move, cancel, Stop-All).
- [x] Gap ambush (HOLD_GAP): a ravine between you and the target, so never chase into it. Walk to a safe stand-off at
      the lip, no strafing or crit hops there, wait. A target in reach while airborne over the drop gets hit at once,
      so the knockback drops it. Works when you ran and jumped the gap too.
- [x] Edge knock (EDGE_KNOCK): the target stands within 2 blocks of a deep drop or lava. Circle to the opposite side,
      then close in with a sprint hit toward the edge.
- [x] Food as a passive objective: no safe food in the inventory and none in remembered containers within 32 blocks,
      so it hunts nearby adult, unnamed, unleashed food animals when no hostile is around and you are not moving, then
      walks over the drops (never into a pit). It switches itself off once there is food.

Next tactics (backlog, roughly in order):
- [ ] Rod/projectile pull at the edge: fishing rod or snowball/egg to knock a target off a ledge from range.
- [ ] Block-in and pillar escape: pillar up to break a melee chase, or cobweb/block a pursuer in a 1-wide tunnel.
- [ ] Water-bucket clutch while knocked off (MLG) and lava-bucket area denial (single-player or allow-list only).
- [ ] Bow play: lead shots on a runner, charge timing, switch to melee at range < 6.
- [ ] Bridge-fight awareness: never stand on a 1-wide bridge in front of an attacker; knock bridgers off.
- [ ] Retreat-and-heal loop: break line of sight with terrain before eating, re-engage after.
- [ ] Pursuit for the hunter: follow a fleeing player with Baritone, predict jumps over gaps, avoid the ambush above.
- [ ] Evasion for the runner role: pick escape routes over gaps or water, then switch to HOLD_GAP on the far side.
- [ ] Food objective extras: cook the meat in a nearby furnace or smoker, fish when no animals, wheat into bread.

## Movement augmentation (user request 2026-11-21)

One engine-free movement brain shared by the player-assist, the overlay and every automated driver (combat, bridge,
the future hunter). "If me and some friends are doing parkour, I can't fail at it."

Done:
- [x] `travel/movement/MovementPhysics`: vanilla 1.21.1 player motion replica (ground/air acceleration, sprint-jump
      boost, friction, gravity and drag, Y-then-X-then-Z voxel collision). Verified: jump apex 1.2522, 12 ticks
      airborne, sprint 0.2806 blocks/tick, edge sprint jump clears 3 air blocks but not 4 (vanilla's flat limit).
- [x] `JumpSearch`: mid-air steering (best of the 9 key combos held to landing) and the edge decision (jump on the
      last tick before walking off, only when it lands better). Score: progress along the heading minus fall damage;
      lethal falls count as no landing, so with no jump possible it picks the least damaging landing. Allocation free.

Next:
- [x] Movement Assist module (Travel): applies MovementAssistBrain in NeoForge's MovementInputUpdateEvent, so it
      adds to the keys you press (a jump on the right tick, a strafe/forward key on an axis you are not holding) and
      never replaces one you hold. Edge jump, air steering (corrects only a miss, fall damage or a much lower landing;
      with no survivable landing it leaves your keys alone), optional sprint hop (head-hitters included). Off while
      sneaking, swimming, climbing, flying, after Stop-All, and while automation drives MOVEMENT unless "Assist
      automation too" is on. Per-tick voxel cache keeps the search cheap.
- [x] Overlay: predicted landing (green safe, yellow fall damage, red nothing survivable) and the blocks a sprint
      jump from here can land on (brighter: higher than you). Only while moving; Clean-Screen hides it.
- [ ] Yaw correction for air steering by legit level (keys only at Legit, small visible yaw at Balanced, silent yaw
      at Relaxed). (Fall survival: see the water bucket clutch below.)
- [x] Escape-path overlay (`EscapePlanner`): Dijkstra over walk and sprint-jump moves within 12 blocks, picks the
      route leaving the most room from nearby hostiles / players (jumps count extra against mobs). Orange steps,
      magenta jumps; only while a threat is near.
- [x] Movement benchmark (`ParkourCourse`, 19 segments, easiest first; sim test + in-game scenario 8b): 19/19.
- [x] SafeWalk yields to valid jumps; with SafeWalk on, a jump that costs health is invalid unless you jump.
- [x] Parkour blocks (`BlockAssistPlanner` + `ParkourBlocks`, default on): mid-jump, one block from the hotbar when it
      keeps the run going (a 2-high wall: a block to land on; a gap one too long: the far edge extended). Turn time
      at the no-snap cap is planned in; keys are re-expressed while the head turns; head returns to the run.
- [x] Look style (NATURAL / FREE on the legit level): automated looks stay along the way you move by default.
- [x] Water bucket clutch (`FallClutchPlanner` + `FallClutch`, default on): falling toward a lethal landing or one
      costing >= the set damage (default 6), pour from the hotbar on the latest tick that reaches the landing block (or
      a wall cell the hitbox passes, head cell included), scoop the water back up, turn back. Not in the Nether.
- [x] Block clutches: without a water bucket (or in the Nether) the same planner places powder snow, a cobweb,
      slime (not while sneaking) or a hay bale (a fifth of the damage) on the landing block or a full-block wall
      face. Powder snow is scooped back up. Playtested: hay and cobweb on a 14-block drop, no damage.
- [ ] Ladder / vine clutch: planned (feet cell on a wall beside the fall) but off in the executor. In the playtest the
      crosshair sat within 0.1 degrees of the planned wall face and the click ray still missed (grazing ray down a wall
      the player hugs). Also: ladders cannot keep up with a terminal-speed fall (feet cell out of reach a tick early).
- [ ] More block tricks: pillar-up (jump + place under), neo around a wall corner, break clutch blocks back (hay,
      slime, ladder) after landing.
- [ ] Multi-jump planner: chain JumpSearch over landing cells (A* over jump edges) for parkour routes and escapes;
      the combat runner/hunter roles reuse it.
- [ ] Physics extras: slabs/stairs step-up, ice/slime/soul sand, ladders and vines, water, Speed/Jump Boost from the
      player's effects, head-hitter jumps.
- [ ] Packet timing (single-player or allow-listed servers, Relaxed level only): hold movement packets for a tick or
      two so a correction lands in the same server tick.
