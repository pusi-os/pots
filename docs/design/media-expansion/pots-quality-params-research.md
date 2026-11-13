---
title: Качество генерации изображений (price-table) и параметры видео (звук/разрешение) — медиа-студия Pots
created: 2026-11-13
updated: 2026-11-13
type: doc
tags: [project, ai, api, pots, media-studio]
sources:
  - https://plusvibeapi.ru/api/media-catalog
  - https://plusvibeapi.ru/api/media/price-table?model=gpt-image-2-6
  - https://plusvibeapi.ru/api/media/price-table?model=gpt-image-2
  - /run/media/al/ARCHIVE/code/pusi/apps/pots/docs/knowledge/media-providers/plusvibe.md
  - /run/media/al/ARCHIVE/code/pusi/apps/pots/composeApp/src/commonMain/kotlin/com/pusios/pots/media/PlusVibeMediaProvider.kt
  - /run/media/al/ARCHIVE/code/pusi/apps/pots/composeApp/src/commonMain/kotlin/com/pusios/pots/ui/media/MediaStudioScreenFull.kt
  - /run/media/al/ARCHIVE/code/pusi/apps/pots/composeApp/src/commonMain/kotlin/com/pusios/pots/ui/media/MediaStudioTopBar.kt
  - /run/media/al/ARCHIVE/code/pusi/apps/pots/composeApp/src/commonMain/kotlin/com/pusios/pots/media/AudioModels.kt
confidence: high
status: active
related: [[design/pots-media-ux-spec]], [[design/pots-audio-flows-research]], [[design/pots-audio-gen-research]]
---

# Качество генерации изображений и параметры видео (звук/разрешение)

Исследование для цепочки `t_4741bec6` (это, @pifiya) → `t_f1e6f686` (спек-дельта, @joni). Директива владельца: селектор качества генерации изображений в медиа-студию (по умолчанию Low) + проверить параметры видео (звук вкл/выкл, разрешение).

База: живой каталог `GET https://plusvibeapi.ru/api/media-catalog` (снят 2026-11-13 ~18:31 MSK, 1 214 115 байт, 208 моделей: 88 video / 56 image / 46 audio / 11 music / 7 3d; `videoDisabled=false`), живые price-table обеих моделей `gpt-image-*`, контракт `docs/knowledge/media-providers/plusvibe.md` (okf 0.2), код приложения — клон `/run/media/al/ARCHIVE/code/pusi/apps/pots`, коммит `023e6354` (2026-11-13 17:33 +0300). Платных запросов не было; все цены — из публичных эндпоинтов без ключа. Рабочие дампы: `/home/al/.hermes/kanban/workspaces/t_4741bec6/` (`catalog.json`, `price-gpt-image-2-6.json`, `price-gpt-image-2.json`).

---

## 1. Ось `quality` у моделей изображений

В каталоге 56 моделей `kind=image`; ось `quality` в `axes`/`params` есть у **8**:

| Модель | Значения `quality` | Дефолт каталога | Цены опций (₽) | `priceStatus` |
|---|---|---|---|---|
| **`gpt-image-2-6`** | `low` / `medium` / `high` | **`high`** | по 1,44 («от», см. §3) | exact |
| `grok-imagine-image-2.0` | только `low` (одна опция — вырожденная ось) | `low` | — | exact |
| `ideogram-4-5` | `very_low` / `low` / `medium` / `high` | `medium` | 1,09 / 4,07 / 8,13 / 13,54 | exact |
| `ideogram-4-5-precise-edit` | `very_low` / `low` / `medium` / `high` | `medium` | 1,09 / 4,07 / 8,13 / 29,79 | exact |
| `gpt-image-1-5` | `medium` / `high` | `medium` | 2,51 / 13,83 | exact |
| `seedream-5` | `basic` / `high` / `ultra` | `basic` | все 3,72 (качество на цену не влияет) | exact |
| `seedream-4-5` | `basic` / `high` | `basic` | — (estimate) | estimate |
| `seedream-5-uncensored` | `basic` / `high` | `basic` | 4,62 / 9,24 (×2) | exact |

Упомянутые в задаче **`gpt-image-2` и `nano-banana-pro` оси `quality` НЕ имеют**:

