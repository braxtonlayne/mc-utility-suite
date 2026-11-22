# Feature proposals, 2026-11-22

New features in the vein of the concept: a STEM-style body autopilot for the owner's own single-player and
whitelisted-server play. Written for the point where the current open items are done.

**Read-only review.** Nothing was built, run or tested for this note. Sources: the three design notes of 2026-11-22,
the four run notes of 2026-11-22, the arena research note, `docs/ROADMAP.md`, the ideas list of 2026-11-21, the
owner's memory notes, and the class list under `src/main/java/com/utilitysuite`. "Not built" below means I found no
class or setting for it by name search. I did not read every class body, so check each plug-in point before a build.

## What is left out, and why

- Anything already built: rank dial, Ghost Line, The Read, Fight Coach, black box, fall clutches, pearl intercept,
  escape path, route follower, technique library, co-steer, hold-to-take-over key for parkour.
- Anything already on the open lists: ranged opener, rod or snowball pulls, pillar escape, ladder clutch, multi-jump
  planner, momentum chains, fight log, offhand shield policy, splash healing, takeover voice lines, the hunter bot
  itself. These are open items, not new ideas. Some proposals below build on them and say so.
- Anything against the owner's rules: reach, packet tricks, through-wall hits, faster-than-cap rotations, anything
  another player could command, anything aimed at players on public servers.

## Proposals

Size: S is about one task, M is two or three, L is a volley of its own.

### 1. Hand-over key

- **What it does.** One key gives STEM the whole body while it is held: parkour in Takeover, combat on the threat in
  front of you, all reflexes armed. Let go and control comes back smoothly within a tick.
- **Builds on.** `ActionArbiter`, `Autopilot`, `KeybindManager`, the existing parkour take-over key in `TravelKeys`,
  `CombatAssistant`, `MomentumRelease`, `AutopilotHud` and `AuthorityIndicator`.
- **Size.** S to M.
- **Risk.** Anticheat: none new, because it only switches on things that already exist, at the current legit level.
  Feel: the hand-back must be clean in every subsystem at once; this is the main work. Performance: none.
- **Why it fits.** This is the film's "you have permission" moment. Today the takeover exists per module. The owner
  has to set three things to get the full effect.

### 2. Course memory with a background jump solver

- **What it does.** The mod remembers each jump you try on a course: where from, where to, landed or not. For jumps
  the live search cannot solve in time, a background solver works out a line and keeps it. The next attempt uses it.
- **Builds on.** `IntentEstimator` (course ring), `TechniqueLibrary` and `TechniqueSource`, `MovementPhysics`,
  `CoSteerModel`, `FlightScript`, `WorldExecutor`, `WorldFiles`, `GhostLine`.
- **Size.** M to L.
- **Risk.** Anticheat: none new; scripts stay inside the level's key and co-steer limits. Feel: a stored line can
  be stale if blocks change, so every script is re-checked against the world before use. Performance: the search is
  heavy, so it runs off-thread on a snapshot, one jump at a time.
- **Why it fits.** "Turns a bad player into a pro" on real courses, not only on test lanes. It also answers two open
  items: the deep corner lanes need more search than a tick allows, and one lane depends on the run-up's tick phase.

### 3. Follow the leader

- **What it does.** On a course with friends, the mod watches where roster allies take off and land. It uses their
  path as the course order, so the target pick is right on a course you have never run.
- **Builds on.** `AllyRoster`, `IntentEstimator` (target scoring), `WorldRadarModule` (already tracks other players),
  proposal 2 for storage.
- **Size.** S to M.
- **Risk.** Anticheat: none; it reads positions the client already has. Feel: low; it only adds a prior, and your
  look and keys still decide. Performance: one sample per ally on takeoff and touchdown, no per-tick scan.
- **Why it fits.** The owner's own words: "If me and some friends are doing parkour, I can't fail at it." It is
  strictly local: nobody else controls anything.

