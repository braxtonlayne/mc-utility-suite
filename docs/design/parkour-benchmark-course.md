# Parkour benchmark course (design, 2026-11-23)

A long fixed course that measures how much Movement Assist helps. It is used two ways:

1. **Automated:** a playtest times a simulated runner through it in each control mode: OFF, CORRECTIONS (NUDGE),
   CO_PILOT (GUIDE), TAKEOVER (CARRY).
2. **Live:** the owner plays it, a few baseline runs with the assist off and then runs in each mode, while a timer
   records the total time and the split at every checkpoint.

Geometry, route and per-jump metadata are data: `src/devtest/resources/course/benchmark-course.txt`.
Nothing here has been built or run in game yet. The takeoff windows were computed with a small model of the vanilla
sprint jump (see "How the geometry was checked"), not with `MovementPhysics`.

## At a glance

- 89 jumps on the main line, 10 sections, 9 checkpoints, 2 optional shortcuts.
- Route length 522 blocks. Footprint 109 x 81 blocks (x -2..106, z -35..45), height 0..14 above the start pad. The
  farthest point is about 110 blocks from the start, so the whole course sits inside a 8-chunk view distance.
- Estimated time for a skilled player with no assist: about 2 to 2.5 minutes clean, 3 to 4 minutes with a few falls.
- Layout: ten lanes running north-south, joined at their ends by short eastward connectors. Section 1 runs south from
  the start; each later section starts at a checkpoint, goes east for two jumps, turns on a 3x3 andesite pad and runs
  the next lane the other way. Lanes are at least 11 blocks apart.

## Sections

| # | Section | Jumps | Difficulty (avg / max) | What it tests |
|---|---|---|---|---|
| 1 | Warm-up gaps | 7 (J01-J07) | 1.6 / 2 | Flat sprint jumps over 1 to 3 air on 3-wide pads, one up, one down, small sideways shifts |
| 2 | Strips and offsets | 9 (J08-J16) | 2.4 / 3 | One-wide strips, strip to strip with a sideways shift, a strip one up, a diagonal down, a single block |
| 3 | Narrow landings | 10 (J17-J26) | 2.7 / 4 | Fence row, lone fence post, lone wall post, lone glass pane, wall row; each followed by a jump off it |
| 4 | Corners and neo | 11 (J27-J37) | 2.6 / 5 | Pillar in the gap, corner onto a block, corner onto a fence post, corner onto a wall post, one-block neo |
| 5 | Ceilings and head-hitters | 7 (J38-J44) | 2.9 / 4 | Two jumps under 3 blocks of headroom, three hops in a 2-high tunnel, a jump up into a 2-high slot |
| 6 | Ladders and vines | 8 (J45-J52) | 1.8 / 3 | Ladder wall climb, jump onto ladders, jump onto vines (+10 surface in all) |
| 7 | Staircase and plunge | 10 (J53-J62) | 2.3 / 4 | Five steps up (one onto slabs, one off them), then 4-air jumps down 1 and 2, a diagonal down, a strip down |
| 8 | Momentum: ice and slime | 9 (J63-J71) | 2.3 / 4 | Packed ice runway into a 4-air jump, ice into 3 air and one up, a 3-block drop onto slime and the bounce |
| 9 | Blocks assist | 8 (J72-J79) | 1.5 / 2 | A 2-high wall and a 4-air gap, each with a slow way round and a shortcut that one placed block opens |
| 10 | Finale: mixed | 10 (J80-J89) | 3.0 / 4 | Strip, single block, fence row, wall post, ladder catch, ice, 4 air from ice, corner, finish, with no rest pads |

Every section ends on a 3x3 gold checkpoint pad (section 10 ends on the 5x5 emerald finish pad). Section colours:
smooth stone, light blue concrete, stone bricks, polished deepslate, cut copper, spruce planks, cut sandstone,
quartz bricks, bricks, purpur. Turn pads are polished andesite, ceilings are orange stained glass, the start pad is
lime concrete.

The per-jump list (technique, difficulty 1-5, expected assist result, takeoff window) is in the data file's `jump`
lines. Difficulty: 1 = any sprint jump, 2 = needs aim, 3 = narrow or long, 4 = a takeoff window of about 3 ticks or
a real technique, 5 = hard for a good player. Four jumps have a window under 2.5 ticks (J32 2.2, J40-J42 2.3);
everything else on the main line is 2.6 ticks or wider.

