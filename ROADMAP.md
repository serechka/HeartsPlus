# HeartsPlus Roadmap

## Version & loader matrix

Each Minecraft "rendering era" needs its own code path, so support lands
branch-by-branch. ✔ = released, 🚧 = planned.

| Era | Game versions | Rendering stack | Fabric | NeoForge | Forge |
|---|---|---|:---:|:---:|:---:|
| 26.x | 26.1 – 26.3 | SubmitNodeCollector | ✔ `main` | 🚧 | 🚧 |
| 1.21-d | 1.21.11 | OrderedRenderCommandQueue | ✔ `1.21` | 🚧 | n/a¹ |
| 1.21-c | 1.21.9 – 1.21.10 | OrderedRenderCommandQueue (early) | 🚧 | 🚧 | n/a¹ |
| 1.21-b | 1.21.6 – 1.21.8 | EntityRenderState + VertexConsumerProvider | 🚧 | 🚧 | 🚧 |
| 1.21-a | 1.21 – 1.21.5 | classic entity render | 🚧 | 🚧 | 🚧 |
| 1.20-b | 1.20.5 – 1.20.6 | GUI sprite atlas (`hud/heart/*`) | 🚧 | 🚧 | 🚧 |
| 1.20-a | 1.20 – 1.20.4 | `icons.png` HUD texture | 🚧 | 🚧 | 🚧 |

¹ Forge as a loader is effectively replaced by NeoForge for 1.21+;
   the older line still receives classic Forge builds.

## Non-version work

- [ ] In-game screenshot for the README / Modrinth gallery
- [ ] Hardcore heart variants
- [ ] Optional numeric HP readout next to the bar
- [ ] Curios-style "third party addon API" for minigame servers (teams colors)

## How to port to a new era

1. Branch off the closest supported era.
2. `./gradlew genSources`, then locate `LivingEntityRenderer.render` /
   its era equivalent and the GUI-atlas sprite accessor.
3. Adapt `HeartsAboveHeadRenderer` (geometry math stays identical) and the
   capture mixin. Config, screen, languages and resources carry over as-is.
4. Build, bump `depends.minecraft` in `fabric.mod.json`, verify in-game,
   add the version to `.github/workflows/release.yml`.
