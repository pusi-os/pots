---
title: Аудио-генерация в медиа-студии Pots — модели PlusVibe (исследование)
created: 2026-11-13
updated: 2026-11-13
type: doc
tags: [project, ai, api, pots, media-studio]
sources:
  - https://plusvibeapi.ru/api/media-catalog
  - /run/media/al/ARCHIVE/code/pusi/apps/pots/docs/knowledge/media-providers/plusvibe.md
confidence: high
status: active
related: [[projects/pots-app]], [[projects/pots-media-expansion-plan]], [[design/pots-audio-catalog-dump]]
---

# Аудио-модели PlusVibe для медиа-студии Pots

Исследование для задач `t_647099fc` (это) → `t_f1891f4c` (генерация аудио, @kodi) → `t_e9b7ed2f` (аудиоплеер/редактор, @kodi). План цепочки: [[projects/pots-media-expansion-plan]]. Приложение: [[projects/pots-app]].

> **Источники.** Свежий дамп каталога: [[design/pots-audio-catalog-dump]] (`GET https://plusvibeapi.ru/api/media-catalog`, публичный, без ключа, снят **2026-11-13 15:0x MSK**, HTTP 200, 1 214 115 байт). Контракт провайдера: `docs/knowledge/media-providers/plusvibe.md` в репозитории (okf_version 0.2, last_verified 2026-11-10). В репозиторий ничего не писалось.

## 1. Состояние каталога на 2026-11-13

| Показатель | Значение |
|---|---|
| Всего моделей в каталоге | **208** (image 56, video 88, audio 46, music 11, 3d 7) |
| Аудио+музыка (`kind=audio\|music`) | **57** — совпадает с ожиданием задачи |
| `pricesAreEstimate` (top-level) | `true` — но у каждой модели свой `priceStatus`: **55 exact**, 2 estimate |
| `pricesArePlaceholder` | `false` |
| `videoDisabled` | `false` |
| `priceUnit` по 57 моделям | `request` ×22, `1000_characters` ×19, `minute` ×13, `second` ×3 |

`priceStatus=estimate` только у двух: `gemini-tts` («списывается по кредитам провайдера, не больше этой цены») и `suno-lyrics` («нет опубликованного тарифа, показана базовая цена»).

> **Верификация 2026-11-13 15:23 MSK (@pifiya):** повторный `GET /api/media-catalog` (HTTP 200) — **0 расхождений** с дампом: те же 208/57 моделей, ни одной добавленной/удалённой, ни одного изменения `fromRub`/`unit`/`priceStatus`/`params`. Спот-чек ключевых моделей (`gpt-4o-mini-tts` 0.07, `suno` 6.29, `elevenlabs-sound-effects-v2` 1.36, `acestep-v15-xl-sft` 0.1403, `whisper-large-v3-turbo` 0.02, `gemini-tts` 10.29 estimate) совпал. Поле `class` присутствует на всех 57 моделях дампа (распределение = `classCounts`). Дамп актуален и пригоден как единственный источник для шита.

## 2. Классификация: генеративные против аналитических

Правило из задачи: **аналитические в тулбар генерации не идут**. Критерий — поле `task` каталога + `inputTypes`.

| Класс | Кол-во | Модели | В шите генерации? |
|---|---|---|---|
| **Генеративные** (текст→аудио) | **28** | 19 TTS + 4 SFX + 5 музыка | ✅ да |
| **Аналитические** (аудио→текст: STT/диаризация) | **12** | все `task=asr` | ❌ нет; отдельный поток «субтитры» (§5) |
| Музыкальные трансформации (аудио-вход) | 6 | `suno-add-instrumental`, `suno-add-vocals`, `suno-cover-audio`, `suno-cover-generate`, `suno-extend-audio`, `suno-mashup` | ❌ в v1 (нужен загруженный трек) |
| Трансформации/утилиты (аудио-вход) | 9 | `elevenlabs-audio-isolation` (изоляция), `elevenlabs-dubbing`, `elevenlabs-voice-changer`, `forced-alignment`, `suno-stem` (стемы), `suno-midi`, `suno-persona`, `suno-timestamped-lyrics`, `suno-wav` | ❌ в v1 |
| Текстовые помощники (выход не аудио) | 2 | `suno-lyrics` (текст песни), `suno-style-boost` (развёртка стиля) | ❌ (вспомогательные) |