### Jumps that show the assist's limits

| Jump | What | Why it is on the course |
|---|---|---|
| J30, J88 | Corner onto a single block behind a 2-high column | The `TechniqueLibrary` corner case. Not yet run in game |
| J32 | Corner onto a lone fence post (the `corner-fence-2gap` lane) | Co-pilot walked off the edge here with a held-back press; the fix is unverified in game. Corrections promises no landing. Difficulty 5 for a player too |
| J34 | Corner onto a lone wall post, 2 air | Easier relative of the `corner-wall-3gap` lane, where Co-pilot finds no script in one of four tick phases |
| J36 | One-block neo | The NEO template exists but has never been run in game |
| J49, J50, J85 | Jump onto ladders / vines and climb | Ladders and vines are not landing candidates, and the replica does not model climbing. Expect no help, possibly a wrong target |
| J69, J70 | Drop onto slime, bounce to a pad 2 higher | Slime bounce is not modelled. Expect no help in the air after the bounce |
| J19, J23 | Lone fence post, lone glass pane | The landing lanes pass in Co-pilot and Takeover; Corrections only promises not to walk off a landing it made |
| Shortcut G | 4-air flat gap | Re-timed press or one far-side block; the only thing the blocks planner saves on a gap |
| Shortcut W | 2-high wall | "Parkour with blocks" wall jump |

Left off on purpose: the deep corner lanes (frame-perfect wall slides only), `corner-wall-3gap` and `wall-3gap` as
built in `ParkourScenario` (a 1.4-tick window from a run: unfair as a mandatory jump), any 5-air flat gap (nothing
saves it), and a head-hitter over a 2-air gap (only a one-tick jump from past the edge clears it). They stay in the
lane tests.

### The two shortcuts (section 9)

The main line is always possible with vanilla movement and no blocks. Section 9 has two obstacles where the main line
goes round (two short hops each) and an `alt` line goes straight:

- **W:** a 2-high wall across the platform. Straight over needs one placed block (jump, place under the feet, land,
  jump). A player can do that by hand; the assist's parkour-with-blocks does it for them.
- **G:** a 4-air flat gap. For a player this is the one frame-tight jump of the course: the jump must come on the
  single tick after the body leaves the edge (window 0.8 tick). The assist can re-time the press or place a block
  against the far side.

Taking a shortcut is allowed in every mode, also with the assist off. The run record says which shortcuts were taken
(the player crossed the `alt` segment without touching the way-round pads), so runs can be compared like with like.

## Falls

**Choice: a fail floor, no void and no liquid.** One layer of red stained glass lies 8 blocks below the lowest course
block, under the whole footprint plus a 6-block margin (`fail -8 minecraft:red_stained_glass`). Glass, so no mobs
spawn on it. Rules for the timer:

- A **fall** is: feet more than 4 blocks below the lower of the two `route` points the runner is between, or standing on the fail
  block. The height test fires within about half a second; the floor is the backstop.
- On a fall: teleport to the last checkpoint reached (position and yaw from its `checkpoint` line; the start if
  none), zero velocity, count one fall against the jump whose takeoff point was passed last. The clock keeps running:
  the cost of a fall is the time to redo the section.
- **Skip rule, so no run can loop forever:** on the 3rd fall at the same jump the runner is placed on that jump's
  `land` point with a 10-second penalty, and the jump is recorded as skipped. The automated runner needs this in OFF
  mode, where some jumps (corner, neo) may never succeed. Live, the owner gets a skip key with the same penalty.
- A run with a skip is **incomplete**. Its time is reported, but it is ranked below every complete run.

World setup while a run is active: `fallDamage false`, `randomTickSpeed 0` (the vines must not grow),
`doMobSpawning false` or peaceful, `doDaylightCycle false`, survival mode, SafeWalk (Terrain Assist) off as in the
other parkour playtests. Restore the rules afterwards. Section 9 gives the runner blocks (`kit` line); rebuild
section 9 from the data before every run so placed blocks are gone.

## Scoring

Per run, record: mode, total time in ticks, the split at each checkpoint, falls per jump, skips, shortcuts taken, and
whether the run is complete.

