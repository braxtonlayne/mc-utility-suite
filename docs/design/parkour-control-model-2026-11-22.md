# Parkour control model: jump commitment (design, 2026-11-22)

Status: design only. No code changed. Written after the owner's hands-on test on 2026-11-22 (Twisted Parkour map,
build `43eb44e`). Scope: Movement Assist (`travel/movement`), the core input layer it needs, the HUD data it
exposes. Combat and the menu restyle are other designers' documents.

## 1. What the test said, and why the code does that

Owner feedback:

- "Its parkour is still kinda meh, like it does the bare minimum with how it helps and it performs poorly for edge
  cases like corner jumps."
- "I like the idea of just smoothed corrections, but that fails in practice when it gives control back to the user
  instantly. When I tried a corner jump ... I ended up walking off the fence/stone wall thing. I want something in
  between smoothed corrections and full takeover. Could offer multiple options with a default that is like STEM, or
  semi STEM."

Root causes found in the code (all in `src/main/java/com/utilitysuite/travel/movement/` unless noted):

| # | Cause | Where |
|---|---|---|
| R1 | Every block with any collision shape is a 1x1x1 cube. A fence or wall is 1.5 high with a 0.25 to 0.5 wide top; a pane is 0.125 wide. The search, the intent target (`feet at y + 1`), `smallLanding`, the "clean landing" check and the overlay all work on a landing that does not exist. | `LevelVoxels.read`, `MovementPhysics` (class doc: "full-cube approximation"), `IntentEstimator.estimate`, `MovementAssistBrain.smallLanding` |
| R2 | The landing hold is a 4-tick sneak tap (`STICK_TICKS = 4`). It ends on a timer, ends at once when a key changes, is skipped when the user holds jump, and is refused when the jump's authorship budget is spent (`budget.tryUse(FULL_HELP_MISS)`), which is exactly the jump that needed the most help. After it ends the user is still holding W from the flight, so the body walks off. | `MovementAssistBrain.shouldStick`, `decideGround` |
| R3 | A key held through the flight is treated as a fresh command on the ground. Nothing separates "W I was already holding" from "W I just pressed". | `MovementAssist.onMovementInput` |
| R4 | Air steering holds one key combination from now to the landing (`JumpSearch.simulate`). A corner jump or a neo needs keys and yaw that change during the flight. No candidate in the search can go around anything. | `JumpSearch.steer`, `evaluate` |
| R5 | The brain never writes a yaw and only scales the user's mouse (`ManualLookAssist`, off on Legit, nothing with a still mouse). Vanilla air acceleration and the sprint-jump boost both follow yaw, so the takeoff line and most of the air authority are out of reach. | `MovementAssistBrain` class doc, `core/input/ManualLookAssist` |
| R6 | The edge jump and the ideal-yaw search only try `forward = 1, strafe = 0` within 22 degrees of the straight line to the target, on the last supported tick. | `JumpSearch.shouldEdgeJump`, `edgeRun`, `YAW_OFFSETS` |
| R7 | Help is all or nothing per tick and capped at 6 corrected ticks per jump; safety steering needs a 0.75 block advantage (`STEER_MARGIN`). A jump that is going to fail by 0.3 block gets little. | `AuthorshipBudget`, `decideAir` |
| R8 | Touching a wall is not penalised (the replica zeroes the velocity on `horizontalCollision`, and vanilla also drops sprint), ladders, vines and water switch the module off (`skip`: "not on foot"), slabs have no step-up. | `MovementPhysics`, `MovementAssist.skip` |
| R9 | With many candidates the intent target is re-picked every tick from the instant look ray; it can flip mid-jump, and reach is a table (`REACH_BY_DY`), not a simulated jump. | `IntentEstimator` |

A number that explains the fence: in the replica's constants a body that touches down at sprint-jump speed
(about 0.3 block/tick) and releases every key still slides about 2x its touchdown speed, 0.5 to 0.65 block. The
centre of the body is supported on a fence post only within 0.425 block of the post centre (0.3 half-width +
0.125 half-post). So a centred, full-speed landing on a fence post slides off even with no key held. The landing
has to be prepared in the air and guarded on the ground. Nothing in today's model does either.

## 2. The model in one paragraph

A jump becomes a **commitment**: one plan with one locked target that the assist holds from the approach through
the takeoff, the flight, the touchdown and until the body is stable, and only then hands back, gradually. During a
commitment the user's input is read as one of three things: **noise** (ignored), a **trim** (the plan bends to it)
or an **override** (the assist lets go of that axis, or of everything). How much the assist may do inside a
commitment is the **control mode**: three selectable modes, with the middle one as the default. Every mode shares
the same landing guarantee: a key you were already holding in the air never walks you off a narrow landing.

Working names in code (project rule 7, neutral): unit `JumpCommit`, modes `NUDGE`, `GUIDE`, `CARRY`. Menu labels:
"Corrections", "Co-pilot" (default), "Takeover".