Итого 28 + 12 + 6 + 9 + 2 = **57** ✓. Каждая модель в дампе помечена полем `class` (`tts`/`sfx`/`music`/`music-transform`/`stt`/`transform`/`utility`) — @kodi может фильтровать прямо по нему.

## 3. Генеративные модели — полный список (28)

Цены «от» (`fromRub`) точные (`priceStatus=exact`), кроме отмеченных. Полный `note`, все `axes`, `inputSchema`, `variants` и `tiers` — в дампе [[design/pots-audio-catalog-dump]] по каждой модели; ниже — выжимка для UI.

### 3.1 TTS — голос (19)

| id | unit | от ₽ | цена | вход | Параметры для UI (`axes`) | Примечания |
|---|---|---|---|---|---|---|
| `gpt-4o-mini-tts` | за 1000 символов | **0.07** | exact | text | voice (13, def `alloy`), instructions, response_format (6, def mp3), speed | Самый дешёвый TTS с русским; до 4096 симв./запрос; совместим с OpenAI `/v1/audio/speech` |
| `mimo-v2-5-tts` | за 1000 символов | **0.01** | exact | text | voice, instruct, output_format (3, def wav) | Xiaomi MiMo через Dune; цена зависит от длины |
| `mimo-v2-5-tts-voiceclone` | за 1000 символов | 0.01 | exact | text | voice, instruct | Нужен voice ID своего образца |
| `mimo-v2-5-tts-voicedesign` | за 1000 символов | 0.01 | exact | text | voice | Тембр описывается текстом |
| `chatterbox-turbo` | за 1000 символов | **0.13** | exact | text | prompt (req) | Resemble AI через Dune |
| `gemini-3.8-flash-tts-lite` | за 1000 символов | **1.7** | exact | text | gemini_voice (30, def Kore), style_instructions | Облегчённый Gemini 3.8; маршруты: базовый 1.7 / flow 3.78 |
| `grok-tts` | за 1000 символов | **2.04** | exact | text | voice (28, def eve), language (21, def auto) | xAI; до **15000** симв./запрос — рекорд |
| `qwen3-tts` | за 1000 символов | **2.71** | exact | text | voice (9, def Vivian), language (11, def auto), instruct, response_format (4, def mp3) | 10 языков вкл. русский; до 4000 симв.; маршруты: базовый 2.71 / flow 12.19 |
| `voice-clone-tts` | за 1000 символов | 2.71 | exact | text | voice_id (req), engine (10, def qwen3), language (11), instruct, stability, similarity_boost, emotion (8), speed, pitch, volume, response_format (5), … | Озвучка **клонированным голосом** (`POST /api/media/voices` → `voice_id`); до 4000 симв.; отдаёт тайминги слов (субтитры JSON) |
| `chatterbox-tts` | за 1000 символов | 3 | exact | text | voice (23), exaggeration, temperature, seed | 23 языка; **лимит 300 симв./запрос**; маршруты 3 / 4 |
| `kokoro-tts` | за 1000 символов | 3 | exact | text | voice (20, def af_heart), speed | **Только английский** |
| `inworld-realtime-tts-1-5-mini` | за 1000 символов | 3.51 | exact | text | voice, speaking_rate | Быстрый, вкл. русский |
| `gemini-3.8-flash-tts` | за 1000 символов | 3.78 | exact | text | engine (flash/lite), gemini_voice (30), style_instructions | tiers: flash 5.66 / lite 3.78 |
| `inworld-realtime-tts-1-5-max` | за 1000 символов | 7.03 | exact | text | voice, speaking_rate, return_timestamps (toggle) | Макс. качество + тайминги слов |
| `tts` (агрегатор) | за озвучку | 7 | exact | text | **engine** (6: turbo 7.54 / multilingual 11.31 / minimax-turbo 8 / minimax-hd 13 / qwen3 12 / gemini-flash 7), voice/minimax_voice (17)/qwen3_voice (9)/gemini_voice (30), language_code, speed, style, timestamps, voice_id | MiniMax+Qwen+Gemini+ElevenLabs одним id; цена зависит от `engine` (tiers) |
| `elevenlabs-v4-tts` | за 1000 символов | 10.84 | exact | text | engine (v4/turbo), voice (21, def Rachel), voice_id, language_code (14, def auto), stability, similarity_boost, apply_text_normalization (3), output_format (3), seed | Премиум: теги эмоций `[whispering]`, `[excited]`; до 5000 симв.; движок Turbo вдвое дешевле |
| `gemini-tts` | за 1000 символов | 10.29 | **estimate** | text | engine (flash), gemini_voice (30), accent (8), style (6), pace (4), audio_profile, scene, temperature | Диалоговый (speakers + dialogue_turns); алиас `gemini-3.1-flash-tts`; списание ≤ указанной цены |
| `elevenlabs-dialogue` | за 1000 символов | 12.62 | exact | text | stability; реплики — массив `dialogue[]` (текст+голос на реплику) | Многоголосый диалог |
| `suno-speech` | за озвучку | 10.82 | exact | text | mode (simple/advanced), script, tone, backgroundMusic (toggle), vocalGender (3), variety (5) | Озвучка сценария с фоновой музыкой; **не** TTS с фикс. голосом; возвращает 2 аудиофрагмента |

