# Utility Suite roadmap

Status as of 2026-11-22 morning (latest volley at the end of this file). Phase 2 directive ("Capability Expansion, Performance & UI Polish") plus the user's
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
- [x] More reflexes (2026-11-21 parallel run, see the end of this file): shield, dodge, threat escape (back off a
      fuse), elytra swap, offhand. Still open: self-defence (Combat Assistant), auto-eat, ladder/vine clutch.
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

## Parallel feature run (2026-11-21, ideas list `docs/research/ideas-2026-11-21.md`)

All eight packages are merged on main. Compiled and unit-tested only (849 tests, 0 failures); **nothing in this section
has run in a game client yet**. Details, caveats and the playtest list: `docs/state/run-2026-11-21-parallel.md` and
`integration/<package>.notes.md`.

### Done (A: upgrades)

- [x] A1 Danger model and reflex scheduler (`core/autopilot/DangerModel`, `ReflexScheduler`), fed by fall clutch (FALL,
      VOID), terrain (LAVA), threat tracker (PROJECTILE, EXPLOSION, MELEE), vitals (LOW_HEALTH, DROWNING, FIRE) and
      Lava Plug. Per-reflex settings for shield, dodge, threat escape, elytra swap, offhand.
- [x] A2 Early staging (`FallRiskEstimator`, `FallStaging`): clutch item pulled into the hotbar and preselected, no
      camera movement.
- [x] A3 Reflex chain: one shared EMERGENCY lease (`ReflexChain.chainLease`) for fall clutch, shield, dodge, threat
      escape and elytra swap. No contract change was needed.
- [x] A4 Dodge and threat escape (`DodgePlanner`, keys only, never turns the camera).
- [x] A5 Shield reflex (`ShieldDecision`, module `combat_reflexes`).
- [x] A6 Clutch ladder (`ClutchLadder`): block clutch, elytra deploy, water steer, glide guard. Pearl is ranked only.
- [x] A7 Humanization in the core (`Humanizer`, `PrimitiveRandom`): Fitts turn time (only ever slower, 450 deg/s cap
      kept), log-normal reactions, aim spread, per-session skill. Settings on the Tools tab core list.
- [x] A8 Tactic utility and opponent model (`TacticUtility`, `OpponentModel`), behind "Read the fight".
- [x] A9 Strip-route fluid cost, torch cadence and wall-glance planning (`StripRoutePlanner`).
- [x] A10 Black box (dump on death or a big hit), "Autopilot: <reflex>" HUD line, camera momentum release.

### Done (B: menu polish)

- [x] B1 effective scale 2 at GUI scale 3-4 (`ui/kit/MenuScale`), B3 CLEAN caption and "Stop all", B4 reflex chips,
      B5 autopilot page, B6 wrapped empty states, B7 outlined presets, B8 Water Walk switch and "Go back", B9 Tools
      help collapse. (B2 was already done in 68f275a.)

### Done (C: new modules)

- [x] Movement: Toggle Sprint / Toggle Sneak (`move_toggles`), Elytra Swap (`elytra_swap`, also a reflex), Mount Jump
      Charge (`mount_jump`), Fly (`fly`) and Velocity Scale (`velocity_scale`), both Relaxed-only and gated to
      single-player / allow-list.
- [x] Render: Freelook, radar tracers and richer nametags, Light Levels, Blast Radius, Spawn Geometry (slime chunks,
      spawn spheres), Render Filters.
- [x] Player: Auto-Fish, Offhand Manager (the combat totem keeper stands aside while it is on), Auto-Respawn,
      Auto-Reconnect, Quick Pearl key.
- [x] World work: Crop Work, Vein Work (tree feller / vein miner), Auto-Torch, Lava Plug.
- [x] Combat: Trigger Assist, Target HUD and visual range alert.
- [x] Misc: Tab-GUI (off by default), Dot-Commands.
- [x] Integration: lang fragments (36 keys) and 7 new mixins merged; humanization settings on the Tools tab;
      `LegitPresets` knobs for `combat_reflexes` (shield_turn, shield_front_cone) and the four field modules (relaxed).