- `gpt-image-2`: оси `aspect_ratio`, `resolution` (1K/2K/4K), `mode` (text-to-image/image-to-image). Цена зависит от соотношения и разрешения (§3.2).
- `nano-banana-pro`: оси `aspect_ratio`, `resolution` (1K/2K = 4,06 ₽, 4K = 5,42 ₽), `mode` (text-to-image/edit). `priceStatus=estimate`.

Вывод для тулбара: селектор качества показывать только моделям с осью `quality` и ≥2 опциями (7 моделей; `grok-imagine-image-2.0` с одной опцией — скрыть). Для `gpt-image-2`, `nano-banana-pro` и остальных 48 моделей контрол скрыт.

## 2. Как `quality` уходит в запрос

**Основной путь приложения — async** (`POST /api/media/generate`, контракт §«Async generation»): параметры модели идут в `opts`, т.е. `opts.quality: "low"|"medium"|"high"`. Неизвестный параметр в не-strict режиме игнорируется, в `strict` — 422 (контракт §«opts»).

**Синхронный шорткат** `POST /v1/images/generations` (контракт §«Sync image shortcut»): поле `quality` принимается **«только если есть в каталоге»** — для `gpt-image-2-6` валидно, для `gpt-image-2` неизвестное поле → `400` по имени поля. Штатный размер уходит как `size`-пропорция (`1024x1024`→1:1 и т.д.). **Приложение синхронный путь не использует**: по коду `composeApp/src/commonMain` совпадений `v1/images` нет — только async (проверено grep по `*.kt`).

**Что шлёт приложение сейчас** (`PlusVibeMediaProvider.kt`, коммит `023e6354`):

- `buildGenerateBody()` собирает `{ model, prompt, opts, quote_id?, max_price_rub? }`; `opts` — сквозной проброс `request.opts` (что выбрал пользователь в тулбаре) + маппинг референсов + авто-`mode=image-to-image` при референсах, если у модели есть параметр `mode` (строки 163–178).
- **`quality` не добавляется нигде** — ни в тулбаре, ни в провайдере. При отсутствии `opts.quality` сервер применяет дефолт каталога: у `gpt-image-2-6` это **`high`** (поле `defaults` price-table). Следствие: выбор 2K/4K без селектора качества молча тарифицируется по 17,33 ₽ вместо 6,77 ₽ — ровно та «ловушка стоимости», которую закрывает директива «дефолт Low».

## 3. Таблицы цен (`/api/media/price-table`)

### 3.1 `gpt-image-2-6` — качество × разрешение × режим

Живой `GET /api/media/price-table?model=gpt-image-2-6`: `ok=true`, `currency=RUB`, `priceAffecting=[resolution, quality, mode]`, `defaults={aspect_ratio:1:1, resolution:1K, quality:high, complexity:high, tier:flare, mode:text-to-image}`, **18 строк** (3 разрешения × 3 качества × 2 режима), все `priceStatus=exact`, `chargeBasis=quote`, `priceScope=job_total`:

| Разрешение | Low | Medium | High |
|---|---|---|---|
| **1K** | 1,44 ₽ | 1,44 ₽ | 1,44 ₽ |
| **2K** | 6,77 ₽ | 12,19 ₽ | 17,33 ₽ |
| **4K** | 6,77 ₽ | 12,19 ₽ | 17,33 ₽ |

Сверка с таблицей владельца: **совпадение 1:1** (Low 1K 1,44 / 2K–4K 6,77; Medium 2K/4K 12,19; High 2K/4K 17,33).

Дополнительно подтверждено по строкам:

- **Генерация и редактирование стоят одинаково**: во всех 9 парах (разрешение×качество) цена `text-to-image` = `image-to-image`. Одно место выбора качества на оба режима корректно и по ценам.
- **Режим формально в `priceAffecting`, но цену не меняет**; цена зависит только от разрешения и качества.
- На 1K качество на цену **не влияет** (все три качества — 1,44 ₽).

**Проверка правила «×2/×3»** (2K/4K, относительно Low):

- Medium/Low = 12,19 / 6,77 = **×1,80** (номинал ×2 завышает на 1,35 ₽);
- High/Low = 17,33 / 6,77 = **×2,56** (номинал ×3 завышает на 2,98 ₽);
- High/Medium = ×1,42.

Итог: «среднее ≈ ×2, высокое ≈ ×3» — **консервативная оценка сверху, подтверждается как приближение** (реальные множители ×1,8 и ×2,56). Для 1K правило не действует — наценка ×1, это нужно отражать в пометке.