### 3.2 SFX — эффекты (4)

| id | unit | от ₽ | цена | вход | Параметры для UI | Примечания |
|---|---|---|---|---|---|---|
| `elevenlabs-sound-effects-v2` | за файл | **1.36** | exact | text | duration (23 опции 0.5–22 с, def 5), prompt_influence (def 0.3), loop (toggle), output_format (2) | Описание до 450 симв. (лучше en); бесшовная петля; цена зависит от длительности |
| `mirelo-sfx-2` | за файл | 6.77 | exact | text | duration (11 опций 1–60 с, def 5), negative_prompt, output_format (2), seed | Описание до 2000 симв.; ⚠️ в `note` провайдера тарификация «за секунду звука» при `unit=за файл` → см. §4.4 |
| `mirelo-sfx-2-video` | за секунду | 1.354 | exact | **text, video** | video_url (req), preserve_speech (toggle, def true), negative_prompt, output_format, seed | Озвучка ролика: синхронные звуки на всю длину видео (до 60 с) |
| `suno-sfx` | за файл | 15.71 | exact | text | version (3, def v6), sound_loop (toggle), sound_tempo, sound_key (25, def Any) | Дорогой, но с петлёй/тональностью/темпом |

### 3.3 Музыка — текст→трек (5)

| id | unit | от ₽ | цена | вход | Параметры для UI | Примечания |
|---|---|---|---|---|---|---|
| `suno` | за трек | **6.29** | exact | text | version (3, def v6), instrumental (toggle), style, title, negativeTags, vocalGender (3), styleWeight, weirdnessConstraint, audioWeight, personaId, personaModel (3), duration | Самая популярная; маршруты: **`suno:white` 6.29** / `suno` 10.82 / `suno:amber` unavailable; tiers по `personaModel` (без персоны 6.29, с персоной 10.82) |
| `suno-replace-section` | за трек | 4.51 | exact | text | source_task_id (req), source_track, source_audio_id, infill_start_s (req), infill_end_s (req), full_lyrics, tags, title | Замена фрагмента **ранее сгенерированного** трека — в v1-шит только при наличии истории Suno-задач |
| `lyria-3-5` | за трек | 8.13 | exact | text | lyrics, title, bpm, seed | Google Lyria 3.5; маршруты: **white 8.13** / flow 13.54 |
| `acestep-v15-xl-sft` | за секунду | 0.1403 | exact | text | lyrics, duration (def 30, диапазон 30–500 с), instrumental (toggle), language_code | Дешёвые длинные треки: 30 с ≈ 4.2 ₽, 500 с ≈ 70 ₽ |
| `elevenlabs-music` | за трек | 40.62 | exact | text | duration (5 опций, 10 с–3 мин, def 30), force_instrumental (toggle), output_format (2), seed | Премиум-музыка по описанию жанра/настроения/инструментов |

### 3.4 Не в v1-шите (но генеративные по природе)

