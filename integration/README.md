# Integration fragments

Parallel packages do not edit the shared resource files. Each package writes its additions here instead:

- `<package-key>.lang.json`: a flat JSON object of new `en_us.json` entries.
- `<package-key>.mixins.txt`: one new client mixin class per line, relative to `com.utilitysuite.mixin` (e.g. `player.FishingHookBiteMixin`).
- `<package-key>.notes.md`: wiring the integrator must do outside the package's owned paths (a bootstrap line, a legit-preset entry), and anything left unfinished.

The integrator merges these into `src/main/resources` and deletes this folder.
