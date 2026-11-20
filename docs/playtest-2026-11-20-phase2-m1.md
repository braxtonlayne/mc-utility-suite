# Utility Suite playtest report

- Generated: 2026-11-20T17:49:59.695812700
- Outcome: all scenarios ran
- World: `utilitysuite-playtest`, seed 8675309, survival, cheats on
- Launch to world ready: 18.1 s

## Summary

| Scenario | Result | Checks | Time |
|---|---|---|---|
| 1-boot Boot | **PASS** | 2 pass / 0 fail / 0 n/t | 0.0 s (0 ticks) |
| 1-world Boot: world profile + test world setup | **PASS** | 2 pass / 0 fail / 0 n/t | 1.5 s (30 ticks) |
| 2-menu-hud Menu & HUD | **PASS** | 15 pass / 0 fail / 0 n/t | 5.2 s (106 ticks) |
| 3-arbiter Arbiter, Stop-All, Resume, manual override | **PASS** | 18 pass / 0 fail / 0 n/t | 9.0 s (182 ticks) |
| 4-goal Goal engine + Baritone | **FAIL** | 9 pass / 1 fail / 0 n/t | 48.8 s (977 ticks) |
| 5-survival Survival autopilot eats, goal job resumes | **PASS** | 4 pass / 0 fail / 0 n/t | 7.3 s (147 ticks) |
| 6-schematic Schematic: Build Assist + gather-only | **PASS** | 6 pass / 0 fail / 0 n/t | 24.5 s (490 ticks) |
| 7-world World: OreSim, overlays, freecam, waypoints, breadcrumbs | **PASS** | 8 pass / 0 fail / 0 n/t | 26.8 s (537 ticks) |
| 8-travel Travel: SafeWalk, bridge, water walk | **PASS** | 4 pass / 0 fail / 0 n/t | 16.3 s (327 ticks) |
| 9-combat Combat: engage/disengage, fall damage, allies, coach | **PASS** | 7 pass / 0 fail / 0 n/t | 12.4 s (250 ticks) |
| 10-tools Tools: refill, shulker preview, PacketLab | **PASS** | 4 pass / 0 fail / 0 n/t | 9.2 s (185 ticks) |
| 11-perf Performance: baseline vs stress (spark, saved to file) | **PASS** | 2 pass / 0 fail / 0 n/t | 91.6 s (1834 ticks) |
| 1b-config-saved Config files written on leave | **PASS** | 4 pass / 0 fail / 0 n/t | 0.0 s (0 ticks) |

## Performance

- baseline (all modules off): 30.1s, 602 ticks, tick mean 2.324 ms / p95 2.706 / max 9.491; 3497 frames (116.2 fps), frame mean 8.60 ms / p95 10.96 / p99 11.68
-   module onTick us/tick (baseline (all modules off)): -
-   allocation by thread: Render thread 26.60 MB/s, Server thread 16.15 MB/s, spark-java-sampler-1-4 0.33 MB/s, spark-java-sampler-1-0 0.28 MB/s, spark-java-sampler-1-1 0.26 MB/s, spark-java-sampler-1-3 0.25 MB/s
- stress (overlays+radar+OreSim+hazards+coach+goal job): 30.1s, 602 ticks, tick mean 3.367 ms / p95 4.849 / max 20.991; 3163 frames (105.1 fps), frame mean 9.51 ms / p95 12.85 / p99 18.38
-   module onTick us/tick (stress (overlays+radar+OreSim+hazards+coach+goal job)): terrain_assist 17.9, seed_oresim 17.4, world_knowledge 16.2, goal_engine 16.0, waypoints 9.8, fight_coach 4.1, smart_inventory 2.4, suite_hud 1.8, world_radar 1.1, world_overlays 0.3
-   allocation by thread: UtilitySuite-World 117.20 MB/s, Render thread 53.07 MB/s, Server thread 17.79 MB/s, pool-12-thread-3 1.15 MB/s, pool-12-thread-4 0.72 MB/s, spark-java-sampler-2-2 0.33 MB/s
- spark profile saved: C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\config\spark\profile-2026-11-20_17.49.03.sparkprofile
- spark profile saved: C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\config\spark\profile-2026-11-20_17.49.45.sparkprofile
- client tick mean 2.324 -> 3.367 ms (+1.043), frame mean 8.60 -> 9.51 ms (+0.91), render-thread alloc 26.60 -> 53.07 MB/s