- **Музыка с аудио-входом (6):** `suno-mashup` (2 URL), `suno-add-instrumental`, `suno-add-vocals`, `suno-cover-audio`, `suno-extend-audio` (все по 10.82 ₽/трек, нужен `upload_url`), `suno-cover-generate` (7.54, нужен `source_task_id`).
- **Трансформации аудио (9):** `elevenlabs-dubbing` (4.97 ₽/с, до 30 мин, 90+ языков), `elevenlabs-voice-changer` (21 ₽/1000 символов ≈ 1 мин исходника, нужен клонированный голос), `elevenlabs-audio-isolation` (21.12 ₽/мин), `forced-alignment` (14.08 ₽/1000 символов текста, **отдаёт готовый SRT** — см. §5), `suno-stem` (12.57), `suno-timestamped-lyrics` (0.45), `suno-wav` (0.2–0.36), `suno-midi` (0.25), `suno-persona` (0.25).

## 4. Рекомендации для UI (для @kodi, задача `t_f1891f4c`)

### 4.1 Состав шита аудио-режима

Три группы с заголовками; внутри — сортировка по цене «от»:

1. **Голос (TTS)** — показывать: все 19 `class=tts`, кроме трёх, требующих предварительного клонирования голоса (`voice-clone-tts`, `mimo-v2-5-tts-voiceclone`, `elevenlabs-voice-changer`) — их выносить в отдельный пункт «Мой голос» (нужен `voice_id` из `POST /api/media/voices`; контракт §«Клонирование голоса»).
   - Дефолт: **`gpt-4o-mini-tts`** (0.07 ₽/1000 симв., русский, instructions, 4096 симв.).
   - Бюджет: `mimo-v2-5-tts` (0.01), `chatterbox-turbo` (0.13) — с пометкой «качество не проверено».
   - Золотая середина: `gemini-3.8-flash-tts-lite` (1.7, 30 голосов, русский авто), `grok-tts` (2.04, до 15000 симв. — для длинных текстов), `qwen3-tts` (2.71, явный выбор языка).
   - Премиум: `elevenlabs-v4-tts` (10.84, теги эмоций) и `tts`-агрегатор (6 движков в одной модели).
   - `kokoro-tts` помечать «только английский»; `chatterbox-tts` — «до 300 симв./запрос».
2. **Музыка** — показывать: `suno` (маршрут `suno:white` 6.29 ₽/трек — дефолт), `lyria-3-5` (white 8.13), `acestep-v15-xl-sft` (0.1403 ₽/с — для длинных треков), `elevenlabs-music` (40.62 — премиум). `suno-replace-section` не показывать, пока нет истории Suno-задач. Музыка с аудио-входом (кавер/мэшап/продолжение) — фаза 2 вместе с загрузкой аудио.
3. **Эффекты (SFX)** — показывать: `elevenlabs-sound-effects-v2` (дефолт, от 1.36 ₽, петли), `mirelo-sfx-2` (длинные эффекты до 60 с), `suno-sfx` (с тональностью/темпом). `mirelo-sfx-2-video` — только если рядом есть видео-элемент (нужен `video_url`).

**Не показывать в шите никогда:** 12 STT (§5), `suno-stem`, `elevenlabs-audio-isolation`, `suno-midi`, `suno-persona`, `suno-wav`, `suno-timestamped-lyrics`, `forced-alignment`, `suno-lyrics`, `suno-style-boost` (аналитика/утилиты/помощники).

### 4.2 Чипы дока: какие параметры выносить

Правило: рендерить `axes[]` модели из дампа универсально (в КМП уже парсится `MediaKind.Audio/Music` — [[projects/pots-media-expansion-plan]]).

| Тип axis | Контрол | Куда |
|---|---|---|
| `select` с ≤ 6 опциями | чип-строка | док |
| `select` с > 6 опциями (голоса 9–30) | чип с дропдауном/шитом выбора | док (значение) |
| `toggle` (`instrumental`, `loop`, `backgroundMusic`) | чип-переключатель | док |
| `text` короткий (`bpm`, `speed`, `duration`) | инлайн-поле | док |
| `text` длинный (`lyrics`, `style`, `instructions`, `negative_prompt`, `script`) | многострочное поле | раскрытие шита |
| обязательные (`required`) | сверху, с `*` | док |
| технические (`seed`, `sample_rate`, `bitrate`, `channel`, `output_format`/`response_format`, `similarity_boost`, `stability`) | скрыть в «Ещё» | раскрытие |