- **Timer start:** the first tick the player leaves the start pad (feet outside its 3x5 column). **Stop:** the first
  tick the feet are inside the `finish` volume. **Split n:** the first tick the feet are on checkpoint pad n (within
  1.5 blocks of its position in x and z, and within 1 block in y).
- **Comparing modes:** N runs per mode (automated: N = 10 with different sloppiness seeds; live: 3 baseline runs with
  the assist off, then 3 per mode). The score of a mode is the **median total time of its complete runs**; the
  baseline is OFF. Report per mode:
  - time saved against OFF, in seconds and in percent, overall and per section (median split differences);
  - completion rate, falls per run, skips per run;
  - per jump: success rate on the first attempt, so the table shows which jumps a mode fixes and which it breaks.
- **Per-jump expectations:** each `jump` line carries `assist=ok|uncertain|hard|blind|none`. A playtest should check
  only the `ok` jumps (first-attempt success in CO_PILOT and TAKEOVER) and report the rest as notes, the same way the
  open corner lanes are reported today.
- Live runs and automated runs are never mixed in one table: the simulated runner is not a model of the owner.

## Data file format

One command per line, tokens separated by single spaces, `#` starts a comment line, blank lines are ignored.
Coordinates are relative to the course origin: (0,0,0) is the centre top block of the start pad, +x east, +z south.
Block coordinates are integers. Positions (`start`, `checkpoint`, `route`, `alt`) are feet positions with decimals;
yaw is vanilla (0 = south, -90 = east, 180 = north). Lines are in build order.

| Command | Meaning |
|---|---|
| `clear x1 y1 z1 x2 y2 z2` | Set this volume to air before anything else. About 326,000 blocks: slice it (`/fill` is capped at 32,768) |
| `fill x1 y1 z1 x2 y2 z2 <block>` | Fill the box with a vanilla block id, with an optional `[state]` |
| `set x y z <block>` | One block |
| `fail y <block>` | The fail floor's level and block (the floor itself is a normal `fill` line) |
| `kit <section> <item> <count>` | Put this in the runner's hotbar when they enter the section |
| `start x y z yaw` | Where a run begins |
| `finish x1 y1 z1 x2 y2 z2` | Finish volume (feet inside it stop the clock) |
| `checkpoint n x y z yaw name` | Standing position and facing after a respawn; the pad is the 3x3 gold block under it. The yaw faces the next section |
| `jump id section technique difficulty assist=<e> [win=<t>t] \| note` | Metadata for the jump whose route points follow. `win` is the computed takeoff window in ticks for a forward-holding sprint jump along the route line; absent where the simple model does not apply (climbs, bounce, neo) |
| `route x y z tag` | Next point of the main line |
| `alt id x y z tag` | Points of an optional shortcut. It leaves the main line at the last `run` point before it and rejoins at the next `run` or `takeoff` point after the main-line jumps it replaces |