## 3. Control modes

| | NUDGE "Corrections" | GUIDE "Co-pilot" (default) | CARRY "Takeover" |
|---|---|---|---|
| Idea | Today's behaviour, with the landing fixed | Semi-STEM: the jump is yours, the assist owns the parts you cannot do by hand | STEM: hold forward toward the course, the assist flies the jump |
| Commits when | Never (per-tick corrections) | You jump (or its edge jump fires) with a locked target and a plan that lands | A target is locked and you hold a movement key toward it |
| Free axes (keys you do not hold) | Filled, within the authorship budget | Filled, no tick cap | Driven |
| Keys you hold | Never replaced | A **stale** key (held since before the commitment) may be withheld. A **fresh** key is never replaced | Stale keys driven. Fresh taps are trims, fresh holds are overrides |
| Jump key | Edge jump, tick phase, buffer (as now) | Takeoff tick chosen in the last 4 supported ticks | Same, pressed for you |
| Yaw | Turn gain only (as now) | Co-steer, bounded (section 6), by legit level | Co-steer with the full smoothed cap, by legit level |
| Landing | Landing hold (section 5) | Air brake + landing hold | Air brake + landing hold |
| Chains | No | When you hold or press jump | Automatic while you hold forward |
| Minimum intervention | n/a | Yes: while your own keys and yaw land it stably, the overlay is empty | Plan seeded from your inputs, then it drives |
| Authorship budget | As now | Only the skill fade survives: it decides how early the overlay departs from your input | Not used |

Why GUIDE is the default: it is the "in between" the owner asked for. It keeps "manual input wins" literally true
for anything pressed after the commitment starts, it does nothing on a jump that is already fine, and it removes
the two things that made corrections fail in practice (the instant hand-back and the 6-tick cap). CARRY is one
click away, and a **hold-to-carry key** (unbound by default) upgrades GUIDE to CARRY for the jump in progress: the
"take over" gesture.

The autopilot's `PARKOUR` reflex uses the module's selected mode, at least GUIDE, with the HUD rules of section 10.

### 3.1 Legit levels

Input states at every level are states a keyboard can produce: keys on or off, sneak, sprint, jump. No analog
impulse scaling at any level. Withholding a key looks to the server exactly like releasing it.

| | LEGIT | BALANCED (shipped default) | RELAXED |
|---|---|---|---|
| Yaw written by the assist | Never. Turn gain off (as now). The plan solves keys and the takeoff tick for the yaw your hand produces; when the yaw cannot make it, the HUD shows a turn cue instead | GUIDE: co-steer up to 25 degrees per jump at up to 150 deg/s. CARRY: up to 90 degrees at the configured camera speed (240 deg/s) | Up to 180 degrees per jump at the hard cap (450 deg/s) |
| Look style | NATURAL: yaw stays within 30 degrees of the travel line | NATURAL | FREE: yaw may sit off the travel line (45-degree air strafe, looking back along a neo) |
| Key changes per jump by the assist | At most 3, each held at least 3 ticks, first change after a 2 to 4 tick reaction delay | At most 6, each at least 2 ticks | Unlimited, 1 tick |
| Takeoff tick | Last supported tick or the one before | Any of the last 4 | Any of the last 4 |
| Techniques (section 8) | Straight, sideways, corner by keys, shaped landings, ladder catch | + corner and neo with yaw, headhitter timing | + 45-degree momentum, headhitter momentum chains |
| Landing hold | Sneak guard + stale-key latch | Same | Same; sneak guard may be skipped when the air brake alone lands stable |
| CARRY | Keys-only takeover | Keys + yaw | Keys + yaw, FREE look |

Hard rules at every level: every assisted yaw change goes through the core and is capped at
`RotationLimits.MAX_DEG_PER_SEC` (450); pitch is never touched; nothing renders when idle.

Open decision for the owner (D1): BALANCED lets the camera move with a still mouse during a committed jump
(bounded, smoothed). That is new: today nothing in Movement Assist turns the view by itself. If that is not
wanted at the default level, D1's alternative is "BALANCED = turn gain only, co-steer from RELAXED", at the cost
of corner jumps and neos needing your own turn.

## 4. The commitment state machine

`JumpCommit` (engine-free, allocation free, one instance in `MovementAssistBrain`).

```
IDLE -> ARMED -> TAKEOFF -> FLIGHT -> TOUCHDOWN -> SETTLE -> RELEASE -> IDLE
                                          \-> CHAIN (= ARMED for the next jump, no SETTLE)
any state -> ABORT -> RELEASE (fast)
```