### Open (found during the run)

- [ ] **In-game verification of everything** (first thing next session): `./gradlew runClient` for the mixin audit
      (7 new mixins: totem pop, velocity, 5 render filters), the menu at GUI scale 3 and 4, then a playtest pass.
- [ ] Spark profiling pass for the new overlays and scans (mandate step 6 beyond the allocation tests).
- [ ] Pearl clutch in game (ledge search, smoothed turn, throw); only the ranking exists.
- [ ] Dodge and fuse back-off while the Combat Assistant is engaged (it owns movement in a fight today).
- [ ] Late better bid cannot preempt a reflex that is already acting.
- [x] Black box keeps recording vitals while the autopilot is off (`black_box_always`, default on; rows marked
      `"off":true`; never acts). Unit-tested only. Still open: post `SuiteEvent.PlayerDied` from the core.
- [x] Tab-GUI arrow keys are swallowed by `mixin/ui/KeyboardHandlerTabGuiMixin` (HEAD of `KeyboardHandler.keyPress`).
      Target checked against the 21.1.265 jars; **not yet loaded in a client** (run the mixin audit).
- [x] `TimelineScreen` at the effective menu scale (unit-tested; look at GUI scale 3 and 4).
- [ ] Sub-grouping for long category lists.
- [ ] Strip job: place torches at `isTorchStep` cells and glance at `wallGlances` points. Auto-Torch wall placement.
- [ ] Vein Work walking to out-of-reach blocks; cocoa replanting.
- [ ] Opponent model shield-use rate (axe shield break); `TacticsPlanner` reading the broad tactic; shared swing
      intent between the shield reflex and `SwingGate`.
- [ ] Fitts target width from the real hitbox (needs a width on `RotationTarget`).
- [ ] Toggle Sprint release on the real sprint key (needs a core/input hook); staging should skip water in the Nether.
- [ ] Quick Pearl: restore the moved stack when its lease is preempted mid-throw.
- [ ] Auto-Reconnect resume: an `IGoalEngine` accessor for the active job's original `GoalRequest`.
- [ ] Blast overlay for beds / respawn anchors, wither skulls, fireballs; freelook orbit in third person; targeted
      light-radius requeue for the light overlay.
- [ ] Autopilot bar chips and legit-preset notes for the remaining new modules (auto_fish / auto_reconnect server
      rules note).
- [x] Goal jobs back off a lit creeper: `combat_reflexes` setting `escape_during_jobs` (default on) arms the threat
      escape while a goal job runs, on its own EMERGENCY lease; the job pauses and resumes. Unit-tested only.
- [x] Camera momentum pitch check honours invert-mouse (`MomentumRelease.start(.., invertMouse)`, read through
      `InputBackend.invertMouseY`). Unit-tested; feel on takeover still to check in game.
- [ ] Dodge strafe speed tuning in game (0.12 blocks/tick is a guess).
- [ ] Takeover voice lines (see Deferred): clips, sound event, off-by-default setting.
- [ ] Rerun the Goal playtest with hands off the game window (the 16:48 rerun paused on a real mouse press).
- [ ] Second volley, in game: mixin audit for the Tab-GUI key mixin (20 mixins now); creeper back-off during a strip
      job (pause, resume, does it walk back into the blast spot); black-box rows while the autopilot is off;
      Timeline at GUI scale 3 and 4; World/Travel "Go back" rows and the Freecam switch.
- [x] Dropped the empty `TabGui.onKey` listener and its registration in `UiBootstrap` (third volley).
- [x] Reflex lease split: `combat_reflexes.move` for movement, `combat_reflexes` for interact/turn, so a shield
      lease and an escape or dodge lease are held together. Unit-tested against the real arbiter; check in game.
- [ ] Still untested in game: dodge, black-box dump, danger ranking under several hazards, humanized turn feel.
- [x] Goal jobs hold their resume after an emergency preemption until the explosion hazard clears
      (`jobs/goal/ResumeHoldGate`: 10 settle ticks, 100-tick cap). Unit-tested only.