Route tags: `start`, `run` (a point to run through, for lining up), `takeoff` (the recommended place to press jump:
half a block before the end of the support for a wide window, the middle of the window for a narrow one; for the
glass pane it is 1.8 blocks before the edge), `via` (a point in the air to steer through, only the neo), `land` (aim
point on the landing; for the corner jumps it is off-centre so the straight line clears the column by 0.2),
`climb` (hold forward into the ladder or vines until the feet reach the next point's height; no jump), `bounce`
(on the slime: keep forward held, do not press jump), `checkpoint`, `finish`.

Block notes:

- Fence, wall and pane states are written out (`oak_fence[north=true,south=true]`,
  `cobblestone_wall[north=low,south=low,up=false]`). They are the states vanilla computes for those neighbours, so
  placing with or without neighbour updates gives the same shapes. Lone posts have no state and no solid block beside
  them (the generator checked), so they stay posts: fence 0.25 wide, wall 0.5, pane 0.125.
- Fence and wall landings sit in the same block layer as the pads around them, so their top is half a block above
  pad level. Slabs are bottom slabs one layer up (top also half a block above).
- Ladders face away from their tower; vines carry the state of the side their tower is on.
- Packed ice, not ice (no melting). No gravity blocks, no liquids.

## What the automated runner needs

The runner is deliberately simple. Movement Assist should be the only clever thing in the loop.

- It holds **forward + sprint** on physical keys (as the landing lanes do: an automation lease on MOVEMENT would make
  the assist stand down) and turns the view toward the next route point through the capped smoother. It moves to the
  next point when it is within 0.6 blocks horizontally of the current one, or has passed it along the line.
- **Jump:** one press per `takeoff` point, when the body passes the point along the line to the next point, shifted
  by the sloppiness model: an offset in ticks drawn per jump from a seeded distribution (suggested: uniform -2..+1
  ticks, early more often than late, as in the gap lane). The press comes even if the assist has already jumped, as
  in the lanes. Same seed, same presses: runs are reproducible, and each mode sees the same ten seeds.
- **Climb:** at a `climb` point it keeps forward held and presses nothing until the feet are at the next point's
  height. **Bounce:** nothing but forward. **Via:** steer through it in the air.
- It never strafes, never sneaks, never releases forward mid-jump. So in OFF mode the corner, neo and narrow-post
  jumps are hit or miss by design; that gap is what the benchmark measures.
- **Shortcuts:** a run setting chooses main line or `alt` per shortcut. Suggested: OFF runs take the main line; each
  assist mode is run both ways.
- **Blocks:** in section 9 the runner needs the `kit` item in the hotbar and does nothing with it; placing is the
  assist's job.
- From the data it needs: `start`, the ordered `route` points with tags, `alt` points, `checkpoint` lines (respawn),
  `finish`, `fail`, and the `jump` lines (to attribute falls and to know which results are checks and which notes).

The live timer needs the same lines minus the route (it uses `takeoff` points only to attribute a fall to a jump).

## How the geometry was checked

The file was produced by a small generator script that builds each jump from a cursor (gap, height change, sideways
shift, landing kind) and checks it as it goes. It is not in the repo.

- **Jump model:** vanilla 1.21.1 numbers for a forward + sprint jump from running speed: 0.2806 blocks/tick on the
  ground, the 0.2 jump boost, air acceleration 0.02548, drag 0.91, jump velocity 0.42, gravity 0.08 with 0.98 drag;
  vertical collision resolved before horizontal (so a landing needs the body over the block one tick before the feet
  would pass its top); a jump allowed on the tick after the body leaves the support. That gives a reach along the
  line of 3.34 blocks for a level landing, 2.49 for one up, 2.77 for half up, 3.91 for one down, 4.20 for two down;
  from packed ice 4.40 level and 3.32 one up; under 3 blocks of headroom 3.06 level and 1.92 one up; in a 2-high
  tunnel 0.77.
- **Window:** for each jump, the range of takeoff positions along the straight line from the takeoff point to the
  aim point that puts the body over the landing's support (shape plus 0.3 on each side), divided by the distance
  covered per tick. Lone posts use their real widths. Every main-line jump has a window of at least 2.2 ticks.
- **By hand:** corner columns (the diagonal line clears the column by 0.2 block), the neo, ladder and vine catches
  (the climbable cells cover every height the body can arrive at), the slime bounce (touchdown 7.3 to 9.0 blocks past
  the drop edge for takeoffs from 1.2 blocks before the edge to the last tick; the pad covers 6.7 to 11.3), ceilings
  (no ceiling overhangs a pad where a full-height jump arrives), standing jumps off every post and single block.
- **Spacing:** no block of one section is within 11 blocks (horizontally, within 8 in height) of a non-neighbour
  section, so the intent estimator should not pick up another lane.

## Open questions

1. Check the windows with the real replica: a unit test that loads the file into a `MovementPhysics.Voxels` grid and
   rolls each `takeoff` to `land` pair would catch any jump where the simple model is wrong. The slime bounce and the
   head-hitter hops are the least certain.
2. Where to build it. The existing lanes found that the body did not move about 200 blocks from the arena. The course
   is 109 x 81; with the origin near the arena the far corner is about 120 blocks out.
3. Is J32 (corner onto a fence post) acceptable as a mandatory difficulty-5 jump, given the skip rule, or should the
   post become a full block with the fence-post version moved to an `alt` line?
4. Shortcut policy for the headline number: main line only, or each mode's best?
5. The sloppiness distribution of the runner (uniform -2..+1 ticks is a guess).
6. Fall damage is off for the run. With it on, the two-block drops (J59, J60, J86) may cost half a heart from a
   jump, and SafeWalk would refuse them.