Осторожно с `priceRub` опций самой оси в каталоге: у `quality` все три опции несут 1,44 ₽, у `resolution` — 1,44/17,33/17,33 ₽. Это цены «от» при дефолтном `quality=high`, а не цены опций при выбранном разрешении — для индикации цены качества использовать строки price-table по ключу (разрешение, качество, режим), а не `priceRub` опций оси.

### 3.2 `gpt-image-2`

Живой `GET /api/media/price-table?model=gpt-image-2`: `ok=true`, `priceAffecting=[aspect_ratio, resolution]` (оси качества нет), 24 строки (8 соотношений × 3 разрешения), все `exact`, только генерация (`requiresInput=prompt`):

| Разрешение | 1:1, 16:9, 9:16, 4:3, 3:4 | 3:2, 2:3, 21:9 |
|---|---|---|
| 1K | 0,45 ₽ | 2,70 ₽ |
| 2K | 2,70 ₽ | 2,70 ₽ |
| 4K | 2,70 ₽ | 2,70 ₽ |

Нюанс: публичный price-table не содержит строк редактирования `gpt-image-2`, но каталожные `tiers` содержат: редактирование 1K = 2,70 ₽ при любом соотношении (×6 к генерации 1K 0,45 ₽), 2K/4K — 2,70 ₽ в обоих режимах. Селектора качества у модели нет; рычаги цены — разрешение и (на 1K) соотношение сторон.

## 4. Видео: звук, разрешение, длительность

88 моделей `kind=video` (`videoDisabled=false`).

### 4.1 Звук «вкл/выкл» — булевы тумблеры генерации звука

Готовый «звук вкл/выкл» дают **12 моделей** с осью-тумблером `audio`/`generate_audio`:

| Модель | Ключ | Подпись в каталоге | Дефолт | Влияет на цену | Без звука → Со звуком (₽) |
|---|---|---|---|---|---|
| `kling-v2.6` | `audio` | «Со звуком» | `false` | да (точно) | 35,64 → 71,28 (**×2,0**) |
| `kling-v3.0` | `audio` | «Со звуком» | `false` | да (цены опций не опубликованы) | — |
| `kling-v3-omni` | `audio` | «Нативное аудио» | `false` | да (точно) | 7,99 → 11,43 (×1,43) |
| `kling-v3-omni-reference` | `audio` | «Нативное аудио» | `false` | да (estimate) | 8,54 → 11,43 (×1,34) |
| `seedance-2.0` | `audio` | «Генерировать звук» | `false` | да (точно) | 17,85 → 23,19 (×1,30) |
| `seedance-2.5` | `audio` | «Генерировать звук» | `false` | флаг не заявлен | — |
| `seedance-2-0-mini` | `audio` | «Генерировать звук» | `false` | флаг не заявлен | — |
| `pixverse-v6` | `audio` | «Генерировать звук» | `false` | да (точно) | 4,07 → 5,43 (×1,33) |
| `pixverse-v6-max` | `audio` | «Генерировать звук» | `false` | да (estimate) | 4,98 → 6,79 (×1,36) |
| `wan-3-0-prime` | `audio` | «Звук» | **`true`** | флаг не заявлен | — |
| `flux-3` (видео) | `generate_audio` | «Синхронный звук» | **`true`** | флаг не заявлен | — |
| `vidu-q4` | `audio` | «Со звуком» | **`true`** | флаг не заявлен | — |

Дефолты: у 9 моделей «без звука» (`false`), у 3 (`wan-3-0-prime`, `flux-3`, `vidu-q4`) — «со звуком» (`true`). Дефолт тумблера в тулбаре брать из каталога, не хардкодить.

**Это не тумблеры «вкл/выкл»**, а другие звуковые оси (в селектор звука не превращать): `audio_url` (входная дорожка; у 20 моделей — аватары/липсинк/озвучка: `skyreels-v4-*`, `p-video-2`, `kling-ai-avatar`, `wan-2-7`, `wan-speech-to-video`, `omnihuman-*`, `volcengine-lipsync`, `lipsync-2-pro`, `sync-3`, `sync-3-avatar`, `react-1`, `minimax-h3-max-lipsync`, `heygen-avatar-*`, `veed-fabric-1.0`, `ltx-2.5-audio-to-video`, `p-video-avatar`), `keep_audio` (`p-video-2-pro-reference`), `keep_original_sound` (`kling-v4`, `kling-v4-flash`), `audio_setting` auto/origin (`happyhorse`), `save_audio` (`p-video-2`), `target_audio_url` (`minimax-h3-max`, `minimax-h3-max-turbo`), `source_audio_id` (`suno-music-video`).

