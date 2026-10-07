<div align="center">

<img src="branding/icon-512.png" width="128" alt="HeartsPlus icon" />

# HeartsPlus

**See every player's health — as vanilla hearts floating above their heads.**

[![CI Build](https://img.shields.io/github/actions/workflow/status/serechka/HeartsPlus/build.yml?branch=main&logo=github&label=build)](https://github.com/serechka/HeartsPlus/actions/workflows/build.yml)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Game versions](https://img.shields.io/badge/minecraft-26.1%20%E2%80%93%2026.3-blueviolet)](https://modrinth.com/mod/heartsplus)
[![Mod loader](https://img.shields.io/badge/loader-Fabric-dbd3c3)](https://fabricmc.net)

A lightweight, fully configurable client-side Fabric mod for Minecraft **26.1 – 26.3**.<br/>
Works in survival, PvP, minigames — anywhere knowing your ally's HP matters.

</div>

---

## ✨ Features

- 💌 **Vanilla-style hearts** above every player — containers, halves and absorption hearts, exactly like your own HUD
- 🧪 **Status variants** — poisoned, withered and frozen hearts, chosen with the same priority as the vanilla HUD
- 📚 **Smart stacking** — long health bars wrap into rows of 10 and grow *upward*, never covering nametags
- 🎨 **Texture source toggle** — take hearts from your active resource pack, or lock them to the classic vanilla look
- ⚙️ **In-game config screen** — integrates with [Mod Menu](https://modrinth.com/mod/modmenu), or open it with a keybind
- 🌍 **11 languages** — English, Русский, Українська, 中文, Español, Português (BR), Deutsch, Français, 日本語, 한국어, Italiano
- 🔆 **Angle-independent shading** — hearts stay perfectly readable from above or below
- 🪶 **Featherweight** — no dependencies beyond Fabric API, no overhead when no one is around

## 📸 Screenshot

Drop screenshots into the [`screenshots/`](screenshots/) folder and reference
them here once available:

<!-- ![HeartsPlus in action](screenshots/26.2-pvp.png) -->

## 📥 Installation

1. Install the [Fabric Loader](https://fabricmc.net/use/) (≥ 0.19.5) for Minecraft 26.1 – 26.3
2. Drop **HeartsPlus** and [Fabric API](https://modrinth.com/mod/fabric-api) into your `mods/` folder
3. *(Optional)* Add [Mod Menu](https://modrinth.com/mod/modmenu) for the settings screen
4. Join a world — hearts appear above other players instantly

> **Multiplayer note:** HeartsPlus is client-side only. Health data comes from what the server
> already syncs about visible players, so it works on vanilla servers without any server mod.

## ⚙️ Configuration

Open **Mod Menu → HeartsPlus → ⚙️**, or press the settings keybind (unbound by default).

| Option | Default | Description |
|---|:---:|---|
| Health Indicators | ON | Master toggle for the whole mod |
| Show Above Own Player | OFF | Draw hearts above yourself (visible in F5 / freecam) |
| Show Invisible Players | OFF | Off = no hearts on invisible players; on = hearts only while they wear armour |
| Show Sneaking | OFF | Off = sneaking players get no hearts (like their name tag) |
| Heart Textures | Current | Vanilla = built-in look, Current = your active resource pack |
| Scale | 1.0 | Heart size, ×0.25 – ×4 |
| Render Distance | 64 | Maximum distance in blocks, 8 – 128 |
| Height Offset | 0 | Fine-tune the height above the head, −40 – +40 |

Settings persist to `config/heartsplus.json` and apply instantly.

**Keybinds** (Controls → HeartsPlus):
| Key | Default | Action |
|---|:---:|---|
| Toggle Health Indicators | **H** | Quick on/off with an action-bar confirmation |
| Open HeartsPlus Settings | *unbound* | Open the config screen |

## 🔧 Building from source

Requires **JDK 25** (e.g. [Temurin](https://adoptium.net/)):

```bash
./gradlew build
```

The mod jar appears in `build/libs/`. CI builds every push; pushing a `v*` tag
creates a GitHub release and publishes to Modrinth automatically.

<details>
<summary>Supported versions</summary>

| Branch | Game versions | Status |
|---|---|---|
| `main` | 26.1 – 26.3 | actively developed |
| `1.21` | 1.21.11 | legacy line |

Older versions (1.20 – 1.21.10) and the NeoForge/Forge loaders are on the
[roadmap](ROADMAP.md) — each Minecraft rendering era needs its own port.

</details>

## 💚 Credits

- **[PlayerHealthIndicators](https://github.com/Gaider10/PlayerHealthIndicators)** by Gaider10 (MIT) — the original inspiration.
  The heart layout math (rows of ten, row compression, halves) was adapted from it;
  all HeartsPlus code was written from scratch for the Minecraft 26.x rendering system.
- Default-texture mode loads its heart sprites straight from Minecraft's own default resource pack.

## 📄 License

[MIT](LICENSE) — free to use, modify and redistribute.