### 4. Training fade

- **What it does.** The mod tracks how much of each jump and each fight was yours and how much was the assist's.
  With "Training" on, it lowers its help in the areas where you are getting better and keeps it where you still
  miss.
- **Builds on.** `AuthorshipBudget` (already counts assisted ticks per jump), `OverrideClassifier`, `SkillEstimator`,
  `FightCoach`, the mode settings of Movement Assist.
- **Size.** M.
- **Risk.** Anticheat: none. Feel: help that changes by itself can feel random, so it moves slowly, shows its level,
  and is off by default. Performance: counters only.
- **Why it fits.** STEM makes the player better while it is on. This makes some of that stick when it is off.
  Pro-player defaults stay; this is an opt-in dial.

### 5. Debrief card

- **What it does.** After a fight, a death or a failed jump, a short card says what decided it, in up to three
  lines. Example: "No crits in 19 hits. Healed at 1 hit left. Lost control for 40 ticks."
- **Builds on.** `BlackBox`, the fight log (open item from the upgrade pass), arena telemetry fields, `CoachReadout`,
  `NoticeBoard`, `TimelineScreen`, `AuthorshipBudget`.
- **Size.** S to M once the fight log exists.
- **Risk.** Anticheat: none; display only. Feel: low; one toast, card on request, nothing in Clean-Screen.
  Performance: computed once when the fight ends.
- **Why it fits.** The owner tunes by feel and reports in a sentence. The card gives the reason behind the feel.
  It would also have caught the arena wall freeze in normal play, because it shows a lost lease or a long no-swing
  gap.

### 6. Pick the ground

- **What it does.** Before contact, the Combat Assistant chooses where the fight happens: high ground, a drop behind
  the opponent and not behind you, a doorway against a pack. It walks there while the threat closes in.
- **Builds on.** `ArenaField`, `ArenaQuery`, `ArenaTactics` (they read the terrain once the fight is on),
  `EscapePlanner`, `RouteRunnerCore`, `GearRead`, `ThreatFightStage`.
- **Size.** M.
- **Risk.** Anticheat: none; it is walking. Feel: medium; walking away from a threat can look like fleeing, so it
  only moves when the spot is close and the gain is clear. Performance: one budgeted field query when a threat is
  first seen, not per tick.
- **Why it fits.** A stronger player wins by position before the first hit. The arena note shows a stone sword
  cannot out-trade full diamond, so position and terrain are the honest way to win from a disadvantage. It will not
  move the arena score by itself: the arena floor is flat.

### 7. Knockback recovery

- **What it does.** When a hit sends you into the air, the parkour brain picks the landing and steers to it, even
  in the middle of a fight. You land on the ledge, not in the ravine.
- **Builds on.** `JumpSearch`, `FlightController`, `FallRiskEstimator`, `ComboEscapeStage`, `ISharedControl`, the
  velocity packet hook that already exists for other modules.
- **Size.** M.
- **Risk.** Anticheat: low; keys only, normal air control. Feel: it must not fight the combat solver's own air
  keys, so one of them owns the flight and the run note states which. Performance: one bounded search per knockback
  event.
- **Why it fits.** Movement and combat are one body in the film. Today Movement Assist writes nothing while another
  module holds the movement lease, so the best movement code is idle in a fight. **To check first:** how much air
  control the combat stages already apply after a hit.

### 8. Legit self-check

- **What it does.** A meter in PacketLab scores your own outgoing play for the tells an anticheat looks for:
  turn speed, turn steps that no real mouse makes, click rhythm that is too even. It warns when a setting pushes a
  number out of the human range.
- **Builds on.** `PacketLab` (`PacketRingBuffer`, `RateRing`, `TimelineEvents`), `RotationLimits`, `ServerPose`,
  `Humanizer`, `LegitPresets`.