### 4.2 Разрешение

Ось разрешения есть у **69** видео-моделей; диапазон значений от 360p до 4K. 4K-класс: `veo-3.1`, `kling-v3-omni`, `kling-v3-omni-reference`, `kling-v3-omni-transformation`, `seedance-2.0`, `vidu-q4` (до 4k + 2k), `gemini-omni-video`, `gemini-omni-flash-1-1`, `gemini-omni-flash-1-2`, `video-upscale` (1080P/2K/4K).

⚠️ У трёх моделей разрешение лежит в оси с ключом **`quality`**, а не `resolution`: `pixverse-v6` и `pixverse-v6-max` (360p/540p/720p/1080p), `runway-gen4` (720p/1080p). Текущий тулбар их не видит (§5) — у этих моделей сейчас вообще нет контрола разрешения. Не путать с `flux-3` (видео): у неё `quality` = `full`/`draft` — уровень качества поверх отдельной оси `resolution` (720p/1080p).

### 4.3 Длительность

Ось `duration` у **62** моделей; типичные значения 5/10 с; максимум 30 с (`seedance-2.5`, до 30 и у `wan-3-0-prime` 2–30); особые: `veo-3.1` 4/6/8, `p-video-2` 2–20, `skyreels-v4-*` 3–15, `hailuo-2-3` 6/10.

### 4.4 Модели без осей разрешения/качества/длительности — контролы скрыты

14 моделей: `MiniMax-H3-Context-IR`, `p-video-edit`, `kling-ai-avatar`, `grok-imagine-extend`, `omnihuman-1-5`, `volcengine-lipsync`, `topaz-video-upscale`, `omnihuman-v2`, `lipsync-2-pro`, `sync-3`, `react-1`, `sync-3-avatar`, `ltx-2.5-audio-to-video`, `suno-music-video` (в основном аватары/липсинк/апскейл — параметры задаются входом, а не осями).

## 5. Что тулбар рендерит сейчас (код `023e6354`)

`MediaStudioScreenFull.kt` + `MediaStudioTopBar.kt`:

- **Изображение/видео**: `toolbarParamDefs = currentModel.params.filter { it.key in listOf("resolution", "duration") && it.options.isNotEmpty() }` — захардкоженная пара ключей. Чипы показывают `params[key] ?: defaultValue`, тап открывает `MediaParamSheet` со списком опций оси; выбор попадает в `params`, который целиком уходит как `opts` в `generateImage/generateVideo`.
- **Соотношение сторон** — отдельный чип, только в режиме изображения (`aspect_ratio`).
- **Аудио**: универсальный механизм `audioToolbarParams()` (`AudioModels.kt`) — все опционные оси модели кроме технических (`seed`, `sample_rate`, `bitrate`, `channel`, `stability`, `similarity_boost`), приоритетный порядок (`duration`, `voice`, `response_format`, `output_format`, `language`, `language_code`), максимум 4 чипа. Это готовый образец для расширения на изображение/видео.
- Порядок чипов в тулбаре: модель → соотношение (изображение) → парам-чипы (`MediaStudioTopBar.kt`).
- При смене модели `params` сбрасывается в пустую карту.

**Чего в тулбаре нет:**

1. `quality` — ни у одной модели (фильтр его не включает);
2. тумблеры звука видео (§4.1) — 12 моделей без контрола «со звуком / без звука»;
3. разрешение у `pixverse-v6`, `pixverse-v6-max`, `runway-gen4` (ключ `quality` мимо фильтра); `flux-3` (видео) не показывает `quality=full/draft`.

## 6. Рекомендации

**Качество изображений (режим «Изображение»):**