Обязательные чипы для любой аудио-модели: **цена + единица** (бейдж) и **промпт** (все генеративные требуют `prompt`, кроме `suno-speech` с `script` и моделей с `lyrics`). Характерные наборы:

- TTS: `voice` + `language` (def `auto`) + скорость (`speed`/`speaking_rate`); текст озвучки — основное поле ввода.
- Музыка: `duration` + `instrumental` + `lyrics`/`style` (раскрытие) + `bpm` (Lyria) + `version` (Suno).
- SFX: `duration` + `loop` + описание; `sound_key`/`sound_tempo` у `suno-sfx` — в «Ещё».

### 4.3 Отображение цены с единицей

- Строку `unit` из каталога показывать **как есть** — она уже на русском: «за 1000 символов», «за трек», «за секунду», «за файл», «за озвучку», «за минуту».
- Формат бейджа: `от {fromRub} ₽ {unit}` → «от 0,07 ₽ за 1000 символов». Десятичный разделитель — запятая (локаль ru).
- `priceStatus=estimate` → префикс «≈» и тултип из `priceWarning`/`estimateReason` (`gemini-tts`: «спишется не больше этой цены»; `suno-lyrics`: «нет опубликованного тарифа»).
- **Metered-модели** (`priceUnit=1000_characters`/`second`, факт зависит от входа): перед отправкой показывать предварительную цену через `POST /api/media/price` (контракт §Price quote) и передавать `quote_id` + `max_price_rub` в `generate`. Для TTS можно считать локально: `ceil(символы/1000) × fromRub` как нижняя оценка.
- **`tiers`** (цена зависит от выбора в `opts`): поле непустое у **18** моделей дампа, из них в шите — **7** (поправлено 2026-11-13 по вердикту @linus, сверено с дампом):
  - переменные (пересчёт бейджа при смене чипа): `tts` (6 тиров по `engine`: 7–13 ₽), `gemini-3.8-flash-tts` (2 по `engine`: flash 5.66 / lite 3.78), `suno` (3 по `personaModel`: пусто 6.29 / значение 10.82), `gemini-3.8-flash-tts-lite` (30 по `gemini_voice` — все 1.7, цена не меняется);
  - плоские (один тир с пустым `when:{}` — фикс-цена в `tiers[0].priceRub`, пересчёт не нужен): `elevenlabs-dialogue` (12.62), `suno-speech` (10.82), `suno-replace-section` (4.51).
  Правило рендера: матчить значение чипа о `when` в `tiers[]`; нет совпадения/выбора → `fromRub` с «от». Остальные 11 моделей с `tiers` — вне шита (мэшапы/стемы/утилиты).
- **`variants`** (маршруты): показывать минимальную (это уже `fromRub`); смену маршрута (`:white`/`:flow`) в v1 не выносить.
- Идемпотентность: повтор запроса — с тем же `Idempotency-Key` и тем же телом (`quote_id`/`max_price_rub`), иначе двойное списание (контракт §Jobs poll, правило от 2026-11-12).

### 4.4 Расхождения и ловушки каталога

- **`mirelo-sfx-2`**: `unit=«за файл»`, `priceUnit=request`, но в `note` провайдера — «Цена за секунду звука». Перед генерацией обязательна котировка `POST /api/media/price`; в UI показывать `от 6,77 ₽ за файл` с пометкой «уточняется по длительности».
- **`speech-to-text`**: в снапшоте контракта (2026-11-10) — 1 ₽/мин; в свежем каталоге `fromRub=0.65` (маршрут `:elevenlabs`; `:flow` = 1 ₽). Каталог — источник правды, контракт обновить при следующем проходе.
- **`suno-cover-generate`**, **`suno-midi`**, **`suno-persona`**: цены назначены владельцем PlusVibe (Pixel публиковал их по 0 кредитов) — помечены в `note`, поведение может измениться.
- `gemini-tts` имеет алиас `gemini-3.1-flash-tts` (принимается в `model`).
- `videoDisabled=false`, но для `mirelo-sfx-2-video` нужен реальный `video_url`.

