# Fable decisions, 2026-11-22

The owner delegated these calls ("send questions to fable"). Read for this: the owner's memory notes, the evening
checkpoint, the bot-arena research (first results and second pass), the "Parkour lanes" section of the open-items
run note, and the STEM UI design. Nothing was built, run, downloaded or committed. Two licence texts and two file
listings were read online (section 3).

Already decided by the owner, not reopened: Corrections only promises "no walk-off after a landing it made". The
Corrections lanes keep that check.

## 1. Stacked-gear pass bar

**Decision: a mix.** The pass bar moves to a new kit, "outgeared". Stacked stays in every run as a stress case
that cannot pass or fail. The saturation effect at round start goes, for every kit.

**The outgeared kit (this is the 8/10 bar):**

| Side | Weapon | Armour | Shield |
|---|---|---|---|
| Bot | diamond sword, Sharpness II | full diamond | yes |
| Ours | iron sword, no enchantment | full iron, no enchantment | no |

The bot keeps everything it has in stacked. We sit one full tier down in weapon and armour, with no shield and no
enchantments.

**Why.**
- Stacked is not a skill test. One bot hit is worth six of ours, and we would need about five hits for each one
  taken. No player wins that 8 times in 10 on a flat arena.
- Outgeared is still a real handicap. By vanilla armour maths our crit does 2.61 into diamond (1.56 plain). Their
  plain hit does 4.85 into iron, and their crit 7.68. That is about 3 to 1 per hit against us. We need about 8
  crits and can take 3 to 4 hits, so we must out-trade the bot by 2 or 3 to 1. Stacked play managed 2.3 to 1. So
  it can be won, but only by timing, spacing and crits. That is what "beats a stronger-geared opponent like a
  higher-skill player would" means.
- The saturation effect gives each side about 13 HP of fast healing. Flat healing is worth more to the side that
  loses less per hit, so it pays the armoured side. Vanilla respawn state is the neutral start.

**Rules that keep the bar honest.**
- Pass = at least 16 wins in 20 rounds on one build, fair server (Grim off). Results swing a lot between runs
  (fists went 1, 9, then 5 of 10), so 10 rounds is too few to trust. A timeout counts as a non-win.
- No pass is claimed while the wall freeze is open. Frozen rounds are named in the result.
- Mixed stays a pass kit (it is also a gear disadvantage, now 10/10). Fists stays as the even baseline.
- Stacked reports wins, the bot's lowest health and the hit-trade ratio. The goal there is a rising trend.
- The kit is not softened again. If outgeared fails, the fix is combat work, not a new kit.

**Next action.**
1. In `docs/research/bot-arena-arena.js`: add the `outgeared` kit. Replace the saturation effect with food 20 and
   vanilla respawn saturation for all kits. Keep instant health and natural regeneration.
2. Re-run fists, mixed, outgeared and stacked on the new start state, 20 rounds each for mixed and outgeared.
3. Before that run: trace the wall freeze, then try the timed approach hop. Both are already open items.
4. Update the bot-benchmark-arena memory note with the kit and the 16 of 20 rule.

## 2. First-built deep corner lanes

**Decision: keep them report-only. No separate trajectory optimiser. They become the test case for the Course
memory jump solver (proposal 2).**

**Why.** The search found only frame-perfect lines that slide along the column, with margins under 0.2. A red
check for that would be noise. The owner's complaint was corner jumps "behind the corner of a block", and that is
the rebuilt 1-deep lanes, which are real checks and land. The jump solver is already planned and already lists
these two lanes as its test, so a second tool would be duplicate work.

**Target, so the note does not stay a note forever.** A deep lane turns back into a check, in Takeover only, when
the solver finds a line with at least 0.08 margin and the client lands it in all four tick phases, 5 runs in a
row. Co-pilot's deep lanes stay notes: a 25 degree turn limit is not meant to fly these.

**Next action.** None now. When proposal 2 is built, its run note states for each deep lane: line found or not,
margin, and client landings out of 5. The lane notes keep printing what the assist wrote on the run-up.

## 3. Fonts for the STEM HUD

**Recommendation: yes to both, no swap. The owner must approve the download.**

**Why.** Rajdhani is squared and narrow, which fits a modern HUD and keeps the status line short. JetBrains Mono
has tabular digits, so numbers do not jitter. The code already looks for these three file names, and the pipeline
falls back to the vanilla font if a file is missing, so the risk is low.

**Licences, checked today in the upstream OFL.txt files.**
- Rajdhani: "Copyright (c) 2014, Indian Type Foundry", SIL Open Font License 1.1. No Reserved Font Name is listed.
- JetBrains Mono: "Copyright 2020 The JetBrains Mono Project Authors", SIL Open Font License 1.1. No Reserved Font
  Name is listed.
