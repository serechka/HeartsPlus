<div align="center">

<img src="branding/icon-512.png" width="128" alt="HeartsPlus icon" />

# HeartsPlus

**See every player's health - as vanilla hearts floating above their heads.**

### [Download on Modrinth](https://modrinth.com/mod/heartsplus)

[![Modrinth](https://img.shields.io/badge/dynamic/json?color=1bd96a&label=modrinth&query=%24.title&url=https%3A%2F%2Fapi.modrinth.com%2Fv2%2Fproject%2Fheartsplus&logo=modrinth&style=for-the-badge)](https://modrinth.com/mod/heartsplus)
[![CI Build](https://img.shields.io/github/actions/workflow/status/serechka/HeartsPlus/build.yml?branch=main&logo=github&label=build&style=flat-square)](https://github.com/serechka/HeartsPlus/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

Fabric & NeoForge for **Minecraft 1.21 – 26.3**. Client-side only - join any
server, no setup, and know your ally's HP before the fight starts.

</div>

![HeartsPlus in action](screenshots/26.x-hearts.png)

## What you get

- **True vanilla look** - the exact hearts from the game's own assets: containers, halves, absorption, poisoned, withered and frozen
- **Damage flash and healing pop** - hearts blink and recover exactly like your own HUD, frame for frame
- **Hearts ride the name tag** - they sit above the name tag and follow it smoothly in every pose: walking, sneaking, swimming, crawling, elytra flight
- **See-through opacity** - an adjustable slider for hearts behind walls, from hidden to fully solid
- **Works with resource packs** - the texture source switches between built-in vanilla sprites and your current pack
- **Flexible config** - a tabbed [Cloth Config](https://modrinth.com/mod/cloth-config) screen with a follow-smoothing slider, per-pose heights, render distance up to 2048 blocks and a full mod off switch
- **11 languages** - English, Русский, Українська, 中文, Español, Português (BR), Deutsch, Français, 日本語, 한국어, Italiano

> Note: invisible players show their health only while wearing armour, and never through walls. A mod like this may be against the rules on some servers - check them before using it there.

![Invisible players: with and without armour](screenshots/invisible-comparison.png)

## Versions and branches

One branch per rendering era - each branch ships one jar covering its whole
range, on both loaders:

| Branch | Loader | Game versions |
|---|---|---|
| `main` | Fabric | 26.1 – 26.3 |
| `neoforge` | NeoForge | 26.1 – 26.3 |
| `1.21` | Fabric | 1.21.11 |
| `neoforge-1.21` | NeoForge | 1.21.11 |
| `1.21.9` | Fabric | 1.21.9 – 1.21.10 |
| `neoforge-1.21.9` | NeoForge | 1.21.9 – 1.21.10 |
| `1.21.6` | Fabric | 1.21.6 – 1.21.8 |
| `neoforge-1.21.6` | NeoForge | 1.21.6 – 1.21.8 |
| `1.21.4` | Fabric | 1.21.4 – 1.21.5 |
| `neoforge-1.21.4` | NeoForge | 1.21.4 – 1.21.5 |
| `1.21.2` | Fabric | 1.21.2 – 1.21.3 |
| `neoforge-1.21.2` | NeoForge | 1.21.2 – 1.21.3 |
| `1.21.0` | Fabric | 1.21 – 1.21.1 |
| `neoforge-1.21.0` | NeoForge | 1.21 – 1.21.1 |

Forge is not planned (replaced by NeoForge for 1.21+) - each rendering era
needs its own port.

## Install

1. Download the HeartsPlus file for your loader and game version from [Modrinth](https://modrinth.com/mod/heartsplus)
2. Also grab [Cloth Config API](https://modrinth.com/mod/cloth-config) for the same game version
3. Fabric builds additionally need [Fabric API](https://modrinth.com/mod/fabric-api); [Mod Menu](https://modrinth.com/mod/modmenu) adds a settings-screen entry
4. Drop the jars into `mods/` and you're done

> **Multiplayer note:** HeartsPlus is client-side only. Health data comes from what the server
> already syncs about visible players, so it works on vanilla servers without any server mod.

## Configuration

The settings screen runs on [Cloth Config](https://modrinth.com/mod/cloth-config)
(tabs, a reset for every option), or press **H** anywhere. Six tabs:

| Tab | Settings |
|---|---|
| **Behavior** | the master switch - when off, the mod does nothing at all, including background work |
| **Animation** | the vanilla damage flash and heal pop on/off |
| **Invisibility & Walls** | show invisible players (with a warning tooltip), see-through opacity from 0 (hidden behind walls) to 100 (fully solid) |
| **Position** | follow smoothing from instant to very smooth, a height offset for each pose (standing, sneaking, swimming, elytra) |
| **Distance** | render distance up to 2048 blocks |
| **Appearance** | scale, texture source (vanilla sprites or your current pack) |

Settings persist to `config/heartsplus.json` and apply instantly.

**Keybinds** (Controls → HeartsPlus): toggle is unbound by default; **H** opens settings.

## Building from source

Requires **JDK 25** (e.g. [Temurin](https://adoptium.net/)):

```bash
./gradlew build    # on the branch of the era you want to build
```

The mod jar appears in `build/libs/`. For a quick in-game check,
`./gradlew runClient` boots a dev client straight into a test world. In a solo
world your own hearts are hidden by default (like vanilla name tags) - press
**H**, enable **Show on Self**, then **F5** to see them above your head.

## License

[MIT](LICENSE) - free to use, modify and redistribute.
