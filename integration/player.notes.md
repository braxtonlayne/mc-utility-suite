# Package player: integration notes

## What landed (all registered in `tools/ToolsBootstrap.initPlayerTools`, owned file)

| Module id | Class | Default | Notes |
|---|---|---|---|
| `dot_commands` | `tools/commands/DotCommands` | ON | Only acts on its own prefix (default `.`). Every prefixed line is cancelled in `ClientChatEvent` (HIGHEST priority) and answered locally; typos never reach the server. `.t <id or name>`, `.goal <text>` (parse + submit, same path as the menu goal box), `.stop` (`arbiter.stopAll`), `.list`, `.help`. A blank prefix falls back to `.`. |
| `offhand_manager` | `tools/player/OffhandManager` | off | Rules in `OffhandRules` (totem at/below health threshold, totem while gliding, shield vs ranged mob within 24 blocks or `danger().has(PROJECTILE)`, preferred item). One SWAP click (button 40) under a HOTBAR lease after a humanizer delay, cooldown 10 ticks. Never with a screen open or while using an item. While OFF it still acts when `autopilot.allows(Reflex.OFFHAND)`, totem rules only, with `mayAct` / `noteActed` / `noteDone`. Combat package: skip the own totem keeper while `offhand_manager` is enabled (agreed in the brief). |
| `auto_fish` | `tools/player/AutoFish` | off | Bite = splash sound (`PlaySoundEvent`, `entity.fishing_bobber.splash` within 2 blocks of the own hook) or the hook's client-side dip (vy < -0.2 after 20 ticks in water, which the client applies when the synced biting flag turns on). Use-key taps through `IInputSmoother.scheduleKeyTap` under an IDLE WORLD_INTERACT lease; reel after `reactionTicks(5,3,12)`, recast after `reactionTicks(14,9,30)`. Stops on rod durability <= setting (3), full inventory, rod not in hand, failed cast, or manual override / Stop-All. No mixin. |
| `auto_respawn` | `tools/player/AutoRespawn` | off | `ScreenEvent.Opening` for `DeathScreen`: one local chat line with the death coordinates, then `player.respawn()` (what the screen's own button calls) after ~30 ticks (>= 22, the screen enables its buttons at 20). Never on hardcore. Posts `SuiteEvent.PlayerDied` only when neither `waypoints` nor `survival_autopilot` is enabled (both already post it), so no duplicate death waypoint. |
| `auto_reconnect` | `tools/player/AutoReconnect` | off | `DisconnectedScreen` (Init.Post): reason read from the screen's widgets; `ReconnectPolicy` refuses kick/ban/whitelist/outdated/duplicate-login reasons, manual disconnects (logout while `PauseScreen` is open) and attempts beyond the cap (5); backoff 5 s doubling, max 300 s. Countdown drawn in Render.Post, "Cancel auto-reconnect" button added to the screen. Reconnects with `ConnectScreen.startConnecting(..., lastServerData, false, null)`. Resume: the active job's `goalLabel` at logout is re-parsed and submitted 100 ticks after LoggingIn when no job is active. |
| key `key.utilitysuite.quick_pearl` | `tools/player/QuickPearl` | unbound | Hotbar pearl: select under lease, tap use, reselect the previous slot. Inventory pearl: SWAP into the selected slot, tap use, swap back. No rotation (throws where the user looks). COMBAT priority lease HOTBAR+WORLD_INTERACT. Refuses on pearl cooldown. |

## For the integrator
- Merge `integration/player.lang.json` into `en_us.json`.
- No mixins in this package (no `player.mixins.txt`; nothing to add to `utilitysuite.mixins.json`).
- No core.api changes.
- Legit preset: all of these are legit (no rotation, all input through the core). If the preset lists modules, `auto_fish`, `auto_reconnect` and `auto_respawn` may want a note that some servers forbid AFK fishing / auto-reconnect.

## Unverified in game (compile + unit tests only)
- The splash-sound bite path and the dip fallback (`AutoFish.DIP_SPEED`), especially with the sound volume at 0 (whether `PlaySoundEvent` still fires).
- That the arbiter's manual-input detection does not count our own scheduled USE taps (it should, per the core contract).
- `ClientPlayerNetworkEvent.LoggingOut` firing while `PauseScreen` is still the current screen on a manual disconnect (the manual-disconnect heuristic). A kick also shows `DisconnectedScreen`, which the reason filter refuses.
- Resume relies on `IGoalEngine.parse(goalLabel)` accepting a job's label ("Gather 32 minecraft:diamond"); when it does not, the module prints "Could not resume" instead. A cleaner contract would be an `IGoalEngine` accessor for the active job's original `GoalRequest`.

## Known limitations / not done
- Quick pearl: if its lease is preempted between the swap-in and the swap-back, the moved stack stays in the hotbar slot (the pearl stack is not moved back).
- Auto-Fish stays stopped after a stop reason until it is toggled again (by design, so it never restarts behind the user's back).
- Offhand ranged-threat scan uses `RangedAttackMob` (skeletons, strays, pillagers, witches, snow golems...); ghasts and blazes are not included except through the danger model's PROJECTILE hazard.