- Both allow bundling in the mod. The licence text must ship with the files. Still ship the TTFs unmodified.
- This means allowing OFL for font files only. The MIT/Apache/BSD rule for code does not change. That exception is
  the owner's to grant.

**Files for the owner to approve (about 1.05 MB in total, plus two 4 KB licence texts):**

| Download | Save as | Size | Source |
|---|---|---|---|
| `Rajdhani-Medium.ttf` | `rajdhani-medium.ttf` | 384 KB | https://github.com/google/fonts/tree/main/ofl/rajdhani |
| `Rajdhani-SemiBold.ttf` | `rajdhani-semibold.ttf` | 390 KB | same folder |
| `JetBrainsMono-Regular.ttf` | `jetbrainsmono-regular.ttf` | 270 KB | https://github.com/JetBrains/JetBrainsMono/tree/master/fonts/ttf |
| `OFL.txt` (Rajdhani) | `LICENSE-Rajdhani.txt` | 4 KB | the Rajdhani folder above |
| `OFL.txt` (JetBrains Mono) | `LICENSE-JetBrainsMono.txt` | about 4 KB | https://github.com/JetBrains/JetBrainsMono/blob/master/OFL.txt |

Notes:
- The design guessed 100 to 300 KB per file. Rajdhani is bigger because it carries Devanagari. About 1 MB in the
  jar is fine.
- Take the single files, not the JetBrains release zip. The zip is much larger and not needed.
- The docs also name `github.com/itfoundry/rajdhani`. I did not check that repo. Use the Google Fonts folder.

**Next action.** Owner says yes or no. On yes: fetch the five files into
`src/main/resources/assets/utilitysuite/font/`, add `THIRD-PARTY-NOTICES.md`, run the client, and tune `SIZES`,
`OVERSAMPLE` and `SHIFT_Y` at GUI scale 1 to 4. If Rajdhani reads too thin at small sizes in game, the first thing
to try is SemiBold for the `UI` role too, not a new family.

## 4. Everything else marked as waiting on the owner

### Decided here (design calls)

- **Next volley.** Order:
  1. The held-back press walk-off on corner-fence-2gap in Co-pilot. It is a bug the owner can hit in play, so it
     goes before any feature.
  2. Combat: the wall freeze, then the timed approach hop, then the outgeared run from section 1.
  3. In parallel: Course memory with the jump solver (proposal 2) and the Debrief card (proposal 5, with the fight
     log first). They touch different packages.
  4. After those: Pick the ground (6) and Knockback recovery (7). Both help the outgeared fight directly.
  The Hand-over key (proposal 1) is already merged.
- **Co-pilot eases W on the ground while ARMED.** Keep it on by default. The owner asked for an assist that
  visibly carries bad jumps, and this is how Co-pilot lands the pane lane. W is eased, never reversed, and
  `approach_control` stays as the off switch. Next action: change design table 4 to match the code. The owner's
  feel check below can still overturn it.
- **HUD wording.** Keep what is built: `STEM` on the HUD during a takeover, `AUTOPILOT` for the menu toggle.
- **Armed marker.** Stays invisible by default (HUD rule 6: nothing renders when idle). The opt-in setting stays.
- **Effects default.** `Full`. The owner wants a premium modern look out of the box. `Subtle` stays as a setting.
  Full must stay inside the frame budget, or the default drops to Subtle.
- **The Read.** Keep the design default: folded into the target reticle. The separate ring stays as a setting.
- **`LeaseEndReason` for the hand-over key.** Add a `HAND_OVER_RELEASED` value when the Debrief card is built,
  since the card reports how a fight ended. Not before: `core.api` changes only with a user for them.
- **Second, closed-licence opponent (PvB).** Not now. The owner's bar names the strongest open-source bot, and that
  is the nxg bot. Revisit only after outgeared passes. Using it at all would need the owner's yes (below).
- **Stale ROADMAP line.** "Movement Assist standing down while a job holds MOVEMENT" looks overtaken: the
  checkpoint says the assist writes under automation leases again (benchmark 19/19). Next action: the orchestrator
  re-reads that line and ticks or rewords it. I did not check the code.

### Owner-only (needs their hands or their approval)

1. Approve or refuse the font download and the OFL-for-fonts exception (section 3).
2. Feel the corner-cut camera turn in game: up to 25 degrees in Co-pilot, 90 in Takeover.
3. Feel Co-pilot easing W on the run-up. Say if it feels like the key goes dead.
4. Play the hand-over key by hand: bind it, hold it through a jump and a fight, let go. It has only been tested by
   scripts.
5. The in-game checklist in the open-items run note (42 steps). Startup and mixin audit first, then the lanes.
6. PvB or any other closed-licence bot, if it ever comes up: licence and download approval.
7. The nxg bot's licence is unclear (MIT badge, GPL-3.0 on npm, no licence file). It stays local and private. None
   of its code goes into the repo. Say so if that should change.