- **Size.** S to M.
- **Risk.** Anticheat: lowers it. Feel: none. Performance: a few counters on packets that are already observed.
- **Why it fits.** "Legit by default" is a rule. Grim gave zero flags in 32 arena rounds, which is good evidence,
  but only for Grim and only in the arena. One thing to look at: I found no mouse-sensitivity step in `core/input`
  by name search, so smoothed turns may not land on the steps a real mouse produces. Some anticheats check that.
  Not confirmed; the meter would show it.

### 9. Sparring ladder

- **What it does.** The dev arena becomes a practice mode the owner can start from the menu on the local test
  server: pick a kit, fight the bot yourself with the coach on, or let STEM fight. It keeps a win-rate history per
  kit and build.
- **Builds on.** `docs/research/bot-arena-arena.js`, the `runArena` client and `ArenaClient` telemetry,
  `ServerAllowList`, `FightCoach`, proposal 5.
- **Size.** M.
- **Risk.** Anticheat: none; local server only. Feel: none. Performance: none in the mod. Cost: it needs the server
  and the bot running, so it is a session activity, not a background job.
- **Why it fits.** The owner asked for an objective bar. This turns it into a number that is tracked over time, and
  every combat change gets a before and after. It is local and single-user. Open question from the arena note
  still applies: the stacked kit may need redefining before it can be a pass bar.

### 10. Encounter playbooks

- **What it does.** STEM knows the big set-piece fights: Ender Dragon, Wither, raids, trial chambers. Each has a
  plan: what to bring, where to stand, what to hit first, when to back off.
- **Builds on.** `MobPlaybook` (per-mob rows today), `ThreatFightStage`, `HealWindowStage`, `GoalEngine` for the
  preparation list, the ranged opener (open item) for crystals.
- **Size.** L. One encounter at a time; trial chambers first, because they are melee and close range.
- **Risk.** Anticheat: none in single-player. Feel: high; a scripted fight that goes wrong looks worse than no
  script, so each playbook needs a hands-on pass. Performance: low; a state machine per encounter.
- **Why it fits.** Single-player is where the owner plays most. These are the fights where a body that "just
  knows" is most visible. The Warden stays on the never-engage list.

### 11. Callouts

- **What it does.** Short sounds and, if wanted, short spoken lines tell you what STEM sees and does: "behind",
  "shield down", "taking over", "yours". Off by default.
- **Builds on.** The deferred takeover voice lines (clips from the local Zonos server), `NoticeBoard` kinds,
  `ThreatTracker`, `AuthorityState`. There is no audio in the mod today.
- **Size.** S for sounds, M with voice.
- **Risk.** Anticheat: none. Feel: high; a line that repeats gets old fast, so each has a cooldown and a priority,
  and there is a sounds-only level. Performance: none. The clips need the owner's ear before they ship.
- **Why it fits.** In the film STEM talks. A voice makes the handover readable without looking at the HUD. This
  extends an item that is already deferred; it is listed here for the callout rules, not the voice alone.

## Top 3 for the next volley

Picked for fit with the concept, for closing known gaps, and because they touch different packages and can be
built in parallel.

### First: Hand-over key (proposal 1)

1. Contract: add a hand-over state to `IAutopilot` in `core.api` (held or not, tick it started). Engine-free class
   `core/autopilot/HandOverState` with a unit test.
2. Key: one new id in `KeybindManager`, unbound by default like the parkour key. Hold to hand over. A setting adds
   double-tap to latch.
3. Movement: `MovementAssist` reads the core state where it reads its own take-over key today. Same thresholds as
   Takeover.
4. Combat: while held, the assistant engages the threat under the crosshair, or the one attacking you. Proactive
   picks stay limited to single-player and allow-listed servers, as now.
5. Reflexes: armed while held, even if the autopilot toggle is off.
6. Release: every lease taken under the key ends on the next tick with its own end reason. The camera lets go
   through `MomentumRelease`. Stop-All still beats the key.