## Details

### 1-boot Boot — PASS

- PASS — every Services entry registered: arbiter=ActionArbiter input=InputSmoother clicker=InputSmoother modState=ModState modules=ModuleRegistry events=SuiteEventBus goals=GoalEngine navigator= worldKnowledge=WorldKnowledgeService storage=StorageMemory waypoints=WaypointStore
- PASS — module list: 21 modules: storage_memory(on) goal_engine(on) survival_autopilot(off) schematic_toolkit(on) world_knowledge(on) world_overlays(off) seed_oresim(off) world_radar(off) scout_camera(off) waypoints(on) terrain_assist(off) travel_autopilot(on) bridge_assist(on) combat_assistant(off) fight_coach(off) ally_roster(on) smart_inventory(on) packet_lab(off) latency_simulator(off) local_rules(on) suite_hud(on)
- note: profile file C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\config\utilitysuite\profiles\sp_utilitysuite-playtest.json deleted before the run

### 1-world Boot: world profile + test world setup — PASS

- PASS — profile id is sp_utilitysuite-playtest: profile=sp_utilitysuite-playtest
- PASS — automation not stopped at start: stopped=false
- note: spawn (25.50, 64.00, 75.50) in minecraft:overworld

### 2-menu-hud Menu & HUD — PASS

- PASS — Right Shift opens the menu: screen=SuiteMenuScreen; mapping=key.keyboard.right.shift down=true modifier=NONE ctxActive=true
- PASS — tab Jobs builds and renders: tab=JOBS, 88 nodes (20 toggles)
- PASS — tab World builds and renders: tab=WORLD, 245 nodes (31 toggles)
- PASS — World toggle '#1 ' lands in the profile: world_knowledge.enabled: - -> false
- PASS — World toggle pressed back restores the value: world_knowledge.enabled: - -> true
- PASS — tab Travel builds and renders: tab=TRAVEL, 90 nodes (11 toggles)
- PASS — Travel toggle '#1 No movement slowdown while eating, drawing a bow or blocking. Single-player or your own server only; other servers will correct your position.' lands in the profile: terrain_assist.no_slowdown: - -> true
- PASS — Travel toggle pressed back restores the value: terrain_assist.no_slowdown: - -> false
- PASS — tab Combat builds and renders: tab=COMBAT, 30 nodes (6 toggles)
- PASS — Combat toggle '#1 Middle-click a player you are looking at to add or remove them as an ally.' lands in the profile: ally_roster.middle_click_toggle: - -> false
- PASS — Combat toggle pressed back restores the value: ally_roster.middle_click_toggle: - -> true
- PASS — tab Tools builds and renders: tab=TOOLS, 127 nodes (18 toggles)
- PASS — closing returns to the game: screen=none
- PASS — HUD draws nothing when idle: text='', draw calls: 0 fills, texts []
- PASS — HUD draws nothing in Clean-Screen: draw calls: 0 fills, texts []
- note: screenshot C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\playtest\menu-jobs.png
- note: screenshot C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\playtest\menu-world.png
- note: screenshot C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\playtest\menu-travel.png
- note: screenshot C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\playtest\menu-combat.png
- note: screenshot C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\playtest\menu-tools.png

### 3-arbiter Arbiter, Stop-All, Resume, manual override — PASS

