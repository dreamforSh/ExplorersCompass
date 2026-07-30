# Explorer's Compass

<!--
  Distribution badges. This fork is not published to CurseForge or Modrinth yet, so the lines below
  are kept commented out rather than rendering as broken images. Once it is published, uncomment
  them and replace the placeholders:

    <CF_PROJECT_ID>  the numeric CurseForge project id (a slug does NOT work here)
    <CF_SLUG>        the CurseForge page slug, used for the link only
    <MR_SLUG>        the Modrinth slug or project id (either works, in both the badge and the link)

  For reference, the upstream project's values are 491794, explorers-compass and explorers-compass.

[![Available on CurseForge](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/curseforge_vector.svg)](https://www.curseforge.com/minecraft/mc-mods/<CF_SLUG>)
[![Available on Modrinth](https://cdn.jsdelivr.net/npm/@intergrav/devins-badges@3/assets/cozy/available/modrinth_vector.svg)](https://modrinth.com/mod/<MR_SLUG>)

[![CurseForge versions](https://badges.moddingx.org/curseforge/versions/<CF_PROJECT_ID>)](https://www.curseforge.com/minecraft/mc-mods/<CF_SLUG>)
[![CurseForge downloads](https://badges.moddingx.org/curseforge/downloads/<CF_PROJECT_ID>)](https://www.curseforge.com/minecraft/mc-mods/<CF_SLUG>)
[![Modrinth versions](https://badges.moddingx.org/modrinth/versions/<MR_SLUG>)](https://modrinth.com/mod/<MR_SLUG>)
[![Modrinth downloads](https://badges.moddingx.org/modrinth/downloads/<MR_SLUG>)](https://modrinth.com/mod/<MR_SLUG>)
-->

Explorer's Compass is a Minecraft mod that lets you search for and locate **structures and biomes**
anywhere in the world, without leaving the chunk you are standing in.

This repository is a fork of [MattCzyr/ExplorersCompass](https://github.com/MattCzyr/ExplorersCompass)
that adds biome search, bookmarks, location sharing, a heads-up direction strip and more. Structures
alone are what the upstream mod covers; biomes are its sister mod,
[Nature's Compass](https://github.com/MattCzyr/NaturesCompass).

## Features

**Searching**

- Locate any structure or biome the world can generate, in the dimension you are in.
- Select several at once (Ctrl+click) and the search answers with whichever is nearest.
- Search a whole group — every village, every ocean ruin — rather than naming one.
- Search past what you already found for the next instance of it, and the one after that.
- Filter the list by mod, by dimension, or with a small query language; sort by name, group,
  dimension or source mod.

**After a search**

- A heads-up panel says what the compass is doing: search radius and progress while it runs, then
  the coordinates, the distance and the compass point once it lands.
- A direction strip across the top of the screen marks where the target lies against the horizon,
  turns green when you are facing it, and points the way to turn when it is off screen.
- The compass needle itself points at the located place, as a compass should.
- Every located place is remembered. Point the compass back at one, share it in chat with
  click-to-copy coordinates, or travel to it where the server allows that.

**Integration**

- Waypoints in [Xaero's Minimap](https://www.curseforge.com/minecraft/mc-mods/xaeros-minimap) are
  created for each located place, when that mod is installed.
- Resource packs can give the compass a different look per structure through custom model data.
- Data packs and modpacks can define structure groups of their own.
- Translated into English, German, Spanish, Japanese, Russian, Simplified Chinese and
  Traditional Chinese.


## Configuration

Server-side options live in `config/explorerscompass-common.toml`, client-side ones in
`config/explorerscompass-client.toml`.

Things worth knowing about:

- `maxRadius`, `maxSamples`, `maxBiomeSamples` — how hard a search is allowed to look before giving
  up. Raising them finds more distant targets at a higher cost.
- `maxSearchTimePerTick` — the slice of each server tick that searching may consume, shared by every
  search running at once. Lower keeps the server responsive; higher finishes sooner.
- `asyncBiomeSearch` — runs biome searches off the server thread, shared out over as many threads as
  searching is allowed, which finishes them several times sooner and costs the server nothing. Safe
  because which biome generates somewhere follows from the seed and the generator's noise alone. Turn
  it off if a biome-source mod turns out not to be thread-safe.
- `asyncStructureSearch` — the same for structure searches, also shared out over threads, by working
  out where a structure would generate the way world generation decides it rather than asking chunk
  storage about every location looked at. That question is almost all of what a structure search costs, and answering it this way
  reads no part of the world. The location the search settles on is still checked against chunk
  storage before the compass points at it; where the two can differ is ground generated under
  settings that have since changed, and there the compass answers with where a structure would
  generate now. Turn it off to have every location answered by chunk storage as before.
- `structureBlacklist` / `biomeBlacklist` — what the compass will not show or search for. `*` matches
  any number of characters and `?` matches one, so `minecraft:*village*` works.
- `allowTeleport`, `allowSharing`, and their cooldowns — what players are allowed to do with a result.
- `showDirectionBar`, `directionBarWidth`, `directionBarSpan` — the horizon strip. Pair a wide strip
  with a large span to have the whole horizon on screen at once.
- `overlayBackground`, `guiHeaderBackground`, `guiSidebarBackground`, `guiStatusBarBackground` — each
  panel can be filled in or left outlined and see-through on its own.

## Credits

Explorer's Compass was created by **ChaosTheDude**
([MattCzyr/ExplorersCompass](https://github.com/MattCzyr/ExplorersCompass)). This repository builds
on that work.

## License

This mod is available under the
[Creative Commons Attribution-NonCommercial-ShareAlike 4.0 International License](https://creativecommons.org/licenses/by-nc-sa/4.0/legalcode).