| State | Entered when | The assist does | Leaves when |
|---|---|---|---|
| IDLE | | Nothing. NUDGE corrections run here | A target locks inside the approach window |
| ARMED | Target locked (section 9), an edge or a takeoff spot within 8 ground ticks, a plan exists | Shapes the run-up: line, sprint, the takeoff tick. GUIDE only fills free axes here; nothing is withheld on the ground before the jump | Takeoff; or the target is lost, or the plan no longer lands (back to IDLE, no hold) |
| TAKEOFF | The jump tick (yours, buffered, or the assist's) | Locks target, plan and the commit line (the takeoff heading). Starts the grant (section 7) | Next tick |
| FLIGHT | Airborne | Flight controller (section 6): keys and yaw toward the landing state, re-solved every tick from the real body | Touchdown, or the landing becomes impossible (ABORT) |
| TOUCHDOWN | First ground or climbable tick | Classifies the landing: wide, narrow, climbable, water, bounce. Decides SETTLE or CHAIN | Same tick |
| SETTLE | Narrow or unstable landing | Landing hold (section 5) | Stable and a fresh input arrived; or the hold limit |
| CHAIN | Chain intent (section 8.9) and the next jump is feasible | Becomes ARMED for the next target with the touchdown state it prepared | |
| RELEASE | SETTLE done, wide landing, or ABORT | Blends authority to zero (section 5.3) | Authority 0 |

A commitment is one unit for the arbiter, the HUD, the autopilot (`noteActed` at TAKEOFF, `noteDone` at the end
of RELEASE) and the tests. A commitment never spans a teleport, a world change, a screen opening or Stop-All.

### 4.1 Reading the user's input during a commitment

Facts come from the core (per key: ticks held, tick of the last press and release, from hardware events; mouse:
the hand's own yaw degrees per tick before any gain). `OverrideClassifier` (engine-free) turns them into:

**Noise (ignored)**
- A key held without interruption since before TAKEOFF (a stale key).
- Mouse yaw under 1.5 degrees accumulated over 4 ticks.
- A release and re-press of the same key within 1 tick (bounce).

**Trim (the plan bends, the commitment holds)**
- A fresh strafe tap of 1 or 2 ticks: the aim point moves up to 0.2 block toward that side inside the target's
  support region.
- Releasing forward in the air: the aim point moves to the near part of the target; if a nearer candidate is
  feasible from the current flight state, the target changes once.
- Mouse yaw under 25 degrees from the commit line: co-steer yields to the hand (section 6.2); the key plan is
  re-solved for the yaw the hand produced.
- Jump pressed in the air: a chain request (section 8.9).
- A look sweep onto another feasible candidate before the apex: one re-target per flight.

**Axis override (that axis is the user's for the rest of the jump)**
- A fresh key held 3 ticks or more (2 on RELAXED, where the assist reacts faster too). The plan is re-solved with
  the remaining axes. If no plan lands any more, the HUD says so (amber) and the commitment goes to ABORT.
- Mouse yaw opposing the co-steer by more than 6 degrees within 5 ticks: the yaw is the user's; keys continue.

**Hard override (ABORT now)**
- Sneak pressed. This is the documented "let go" key: the module already stands down while the user sneaks.
- Back pressed against a forward plan and held 2 ticks.
- Mouse yaw more than 25 degrees off the commit line, or faster than 20 degrees in one tick.
- Two axes overridden.
- Stop-All, a screen opening, the module or mode switched off, an EMERGENCY lease (fall clutch), a higher
  priority lease on MOVEMENT.

Knockback or any other outside change to the body is not an override: the plan re-solves from the new state and
aborts only when nothing lands.

In GUIDE a fresh key is never replaced, not even for a tick: the axis override rule only decides when the plan
stops counting on that axis. In CARRY the first 2 ticks of a fresh key are a trim and the plan's key still
applies; from tick 3 the user's key applies.

Override sensitivity is one setting (LOW, NORMAL, HIGH) that scales the degree and tick thresholds above by
1.5, 1.0 and 0.6.

## 5. Landing: brake, guard, latch, hand-back

### 5.1 Support region and the two predictions

`SupportRegion` (engine-free): for a landing top (the exact collision boxes at the landing height, section 7.2)
the set of body-centre positions that are supported is the union of the top boxes grown by the body half-width
(0.3). Two numbers per predicted landing:

- **margin**: distance from the touchdown point to the nearest edge of the region (negative = off).
- **stop point**: where the body comes to rest with the planned post-landing keys, from the ground friction of
  the surface (normal blocks, ice, slime, honey, soul sand have their own).

A landing is **wide** when the region contains a 1.6 x 1.6 square around the stop point of the user's own keys
with 0.3 to spare (a normal platform: nothing below applies, you keep running). Otherwise it is **narrow**.

### 5.2 The three tools on a narrow landing

1. **Air brake** (GUIDE, CARRY): the flight plan arrives slow. In the last air ticks it releases forward or holds
   back so the touchdown speed puts the stop point inside the region. This is what a good player does and is the
   main tool; it is skipped for a chain.
2. **Sneak guard** (all modes): sneak goes down on the last air tick and stays down on the ground. Vanilla's own
   edge back-off then keeps the body on the top using the real collision shapes, on any block. Vanilla does not
   guard the touchdown tick itself (the back-off needs ground under the body at the start of the move), so the
   plan must put the end of the touchdown tick over the region; the replica checks that. Never while the user
   holds jump for a chain. To verify in the build: the first sneak tick set in `MovementInputUpdateEvent` behaves
   like a real key press (edge back-off on, the 0.3 input scale one tick later).
3. **Stale-key latch** (all modes): on touchdown of a narrow landing every movement key that was held through the
   flight is latched off, per key, until that key is released. This is the rule that removes the walk-off.

The hold (sneak guard on) ends when the body is stable (speed under 0.03 block/tick, stop point inside the region
with 0.05 margin) and one of these arrives:

- a fresh press of any movement key (that key applies at once; latched keys stay latched until released);
- jump (a fresh press, or held as a chain request): the next jump starts, latches clear;
- the hold limit (setting, default 12 ticks after stable): the sneak guard lifts so the body stands upright, the
  latch stays. The status line shows "Hold: release W to move" while a latched key is still down.

There is no timer on the latch. A key that was down in the air cannot move the body off a narrow top; releasing
and pressing it again always can. This is unlike today's 4-tick tap, and it is not gated by any budget.

Settings: `landing_hold` (on), `hold_release` (FRESH_INPUT default, or TIMER with a tick count for players who
want the old feel with a longer window), `hold_limit_ticks`.

### 5.3 Hand-back blend

RELEASE lasts 6 ticks in GUIDE, 8 in CARRY, 2 after a hard override. Authority `a` goes from its current value
to 0 on a smooth ramp, and each output is tied to it:

- co-steer: rate limit and gain scale with `a`; the camera never turns back to where it was;
- key overlay: fill ends first; withholding continues only where the user's raw keys step off within
  `2 + 4a` simulated ticks (an edge-guard horizon that shrinks to nothing);
- HUD: the target bracket opens and fades with `a` (section 10), so the hand-back is visible.

After a hard override nothing is withheld except the stale-key latch on a narrow landing, which is a property of
the landing, not of the commitment.

## 6. Flight control

### 6.1 Solving the flight

Vertical motion does not depend on the horizontal keys (apart from collisions), so the number of air ticks `n`
to the landing height is known from one vertical roll. Horizontal motion under linear drag is linear in the
acceleration applied, so the constant world-space acceleration `A` that reaches the aim point in `n` ticks has a
closed form (two multiplies per axis from precomputed drag sums for `n` up to 60). Per air tick:

1. Compute `A` for the aim point. If `|A|` exceeds what the keys can give (0.02 per tick, 0.026 forward while
   sprinting), the aim point is unreachable: move it to the nearest reachable point of the support region, or
   ABORT when there is none.
2. Arrival shaping: a small family of two-phase plans, "accelerate along `A`, then from tick `k` brake" with
   `k` in {n, n-2, n-4, n-6} and brake in {release, back}: at most 8 candidates. For a chain the family is
   "arrive on the next takeoff line" instead (section 8.9).
3. Pick the key combination (9 of them) whose acceleration at the current yaw is closest to the wanted one, and
   the yaw that would make it exact; the yaw goes to co-steer as the ideal (never applied directly).
4. Verify the best candidate with one full roll in the exact-shape replica (collisions, sprint loss, ceiling).
   Any wall contact fails a candidate. If the verified plan fails, fall back to the existing 9-combination search.
5. Minimum intervention (GUIDE): first roll the user's own keys at the user's own yaw. If that lands with a
   stable stop point (or with the sneak guard on a narrow top), output nothing this tick. Once the overlay has
   departed from the user's input it keeps its choice for at least 2 ticks (no flicker), and the skill fade from
   `AuthorshipBudget` widens the "already fine" tolerance for a player who lands most jumps unaided.

Time-varying plans (corner, neo) come from the technique templates of section 8, which the same verify step rolls.

### 6.2 Yaw: co-steer

New core primitive next to turn gain, in `core/input` (`YawCoSteer`, applied in the existing mouse-turn hook so
it is smooth at any frame rate and adds to the hand's motion instead of replacing it):

- Input per tick from the module: ideal yaw, maximum rate, maximum total degrees for this commitment, authority.
- Output per frame: an added yaw step toward the ideal, from a critically damped follower with a rate limit and
  an acceleration limit (ease in and out; no step in angular speed), never past the ideal, and the total it has
  added in this commitment never past the allowance.
- The hand's own motion always passes through in full. Motion that opposes the follower by more than the
  threshold of section 4.1 switches the follower off for the commitment.
- Hard cap `RotationLimits.MAX_DEG_PER_SEC` on the added part, whatever is asked. Pitch never passes through.
- Off while a screen is open, while an automated look is running (`InputSmoother.isAutomatedLookRunning`), after
  Stop-All, and on LEGIT (the module does not ask; the core also refuses when the legit level is LEGIT).
- Lapses by itself after its ttl, like turn gain.

NATURAL look style: the ideal yaw is clamped to within 30 degrees of the horizontal velocity direction, so the
head stays on the line of travel; the key combination supplies the rest of the direction. FREE lifts the clamp.

Turn gain stays as it is and stacks: when the hand is already turning toward the ideal, gain amplifies it and
the follower has less to add.

Takeoff line: in ARMED the follower (or, on LEGIT, the HUD cue) brings the yaw to the planned takeoff yaw before
the takeoff tick, because the sprint-jump boost (0.2 block/tick) goes along the yaw at that tick. The planner
only offers takeoff yaws the follower can reach in the ground ticks left at its rate limit; otherwise the
takeoff tick or the technique changes.

## 7. Core and physics groundwork

### 7.1 Shared control grant (core contract)

Today the module writes `Input` fields in `MovementInputUpdateEvent` itself and calls `setSprinting` directly
(flagged in the run note). A full MOVEMENT lease does not fit: any hardware key press ends it
(`ActionArbiter.onManualInput`), and the commitment has to classify presses, not die on them. New contract in
`core.api`, implemented in `core.input` and `core.arbiter`:

```java
public interface ISharedControl {                      // Services.sharedControl()
    /** Null when refused: automation stopped, a lease at or above the priority holds MOVEMENT, or a screen is open. */
    SharedGrant begin(String moduleId, ControlPriority priority, boolean wantsYaw);
    ManualFacts manual();                               // per-key held ticks, last press/release tick, hand yaw per tick
}
public interface SharedGrant {
    boolean isActive();
    void axes(int forward, int strafe);                 // KEEP, or -1/0/1; applies to the next movement input only
    void jump(int state);                               // KEEP, ON, OFF (OFF = withhold)
    void sneak(boolean on);
    void startSprint();                                 // fill-only, under vanilla's own conditions
    void coSteer(float idealYaw, double maxDegPerSec, double maxTotalDeg, double authority);
    void end();
}
```

- The grant is not ended by manual key presses; it reports them. Stop-All, world unload, a higher-priority or
  EMERGENCY lease, and a screen end it. While it is live the arbiter shows MOVEMENT as shared by the module, so
  other automation is refused as it is today for a lease of that priority.
- Every write lapses after one tick. A module that stops calling leaves the user's input untouched.
- Primitive arguments only; no allocation per call. The overlay is applied in one place
  (`core/input/SharedInputOverlay`, on `MovementInputUpdateEvent`), which is also the home for the sprint start.
- The static input-rule grep stays clean: `options.key`, `setYRot`, `setDown` are still only touched in
  `core/input`.

`core.api` is orchestrator-owned: this item goes first and alone.

### 7.2 Exact collision shapes

`MovementPhysics.Voxels` gains a shape path; cubes stay the fast path.

- `LevelShapes` (replaces `LevelVoxels` for the assist): per-tick cell cache of a `short` shape id. 0 = air,
  1 = full cube, others index a table of box lists (`float[6 * n]` in block-local coordinates) built once per
  `BlockState` from its collision shape and kept for the session. Building a table entry allocates and happens
  on first sight of a state, not per tick. States whose shape depends on position (offset blocks) or on the
  entity (scaffolding, powder snow) are marked UNMODELLED.
- A box may rise above its cell (fences and walls: 1.5). The clip functions also scan one cell below the range.
- A surface byte per cell: NORMAL, ICE (slipperiness from the block), SLIME (bounce), HONEY, SOUL_SAND, CLIMBABLE,
  WATER, HARM (cactus, berry bush, magma, fire, lava), STUCK (cobweb, powder snow), UNMODELLED.
- **Fail-safe rule:** a plan whose roll touches an UNMODELLED or STUCK cell, or lands on HARM, is not committed.
  The jump falls back to NUDGE behaviour and the sneak guard. The assist never commits to physics it does not
  model.

Reference numbers (read from the game at run time, listed here only to size the problem): fence post 0.25 wide
and arms 0.125, 1.5 high; wall post 0.5 and sides 0.375, 1.5 high; pane and iron bars 0.125, 1.0 high; bottom
slab 0.5 high; ladder 0.1875 thick.

### 7.3 Replica additions (`MovementPhysics`)

Each one with a unit test against hand-computed values and covered by the in-game parity run (section 11):

- shape-aware clipping and `supported`; step-up of 0.6 (slabs, stairs, carpets);
- sneak: input scale, the edge back-off (same rule as vanilla, including "not on the touchdown tick"), the
  1.5-high hitbox;
- jump delay (a held jump key re-jumps only after 10 ticks; a fresh press has no delay): needed for headhitter
  chains and for chain timing;
- sprint loss on wall contact and its return on the next tick while the sprint input is on;
- surface effects: ice and custom slipperiness under the feet (read per tick, not once), slime bounce, honey and
  soul sand speed and jump factors;
- climbable: horizontal clamp 0.15, descent clamp, sneak hold, climb on wall contact or jump, fall distance reset;
- water as a landing (splash ends the flight, no damage). Swimming and currents stay unmodelled.

All of it verified against the 1.21.1 sources in the dev environment (Mojang mappings), written fresh, no code
taken from other projects.

## 8. Jump classes

Each class is a template in `TechniqueLibrary` with a few integer parameters; the planner rolls candidates in the
exact-shape replica, a fixed number per tick, keeping the best so far across the approach window (the pattern
`YAW_OFFSETS` and `TakeoffShaper` already use). A candidate is a short script: takeoff tick, takeoff yaw offset,
and up to three air segments of (keys, yaw rate, ticks). Scripts live in preallocated primitive arrays.

Scoring: lands on the locked target; no wall contact; margin; stop point (or the next takeoff for a chain);
least departure from the user's own keys and yaw; least yaw travel. Costs that the legit level forbids remove
the candidate.

| # | Class | What makes it hard | Template |
|---|---|---|---|
| 1 | Straight and sideways gap | Already handled | Takeoff tick in the last 4 supported ticks, yaw offset 0 to 22 degrees, constant air keys. Replaces the single last-tick rule of `shouldEdgeJump` for committed jumps |
| 2 | Corner jump (the landing is behind the corner of a block beside the gap) | The straight line clips the corner; touching it zeroes the speed and drops sprint | Takeoff angled away by 10 to 45 degrees (5 steps), outward air keys for `k1` ticks, then inward keys with a yaw sweep toward the target at the level's rate. On LEGIT: the same with keys only at the hand's yaw |
| 3 | Diagonal and 45-degree momentum | Full-length input needs W plus a strafe key with the view 45 degrees off the travel line; the boost needs the view on the line at the takeoff tick | Takeoff on the line, then turn 45 degrees with W+strafe. Gain is small (about 0.02 block per the takeoff-shaping notes), so: RELAXED only, last resort for maximum-distance jumps |
| 4 | Neo (around a wall 1 to 3 blocks long beside the path) | Out, along and back in during one flight; needs side speed built on the ground | Run-up with a strafe-away for the last `g` ground ticks, takeoff parallel to the wall, strafe-toward from air tick `k1`, yaw sweep back in. With yaw from BALANCED; keys-only attempts on LEGIT for 1-block neos |
| 5 | Headhitter and low ceilings | The ceiling cuts the arc: shorter, flatter, and an early jump hits sooner | Exact ceiling boxes (trapdoors, slabs). The takeoff-tick search already covers "jump earlier". Momentum variant (RELAXED, CARRY): repeated fresh jump presses under a 2-high ceiling to stack the sprint boost before a long gap |
| 6 | Shaped landings: fence, wall, pane, bars, slab, stairs, trapdoor, chain, head, chest, ladder top | Height is not `y + 1`, the top is narrow | Nothing special in the template: exact shapes give the right height and `SupportRegion` the right top. Always narrow: air brake, sneak guard, latch |
| 7 | Ladder and vine | The "landing" is a catch in the air | Landing test = body inside a climbable cell at the catch tick. SETTLE = sneak (holds position on a climbable), release as section 5.2. Leaving a ladder is a step-off with air control, planned as a fall with keys. `MovementAssist.skip` stops excluding climbables once the replica models them |
| 8 | Water | Into water: any depth of at least 1 is a safe landing. Out of water: different physics | Phase 1: water as a landing (the commitment ends at the splash, no hold) and as a damage-free option the target picker may prefer on a long drop. Water takeoffs later, not in this plan |
| 9 | Momentum chain | The landing has to set up the next jump, not stop | Section 8.9 |
| 10 | Bounce (slime) and slides (ice, honey) | The landing is not the end | Slime: with the sneak guard the bounce is cancelled (vanilla); for a chain the bounce is rolled as part of the next flight. Ice: stop point uses the real friction; a narrow ice top is not committed unless the air brake alone stops inside the region |

### 8.9 Momentum chains

Chain intent: the user holds jump through the landing or presses it in the last 4 air ticks (GUIDE), or simply
keeps holding forward (CARRY, setting `chain_jumps` = AUTO), and the next target is locked and feasible.

Planning looks two jumps ahead: the best few flight candidates for jump 1 are each extended with one roll of
"run to the next takeoff and jump to target 2". The candidate whose second jump lands with the most margin wins,
which typically means landing on the near part of the pad, already on the next line, at full speed. On a chain
there is no air brake, no sneak, no latch: TOUCHDOWN goes straight to ARMED. If the second jump stops being
feasible during the flight, the commitment falls back to a normal landing (brake and hold) while there are still
air ticks to brake in; this cut-off tick is part of the plan.

## 9. Which block is the target

`IntentEstimator` changes (still one bounded estimate per tick, allocation free):

1. **Candidates are real tops.** A candidate is a standable top from the exact shapes, with its true height, a
   body-sized clearance above it, and its surface kind. Fences, walls, slabs and ladders become candidates.
   HARM tops are not candidates; UNMODELLED tops are candidates for NUDGE only.
2. **Reach is simulated.** The table stays as a pre-filter. The best 6 by score get one feasibility roll each
   (from the coming edge at the current speed); infeasible ones drop out. Spread over ticks through a cursor.
3. **Gaze over time.** The look term uses a short exponential average of the look ray's hit (about 6 ticks), not
   the instant ray. Players look at the next block before they jump; a glance elsewhere does not flip the target.
4. **Course line.** A ring of the last 8 platforms landed on. The platform just left and the ones before it are
   penalised; candidates that continue the line of the recent jump vectors keep the existing bonus.
5. **Not a dead end.** A candidate from which no further jump target is feasible (one more roll per finalist,
   only when two finalists are close) loses to one that continues.
6. **Lock.** At TAKEOFF the target is frozen for the commitment. It changes only by the two trims of section 4.1
   (look sweep before the apex, forward released), at most once.
7. **Deferred choice.** When two finalists are close and one takeoff serves both, the commitment locks the
   takeoff only, and the target is decided in the air from the first strafe key or look movement, by air tick 3
   at the latest (then the nearer one).
8. **Thresholds by mode.** GUIDE commits at confidence 0.5 (as now); CARRY at 0.35, because the HUD shows the
   lock and looking elsewhere changes it; NUDGE unchanged.

The locked target is drawn (section 10), so a wrong pick is visible before the takeoff and a look fixes it.

## 10. UX

### 10.1 Settings (Movement Assist)

Top of the module, in this order:

- **Control** `control_mode`: Corrections / Co-pilot / Takeover. Default Co-pilot. One line of help text each.
- **Hold to take over** key (unbound): while held, the current or next jump runs in Takeover.
- **Landing hold** `landing_hold` (on), **Release hold on** `hold_release` (Fresh input / Timer), limit ticks.
- **Chain jumps** `chain_jumps`: With the jump key (default) / Automatic / Off.
- **View assist** `yaw_assist`: By legit level (default) / Off / Turn gain only / Co-steer. Maximum degrees per
  jump. This is the one knob LegitPresets drives (`knob("movement_assist", "yaw_assist_level", ...)`).
- **Override sensitivity**: Low / Normal / High.
- **Show the lock** `commit_hud` (on).

Group "Techniques" (advanced, all on by default where the legit level allows): corner, neo, headhitter timing,
headhitter momentum, 45-degree momentum, shaped landings, ladder and vine catch, water landings.

Existing settings stay and keep their meaning in Corrections mode. In the other two modes `authorship_budget`,
`stick_landing`, `tick_phase` and `takeoff_shaping` are shown greyed with "handled by the control mode".
Migration: a profile without `control_mode` reads Co-pilot; a profile that had `stick_landing` off reads
`landing_hold` off.

### 10.2 HUD and overlay (data contract for the HUD designer)

Nothing is drawn in IDLE. Clean-Screen hides all of it. The module publishes one immutable-per-tick view,
`CommitView`, primitives only: phase, mode, authority 0..1, target top box (6 floats), predicted touchdown point,
stop point, takeoff point, flags (narrow, chain, latched keys bitmask, override kind, plan lost), ideal takeoff
yaw and whether the yaw is the user's to turn (LEGIT cue).

Suggested presentation in the sci-fi thin-line language chosen for the suite:

- **Lock bracket** (world): four corner brackets around the real top of the locked target (so a fence post gets
  a small bracket at 1.5 high, which also tells the user the landing is narrow). Thin cyan lines. Appears in
  ARMED, tightens at TAKEOFF, opens and fades through RELEASE.
- **Arc** (world): the planned flight from the takeoff point to the touchdown point, drawn with the existing
  `GhostLine` geometry, plus a pip at the stop point.
- **Authority arc** (screen, near the crosshair): a thin partial ring whose filled length is the authority.
  It drains during RELEASE: the visible hand-back. White when the control is the user's.
- **Override**: the bracket and ring flash amber for 6 ticks on an axis or hard override; red tint when the
  plan is lost.
- **LEGIT turn cue**: a small caret on the ring at the side to turn toward, sized by the yaw error. No camera
  motion.
- **Status line** (the one line, HUD rule 6): "Parkour: locked", "Parkour: hold (release W to move)",
  "Parkour: chain", "Parkour: yours". Constant strings.

All world lines are batched in `RenderLevelStageEvent` from preallocated arrays; no chunk mesh work; the screen
elements are a handful of quads. Under the autopilot the same view is published and the HUD shows only the
status line unless `commit_hud` is on.

### 10.3 What the user should feel

- A jump that was fine: nothing happened, nothing was drawn except the bracket.
- A jump that was off: the keys they were not using moved, the view eased a few degrees, they landed, and the
  body stayed put on the fence until they pressed something.
- They changed their mind mid-air: they pressed a key and it was theirs.
- They want it to just do it: Takeover, or the hold-to-take-over key.

## 11. Verification

- **Unit, engine-free** (JUnit 5, hand-built shape grids): every replica addition; `SupportRegion`;
  `OverrideClassifier` (table-driven: each row of section 4.1); `JumpCommit` transitions; the landing hold
  (a scripted "holds W through the landing" user on a fence post, wall and pane never leaves the top; a fresh
  press leaves at once; no timer path releases a latched key); co-steer math (rate, acceleration, no overshoot,
  total allowance, hard 450 cap, zero on LEGIT, hand motion passes through unchanged).
- **Benchmark**: `ParkourCourse` gains landing kinds (full, fence, wall, pane, bottom slab, ladder, water),
  corner blocks, neo walls, exact ceilings and chain flags, and a second list `EDGE_CASES`: corner onto a block,
  corner onto a fence, 2-gap onto a fence post, 3-gap onto a wall, three fence posts in a chain, 1-gap onto a
  pane, neo 1/2/3, headhitter 3-gap, slab up, ladder catch, water drop, diagonal gap. `ParkourBenchmarkTest`
  runs each with a seeded "sloppy player" (holds W through the landing, yaw error up to 8 degrees, jump timing
  off by up to 2 ticks; 200 seeds):

  | Mode, BALANCED | Target |
  |---|---|
  | NUDGE | Zero walk-offs after any landing that was made |
  | GUIDE | At least 95% clear rate on shaped-landing and corner segments, zero walk-offs |
  | CARRY | At least 99% on everything the feasibility table marks possible |

  LEGIT targets are recorded, not asserted, in the first pass (keys-only corner jumps set the baseline).
- **Allocation and budget tests** for every per-tick path: `JumpCommit`, the flight controller, the technique
  planner, `LevelShapes` steady state, `SharedInputOverlay`, `YawCoSteer`, `CommitView` publishing. Planner
  budget: at most 1,200 simulated ticks per client tick for the commitment on top of the existing 1,800 bound,
  both asserted; candidates spread across ticks.
- **In-game parity** (devtest, run by the owner or the integration step, not by build agents):
  `ParkourScenario` records the real body per tick on the edge-case course with scripted inputs and compares it
  with the replica fed the same inputs; maximum position error per flight under 0.001 block, per landing class.
  Any class that fails parity is switched to UNMODELLED until fixed.
- **Hands-on checklist** added to `docs/state/playtest-checklist.md`: the fence walk-off case first.

## 12. Build plan

Order matters for the first four; the rest can run in parallel after them. Tiers: opus = cross-cutting or
physics-exact, sonnet = contained, haiku = mechanical.

| # | Item | Tier | Depends on |
|---|---|---|---|
| 1 | Shared control grant and manual facts (core contract) | opus | |
| 2 | Yaw co-steer (core) | opus | 1 |
| 3 | Exact collision shapes: `LevelShapes`, shape-aware `MovementPhysics`, `SupportRegion` | opus | |
| 4 | Landing hold: sneak guard, stale-key latch, hold release (works in all modes, fixes the walk-off) | opus | 1, 3 |
| 5 | Replica additions: step-up, sneak, jump delay, sprint loss, surfaces | opus | 3 |
| 6 | `JumpCommit` state machine and `OverrideClassifier` | opus | 1, 4 |
| 7 | Flight controller (closed form, arrival shaping, minimum intervention) | opus | 3, 6 |
| 8 | Technique library: takeoff-tick search, corner, neo, headhitter, 45-degree | opus | 5, 7 |
| 9 | Intent estimator v2 (real tops, simulated reach, gaze average, course ring, lock, deferred choice) | opus | 3 |
| 10 | Momentum chains | sonnet | 7, 9 |
| 11 | Climbables and water landings | sonnet | 5, 6 |
| 12 | Module wiring, settings, legit knob, migration, autopilot hook | sonnet | 6 |
| 13 | `CommitView`, world overlay and HUD elements | sonnet | 6 |
| 14 | Course edge cases and benchmark targets | sonnet | 3 |
| 15 | Devtest parity run and playtest checklist | sonnet | 5, 14 |
| 16 | Lang entries, notes file, roadmap | haiku | 12 |

Item 4 alone, on top of 1 and 3, fixes the reported walk-off and can ship first. Items 6 to 8 are the
"in between" control model. Items 9 to 11 are what makes it good on a real course.

## 13. Open decisions

- **D1** Co-steer at BALANCED (camera eases with a still mouse during a committed jump) or only from RELAXED.
  The design assumes BALANCED.
- **D2** Default of `chain_jumps` in Co-pilot: with the jump key (assumed) or automatic.
- **D3** Whether the hold-to-take-over key should ship bound (for example to a mouse side button).
- **D4** CARRY commits on forward held toward a target. An alternative is to require the jump key once per
  chain as the "go".

## 14. Out of scope

Water takeoffs and swimming, elytra, boats, pearls, blocks placed mid-jump (the existing block assist keeps its
own lease and runs after an ABORT as today), server-side lag compensation, and any rotation path outside the
core.