- PASS — arena built: centre minecraft:stone, pool minecraft:water at 25,150,123; player (25.50, 151.00, 123.50)
- PASS — lease granted: holder=playtest_mover
- PASS — setKeyHeld accepted: forward=true jump=true
- PASS — vanilla keyUp/keyJump are down: keyUp=true keyJump=true
- PASS — player moved while FORWARD held: moved 3.29 blocks to (25.50, 151.50, 119.75)
- PASS — Backspace (Stop-All key) stops automation: stopped=true
- PASS — every KeyMapping released: keyUp=false keyJump=false any=false
- PASS — lease revoked with STOP_ALL: active=false reason=STOP_ALL
- PASS — no channel holders: anyHolder=false
- PASS — new lease refused while stopped: granted=false
- PASS — player stops moving after Stop-All: drifted 0.01
- PASS — Resume: lease granted again: granted=true
- PASS — Resume: moves again: moved 2.33
- PASS — physical W press ends the lease (MANUAL_OVERRIDE): active=false reason=MANUAL_OVERRIDE
- PASS — override pause active: pause=true
- PASS — non-emergency lease refused during the pause: granted=false
- PASS — simulated FORWARD released after override: heldByAutomation=false
- PASS — override pause expires: pause=false

### 4-goal Goal engine + Baritone — FAIL