1. Единственный выпадающий список качества в тулбаре, позиция — сразу после чипа разрешения (кнопка-индикатор текущего значения, как чип соотношения сторон). Показывать, только если у выбранной модели есть ось `quality` с ≥2 опциями; иначе контрол скрыт. Одна точка выбора на генерацию и редактирование — цены обоих режимов идентичны (§3.1).
2. **Дефолт Low**: для `gpt-image-2-6` — `low`. Универсальное правило: опция с минимальной ценой (по `priceRub` опций оси, при равенстве — первая): `seedream-5`→`basic`, `seedream-5-uncensored`→`basic`, `ideogram-4-5(-precise-edit)`→`very_low`, `gpt-image-1-5`→`medium` (низшего уровня у модели нет — дефолт честный минимум). Подписи опций — из каталога (`optionLabels`: Low/Medium/High, Basic/Ultra и т.д.).
3. **Отправлять явно**: выбранное значение обязательно класть в `opts.quality`. Серверный дефолт `gpt-image-2-6` — `high`; неотправка при 2K/4K стоит ×2,56 (§2).
4. **Цена в селекторе**: для `gpt-image-2-6` — точные ₽ из строк price-table по выбранному разрешению (1,44 / 6,77 / 12,19 / 17,33; генерация=редактирование). Где точной цены качества нет (`seedream-4-5`, `kling-v3.0` и пр.) — пометка «среднее ≈ ×2, высокое ≈ ×3»: для `gpt-image-2-6` реальные множители ×1,80/×2,56 на 2K/4K, т.е. пометка консервативна и безопасна; на 1K качества цена не меняется — при выбранном 1K пометку скрывать (или писать «на цену не влияет»). `priceRub` опций оси для этих пометок не использовать (§3.1).

**Видео-режим:**

5. Тумблер «со звуком / без звука» — чип по оси `audio`/`generate_audio` для 12 моделей §4.1; дефолт из каталога (у 9 — выкл, у `wan-3-0-prime`/`flux-3`/`vidu-q4` — вкл); подпись — из каталога. Если ось `priceAffecting` и у опций есть `priceRub` — показывать дельту цены (у `kling-v2.6` ровно ×2: 35,64→71,28 ₽; у остальных ×1,3–1,43). Остальные звуковые оси (`audio_url`, `keep_audio`, …) в этот тумблер не включать.
6. Разрешение видео уже рендерится чипом для 69 моделей с осью `resolution`; добавить обработку ключа `quality` со значениями формата `*p`/`4k` (`pixverse-v6`, `pixverse-v6-max`, `runway-gen4`) — иначе у них нет контрола разрешения. `flux-3` (видео): `quality=full/draft` — отдельный уровень качества, показывать отдельным чипом либо оставить скрытым до решения спеки. Длительность уже рендерится (62 модели). Для 14 моделей без осей (§4.4) контролы не показывать.

**Механизм:**

7. Заменить захардкоженный фильтр `["resolution", "duration"]` на обобщённый механизм по образцу `audioToolbarParams()`: приоритетный порядок (предложение: `resolution`, `quality`, `duration`, `audio`/`generate_audio`) + список технических ключей, которые никогда не становятся чипами (`seed`, `negative_prompt`, `guidance_scale`, `image_url`-поля и пр. входные/служебные оси) + ограничение числа чипов. Это закроет пункты 1, 5, 6 одним изменением и сохранит существующий паттерн чипов/шитов (`MediaParamSheet`).

**Синхронный шорткат (на будущее):** если приложение когда-либо пойдёт в `POST /v1/images/generations`, поле `quality` отправлять только моделям с осью качества в каталоге (`gpt-image-2-6` — да; `gpt-image-2`, `nano-banana-pro` — нет, будет 400 по имени поля). Сейчас приложение использует только async-путь, поэтому основной маршрут — `opts.quality`.

## Источники и сверка

- `GET https://plusvibeapi.ru/api/media-catalog` — 2026-11-13 ~18:31 MSK (дамп `t_4741bec6/catalog.json`, 208 моделей).
- `GET https://plusvibeapi.ru/api/media/price-table?model=gpt-image-2-6` — 18 строк, `exact`; `…?model=gpt-image-2` — 24 строки, `exact` (дампы рядом).
- Контракт `docs/knowledge/media-providers/plusvibe.md` (okf 0.2, last_verified 2026-11-10): async `opts`, sync-шорткат «`quality` только если есть в каталоге», price-table формат.
- Код: `PlusVibeMediaProvider.kt` (buildGenerateBody, строки 163–178), `MediaStudioScreenFull.kt` (toolbarParamDefs, строки 143–160), `MediaStudioTopBar.kt`, `AudioModels.kt` (audioToolbarParams) — коммит `023e6354`.
- Таблица владельца (Low 1K 1,44 / 2K–4K 6,77; Medium 12,19; High 17,33; «×2/×3») — сверена в §3.1: числа точные, «×2/×3» подтверждается как приближение сверху (×1,80/×2,56).