## 5. STT-модели для субтитров (12)

Все: `kind=audio`, `task=asr`, вход **`opts.audio_url`** (публичный HTTPS) **или `opts.audio_file_id`** (`anyOf` в `requiredInputs`; по контракту также парное поле `audio_base64`), `prompt:""`, файл ≤ 25 МБ, форматы `mp3/wav/m4a/flac/ogg/webm` (контракт §STT). Выход: **`resultText`** (`resultUrls` пуст) — контракт §Jobs poll. У всех `minimumChargeRub=0.01` (минимальное списание 1 копейка).

| id | ₽/мин (от) | Диаризация | Языки/опции | Примечание |
|---|---|---|---|---|
| `qwen3-asr-0.6b` | **0.02** | – | 30 языков вкл. русский; `language_code`; **таймкоды фраз**; multipart audio | Лучший бюджет для субтитров |
| `whisper-large-v3-turbo` | **0.02** | – | `language_code`; тарификация посекундно по измеренной длительности | Быстрый Whisper |
| `nemotron-asr-multilingual` | 0.02 | – | `language_code`; посекундно | NVIDIA, стриминговый |
| `whisper-large-v3` | 0.06 | – | `language_code` | Точнее turbo, втрое дороже |
| `qwen3-asr-1.7b` | 0.06 | – | `language_code`; **распознавание + перевод** | |
| `nemotron-3-diarization` | 0.08 | ✅ отрезки говорящих | `language_code` | Дешёвая диаризация |
| `universal-2` | 0.35 | ✅ `speaker_labels` (toggle) | **русский** (`language_code=ru`/авто), `keyterms_prompt` | Русский фокус; посекундно |
| `voxtral-small-24b-2507` | 0.42 | – | `language_code`; multipart | Mistral |
| `gpt-4o-mini-transcribe` | 0.49 | – | `language_code` | Списанию по токенам, не выше границы на карточке |
| `speech-to-text` | 0.65 (`:elevenlabs`; `:flow` 1.0) | ✅ `diarize` (toggle, def true) | `language_code`, `tag_audio_events`, `keyterms` | Scribe v2; длительность измеряется и озвучивается до списания |
| `gpt-4o-transcribe` | 0.68 | – | `language_code` | |
| `gpt-4o-transcribe-diarize` | 3.59 | ✅ говорящие A, B… + таймкоды фраз | `language_code`; `response_format=diarized_json/verbose_json` | Премиум-диаризация |

Рекомендации для потока «субтитры»: дефолт **`whisper-large-v3-turbo`** (0.02 ₽/мин, мультиязычный); для русского со спикерами — `universal-2` (0.35) или `gpt-4o-transcribe-diarize` (3.59); бюджет с таймкодами фраз — `qwen3-asr-0.6b` (0.02). Вход из приложения: свой файл грузить через `POST /v1/files` → `audio_file_id` (публичного HTTPS у локального файла нет; контракт §«Свои файлы»).

Бонус для точных субтитров: `forced-alignment` (utility, не в шите) — по аудио + готовому тексту возвращает тайминги каждого слова и **готовый SRT-файл** (14.08 ₽/1000 символов текста). Также `voice-clone-tts` и `inworld-realtime-tts-1-5-max` отдают тайминги слов вместе с озвучкой — субтитры к собственной генерации бесплатно.

## 6. Лучшие практики UX аудиоредакторов (для @joni, задачи `t_f1891f4c`/`t_e9b7ed2f`)