- PASS — parse 'gather 1 diamond': Gather[itemId=minecraft:diamond, count=1, cost=FASTEST, dataSource=OBSERVED_ONLY]
- PASS — preview shows the bootstrap chain (logs -> table -> pickaxes -> iron): 14 steps: [Wood — Wood (3 minecraft:oak_log from minecraft:oak_log/minecraft:spruce_log/minecraft:birch_log/minecraft:jungle_log/minecraft:acacia_log/minecraft:dark_oak_log/minecraft:mangrove_log/minecraft:cherry_log/minecraft:crimson_stem/minecraft:warped_stem), Planks — Planks (8 minecraft:oak_planks, 3x minecraft:oak_planks), Crafting Table — Crafting Table (1 minecraft:crafting_table, 1x minecraft:crafting_table), Wooden Pick — Sticks (4 minecraft:stick, 2x minecraft:stick), Wooden Pick — Wooden Pick (1 minecraft:wooden_pickaxe, 1x minecraft:wooden_pickaxe), Cobblestone — Cobblestone (11 minecraft:cobblestone from minecraft:stone/minecraft:cobblestone, tier 1), Stone Pick + Furnace — Furnace (1 minecraft:furnace, 1x minecraft:furnace), Stone Pick + Furnace — Stone Pick (1 minecraft:stone_pickaxe, 1x minecraft:stone_pickaxe), Iron Ore + Fuel — Iron Ore (3 minecraft:raw_iron from minecraft:iron_ore/minecraft:deepslate_iron_ore, tier 2), Iron Ore + Fuel — Coal (1 minecraft:coal from minecraft:coal_ore/minecraft:deepslate_coal_ore, tier 1), Iron Ingots — Iron Ingots (3 minecraft:iron_ingot, 3x minecraft:iron_ingot_from_smelting_raw_iron), Iron Pick — Iron Pick (1 minecraft:iron_pickaxe, 1x minecraft:iron_pickaxe)] ... (+2); warnings [Data source: OBSERVED_ONLY, cost profile: FASTEST, Fastest: no optional prep, best stored pickaxe, ore candidates at any depth]
- PASS — parse 'craft 1 crafting_table': Craft 1 minecraft:crafting_table
- PASS — crafting table crafted via recipe book: tables 0 -> 1; job: job gone; planks=0 logs=1
- PASS — parse 'gather 4 coal': Gather 4 minecraft:coal
- PASS — Baritone available: navigator available=true
- PASS — HUD shows one status line while the job runs: '[Goal] Gathering 4 Coals — Planning'
- PASS — Baritone pathing moves the player: after 21 ticks, at (26.61, 64.00, 72.74)
- FAIL — started from an empty inventory and bootstrapped its own tools (log + pickaxe steps): steps: wood > planks > crafting table > sticks > wooden pick > planks > wooden pick > mine 4 coal > return/deposit
- PASS — gather 4 coal: inventory delta >= 4 and job completes: coal delta 4; job: job gone
- note: t=0 PLANNING step='' progress=0/0 reason='Planning from current inventory and known storage' replans=0 at (25.50, 64.00, 74.50)
- note: t=1 RUNNING step='Wood' progress=0/3 reason='Plan ready: 7 steps' replans=0 at (25.50, 64.00, 74.50)
- note: screenshot goal-gather-hud.png
- note: t=93 RUNNING step='Wood' progress=1/3 reason='Plan ready: 7 steps' replans=0 at (26.70, 64.00, 72.43)
- note: t=164 RUNNING step='Wood' progress=2/3 reason='Plan ready: 7 steps' replans=0 at (27.25, 64.00, 72.47)
- note: t=231 RUNNING step='Planks' progress=0/1 reason='Plan ready: 7 steps' replans=0 at (27.25, 64.00, 72.47)
- note: t=254 RUNNING step='Crafting Table' progress=0/1 reason='Plan ready: 7 steps' replans=0 at (27.25, 64.00, 72.47)
- note: t=266 RUNNING step='Sticks' progress=0/1 reason='Plan ready: 7 steps' replans=0 at (27.25, 64.00, 72.47)
- note: t=279 RUNNING step='Wooden Pick' progress=0/1 reason='Plan ready: 7 steps' replans=0 at (27.25, 64.00, 72.47)
- note: t=320 REPLANNING step='Planks' progress=0/1 reason='Crafting 'Wooden Pick' failed: Missing ingredients for minecraft:wooden_pickaxe; re-resolving minecraft:wooden_pickaxe' replans=1 at (27.52, 64.00, 71.54)
- note: t=321 RUNNING step='Planks' progress=0/1 reason='Crafting 'Wooden Pick' failed: Missing ingredients for minecraft:wooden_pickaxe; re-resolving minecraft:wooden_pickaxe' replans=1 at (27.52, 64.00, 71.54)
- note: t=332 RUNNING step='Wooden Pick' progress=0/1 reason='Crafting 'Wooden Pick' failed: Missing ingredients for minecraft:wooden_pickaxe; re-resolving minecraft:wooden_pickaxe' replans=1 at (27.52, 64.00, 71.54)
- note: t=359 RUNNING step='Mine 4 Coal' progress=2/4 reason='Crafting 'Wooden Pick' failed: Missing ingredients for minecraft:wooden_pickaxe; re-resolving minecraft:wooden_pickaxe' replans=1 at (27.52, 64.00, 71.54)
- note: t=360 RUNNING step='Mine 4 Coal' progress=0/4 reason='Crafting 'Wooden Pick' failed: Missing ingredients for minecraft:wooden_pickaxe; re-resolving minecraft:wooden_pickaxe' replans=1 at (27.52, 64.00, 71.54)
- note: t=524 RUNNING step='Mine 4 Coal' progress=1/4 reason='Crafting 'Wooden Pick' failed: Missing ingredients for minecraft:wooden_pickaxe; re-resolving minecraft:wooden_pickaxe' replans=1 at (19.30, 60.00, 74.57)
- note: t=596 RUNNING step='Mine 4 Coal' progress=2/4 reason='Crafting 'Wooden Pick' failed: Missing ingredients for minecraft:wooden_pickaxe; re-resolving minecraft:wooden_pickaxe' replans=1 at (19.09, 60.00, 74.55)
- note: t=668 RUNNING step='Mine 4 Coal' progress=3/4 reason='Crafting 'Wooden Pick' failed: Missing ingredients for minecraft:wooden_pickaxe; re-resolving minecraft:wooden_pickaxe' replans=1 at (19.09, 60.00, 74.55)
- note: t=723 RUNNING step='Return/Deposit' progress=0/1 reason='Crafting 'Wooden Pick' failed: Missing ingredients for minecraft:wooden_pickaxe; re-resolving minecraft:wooden_pickaxe' replans=1 at (19.45, 59.54, 73.30)
- note: t=797 job gone at (23.17, 64.00, 74.49)
- note: step sequence: Wood > Planks > Crafting Table > Sticks > Wooden Pick > Planks > Wooden Pick > Mine 4 Coal > Return/Deposit
- note: ends with: pickaxes 1 wooden / 0 stone, cobblestone 0

