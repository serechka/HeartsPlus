<div align="center">

<img src="branding/icon-512.png" width="128" alt="HeartsPlus icon" />

# HeartsPlus

**See every player's health — as vanilla hearts floating above their heads.**

### [Download on Modrinth](https://modrinth.com/mod/heartsplus)

[![Modrinth](https://img.shields.io/badge/dynamic/json?color=1bd96a&label=modrinth&query=%24.title&url=https%3A%2F%2Fapi.modrinth.com%2Fv2%2Fproject%2Fheartsplus&logo=modrinth&style=for-the-badge)](https://modrinth.com/mod/heartsplus)
[![CI Build](https://img.shields.io/github/actions/workflow/status/serechka/HeartsPlus/build.yml?branch=main&logo=github&label=build&style=flat-square)](https://github.com/serechka/HeartsPlus/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-MIT-green?style=flat-square)](LICENSE)

A lightweight, fully configurable client-side mod for Minecraft.<br/>
Fabric **26.1 – 26.3**, NeoForge **26.x**, and a Fabric **1.21.11** legacy build.<br/>
Works in survival, PvP, minigames — anywhere knowing your ally's HP matters.

</div>

---

## Features

- **Vanilla-style hearts** above every player — containers, halves and absorption hearts, exactly like your own HUD
- **Damage blink** — hearts flash after damage with the vanilla HUD animation, including the pre-drop highlight
- **Status variants** — poisoned, withered and frozen hearts, chosen with the same priority as the vanilla HUD
- **Smart stacking** — long health bars wrap into rows of 10 and grow upward, never covering nametags
- **Texture source toggle** — take hearts from your active resource pack, or lock them to the classic vanilla look
- **In-game config screen** — integrates with Mod Menu on Fabric, or open it with a keybind
- **11 languages** — English, Русский, Українська, 中文, Español, Português (BR), Deutsch, Français, 日本語, 한국어, Italiano
- **Angle-independent shading** — hearts stay perfectly readable from above or below
- **Featherweight** — no dependencies beyond Fabric API (Fabric builds), no overhead when no one is around

## Screenshot

Drop screenshots into the [`screenshots/`](screenshots/) folder and reference
them here once available:

<!-- ![HeartsPlus in action](screenshots/26.2-pvp.png) -->

## Installation

Get the jar from [Modrinth](https://modrinth.com/mod/heartsplus) — GitHub
releases link straight to the Modrinth download pages.

1. Pick the build matching your loader and Minecraft version
2. Drop **HeartsPlus** into your `mods/` folder (Fabric builds also need [Fabric API](https://modrinth.com/mod/fabric-api))
3. *(Optional, Fabric)* Add [Mod Menu](https://modrinth.com/mod/modmenu) for the settings screen entry
4. Join a world — hearts appear above other players instantly

> **Multiplayer note:** HeartsPlus is client-side only. Health data comes from what the server
> already syncs about visible players, so it works on vanilla servers without any server mod.

## Configuration

Open **Mod Menu → HeartsPlus → Settings** on Fabric, or press the settings
keybind (unbound by default). On NeoForge, use the keybind.

| Option | Default | Description |
|---|:---:|---|
| Show Hearts | ON | Master toggle for the whole mod |
| Show Above Yourself | OFF | Draw hearts above your own player (visible in F5 / freecam) |
| Show Invisible Players | OFF | Off = no hearts on invisible players; on = hearts only while they wear armour |
| Show Sneaking Players | OFF | Off = sneaking players get no hearts, just like their hidden name tag |
| Heart Textures | Resource Pack | Resource-pack sprites or the built-in vanilla set |
| Scale | 1.0 | Heart size, ×0.25 – ×4 |
| Render Distance | 64 | Maximum distance in blocks, 8 – 128 |
| Height Offset | 0 | Fine-tune the height above the head, −20 – +40 |

Settings persist to `config/heartsplus.json` and apply instantly.

**Keybinds** (Controls → HeartsPlus):

| Key | Default | Action |
|---|:---:|---|
| Toggle Health Indicators | **H** | Quick on/off with an action-bar confirmation |
| Open HeartsPlus Settings | *unbound* | Open the config screen |

## Building from source

Requires **JDK 25** (e.g. [Temurin](https://adoptium.net/)):

```bash
./gradlew build    # on the branch you want: main, neoforge or 1.21
```

The mod jar appears in `build/libs/`. CI builds every push; pushing a `v*` tag
creates a GitHub release linking to the Modrinth downloads.

<details>
<summary>Supported versions and branches</summary>

| Branch | Loader | Game versions | Status |
|---|---|---|---|
| `main` | Fabric | 26.1 – 26.3 | actively developed |
| `neoforge` | NeoForge | 26.1 – 26.3 | kept in sync with `main` |
| `1.21` | Fabric | 1.21.11 | legacy line |

Older versions (1.20 – 1.21.10) and Forge are not planned — each Minecraft
rendering era needs its own port; see the [roadmap](ROADMAP.md).

</details>

## Credits

- **[PlayerHealthIndicators](https://github.com/Gaider10/PlayerHealthIndicators)** by Gaider10 (MIT) — the original inspiration.
  The heart layout math (rows of ten, row compression, halves) was adapted from it;
  all HeartsPlus code was written from scratch for the Minecraft 26.x rendering system.
- Vanilla heart sprites used by the built-in texture mode come from Minecraft itself.

## License

[MIT](LICENSE) — free to use, modify and redistribute.
