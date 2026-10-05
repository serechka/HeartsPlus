# HeartsPlus

Клиентский мод **Fabric** для **Minecraft 26.2**, отображающий здоровье игроков ванильными сердечками над головой — как HUD, но в мире. Вдохновлён [PlayerHealthIndicators](https://github.com/Gaider10/PlayerHealthIndicators), переписан под новую систему рендера Minecraft 26.x (extract/submit render states).

A client-side **Fabric** mod for **Minecraft 26.2** that shows players' health as vanilla hearts above their heads. Inspired by [PlayerHealthIndicators](https://github.com/Gaider10/PlayerHealthIndicators), rebuilt for the 26.x extract/submit rendering system.

## Возможности / Features

- 💌 Ванильные спрайты сердец (красные + жёлтые сердечки поглощения) над каждым игроком
- 🧪 Варианты сердечек: отравление, иссушение, заморозка — как в HUD
- ⚙️ Экран настроек через **Mod Menu** (или клавишей)
- 🎨 Выбор текстур: из активного ресурспака или встроенные ванильные
- 🌍 Локализация: **русский** и **английский**
- ❤️ Половинки сердец и контейнеры — ровно как в HUD
- 📏 Настраиваемые масштаб, дистанция и смещение по вертикали
- 👻 Скрытие невидимых/крадущихся игроков (опционально)

## Настройки / Options

| Ключ | По умолчанию | Описание |
|---|---|---|
| `enabled` | `true` | Вкл/выкл индикаторы |
| `showOwnHearts` | `false` | Показывать над своим игроком (виден в F5) |
| `showAbsorption` | `true` | Жёлтые сердечки поглощения |
| `stackHearts` | `true` | Ряды по 10 сердец, если их много (лишние ряды — вверх) |
| `hideWhenInvisible` | `true` | Скрывать невидимых игроков |
| `hideWhenSneaking` | `false` | Скрывать крадущихся игроков |
| `useVanillaTextures` | `false` | `false` — спрайты из GUI-атласа (следуют ресурспаку), `true` — встроенные ванильные текстуры |
| `scale` | `1.0` | Масштаб (0.25–4.0) |
| `renderDistance` | `64.0` | Дистанция в блоках (8–128) |
| `heartOffset` | `0` | Смещение по вертикали в «пикселях» GUI (−20–40) |

Конфиг: `.minecraft/config/heartsplus.json`. Все настройки меняются в игре: **Mod Menu → HeartsPlus → Настройки**.

## Горячие клавиши / Keybinds

| Клавиша | Действие |
|---|---|
| **H** | Вкл/выкл индикаторы |
| **(не задано)** | Открыть настройки |

Настраиваются в: Управление → Категория «HeartsPlus».

## Сборка / Building

Требуется JDK 25 (например, [Temurin](https://adoptium.net/)):

```bash
./gradlew build
```

Готовый мод: `build/libs/heartsplus-<version>.jar`.

## Установка / Installation

1. Установите [Fabric Loader](https://fabricmc.net/use/) ≥ 0.19.5 для Minecraft 26.2
2. Положите в `mods/`: мод + [Fabric API](https://modrinth.com/mod/fabric-api)
3. (Опционально) [Mod Menu](https://modrinth.com/mod/modmenu) для экрана настроек

## Публикация / Publishing

Проект готов к GitHub и Modrinth:

**GitHub:**
```bash
git remote add origin https://github.com/<твой-логин>/HeartsPlus.git
git push -u origin main        # CI соберёт мод (.github/workflows/build.yml)
```

**Modrinth:**
1. На [modrinth.com](https://modrinth.com) создай проект: slug `heartsplus`, иконка — `branding/icon-512.png`, категория `utility`, загрузчик `Fabric`, игра `26.2`, окружение `Client`, лицензия MIT.
2. Проще всего публиковать автоматически: добавь в секреты репозитория `MODRINTH_ID` (ID проекта) и `MODRINTH_TOKEN` (PAT из настроек Modrinth) — и пушь теги (`git tag v1.1.1 && git push origin v1.1.1`): workflow `release.yml` сам соберёт джарник, создаст GitHub Release и выложит версию на Modrinth.
3. Вручную: загрузи `build/libs/heartsplus-<версия>+26.2.jar` (не `-sources`) на страницу версций.

Если твой логин на GitHub не `Intador` — поправь `contact` в `src/main/resources/fabric.mod.json`.

## Благодарности / Credits

- **[PlayerHealthIndicators](https://github.com/Gaider10/PlayerHealthIndicators)** (Gaider10, MIT) — вдохновение и референс поведения: логика раскладки сердечек (ряды по 10, сжатие рядов, половинки) адаптирована из этого мода. Код HeartsPlus написан с нуля под рендер-систему Minecraft 26.x; файлы из репозитория не копировались.
- Спрайты сердечек «ванильного» режима — стандартные текстуры Minecraft.

## Лицензия / License

MIT.