1. **Волна только когда форма полезна.** Волнограмма оправдана, если по ней делают действия: найти тишину между репликами, отрезать ложный старт; иначе достаточно прогресс-бара. Тест: «может ли пользователь действовать по форме?» — https://mobileappdesign.io/blog/audio-player-ui
2. **Неразрушающая обрезка за ручки на краях клипа.** Верхние ручки — trim (обрезанное не удаляется, край можно оттянуть назад), нижние «часики» — растяжение/скорость; трим из выделения — обратной операцией. — https://www.audacityteam.org/manual/clips/trim-and-stretch/ и https://www.audacityteam.org/manual/clips/
3. **Скорость: клип-стрейч с бейджем процента + предпрослушивание без изменения файла.** Изменение скорости с сохранением высоты — отдельно (для речи — «optimize for voice», чтобы не было «бурундука»); Play-at-Speed (0.01×–3×) для предпросмотра; повторный рендер стрейча теряет качество. — https://support.audacityteam.org/basics/audacity-editing, https://support.audacityteam.org/au3/audio-editing/speeding-up-and-slowing-down-audio
4. **Прогрессивная перемотка и транспорт.** Грубая перемотка по всему треку → точная с аудио-предпрослушкой → snap к маркерам; транспортные кнопки по индустриальным иконкам и J/K/L. — https://graceguo.io/project/notetracks
5. **Фейды — отдельные длительности с явным нулём.** Fade In/Out — собственные значения (0 = выключено, никаких скрытых дефолтов), привязка к границе клипа после обрезки, предпрослушивание у изменённой границы, а не с начала проекта. — https://screensdesign.com/articles/mobile-audio-fade-ducking-controls-ux
6. **Оригинал всегда под рукой.** Обработанный результат сравнивается с оригиналом (A/B); состояние «включено» отдельно от сохранённого значения слайдера; при отключении показывать, что слышно: оригинал, байпас или ранее обработанная версия. — https://screensdesign.com/articles/mobile-audio-noise-reduction-ux
7. **Мобильная планка плеера.** Тач-цель скраббера ≥ 44 pt независимо от толщины линии, время с обоих концов, мини-плеер + полный экран, скорость/таймер на основной поверхности (а не в меню); для голосового контента сильнее работает пара «плейбек + транскрипт», где тап по строке перематывает. — https://mobileappdesign.io/blog/audio-player-ui, https://medium.com/anchor/rethinking-audio-editing-on-mobile-684d754c6460

Смежные артефакты дизайна: `/home/al/wiki/design/pots-media-icons/` (иконки) и `/home/al/wiki/design/pots-media-ux-spec.md` (спек) — от @joni, задача `t_5b9da93f`.

## 7. Контракт: памятка для реализации (из `plusvibe.md`)

- Тот же поток, что фото/видео: `POST /api/media/generate` → 202 `jobId` → polling `GET /api/media/jobs/{id}` (3–5 с, экспоненциально до 30 с; `submitting` — нетерминальный, дубль не создавать) → `success` с `resultUrls[]` (для STT — `resultText`).
- Тело: `model`, `prompt` (для STT — пустая строка), `opts{...}` (параметры модели), `strict`, опционально `quote_id` + `max_price_rub`. Неизвестный параметр игнорируется, при `strict` — 422.
- Ссылки результата живут **5 дней** → скачивать байты сразу (задача `t_f1891f4c` это уже требует).
- Файлы пользователя: только публичный HTTPS в `*_url` полях; локальные файлы — через `POST /v1/files` (`audio_file_id`) или `audio_base64` (`data:<mime>;base64,…`).
- Платные запросы к провайдеру в задачах разработки запрещены ([[projects/pots-media-expansion-plan]]) — всё выше проверено по публичному каталогу и доку, без генераций.

## Источники

- `GET https://plusvibeapi.ru/api/media-catalog` — снят 2026-11-13 (дамп: [[design/pots-audio-catalog-dump]])
- `/run/media/al/ARCHIVE/code/pusi/apps/pots/docs/knowledge/media-providers/plusvibe.md` (okf 0.2, 2026-11-10)
- https://plusvibeapi.ru/docs/media-models, https://plusvibeapi.ru/docs/media-generation
- UX: https://mobileappdesign.io/blog/audio-player-ui · https://www.audacityteam.org/manual/clips/trim-and-stretch/ · https://support.audacityteam.org/basics/audacity-editing · https://support.audacityteam.org/au3/audio-editing/speeding-up-and-slowing-down-audio · https://graceguo.io/project/notetracks · https://screensdesign.com/articles/mobile-audio-fade-ducking-controls-ux · https://screensdesign.com/articles/mobile-audio-noise-reduction-ux · https://medium.com/anchor/rethinking-audio-editing-on-mobile-684d754c6460