### 5-survival Survival autopilot eats, goal job resumes — PASS

- PASS — parse 'gather 64 dirt': Gather 64 minecraft:dirt
- PASS — autopilot ate (food rose): food 12 -> 17, bread left 7
- PASS — eating took the SURVIVAL lease (preempting GOAL): sawEatLease=true jobPausedSeen=true
- PASS — goal job resumes after eating: RUNNING step='Gather 64 Dirt' progress=3/64 reason='Resumed from checkpoint at step 1' replans=0
- note: food 12; job PAUSED step='Gather 64 Dirt' progress=2/64 reason='Paused: preempted by survival_autopilot; will resume from step 1' replans=0

### 6-schematic Schematic: Build Assist + gather-only — PASS

- PASS — parse build command: Build pt_hut
- PASS — Build Assist placed every block: 10/10 blocks match in the world; card BUILDING 9/10 missing={} notes=[]
- PASS — card says why when blocks are hidden from the player's spot: needed 1 move(s); hidden-blocks note seen=true
- PASS — build job completes: job gone
- PASS — parse gather-only build: Gather materials for pt_bricks
- PASS — gather-only submits a GatherMany for the missing bricks: gatherSubmitted=true; jobs: Gather 1 item types (schematic pt_bricks)[QUEUED] ; card READY missing={minecraft:bricks=2}
- note: wrote .\schematics\pt_hut.nbt and pt_bricks.nbt
- note: t=0 card: no card; job PLANNING step='' progress=0/0 reason='Planning from current inventory and known storage' replans=0
- note: t=2 card: LOADING 0/0 missing={} notes=[]; job RUNNING step='Build pt_hut' progress=0/1 reason='Building pt_hut' replans=0
- note: t=3 card: SCANNING 0/0 missing={} notes=[]; job RUNNING step='Build pt_hut' progress=0/1 reason='Building pt_hut' replans=0
- note: t=5 card: BUILDING 0/10 missing={} notes=[]; job RUNNING step='Build pt_hut' progress=0/1 reason='Building pt_hut' replans=0
- note: screenshot schematic-building.png
- note: t=101 card: BUILDING 0/10 missing={} notes=[Some blocks are hidden behind others from here: move around the build]; job RUNNING step='Build pt_hut' progress=0/1 reason='Building pt_hut' replans=0
- note: t=106 card: BUILDING 7/10 missing={} notes=[Some blocks are hidden behind others from here: move around the build]; job RUNNING step='Build pt_hut' progress=0/1 reason='Building pt_hut' replans=0
- note: t=162 card: READY 7/10 missing={} notes=[Some blocks are hidden behind others from here: move around the build]; job RUNNING step='Build pt_hut' progress=0/1 reason='Building pt_hut' replans=0
- note: t=207 card: READY 9/10 missing={} notes=[Some blocks are hidden behind others from here: move around the build]; job RUNNING step='Build pt_hut' progress=0/1 reason='Building pt_hut' replans=0
- note: diag t=280: missing cells (relative to anchor 17,151,115): 1,0,2 wants minecraft:cobblestone has minecraft:air; 
- note: diag t=280: assist plan=com.utilitysuite.jobs.schematic.plan.BuildPlan@74f8a334 lease=null pausedByPreemption=false leaseRetry=0 cooldown=0 waitTicks=0 windowCount=0 idleTicks=159 firstOpen=0 searchPos=0 targetStep=-1 targetX=18 targetY=151 targetZ=117 targetFace=-1 aimTicks=0 placements=9; stopped=false MOVEMENT=- CAMERA=- HOTBAR=- WORLD_INTERACT=- screen=-; player (20.50, 151.00, 116.50) reach 4.5; main hand 3 minecraft:oak_planks
- note: t=300: 9/10 placed; moving the player to spot 1 ((20.50, 151.00, 116.50))
- note: t=301 card: BUILDING 9/10 missing={} notes=[]; job RUNNING step='Build pt_hut' progress=0/1 reason='Building pt_hut' replans=0
- note: screenshot schematic-done.png

