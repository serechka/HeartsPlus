<div align="center">

<img src="branding/icon-512.png" width="128" alt="HeartsPlus icon" />

# HeartsPlus

**See every player's health — as vanilla hearts floating above their heads.**

### [Download on Modrinth](https://modrinth.com/mod/heartsplus)

[![Modrinth](https://img.shields.io/badge/dynamic/json?color=1bd96a&label=modrinth&query=%24.title&url=https%3A%2F%2Fapi.modrinth.com%2Fv2%2Fproject%2Fheartsplus&logo=modrinth&style=for-the-badge)](https://modrinth.com/mod/heartsplus)
[![CI Build](https://img.shields.io/github/actions/workflow/status/serechka/HeartsPlus/build.yml?branch=main&logo=github&label=build&style=flat-square)](https://github.com/serechka/HeartsPlus/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

Fabric & NeoForge for **Minecraft 1.21 – 26.3**. Client-side only — join any
server, no setup, and know your ally's HP before the fight starts.

</div>

![HeartsPlus in action](screenshots/26.x-hearts.png)

## What you get

- **True vanilla look** — the exact hearts from the game's own assets: containers, halves, absorption, poisoned, withered and frozen
- **Damage flash and healing pop** — hearts blink and recover exactly like your own HUD, frame for frame
- **Through walls** — hearts dim behind blocks just like name tags; a sneaking player keeps only the depth-tested pass, exactly like a vanilla name tag on sneak (bright in the open, hidden behind blocks)
- **Smart stacking** — long health bars wrap into rows of 10 and grow upward, never covering nametags
- **Yours to tune** — scale, height, render distance, texture source, animation toggle; everything changes in-game and applies instantly
- **11 languages** — English, Русский, Українська, 中文, Español, Português (BR), Deutsch, Français, 日本語, 한국어, Italiano

## Versions and branches

One branch per rendering era — each branch ships one jar covering its whole
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

Forge is not planned (replaced by NeoForge for 1.21+) — each rendering era
needs its own port.

## Install

1. Grab the file for your loader and game version from [Modrinth](https://modrinth.com/mod/heartsplus)
2. Drop it into `mods/` (Fabric builds also need [Fabric API](https://modrinth.com/mod/fabric-api))
3. *(Optional, Fabric)* Add [Mod Menu](https://modrinth.com/mod/modmenu) for the settings screen entry
4. Join a world — hearts appear above other players instantly

> **Multiplayer note:** HeartsPlus is client-side only. Health data comes from what the server
> already syncs about visible players, so it works on vanilla servers without any server mod.

## Configuration

Open **Mod Menu → HeartsPlus → Settings** on Fabric, or press **H** anywhere
(no menu needed on NeoForge).

| Option | Default | Description |
|---|:---:|---|
| Show Hearts | ON | Master toggle for the whole mod |
| Show Above Yourself | OFF | Draw hearts above your own player (visible in F5 / freecam) |
| Show Invisible Players | OFF | Off = no hearts on invisible players; on = hearts only while they wear armour |
| Show Behind Blocks | ON | Off = walls hide the hearts; on = a dimmed see-through copy stays visible through them, like a name tag |
| Animation | ON | Vanilla HUD animation above a player: damage flash, healing pop, low-health shake |
| Heart Textures | Current | Vanilla = built-in look, Current = your active resource pack |
| Scale | 1.0 | Heart size, ×0.25 – ×4 |
| Render Distance | 128 | Maximum distance in blocks, 8 – 128 |
| Height Offset | 10 | Fine-tune the height above the head, −20 – +40 |

Settings persist to `config/heartsplus.json` and apply instantly.

**Keybinds** (Controls → HeartsPlus): toggle is unbound by default; **H** opens settings.

## Building from source

Requires **JDK 25** (e.g. [Temurin](https://adoptium.net/)):

```bash
./gradlew build    # on the branch of the era you want to build
```

The mod jar appears in `build/libs/`. For a quick in-game check,
`./gradlew runClient` boots a dev client straight into a test world. In a solo
world your own hearts are hidden by default (like vanilla name tags) — press
**H**, enable **Show on Self**, then **F5** to see them above your head.

## License

[MIT](LICENSE) — free to use, modify and redistribute.
