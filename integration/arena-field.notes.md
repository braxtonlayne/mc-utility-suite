# arena-field (review 2.5 + 2.6)

Files: `combat/tactics/ArenaField.java`, `combat/assistant/mc/ArenaVoxels.java`, tests
`combat/tactics/ArenaFieldTest`, `ArenaFieldAllocationTest`. Frozen contracts (`ArenaQuery`, `CombatVoxels`)
unchanged; no query was added.

## Grid layout

- 33 x 33 columns (radius 16) around the centre. Storage is primitive arrays of 1089 slots allocated once:
  `colX/colZ` (world column held), `colY` (centre y at scan time), `floor` (int, `NO_FLOOR` = none),
  `head` (byte, capped at 4), `kind` (byte, `CombatVoxels` kind), `mask` (short, standable cells of the
  window above the floor) and `stamp` (scan epoch, 0 = never).
- Slot index is toroidal by world position: `floorMod(x, 33) * 33 + floorMod(z, 33)`. A column is valid when its
  slot's stamp is the current epoch and its stored x/z match, so a recentre keeps every overlapping column without
  moving data (recentre recounts coverage in one 1089-slot pass, only when the centre changed).
- Restart (epoch bump, coverage 0): first recentre, a vertical move of 3+ from the epoch's base y, or a horizontal
  jump of more than 8 blocks in one recentre.
- Scan order: a spiral (offsets sorted by distance, computed once in the static initialiser). Phase 1 walks it
  scanning only unscanned columns; once all are covered, phase 2 refreshes round-robin over the same order.

## Column scan (at most 14 lookups)

Window `cy+5` down to `cy-8`. Walking down: the floor is the first standable (SOLID/SLOW) block at or below
`cy+1` with 2+ open (AIR/WATER) cells above; LAVA/HARM/COBWEB/WATER at or below `cy+1` with 1+ open cell above
is recorded as the floor with that kind. UNKNOWN stops the scan and the column stays UNKNOWN (never safe, never a
drop). Nothing found: kind AIR (open to the bottom = a drop of at least `floor(target) - (cy-9)`) or SOLID (buried).
Headroom = open cells above the floor, capped at 4. The scan stops at the floor; cells at or below a standable
floor count as solid for the wall check.

## Budgets and measurements (dev laptop, JIT warm, scripted world)

| call | measured | target |
| --- | --- | --- |
| `scan(16)` | ~0.2 us | < 30 us |
| `evalKnock` | ~0.4 us | - |
| `findHighGround` | ~17 us | < 100 us |
| `findLowCeiling` | ~18 us | < 100 us |

All of recentre + scan(16) + evalKnock + both finds: 0 bytes over 20k iterations (budget 1 KB). Real
`ArenaVoxels` lookups cost far more than the test lambda; 16 columns x 14 lookups = 224 `getBlockState` at worst.

## Knock value table

16 directions, samples at 1, 2, 3 blocks from the target, weighted 1.0 / 0.75 / 0.5 by nearness. A ray stops at
an unscanned or UNKNOWN column and at a wall.

| feature | value | kind |
| --- | --- | --- |
| lava floor | 1.0 | KNOCK_LAVA |
| drop of 6+ (or open to below the window by 6+) | 1.0 | KNOCK_DROP |
| drop of 3..5 | 0.6 | KNOCK_DROP |
| cobweb | 0.4 | KNOCK_COBWEB |
| damaging floor (fire, magma, cactus...) | 0.35 | KNOCK_LAVA (damaging family) |
| wall: solid at feet and head height, 1 block behind only | 0.3 | KNOCK_WALL |
| slow floor (soul sand, honey, slime) | 0.15 | KNOCK_COBWEB (trap family) |
| water | 0 | - |

Smoothing: `0.6 * v[k] + 0.2 * (v[k-1] + v[k+1])`, best direction wins; `knockValue` is the smoothed value (a
single lava column straight behind reads about 0.6-1.0 depending on how many rays hit it). The contract has no
HARM or SLOW knock kind, so they report under LAVA and COBWEB; tactics that care can read `floorKind`.

## Positions

Both finds flood-fill from the player's column over walkable scanned columns (standable floor, headroom 2+, step
+1 up / -3 down; preallocated queue, bounded to the grid).
- `findHighGround`: floor kind SOLID, gain = feet above target feet >= 1, headroom >= 3, within maxDist of the
  player and 4 of the target, none of the 8 neighbours unknown, lava, harm, open drop or 3+ lower. Score prefers
  gain 1-2, near the player, about 2.5 from the target.
- `findLowCeiling`: headroom exactly 2, SOLID, feet within 1 of the player's, within maxDist, in a connected
  (4-way, steps of 1) low-ceiling region of 3+ columns (memoised component sizes). Score prefers the far side of
  the player from the target, then larger regions, then nearer. spotGain = region size.

## Left for playtest

- `ArenaVoxels` cannot be unit-tested: state mapping (campfire lit check, powder snow, waterlogged blocks) and
  chunk-edge behaviour (`getChunkSource().hasChunk`). Below min build height reads LAVA, at/above max reads AIR.
- Spark: confirm the scan stays far below 1% of the client tick at the chosen per-tick budget.
- Knock directions on real terrain (stairs, slabs, fences: slabs and fences read SOLID full blocks here).