### 7-world World: OreSim, overlays, freecam, waypoints, breadcrumbs — PASS

- PASS — OreSim predicts diamond/iron in loaded chunks (precision and recall >= 0.5): radius 48 around (24,74): predicted 4439 (diamond 821), real 3254 (diamond 489); precision 0.718 (diamond 424/821), recall 0.980 (diamond 424/489)
- PASS — X-Ray index holds ores and matches the world: 28343 highlighted blocks; sampled 200, 200 match 
- PASS — cave/fluid near view populated: fluid pockets 1120
- PASS — freecam activates: active=true camera=Marker['Marker'/1444, l='ClientLevel', x=24.50, y=65.62, z=74.50]
- PASS — camera flies, player stays: camera moved 24.69, player moved 0.00; stopped=false MOVEMENT=- CAMERA=- HOTBAR=- WORLD_INTERACT=- screen=-
- PASS — freecam off: camera back on the player: active=false, camera-eye distance 0.00
- PASS — waypoint add + 8:1 nether conversion: overworld 800,-1600 -> nether 100,70,-200
- PASS — breadcrumbs recorded while walking: trail 16 -> 21 points, now at (25.50, 151.00, 130.06); stopped=false MOVEMENT=- CAMERA=- HOTBAR=- WORLD_INTERACT=- screen=-
- note: OreSim predictions after 9 s: 2497 (stable)
- note: OreSim breakdown: deepslate_diamond_ore real 487 found 422; deepslate_iron_ore real 1552 found 1552; diamond_ore real 2 found 2; iron_ore real 1213 found 1213; deepslate_diamond_ore predicted 799 hit 422; deepslate_iron_ore predicted 1806 hit 1472; diamond_ore predicted 22 hit 2; iron_ore predicted 1812 hit 1293; chunks in radius with snapshot predictions: 40; real in covered chunks 3254, found 3189
- note: screenshot world-overlays.png
- note: screenshot world-freecam.png

### 8-travel Travel: SafeWalk, bridge, water walk — PASS

- PASS — SafeWalk stops at the cliff edge with FORWARD held: player at z=cz+13.28 (edge block ends at +13.0), y=151.00, keyUp=true
- PASS — bridge starts: running=true
- PASS — bridge places blocks over the gap and stops at the budget (5): deck blocks east of the edge: 5, cobblestone used 5, running=false, player (42.27, 151.00, 123.48); stopped=false MOVEMENT=- CAMERA=- HOTBAR=- WORLD_INTERACT=- screen=-
- PASS — Water Walk (solid) keeps the player on the water surface: y=151.000 (water top at 150+1), inWater=false, onGround=true, block below minecraft:water
- note: screenshot travel-bridge.png
- note: screenshot travel-waterwalk.png

### 9-combat Combat: engage/disengage, fall damage, allies, coach — PASS

- PASS — combat assistant armed and DORMANT: enabled=true state=DORMANT
- PASS — first hit -> ENGAGED with a lease: state=ENGAGED movement holder=combat_assistant target=1530
- PASS — zombie dies -> DORMANT, lease released: state=DORMANT holder=none swings=2 lastDisengage=TARGET_LOST engagements=1
- PASS — Fight Coach HUD renders during the fight: drew=true
- PASS — fall damage keeps the assistant DORMANT: health 20.0 -> 17.0, state=DORMANT, note 'Engaged Zombie #1530 (offensive: confirmed hit)'
- PASS — ally entry added for a fake UUID: added=true entries=1
- PASS — ally roster persisted to disk: C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\config\utilitysuite\combat\allies\sp_utilitysuite-playtest.json (182 bytes)
- note: hit zombie #1530 at distance 2.65
- note: screenshot combat-engaged.png