- [ ] Resume hold follow-ups: hazard position on `IDangerModel` so the hold is distance-aware (the overload exists);
      a planner no-go zone around the blast spot; a `JobExecutor`-level test of the arming.
- [ ] Resolve setting lang keys in `SettingsRenderer` (four keys exist for `black_box_always` and
      `escape_during_jobs`; fall back to `spec.label()`), then add keys for the other settings.
- [x] Allocation tests for `JobEscapeGate`, `TabGuiNav` and `MomentumRelease`; 8 more `DodgePlanner` edge tests.
- [ ] Third volley, in game: work through `docs/state/playtest-checklist.md` (shield during an escape, the
      "Waiting for hazard to clear" hold, Tab-GUI keys on the mixin path only).

### Deferred

- Takeover voice lines (C, Misc): needs the local Zonos TTS server on the GPU to pre-generate clips, and the clips
  cannot be reviewed by ear in an unattended run. Next: generate clips, add a sound event and an off-by-default
  setting keyed on `IAutopilot.noteActed`.
- A8 hunter-bot reuse and LFM/Raon-driven opponents: only the engine-free `TacticUtility` / `OpponentModel` were in
  scope; the hunter bot is a separate feature.

## Pro-feel volley (2026-11-22, run note `docs/state/run-2026-11-22-pro-feel.md`)

Pro-level combat spacing, route pursuit and parkour intent assist. All nine tasks are merged on main; 1094 unit
tests pass. **Nothing here has run in a game client yet.**

### Done (unit-tested only)

- [x] `HitTradeSolver`: forward rollout of key combinations with cooldowns, reach, hurt immunity and knockback.
- [x] Opponent cooldown read (`OpponentModel.noteSwing/attackReadyIn`), snapshot fields from the client, `SwingGate`
      hurt-window hold.
- [x] Combat wiring: `TradeDriver` drives spacing; route pursuit and route flee through `IRouteRunner`. Settings
      `hit_trade`, `hit_trade_horizon`, `cooldown_read`, `route_pursuit`.