7. HUD: reuse the takeover brackets and the authority indicator. One status line. No new overlay.
8. Limits: the key raises authority, never the legit level. Rotation caps and line-of-sight rules are untouched.
9. Tests: all leases gone one tick after release (real arbiter); no rotation step over the cap on engage or
   release; nothing allocated per tick while held.

### Second: Course memory with a background jump solver (proposal 2)

1. `travel/movement/course/CourseMemory`, engine-free. Key: packed takeoff cell and packed landing cell, in a
   fastutil primitive map. Value: attempts, landings, best margin, script id.
2. Recording is event-driven: the takeoff and touchdown notes `MovementAssist` already produces. No polling.
3. Solver: move the hill climb from `ParkourLaneReplicaTest` into `course/JumpSolver`. It runs on the world executor
   over an immutable copy of the voxels around the jump, with `MovementPhysics` and `CoSteerModel`, inside the
   current level's limits. It may also choose the run-up start, which covers the tick-phase case.
4. A result must pass the existing rules: touchdown at least 0.08 inside the support region, replay drift under
   0.08, co-steer total respected. It is published to the client thread as an immutable `FlightScript`.
5. `CourseMemory` is a `TechniqueSource` asked before the template families. A stored script is used only if the
   voxel hash of the jump still matches.
6. When to solve: after a miss on a remembered jump, or when the estimator marks a far target blocked or
   infeasible. One job at a time. Cancel on teleport or world change.
7. Storage: compact JSON per world or server through `WorldFiles`, debounced and off-thread. Cap the entry count and
   drop the oldest.
8. Display: Ghost Line draws the stored line when one exists. Nothing new on screen.
9. Tests: the two deep corner lanes in the replica (does the solver find a line, and how long does it take);
   an allocation test for the per-tick lookup; a stale-world test. Then a client run of the lanes.
10. Known limit: the deep-corner solutions found so far are frame-perfect and slide along the column. The solver
    may find them and the client may still drop them. Say so in the run note with the numbers.

### Third: Debrief card (proposal 5)

1. Needs the fight log from the open list. If it is not done, build it first; it is small.
2. `combat/coach/FightDebrief`, engine-free. Input: the fight's counters at its end. Output: up to three findings
   from a fixed rule table, as constant reason ids. No strings built in the fight.
3. Starter rules: crit share, hits taken while airborne, heal started at how many hits left, heals interrupted,
   swings into a shield, longest gap without a swing, ticks without the lease, how the fight ended.
4. Parkour gets the same from data that already exists: miss distance, press timing against the planned takeoff,
   assisted ticks from `AuthorshipBudget`, the reason a script was dropped.
5. Display: one toast through `INoticeBoard` ("Won in 14 s, 9 hits to 3"). The card opens from the Timeline screen
   or a key. Hidden in Clean-Screen.
6. Storage: the last 50 debriefs as JSON lines, written off-thread.
7. Tests: one per rule on recorded counters; an allocation test showing the fight path is unchanged.
8. Follow-up use: the same findings give the sparring ladder (proposal 9) its per-round notes.

## Next after those

In order: Pick the ground (6), Knockback recovery (7), Legit self-check (8), Follow the leader (3, small once 2
exists), Sparring ladder (9), Training fade (4), Callouts (11), Encounter playbooks (10).

## Rules every proposal keeps

- Modules talk through `core.api` and `Services`. Input only through leases, `IInputSmoother` and
  `IContainerClicker`.
- Manual input wins. Stop-All ends everything.
- Every rotation is smoothed and capped in the core. Looks stay along the movement direction by default.
- Line of sight by default. Faster-than-human behaviour stays behind the legit level or the allow list.
- Nothing allocated per tick or per frame. Scans are budgeted or run off-thread on snapshots. No polling where an
  event exists. Compact files, written off-thread and debounced.
- Nothing renders when idle. One status line while something runs.
- Each feature ships with a behaviour test and an allocation test, and a Spark check in its playtest list.
- Single user. No feature takes commands from another player.