### 10-tools Tools: refill, shulker preview, PacketLab — PASS

- PASS — hotbar refill from main inventory: hotbar.0 = 64 minecraft:cobblestone, cobblestone total 66
- PASS — pinned slot 2 keeps its own item (dirt): hotbar.1 = 64 minecraft:dirt
- PASS — shulker preview tooltip component builds: component ClientComponent 164x22
- PASS — PacketLab ring buffer fills with packet types: written 3088 (capacity 8192), last 512: 511 in / 1 out, 10 types e.g. [block_update (S→C), custom_payload (S→C), move_entity_pos (S→C), move_entity_pos_rot (S→C), move_player_pos (C→S), rotate_head (S→C), section_blocks_update (S→C), set_entity_data (S→C)]; sizes: 0 unknown, 4543 bytes known

### 11-perf Performance: baseline vs stress (spark, saved to file) — PASS

- PASS — all module onTick under stress < 500 us/tick: 87.1 us/tick total: terrain_assist 17.9, seed_oresim 17.4, world_knowledge 16.2, goal_engine 16.0, waypoints 9.8, fight_coach 4.1, smart_inventory 2.4, suite_hud 1.8, world_radar 1.1, world_overlays 0.3
- PASS — spark profiles saved to local files (no upload): [C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\config\spark\profile-2026-11-20_17.49.03.sparkprofile, C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\config\spark\profile-2026-11-20_17.49.45.sparkprofile]
- note: baseline window starts at epoch ms 1795214911389
- note: spark baseline profile: C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\config\spark\profile-2026-11-20_17.49.03.sparkprofile (336 KiB)
- note: stress window starts at epoch ms 1795214954634
- note: goal job during stress: RUNNING step='Gather 64 Dirt' progress=0/64 reason='No progress for 15 s on 'Gather 64 Dirt'' replans=2
- note: spark stress: Dev has made the advancement [Isn't It Iron Pick]
- note: spark stress: Gave 1 [Iron Pickaxe] to Dev
- note: spark stress profile: C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\.\config\spark\profile-2026-11-20_17.49.45.sparkprofile (427 KiB)
- note: baseline (all modules off): 30.1s, 602 ticks, tick mean 2.324 ms / p95 2.706 / max 9.491; 3497 frames (116.2 fps), frame mean 8.60 ms / p95 10.96 / p99 11.68
- note: stress (overlays+radar+OreSim+hazards+coach+goal job): 30.1s, 602 ticks, tick mean 3.367 ms / p95 4.849 / max 20.991; 3163 frames (105.1 fps), frame mean 9.51 ms / p95 12.85 / p99 18.38
- note: client tick delta under stress (includes Baritone pathing + vanilla chunk loading): client tick mean 2.324 -> 3.367 ms (+1.043), frame mean 8.60 -> 9.51 ms (+0.91), render-thread alloc 26.60 -> 53.07 MB/s

### 1b-config-saved Config files written on leave — PASS

- PASS — per-world profile written on leave: C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\config\utilitysuite\profiles\sp_utilitysuite-playtest.json (1401 bytes)
- PASS — global profile written: C:\Users\braxt\Desktop\Minecraft\mc-utility-suite\run\config\utilitysuite\global.json (96 bytes)
- PASS — profile JSON has module sections: {   "local_rules": {     "enabled": true   },   "world_knowledge": {     "enabled": true   },   "terrain_assist": {     "no_slowdown": false,     "enabled": true,     "hazards": true,     "safewalk": true,     "surface_mode": "SOLID_COLLISION"   },   "ally_roster": {     "middle_click_toggle": true,...
- PASS — profile unloaded after leaving: hasActiveProfile=false