- [x] Plan-only Baritone routes (`IRoutePlanner`), registered only when Baritone is loaded.
- [x] `RouteFollower` with corridor corner cutting, sprint hops and simulated gap / shortcut jumps.
- [x] `MinecraftRouteRunner` (pursue / flee, planner beyond 12 blocks, local plan otherwise).
- [x] `ManualLookAssist` turn gain in the core (scales only the user's own mouse motion, capped at 450 deg/s) with
      `MouseHandlerTurnGainMixin` (21 mixins).
- [x] `IntentEstimator` and parkour intent assist: target-aware jumps, ideal takeoff yaw, precision air steer,
      stick the landing, takeoff timing, `AuthorshipBudget`, opt-in jump buffer, Legit knob for turn gain.

### Open

- [ ] In game: work through the checklist in the run note (7 sections). First: mixin audit with 21 mixins, then
      "a still mouse moves nothing" for turn gain, then "no movement while a Baritone plan is calculating".
- [ ] Core input guard (or lease) while a Baritone plan is CALCULATING, to close the same-tick executor race.
- [ ] Freecam / freelook state in `core.api` so `movement_assist` clears its turn-gain request while the camera is
      detached.
- [ ] Test for step-in-after-swing with the real `OpponentModel` behind `TradeDriver`; an end-to-end test of
      `CombatAssistant` -> `RouteRunnerCore` -> `RouteFollower` on fakes.
- [ ] Route follower: joint 2-3 jump search; hazard cells (lava, fire, magma) as non-standable; slabs, stairs,
      ladders and water in `MovementPhysics`.
- [ ] Route runner: reuse the `RotationTarget` instead of allocating one per 4-degree yaw change.
- [ ] Lang keys for the 8 new movement settings and 4 new combat settings.
- [ ] Tune after the playtest: solver dwell and margins, pursuit thresholds (6 / 5 blocks, 10 ticks without line of
      sight), `HURT_HOLD`, intent weights, turn-gain strength.

## Next-layer volley (2026-11-22, run note `docs/state/run-2026-11-22-next-layer.md`)

Combos, opponent reads, arena awareness, pearl chases, two overlays and takeoff shaping. All nine tasks are merged
on main; 1257 unit tests pass. **Nothing here has run in a game client yet.**

### Done (unit-tested only)

- [x] `ComboKeeper`: plans the sprint reset and follow-up keys on the predicted knockback arc (`combo_keeper`).
- [x] `StrafePhaseModel` aim lead and strafing with the opponent (`anticipatory_aim`, `strafe_with`).
- [x] `ArenaField` + `ArenaVoxels`: budgeted column scan, knock vectoring, high ground and low ceiling finds
      (`arena_read`).
- [x] `ArenaTactics`, `TacticUtility` HIGH_GROUND / CEILING_DENY, `TacticsPlanner.KNOCK_VECTOR`.
- [x] `FeintPlanner` (players only) and `SkillEstimator` rank dial (`feints`, `rank_dial` off by default).
- [x] `PearlLandingSim`, `PearlWatch`, `PearlIntercept` route mode (`pearl_intercept`).
- [x] `ProFeelLayer` facade and the call sites in `CombatAssistant`; `the_read` ring registered in `CombatBootstrap`.
- [x] `the_read` ring and `ghost_line` overlays.
- [x] `TakeoffShaper` in Movement Assist (`takeoff_shaping`).

### Open

- [ ] In game: work through the checklist in the run note (sections 0-10). First: startup, then one feature at a
      time, then everything on with a Spark profile.
- [ ] Core "fill-only sprint start" call so takeoff shaping stops calling `LocalPlayer.setSprinting(true)` from
      `travel/movement/MovementAssist` (a player state change outside `core.input`).
- [ ] Knock kinds for damaging and slow floors in the contract (they report as LAVA / COBWEB today).
- [ ] Arena read: partial blocks (slabs, fences) as their real shapes.
- [ ] `StrafePhaseModel`: per-side hold medians for uneven strafers; a deadband for quantised server yaw in the
      `FeintPlanner` pre-aim read.
- [ ] `PearlWatch` has no unit test (client entity sync); ignore a zero first-tick pearl velocity if the playtest
      shows it.
- [ ] Assistant-level behaviour test of the positional drive's steering (allocation and precedence are covered).
- [ ] Lang keys for the 7 new combat settings, `takeoff_shaping`, and the `the_read` / `ghost_line` settings.
- [ ] Tune after the playtest: combo deviation tolerance under ping, feint cooldowns and bait window, rank-dial
      step, knock values, high-ground reach cap, pearl start distance, shaping cost against the authorship budget.

## Advanced-client volley (2026-11-22, run note `docs/state/run-2026-11-22-advanced-client.md`)

Ten tasks merged on main; 1542 unit tests pass. Nothing was run in a game client.

### Done (unit-tested only)

- [x] Parkour control model: `JumpCommit`, `LandingHold`, `OverrideClassifier`, `FlightController` air brake,
      `SupportRegion`; three modes (`control_mode`: CORRECTIONS, CO_PILOT default, TAKEOVER); the HUD status line.
- [x] Shape physics: exact boxes, sneak edge back-off, step-up, real `LevelShapes`; shaped `ParkourCourse` cases.
- [x] Core shared control (`ISharedControl`): input overlay, manual facts, yaw co-steer, shared grants in the arbiter.
- [x] STEM UI: `HoloKit` / `HudHost` / `Visuals`, the Appearance module, HUD elements ported, kit widgets restyled,
      menu chrome, boot sequence, autopilot strip, reticle layer, authority bar, threat and target marks.
- [x] Combat: `HitPlanner` (sprint knockback, crit, sweep, plain; "Hit choice"), `GearRead`, duel mode.
- [x] Inventory: eat from the whole pack with swap-back; same-class hotbar refill.
- [x] Devtest: 17 control-model lanes in `ParkourScenario` (not run yet).

### Open

- [ ] In game: work through the run note's checklist (startup, menu, HUD, parkour modes, combat, inventory).
- [ ] Run and tune the 17 parkour devtest lanes; read the replica parity note.
- [ ] Move Movement Assist onto `ISharedControl` (keys through the grant; `startSprint()` replaces
      `LocalPlayer.setSprinting(true)`, which closes the open item of the next-layer volley).
- [ ] Owner decision D1: co-steer (yaw) in Co-pilot / Takeover. Core support exists; nothing requests it.
- [ ] Technique templates (corner, neo, headhitter, 45-degree), takeoff-tick search, momentum chains (CHAIN is a
      stub), intent v2 with the target frozen at takeoff, the hold-to-take-over key.
- [ ] Replica: slime, honey, soul sand, climbables, water landings, cobweb and powder snow.
- [ ] Combat, left from the upgrade pass: shield duel, healing in windows, mob-pack fights, combo escape, ranged
      opener, weapon choice by enchantments, fight log; sprint-hit opener when the target is already in reach.
- [ ] UI: menu close animation, section headings inside cards, tabs still passing literal colours, the suite font,
      `ToggleSwitch.warn` on less-legit toggles, coach pips as a cooldown arc, notices and world ribbons, the status
      line cross-fade, flight arc and stop pip (`CommitView`), greying Corrections-only settings in other modes.
- [ ] Remove the no-op `target_hud` GUI layer and the unused `TargetLine`.
- [ ] Switch settings and HUD text to the lang keys now in `en_us.json`.
- [ ] Eat-from-pack: swap the food back when the lease is preempted between the pull and the swap-back.

## Approach-control volley (2026-11-22, run note `docs/state/run-2026-11-22-open-items.md`)

Eleven tasks merged on main, none left on a branch; 1797 unit tests pass. Nothing was run in a game client.

### Done (unit-tested only)

- [x] Movement Assist on `ISharedControl` (no direct `Input` or `setSprinting` writes); core sprint withhold with
      one new mixin (22 total).
- [x] Approach control: takeoff-tick search, held-back press, eased W, walking run-up; target frozen at takeoff.
- [x] Owner decision D1 built: yaw co-steer in Co-pilot / Takeover on Balanced and above; hold-to-take-over key.
- [x] Technique templates (corner, neo, headhitter, 45-degree) as `TechniqueLibrary`, wired into the brain.
- [x] Intent v2: real tops, simulated reach, gaze over time, course ring, per-mode threshold (wired).
- [x] Replica parity: measurement fixed, full path on vanilla float arithmetic, `VanillaParityTest`.
- [x] Combat stages wired: threat fights with a mob playbook, combo escape, shield duel, heal windows.
- [x] Eat-from-pack swap-back after a preempted lease.
- [x] UI: notices (toasts), autopilot HUD, coach cooldown arc, world ribbons (`HoloWorldKit`), suite font pipeline
      (off until the font files are added), Timeline and minimap restyle, lang keys resolved for settings, style scan.

### Open

- [ ] In game: the run note's checklist (42 steps). First the startup and mixin audit, then the Parkour lanes.
- [ ] Owner: approve and add the three font files (Rajdhani Medium and SemiBold, JetBrains Mono Regular, SIL OFL
      1.1 to verify) with licence texts and `THIRD-PARTY-NOTICES.md`.
- [ ] Owner: confirm Co-pilot easing W on the ground while ARMED (design table 4 says it withholds nothing there).
- [ ] Owner: Movement Assist standing down while a job holds MOVEMENT with "Assist automation" on.
- [ ] Parkour: exact in-flight co-steer yaw (design 6.1 step 3), CHAIN phase, straight-line corner onto a fence
      post, corner lane geometry, techniques in the estimator's reach roll, `intent.targetTopY()` in the jump search.
- [ ] Replica: climbables and water landings, slime, honey, soul sand, cobweb, powder snow.
- [x] Combat: the shield reflex stands down while the assistant is engaged and the shield duel's "Block with shield"
      runs USE (`ShieldOwnership`, `CombatAssistant.stageOwns`), and never turns the camera in a fight.
- [x] Combat: in such a fight the reflex keeps sensing and reports to the stage (`ShieldThreatFeed`,
      `ShieldOwnership.reportToStage`); the stage, still the one writer of USE, raises the shield for arrows and
      blasts (held until they land) and for a second attacker in front (between our swings), with the reflex's
      cone and raise lead, never into an axe in reach, never turning (`ShieldDuelStage.guardStep`).
- [ ] Combat, in game: the fed guard against a skeleton and a creeper during a duel, and in a two-mob fight (does
      blocking between swings cost too much movement; the second attacker's timing is a standing estimate, and
      only the nearest facing attacker is seen, so one hidden behind the target is not blocked).
- [ ] Combat: shield disable without the sound;
      `FightContext` requests (`takenWeightScale`, `holdGround`, `pullConsumable`, no-kite, hop veto in an escape);
      offhand totem/shield policy, splash healing, ranged opener, weapon choice by enchantments, fight log.
- [ ] UI: place the `GoalConsole` nodes in `JobsTab`; hero frame for the takeover status line; remove the duplicate
      autopilot action-bar message; move the remaining chat / action-bar call sites to notices; `show_swing` for
      the coach arc; `world_lines` setting and a see-through world render type; style-scan backlog.

## Selected next features (user list 2026-11-24, synced to 10fd736)

Rules as always: zero steady-state allocation in tick/render paths, input only through core.input leases,
heavy planning off-thread, HUD discipline. Combat items wait until combat testing resumes (owner paused it
2026-11-24), so they would land unverified in live fights.

Tier 1 (project fit, immediately useful)
- [ ] J4 Baritone-free local pathfinder (bounded voxel A*, <= 64 blocks) behind INavigator when `baritoe` is
      absent, so waypoint Go / Take Me Back work without Baritone; live job ETA (EMA of completed steps) on the HUD line.
- [ ] T1 Cross-dimension Nether-hub routing: overworld leg -> nearest Portals waypoint -> Nether transit at 8:1 ->
      paired exit portal -> final leg; multi-leg state survives the dimension change and re-acquires the TRAVEL lease.
- [ ] J1 Shulker-aware StorageMemory (DataComponents.CONTAINER in chests and inventory) and a SHULKER_RESTOCK
      job step: place a carried shulker, take what the plan needs, break it with the right tool, pick it up.
- [ ] W8 Two-eye stronghold triangulator from thrown EyeOfEnder velocities, snapped to the vanilla ring bands,
      posted as a waypoint.
- [ ] W10 Trial Chamber / vault assistant: normal vs ominous spawners and vaults, spawner cooldowns, looted vs
      unlooted vaults (Breeze alert hook into DodgePlanner waits for combat testing).
- [ ] T2 Elytra: hands-free clear-sky takeoff (>= 6 clear above), mid-flight swap to a spare elytra before the
      durability reserve, optional landing to mend with XP bottles.
- [ ] U3 Local Rules visual builder (trigger / threshold / action cycles) and one-click safety templates:
      stranger within 48 m while mining -> Stop-All + alert; armour < 12% -> retreat; totem pop -> QuickPearl escape.

Tier 2 (bigger, own-world, seed- or combat-dependent)
- [ ] C1 Ranged ballistics lead and charge timing (bow, crossbow volleys, trident). Combat: deferred.
- [ ] C2 Combat potions, splash-heal foot throws, post-pop pressure. Combat: deferred.
- [ ] C3 Mace + wind charge + elytra dive planner (no attribute/breach swap exploits). Combat: deferred.
- [ ] C4 Wind-charge / fireball deflection and a pearl landing countdown. Combat: deferred.
- [ ] C5 Parkour + combat movement fusion (gap jumps mid-fight via MovementPhysics). Combat: deferred.
- [ ] J2 Villager bulk trading and librarian enchantment roller (opt-in, protected-area guards).
- [ ] J3 Schematic auto-supply runs between layers and temporary scaffolding cleanup.
- [ ] W1 Client-side terrain and biome oracles for OreSim on remote servers (full value needs a known seed).
- [ ] W2 Seed-powered structure and slime-cluster locator (spiral search, minimap markers, Add waypoint / Go).
- [ ] W6 Enchanting-table xpSeed cracker and cheapest dummy-enchant sequence (single player).
