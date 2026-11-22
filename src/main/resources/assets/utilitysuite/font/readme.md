# Suite font files (not shipped yet: owner approval needed before download)

The suite font pipeline (`com.utilitysuite.ui.holo.SuiteFonts`) switches on **by presence**: when all three
files below are in this folder at build time, the mod adds a hidden in-memory resource pack that defines the font
ids `utilitysuite:ui`, `utilitysuite:micro` and `utilitysuite:mono`, and HUD text drawn through `HoloKit` uses them.
While any file is missing nothing is defined, the vanilla font is used, and no error is logged on resource reload.
No font definition JSON belongs in this folder: the definitions are generated in code (`SuiteFonts.definitionJson`).

## Files the code looks for (exact names, lower case)

| File name in this folder      | Family         | Style / weight      | Used for (TextRole)              |
|-------------------------------|----------------|---------------------|----------------------------------|
| `rajdhani-medium.ttf`         | Rajdhani       | Medium (500)        | `UI`: body text and titles       |
| `rajdhani-semibold.ttf`       | Rajdhani       | SemiBold (600)      | `MICRO`: small caps captions     |
| `jetbrainsmono-regular.ttf`   | JetBrains Mono | Regular (400)       | `MONO`: numerals (tabular)       |

Rename the downloaded files to these names. Use the **unmodified** TTF files (no subsetting, no renaming of the font
inside the file: the SIL OFL forbids distributing a modified file under its Reserved Font Name).

## Licences and sources (to be verified by the owner before download)

Nothing below was checked online; every link and licence must be verified by the owner first.

- **Rajdhani**: SIL Open Font License 1.1, by Indian Type Foundry. Sources to verify: Google Fonts
  (`https://fonts.google.com/specimen/Rajdhani`) and the foundry's GitHub repository
  (`https://github.com/itfoundry/rajdhani`). To be verified by the owner before download.
- **JetBrains Mono**: SIL Open Font License 1.1, by JetBrains. Source to verify: the GitHub releases of
  `https://github.com/JetBrains/JetBrainsMono` (the release zip contains `fonts/ttf/JetBrainsMono-Regular.ttf`).
  To be verified by the owner before download.

## What has to ship with the files

- The licence text of each family, unmodified, beside the files: `license-rajdhani.txt` and
  `license-jetbrainsmono.txt` (the OFL.txt from each source, with its copyright line).
- An entry for each family in a top-level `THIRD-PARTY-NOTICES.md` (family, copyright, licence, source URL, the
  file names above).

## What makes the pipeline switch on

1. The three TTFs above are present in `src/main/resources/assets/utilitysuite/font/` (so they are in the mod jar or
   the dev resource folders). Checked once at start-up when the client resource packs are discovered.
2. After each resource reload the files are found through the resource manager, and the first HUD frame confirms the
   faces loaded (a failed load falls back to vanilla and logs one warning).
3. The appearance setting **Font** is **Suite** (default) and the game's **Force Unicode Font** option is off.

Characters a face does not have fall back to the vanilla glyphs (each definition references `minecraft:default`).
Size, oversampling and vertical shift per face are constants in `SuiteFonts` (`SIZES`, `OVERSAMPLE`, `SHIFT_Y`), to
be tuned in the playtest at GUI scale 1 to 4.
