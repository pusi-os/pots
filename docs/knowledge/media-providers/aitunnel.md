---
okf_version: "0.2"
providers: [aitunnel]
last_verified: 2026-11-22
base_urls: [https://api.aitunnel.ru]
---

# AITUNNEL — медиа-контракт (snapshot 2026-11-22)

Источник правды: публичные каталоги `GET https://api.aitunnel.ru/public/aitunnel/models[/{chat,images,videos}]` (без ключа, 200) + первоисточники документации в markdown: `https://aitunnel.ru/docs/images.md`, `https://aitunnel.ru/docs/videos.md`, `https://aitunnel.ru/docs/models.md`, `https://aitunnel.ru/docs/errors.md`, `https://aitunnel.ru/docs/limits.md`, `https://aitunnel.ru/docs/payments.md`, индекс `https://aitunnel.ru/llms.txt`.

**Ничего платного не запускалось.** Генерация требует заголовка `Authorization: Bearer <ключ>` — ключа AITUNNEL на comp нет. Живыми `curl` проверены только публичные каталоги и коды ответа (существование маршрута, требование авторизации).

## Base URLs и авторизация

| Роль | Значение |
|---|---|
| Base | `https://api.aitunnel.ru` (OpenAI-совместимый: `…/v1/chat/completions`, `…/v1/images/generations`, `…/v1/videos`) |
| Auth | `Authorization: Bearer <ключ>` **или** `x-api-key: <ключ>`; ключ без префикса `Bearer` в `Authorization` отклоняется |
| `ApiKeyAuthStyle` для `Service.AiTunnel` | `BEARER` (уже так). `RAW` (как у GPTunnel) здесь **не сработает** |
| Ключ для каталога | не нужен для `/public/aitunnel/models*` (живой 200), **нужен** для `/v1/models` (живой 401) |
| Ключ для генерации | нужен: `POST /v1/images/generations`, `/v1/images/edits`, `/v1/videos`, `/v1/media`, `GET /v1/videos/{id}` — все 401 без ключа |

Живые коды без ключа (2026-11-22): `/public/aitunnel/models` → 200 (617 011 байт), `/public/aitunnel/models/images` → 200 (84 767), `/public/aitunnel/models/videos` → 200 (71 559), `/public/aitunnel/models/chat` → 200 (281 261); `GET /v1/models`, `GET /v1/videos/{id}`, `GET /v1/videos/{id}/content`, `GET /v1/jobs` → 401; `POST /public/aitunnel/models` → 404. **Оговорка:** AITUNNEL проверяет ключ раньше маршрута, поэтому 401 на `POST /v1/...` сам по себе не доказывает существование пути — существование `/v1/images/generations`, `/v1/images/edits`, `/v1/videos`, `/v1/videos/{id}`, `/v1/videos/{id}/content` подтверждено документацией, а не кодом ответа.

## Каталог моделей (публичный, без ключа)

| Метод | Путь | Что отдаёт |
|---|---|---|
| GET | `/public/aitunnel/models` | **все** группы одним объектом: `{chat:{…}, decisions:{…}, embeddings:{…}, images:{…}, moderations:{…}, rerank:{…}, speech:{…}, transcriptions:{…}, videos:{…}}` |
| GET | `/public/aitunnel/models/images` | только картинки (50 моделей) |
| GET | `/public/aitunnel/models/videos` | только видео (35 моделей) |
| GET | `/public/aitunnel/models/chat` | чат (202) — для медиа не нужен |
| GET | `/v1/models` | OpenAI-совместимый список, **требует ключа** |

Живой срез: `images` — **50** моделей, `videos` — **35**, `chat` 202, `embeddings` 27, `speech` 35, `transcriptions` 27, `decisions` 34, `rerank` 10, `moderations` 1. `id` — **без префикса провайдера** (`seedream-4.5`, не `bytedance-seed/seedream-4.5`; прямое указание доки).

Каталог картинок (`images[id]`) — это готовая витрина для UI:

```
provider, short_description, description,
modalities: { input:[text,image], output:[image] }, created,
min_price_per_image, max_price_per_image,          // ₽ за картинку, «ориентировочный диапазон»
supported_resolutions[]      // "1K","2K","4K"; пусто = не валидируется
supported_aspect_ratios[]    // пусто = не валидируется
supported_sizes[]            // точные пиксели
custom_size: { step, min_side, max_side, max_ratio, min_pixels, max_pixels } | null
gif_limits: { frame_count:{min,max}, frame_duration_ms:{min,max} } | null
max_input_image_bytes, supports_combined_resolution_and_aspect_ratio,
supported_quality[], supported_output_formats[], supported_background[],
supports_seed, max_n, max_input_references,
allowed_passthrough_parameters[],
supports_generation, supports_edit,
text_tokens_input_price, image_tokens_input_price, image_tokens_output_price   // ₽ за 1M, у токенных моделей
```

Каталог видео (`videos[id]`) добавляет то, чего нет у Polza одним куском:

```
min_price_per_second, max_price_per_second,        // ₽ за секунду
size_prices: {"1280x720": 5.1, "1920x1080": 23.8, …}          // ₽/с по каждому точному размеру
size_prices_no_audio: { … }                        // ставка без звука
supported_resolutions[], supported_aspect_ratios[], supported_sizes[],
supported_durations[]        // [4,5,6,…,15] — допустимые значения duration
supported_frame_images[]     // ["first_frame","last_frame"]
generate_audio: true|false|null                    // null = переключателя нет, звук непредсказуем
supports_seed, supports_input_references,
billing_only_parameters[]    // "duration","size" — провайдеру не уходят
prompt_optional, requires_input_video, supports_previous_job,
upscale_factor: {min,max}|null, creativity: [0,1]|null,
price_per_megapixel_second, price_per_megapixel_second_creative, max_output_megapixels,
max_source_duration, max_source_megapixels
```

## Изображения — синхронный путь

`POST https://api.aitunnel.ru/v1/images/generations`. «Запрос синхронный — ответ с готовым изображением приходит в том же HTTP-ответе, без опроса статуса». Ждём ответа провайдера не более **9 минут**, дальше `504` **без списания**.

Тело:

| Поле | Тип | Обяз. | Смысл |
|---|---|---|---|
| `model` | string | да | id из каталога (без префикса провайдера) |
| `prompt` | string | да | описание |
| `n` | integer | нет | 1 — по умолчанию; потолок — `max_n` модели (у `gpt-image-*` 10) |
| `resolution` | string | нет | тир: `1K`, `2K`, `4K` (+`768`, `1.5K` у `flux-3-image*`); сверяется с `supported_resolutions`, если список непуст |
| `aspect_ratio` | string | нет | `16:9`, `1:1`, …; сверяется с `supported_aspect_ratios`, если непуст |
| `size` | string | нет | тир (`2K`), точные пиксели `WIDTHxHEIGHT` или `auto`; **нельзя** комбинировать пиксели с `resolution`/`aspect_ratio` |
| `quality` | string | нет | `auto`, `low`, `medium`, `high`, `xhigh`, `max` (xhigh/max — только gpt-image-2.6/2.5 flare/sunburst) |
| `output_format` | string | нет | `png`, `jpeg`, `webp`, `svg`, `gif` |
| `frame_count` | integer | нет | только с `gif`: 2–16 (по умолч. 9) |
| `frame_duration_ms` | integer | нет | только с `gif`: 20–2000 (по умолч. 200) |
| `background` | string | нет | **строго** по `supported_background` модели, иначе 400 |
| `output_compression` | integer | нет | 0–100 для jpeg/webp |
| `seed` | integer | нет | универсальный, но эффект только при `supports_seed: true` |
| `input_references` | array | нет | референсы для image-to-image (см. ниже) |
| `<passthrough>` | — | нет | ключи из `allowed_passthrough_parameters` прямо на верхнем уровне (`moderation`, `complexity`, `web_grounding`) |

Разделение проверок (доки, «Параметры запроса»): строго валидируются `background`, `input_references`, а также `resolution`/`aspect_ratio` — но только если у модели непустой список; `size`, `quality`, `output_format`, `seed` — универсальные, проверяется только формат значения; незначащий параметр модель «применит или проигнорирует без ошибки».

**Ответ** (готовое изображение, без поллинга):

```json
{ "created": 1234567890,
  "data": [ { "b64_json": "iVBORw0KGgo…", "media_type": "image/png" } ],
  "model": "seedream-4.5",
  "usage": { "cost_rub": 3.4, "balance": 1245.6 } }
```

`data[i].b64_json` — base64 (URL-поля **нет**), `data[i].media_type` — MIME (`image/png|jpeg|webp|gif`), `usage.cost_rub` — факт списания, `usage.balance` — баланс после. Если провайдер не назвал цену сразу — ответ приходит с заголовком `X-AITunnel-Cost-Pending: true` и **без** `usage.cost_rub`.

### image-to-image и правка

| Способ | Контракт |
|---|---|
| Тот же `POST /v1/images/generations` + `input_references` | `[{ "type": "image_url", "image_url": { "url": "<https-URL или data:…;base64>" } }]`; потолок — `max_input_references` модели (0 = правка не поддерживается), проверять `supports_edit` |
| `POST /v1/images/edits` (multipart, привычный OpenAI) | `image` (файл ≤25 МБ) или `image[]` + `model`, `prompt` и те же параметры; «под капотом делает то же самое, что `input_references`» |
| Маска | в документированном контракте `/images/edits` только `image`/`image[]`; `max_input_image_bytes` описан для «`image` или `mask`», отдельного поля маски в таблице параметров нет — **не проверено** |

Локальный файл уходит как `data:image/png;base64,…` прямо в `url` — предварительная загрузка в хранилище не нужна.

## Видео — асинхронный путь

Три шага (доки, «Генерация видео»): `POST /v1/videos` → `GET /v1/videos/{id}` → `GET /v1/videos/{id}/content?index=0`.

| Метод | Путь | Ответ |
|---|---|---|
| POST | `/v1/videos` | **`202 Accepted`**: `{ id, polling_url, status:"pending" }`; `usage` на этом шаге **нет** |
| GET | `/v1/videos/{id}` | `{ id, polling_url, status, unsigned_urls?[], model?, usage?{cost_rub} , error? }` |
| GET | `/v1/videos/{id}/content?index=0` | бинарь MP4; нужен `Authorization`, либо `?token=<ключ>` (только на этом маршруте) |
| GET | `/v1/videos?status=&active=true&limit=&after=` | список задач (`data`, `has_more`, `last_id`) |
| GET | `/v1/jobs?active=true` | все асинхронные задачи аккаунта: `active_count`, `active_reserved_rub`, `kind: video\|batch`, `reserved_rub`, `cost_rub`, `refunded_rub` |

Тело `POST /v1/videos`:

| Поле | Тип | Смысл |
|---|---|---|
| `model` | string | да |
| `prompt` | string | обычно да; пусто допустимо при `prompt_optional: true` (апскейл) |
| `duration` | integer | секунды; должен входить в `supported_durations`, если список непуст; если пуст — обязателен, любое положительное; `-1` не принимается |
| `resolution` | string | `360p`…`1080p`, `1K`, `2K`, `4K` (по `supported_resolutions`) |
| `aspect_ratio` | string | `16:9`, `9:16`, `1:1`, `4:3`, `3:4`, `21:9`, `9:21`; `adaptive` не принимается |
| `size` | string | точные пиксели `WIDTHxHEIGHT` — альтернатива `resolution`+`aspect_ratio` |
| `frame_images` | array | опорные кадры: `[{type:"image_url", image_url:{url}, frame_type:"first_frame"\|"last_frame"}]` |
| `input_references` | array | `image_url`; у моделей с `"video"` во входе — ещё `video_url` и (если провайдер умеет) `audio_url` |
| `upscale_factor` | number | в диапазоне `upscale_factor.min…max` |
| `creativity` | number | `0` Precise / `1` Creative |
| `generate_audio` | boolean | только при `generate_audio: true` модели, иначе 400 |
| `seed` | integer | по `supports_seed` |
| `previous_job_id` | string | `vid_…` завершённой задачи той же модели (продление/правка), при `supports_previous_job: true` |
| `provider` | object | passthrough: `provider.options.<slug>.parameters` |

Статусы: `pending` → `in_progress` → `completed` | `failed` | `expired` | `cancelled`. Терминальные — последние четыре; `expired` наступает по жёсткому `expires_at` (**3 часа** для видео). Отмены через API нет. Результаты: `unsigned_urls[]` указывают на `/v1/videos/{id}/content?index=N` и «не имеют срока действия с истекающей подписью», но задачи хранятся **3 месяца**, а сам файл у провайдера может пропасть раньше — скачивать сразу.

## Прайс

Отдельного эндпоинта котировки **нет**. Модель оплаты — серверный резерв:

1. При отправке резервируется **worst-case** (`max_price_per_image × n` для картинок; для видео — самый дорогой размер/длительность, если `size`/`duration` не заданы явно).
2. По завершении списывается **факт** и разница возвращается; при `failed`/`expired`/`cancelled` возвращается весь резерв.
3. Факт — в `usage.cost_rub` ответа (картинки — сразу в ответе генерации, видео — в `GET /v1/videos/{id}` после расчёта).
4. Не хватило на резерв → `402` `insufficient_balance` / `budget_exceeded`, нужная сумма в `error.metadata.required_rub`; там же `available_rub`.

Единицы в каталоге: картинки — `min_price_per_image`/`max_price_per_image` (**₽ за картинку**, «ориентировочный диапазон, комиссия включена»; у токенных моделей реальная цена зависит от объёма); видео — `min/max_price_per_second` (**₽ за секунду**) и точные `size_prices` (₽/с по каждому `WIDTHxHEIGHT`); апскейл — `price_per_megapixel_second` (₽ за мегапиксель-секунду).

Живой срез (₽, 2026-11-22):

| Модель | Единица | Цена | Примечание |
|---|---|---|---|
| `gpt-image-1-mini` | за картинку | от 0,432 до 9,072 | токенная, диапазон; `n` ≤10, референсов ≤16 |
| `recraft-v4.1-flash-v2` | за картинку | 1,26 ровно | только текст (`max_input_references: 0`), `n` ≤6 |
| `mai-image-2.7-flash` | за картинку | от 0,54 до 11,70 | правка есть, референсов ≤5, `n` ≤1 |
| `flux-3-image-klein` | за картинку | 1,44 (768) … 6,48 (4K) | цена по `resolution`, референсы не меняют |
| `seedream-5-0-flash` | за картинку | 3,24 (1K и 2K) | референсы бесплатно, ≤14 |
| `mischief-1` | **за секунду** | 0,85 (854x480) · 1,70 (1280x720) | `duration` только 3 или 5 → 3 с 480p ≈ **2,55 ₽**, самое дешёвое видео |
| `grok-imagine-video-1.5-lite` | за секунду | 3,40 (480p) · 5,10 (720p) · 23,80 (1080p) | 1–15 с, `first_frame` |
| `seedance-2.0-fast` | за секунду | 3,8556 (480x480) … 20,2419 (1680x720) | 4–15 с, `first_frame`+`last_frame`, звук |
| `veo-3.1-lite` | за секунду | 5,10 без звука / 8,50 со звуком (720p) · 8,50/13,60 (1080p) | 4/6/8 с |
| `flux-video-upscale` | за Мп·с | 5,95 Precise / 8,50 Creative | требует исходное видео |

## Референсы (сводка)

| Что нужно | Поле | Лимит |
|---|---|---|
| Картинка-основа / правка | `input_references[{type:"image_url", image_url:{url}}]` (генерация) или `image`/`image[]` (multipart `/images/edits`) | `max_input_references` (0…20) |
| Первый/последний кадр видео | `frame_images[{…, frame_type:"first_frame"\|"last_frame"}]` | по `supported_frame_images` (1–2) |
| Визуальный референс видео | `input_references` (несколько, порядок сохраняется) | AITUNNEL не ограничивает, решает модель (Seedance 2.0 ≈9) |
| Видео на вход (v2v/апскейл) | `input_references[{type:"video_url", …}]` | нужна `"video"` в `modalities.input`; при `requires_input_video: true` обязательно |
| Аудио на вход (лип-синк, аватары) | `input_references[{type:"audio_url", …}]` | по модели |
| Продление/правка готового ролика | `previous_job_id` | `supports_previous_job: true`, та же модель |

## Параметры модели для UI

Всё, что нужно для контролов, лежит в публичном каталоге — **отдельный запрос на модель не нужен**:

| UI-контрол | Откуда |
|---|---|
| Разрешение | `supported_resolutions` (+ `supported_sizes` для точных пикселей, `custom_size` для произвольного `WIDTHxHEIGHT`) |
| Aspect ratio | `supported_aspect_ratios` |
| Длительность | `supported_durations` (видео) |
| Seed | `supports_seed` |
| Аудио в видео | `generate_audio` (`true` — переключатель, `false` — никогда, `null` — непредсказуемо) |
| Кол-во картинок | `max_n` |
| Референсы | `max_input_references`, `supports_edit`, `supported_frame_images`, `supports_input_references` |
| Качество / формат / фон | `supported_quality`, `supported_output_formats`, `supported_background` |
| Доп. ручки провайдера | `allowed_passthrough_parameters` (`moderation`, `complexity`, `web_grounding`, `safety_tolerance`, `version`) |
| Negative prompt | **нет ни у картинок, ни у видео** — в контракте такого поля нет |

## Лимиты, ошибки, идемпотентность

| `error.type` | HTTP | Когда | Повторять |
|---|---|---|---|
| `invalid_request` | 400 (413, 409) | битый запрос/значение | нет |
| `authentication_failed` | 401 | нет ключа / нет `Bearer` / ключ отозван | нет |
| `permission_denied` | 403 | IP/модель/права ключа, модерация | нет |
| `model_not_found` | 404 (400 в генерации) | нет модели | нет |
| `insufficient_balance` / `budget_exceeded` | 402 | нет денег на резерв (`metadata.required_rub`) | после пополнения |
| `rate_limited` | 429 | лимит провайдера или >500 одновременных запросов с аккаунта | да, по `Retry-After` |
| `provider_error` | 502 / статус провайдера | ошибка/модерация у апстрима | 4xx — нет |
| `provider_unavailable` | 503/502/504 | никто не взял запрос | да |
| `upstream_invalid_response` | 502 | пустой/битый ответ | да |
| `service_unavailable` | 503 | сбой AITUNNEL | да, по `Retry-After` |
| `internal` | 500 | внутренняя ошибка | да |

Таймауты: картинки — `504` после **9 минут** ожидания провайдера, **без списания**, клиентский таймаут держать ≥9 мин; видео — `504`, если провайдер не принял задачу за **60 секунд** (резерв возвращается сразу), `503` — сбой на стороне AITUNNEL. Лимиты: RPM/квот нет (только 500 одновременных запросов на аккаунт и защита от аномального потока), JSON-тело ≤50 МБ, multipart ≤100 МБ, файл в `/images/edits` ≤25 МБ, лог-медиа ≤500 МБ.

**Идемпотентность: ключа нет.** Ни в `images.md`, ни в `videos.md`, ни в `errors.md`/`payments.md`/`limits.md`, ни в `llms.txt` нет ни `Idempotency-Key`, ни аналога (проверено `grep -i idempot` по всем скачанным докам — 0 совпадений). Повторная отправка — новая задача. Для видео это частично компенсируется тем, что задача идёт до терминального статуса независимо от опроса и её можно найти через `GET /v1/videos?active=true` или `GET /v1/jobs` — то есть восстановление после потери id возможно, но не по ключу идемпотентности.

## Таблица: поле нашей абстракции → чем закрывается → чего нет

| Метод `MediaProvider` | Чем закрывается у AITUNNEL | Чего нет / что писать самим |
|---|---|---|
| **`listModels(apiKey)`** | `GET /public/aitunnel/models` (без ключа, 200) → группы `images`/`videos`; на модель: `id`, `short_description`, `modalities.input`→`acceptsImageReference`/`acceptsVideoReference`, `min_price_per_image`/`min_price_per_second`→`fromRub`, `unit` («₽/картинка», «₽/сек»), `max_n`, `max_input_references`, `supported_*`→`MediaParamDef` с `options` и `default` (дефолт у видео = первый элемент `supported_*`, у картинок его нет) | Нет `priceStatus` (у картинок только диапазон `min…max` → `estimate`), нет `promptLimit` (лимита промпта в каталоге нет), нет `generationAvailable`/`priceUnavailable`, нет `imageInputModes`/`mode`-роутов, нет `note` (есть `short_description`) |
| **`priceTable(apiKey, modelId)`** | Отдельного эндпоинта **нет**. Для видео строится точно из `size_prices` (+`size_prices_no_audio`): строка = `{opts:{size:"1280x720"}, price: 5.1}` — это готовые `MediaPriceTableRow`. Для картинок — только `min…max` на модель, поразрешенных цен в каталоге **нет** | Нет `GET /price-table`, нет `defaults`, нет `priceStatus`, нет `chargeBasis`. Для картинок таблица получится «диапазон», а не точные строки; точные по разрешению цены есть только в тексте доки у 4–5 моделей (`flux-3-image`, `flux-3-image-klein`, `seedream-5-5`, `seedream-5-0-flash`) — парсить нечего |
| **`quotePrice(apiKey, request)`** | Эндпоинта **нет**. Видео: `size_prices[<size>] × duration` (или `max_price_per_second` при неизвестном размере). Картинки: `min_price_per_image` (оценка снизу) или `max_price_per_image × n` (worst-case, как резерв AITUNNEL). Апскейл: `price_per_megapixel_second × Мп × сек` | Нет `quoteId`/`expiresAt`, серверной гарантии нет: фактическое списание — только `usage.cost_rub` после ответа, а иногда позже (`X-AITunnel-Cost-Pending`) |
| **`submitJob(apiKey, request)`** | Картинки: `POST /v1/images/generations` — **синхронно, до 9 мин**, ответ сразу с `data[].b64_json` + `usage.cost_rub`. Видео: `POST /v1/videos` → `202 {id, polling_url, status:"pending"}` | **Нет `Idempotency-Key`**. Картинки не имеют асинхронного варианта вообще: `submitJob` обязан блокироваться до 9 минут (или уводить в фон) — `MediaJobStatus.Submitting` тут не поможет, промежуточного jobId у картинок нет. Для видео `submitJob` кладёт `jobId` + `Processing`, но `usage`/цена на этом шаге отсутствуют |
| **`pollJob(apiKey, jobId)`** | `GET /v1/videos/{id}`: `status: pending\|in_progress\|completed\|failed\|expired\|cancelled`, `unsigned_urls[]`, `usage.cost_rub`, `error` (строка), `model`. Рекомендованный интервал — 15–30 с | `MediaJobStatus` без `Cancelled`/`Expired` → маппить в `Fail` с `errorCode`. Нет `progress`, нет `advice`/`definitive`, `error` — просто строка (кода ошибки в poll-ответе нет). Для картинок `pollJob` неприменим (нет id) |
| **`downloadResult(apiKey, url)`** | `GET /v1/videos/{id}/content?index=N` — MP4; требует `Authorization: Bearer` **или** `?token=<ключ>`. Для картинок `downloadResult` не нужен: байты уже в `data[].b64_json` | Нет публичных подписанных URL (в отличие от PlusVibe/Polza): результат отдаётся только с ключом → `downloadResult(apiKey, url)` должен слать заголовок (наш интерфейс ключ передаёт — совместимо). Задачи живут 3 месяца, файл у провайдера может пропасть раньше |

## Минимальный путь

**Картинки (самое дешёвое):**
1. `GET /public/aitunnel/models/images` без ключа → 50 моделей с ценами, разрешениями, форматами и лимитами. Витрина готова без ключа.
2. `POST /v1/images/generations` с `{model:"recraft-v4.1-flash-v2", prompt, aspect_ratio:"1:1"}` → 200, `data[0].b64_json`, `usage.cost_rub` ≈ **1,26 ₽**. Синхронно, без референсов, `n` до 6.
3. Нужна правка по референсу — та же ручка с `input_references` (например `mai-image-2.7-flash` от 0,54 ₽, до 5 референсов) или `POST /v1/images/edits` multipart.

**Видео:**
1. `POST /v1/videos` с `{model:"mischief-1", prompt, size:"854x480", duration:3}` → `202 {id, polling_url, status:"pending"}`; резерв считается по `size_prices["854x480"]=0,85 × 3 = 2,55 ₽`.
2. `GET /v1/videos/{id}` каждые 15–30 с до `completed` → `unsigned_urls[0]`, `usage.cost_rub` ≈ 2,55 ₽.
3. `GET /v1/videos/{id}/content?index=0` с `Authorization` → MP4.

**Обязательно для UI:** явно передавать `duration` и `size` — иначе резерв считается по худшему случаю (у `seedance-2.0` разница 25,70 ₽ против ~694 ₽ за 4 с).

## Не проверено (нужен ключ / платная генерация)

* Живые `POST /v1/images/generations`, `/v1/images/edits`, `/v1/videos`, `GET /v1/videos/{id}` — не запускались. Тела/ответы — из документации `images.md`/`videos.md`.
* Существование маршрутов подтверждено докой, а не кодом ответа: AITUNNEL отвечает 401 до роутинга — проверено на заведомо существующем `POST /v1/models` (401) и на заведомо несуществующем `POST /public/aitunnel/models` (404, там путь есть только для GET). Значит 401 на `POST /v1/...` сам по себе ничего не доказывает.
* Наличие маски в `/images/edits` (`max_input_image_bytes` упоминает `mask`, но поля в таблице параметров нет).
* Реальные цены `min_price_per_image` (диапазон «ориентировочный», у токенных моделей зависит от объёма) и фактическое поведение `X-AITunnel-Cost-Pending`.
* Есть ли `negative_prompt` через passthrough-параметры конкретных моделей — в `allowed_passthrough_parameters` живого каталога его нет ни у одной модели картинок/видео.

## Sources

* `GET https://api.aitunnel.ru/public/aitunnel/models` (живой, без ключа, 617 011 байт) — срез 50 images / 35 videos
* `GET https://api.aitunnel.ru/public/aitunnel/models/images`, `/videos`, `/chat` (живые, без ключа)
* https://aitunnel.ru/docs/images.md — генерация, параметры, тарификация, i2i, `/images/edits`, ответ, устранение неполадок
* https://aitunnel.ru/docs/videos.md — `POST /v1/videos`, опрос, статусы, `content`, `frame_images`, `input_references`, `previous_job_id`, тарификация/резерв, список задач
* https://aitunnel.ru/docs/models.md — публичный каталог, группы, поля
* https://aitunnel.ru/docs/errors.md — таблица `error.type` × HTTP × «повторять?»
* https://aitunnel.ru/docs/limits.md — квоты, размеры тела
* https://aitunnel.ru/docs/payments.md — резерв/списание, `X-AITunnel-Cost-Pending`
* https://aitunnel.ru/llms.txt — индекс документации (навигация по разделам)
