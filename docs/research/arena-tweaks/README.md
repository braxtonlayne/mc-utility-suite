# arena-tweaks (local bot arena only)

A one-mixin Fabric companion mod for the Fabric bot arena server (`tools/bot-arena/server-fabric/mods/`). It is not
part of mc-utility-suite and never ships with it.

- **No retreat** (owner, 2026-11-23: "the other bot flees too much, don't let the opponent flee"): Vulgar's PvP Bot
  enters RETREAT at health <= `gappleThreshold` (16 on HARD) and then only runs away. The arena kit has no golden apples, so the
  threshold has no other use; the mixin zeroes it (and `notchAppleThreshold`) after every `applyDifficulty`.

Build (no Gradle, no downloads; the bot's classes are not obfuscated, so no refmap):
`javac -proc:none --release 21 -cp <server-fabric>/libraries/net/fabricmc/sponge-mixin/0.17.5+mixin.0.8.7/sponge-mixin-0.17.5+mixin.0.8.7.jar -d out NoRetreatMixin.java`,
copy `fabric.mod.json` and `arenatweaks.mixins.json` into `out/`, then `jar cf arenatweaks-1.0.0.jar -C out .`.
