---
okf_version: "0.2"
providers: [polza]
last_verified: 2026-11-22
base_urls: [https://polza.ai/api]
---

# Polza.ai — медиа-контракт (snapshot 2026-11-22)

Источник правды: **OpenAPI-спека самого Polza** — `GET https://polza.ai/api/openapi.json` (OpenAPI 3.0.0, 412 347 байт, `servers: [{url: "https://polza.ai/api"}]`, 59 путей) + полный дамп документации `https://polza.ai/docs/llms-full.txt` (869 799 байт) + живой публичный каталог `GET /api/v1/models`. Доки: https://polza.ai/docs/api-reference/introduction, https://polza.ai/docs/api-reference/images/generations, https://polza.ai/docs/api-reference/media/create, https://polza.ai/docs/api-reference/media/status.

**Ничего платного не запускалось.** Все генерации требуют `Authorization: Bearer` — ключа Polza на comp нет. Проверено живым `curl` только то, что отдаётся без ключа (каталог) и то, что видно по кодам ответа (существование маршрута, требование авторизации). Контракт тела/ответа — из OpenAPI и доков, ссылки на конкретные разделы ниже.

## Base URLs и авторизация

| Роль | Значение |
|---|---|
| Base | `https://polza.ai/api` (все пути ниже — от него) |
| Auth | `Authorization: Bearer <ключ>`; других схем (x-api-key, query) в доке нет |
| `ApiKeyAuthStyle` для `Service.Polza` | `BEARER` (уже так в `data/Service.kt`) |
| Ключ для каталога | **не нужен** — `/v1/models` документирован как публичный («не требует аутентификации»), живой `curl` без ключа → 200 |
| Ключ для генерации/статуса/баланса | нужен; без него все четыре маршрута генерации отдают `401 {"error":{"code":"UNAUTHORIZED"}}` |

Живые коды без ключа (2026-11-22): `GET /v1/models` → 200; `GET /v1/models/catalog?type=image&limit=2` → 200; `GET /v1/models/qwen/image` → 200; `POST /v2/images/generations`, `POST /v1/images/generations`, `POST /v1/media`, `POST /v1/videos/generations`, `GET /v1/media/{id}`, `GET /v1/media/{id}/operations`, `GET /v1/balance`, `GET /v1/history/generations` → **401**; `POST /v1/images/edits` и `POST /v2/images/edits` → **404** (маршрута нет); `GET /v2/media/{id}` → **404** (в спеке упомянут, живого маршрута нет); `POST /v1/zzz-nonexistent` → 404 (значит 401 = маршрут есть, авторизация проверяется после роутинга).

## Каталог моделей (публичный)

| Метод | Путь | Что отдаёт |
|---|---|---|
| GET | `/v1/models` | `{object:"list", data:[…]}` — все модели; фильтры `?type=chat\|image\|video\|audio\|…`, `?include_providers=true` |
| GET | `/v1/models/catalog` | пагинация + поиск: `search`, `type`, `inputModalities`, `outputModalities`, `providers`, `sortBy=price\|name\|created_at`, `page`, `limit` (по умолч. 20), ответ `{data, meta:{page,limit,total,totalPages}}` |
| GET | `/v1/models/{path}` | детальная карточка модели: `description` (markdown-гайд), `parameters`, `providers[]`, `endpoints[]` |

Живой срез `/v1/models` (2026-11-22): **394 модели**, из них `image` — **31**, `video` — **23**, `chat` 282, `embedding` 26, `tts` 12, `stt` 11, `music` 5, `classification` 4. `id` — со слэшем (`qwen/image`, `black-forest-labs/flux-3-image`, `kling-4.0/video`); в `model` запроса уходит как есть.

Ключевые поля записи каталога (это всё, что нужно для `listModels`):

```
id, object, owned_by, name, type, short_description, icon_provider_slug,
architecture: { modality, input_modalities[], output_modalities[] },
top_provider: {
  name, pricing: {
    tiers: [{ conditions: ["resolution=720p"], cost_rub: "21.37500000" }],
    unitParam: "duration" | "max_images" | null,
    currency: "RUB",
    prompt_per_million, completion_per_million, image_input_per_million,
    image_output_per_million, per_request, video_per_second, video_per_second_min,
    stt_per_minute, tts_per_million_characters, overrides[], tiers[]
  }
},
providers: [ … то же самое по каждому апстриму … ],
endpoints: ["/api/v1/media", "/api/v1/images/generations"],
parameters: { <per-model JSON-схема входа> }
```

`endpoints[]` — готовый ответ на вопрос «каким путём эту модель можно звать»: у всех 31 image-модели это `["/api/v1/media", "/api/v1/images/generations"]`, у всех 23 video-моделей — `["/api/v1/media", "/api/v1/media/:id/operations"]`.

## Изображения — OpenAI-совместимый путь (синхронный)

`POST /v2/images/generations` — «Создать генерацию изображения (OpenAI-совместимый API)». Тело (OpenAPI `ImageGenerationRequestDto`):

| Поле | Тип | Дефолт | Смысл |
|---|---|---|---|
| `model` | string | — (обяз.) | `gpt-image-1`, `dall-e-3`, `qwen/image`, … |
| `prompt` | string | — (обяз.) | до 32 000 символов для GPT image, 4000 для dall-e-3 |
| `n` | number | 1 | 1–10 (dall-e-3 — только 1) |
| `size` | string | `auto` | `auto`, `256x256`, `512x512`, `1024x1024`, `1536x1024`, `1024x1536`, `1792x1024`, `1024x1792` |
| `quality` | string | `auto` | `auto`, `low`, `medium`, `high`, `standard`, `hd` |
| `response_format` | string | `url` | `url` \| `b64_json` |
| `style` | string | `vivid` | `vivid` \| `natural` (только dall-e-3) |
| `output_format` | string | — | `png`, `jpeg`, `webp`, `gif` (gpt-image-1) |
| `animation` | boolean | — | анимированный GIF, если модель умеет |
| `background` | string | — | `transparent`, `opaque`, `auto` |
| `output_compression` | number | — | 0–100 |
| `user` | string | — | end-user id для антифрода |
| `provider` | object | — | роутинг (см. ниже) |

**Ответ `200`** (синхронно): `{ created, data:[{ url?, b64_json?, revised_prompt? }], usage:{ input_tokens, output_tokens, total_tokens, cost_rub, cost } }`. Живой пример из доки: `usage.cost_rub: 2.50`.

**Поведение при таймауте** (доки, раздел «Поведение при таймауте»): генерация синхронная с таймаутом **120 секунд**. Если не успела — тот же HTTP-ответ приходит как `{ id: "gen_…", status: "pending", model, created }`, и дальше **только** `GET /api/v1/media/{id}` (в тексте спеки ошибочно написано `GET /v2/media/{id}` — живого маршрута нет, проверено: 404). Рекомендованный поллинг 3–5 с.

Ограничение этого пути: **редактирования по референсу здесь нет** — в теле нет ни `images`, ни `image`. Правка/имаге-ту-имаге — только через `/v1/media`.

Второй маршрут для картинок — `POST /api/v1/images/generations`: в OpenAPI он объявлен **без схемы тела**, ответ `201` (пустая схема). FAQ Polza перечисляет именно его как «генерацию изображений», а справочник картинок — `/v2/...` с пометкой «при `base_url=https://polza.ai/api/v1` SDK сам направляет на правильный путь». Живой вызов не проверялся (нужен ключ). **Практический вывод: для картинок писать на `/v2/images/generations` (полный контракт) либо на `/v1/media` (единый путь с референсами).**

## `/v1/media` — универсальный путь (изображения, видео, аудио, музыка)

`POST /v1/media`. Тело (`MediaRequestDto`):

| Поле | Тип | Обяз. | Смысл |
|---|---|---|---|
| `model` | string | да | id из каталога |
| `input` | object | да | параметры модели (`oneOf` по семействам) |
| `async` | boolean | нет, `false` | `false` — ждать до 120 с; `true` — сразу вернуть id |
| `user` | string | нет | end-user id |
| `provider` | object | нет | роутинг |

`input` для картинок: `prompt*`, `aspect_ratio`, `images[]`, `seed`, `watermark`, `image_resolution` (`0.5K|1K|2K|4K`), `quality`, `output_format` (`png|jpeg|webp|gif`), `animation`, `max_images` (≥1, потолок — в каталоге), `isEnhance`, `guidance_scale`, `strength` (0–1, i2i), `enable_safety_checker`, `upscale_factor`, `mask_url` (у части моделей), `callBackUrl`.

`input` для видео: `prompt*`, `aspect_ratio`, `images[]` (опорные кадры), `videos[]`, `reference_images[]`, `audios[]`, `audio_ids[]` (≤3), `character_ids[]` (≤3), `video_list[]` (≤1: `{url,start,ends}`), `video_start`/`video_end`, `duration` (строка!), `resolution` (`480p|512p|580p|720p|768p|1080p|2160p|4k`), `quality` (`720p|1080p`, Runway), `seed`, `watermark`, `multi_shots`, `sound`, `generate_audio`, `mode`, `character_orientation`, `upscale_factor`, `creativity` (0|1), `callBackUrl`.

`aspect_ratio` (общий superset): `1:1, 1:4, 4:1, 1:8, 8:1, 2:3, 3:2, 3:4, 4:3, 4:5, 5:4, 9:16, 16:9, 21:9, 9:21, 2:1, 1:2, 3:1, 1:3, 27:16, 16:27, 9:8, 8:9, auto, adaptive` — «конкретная модель принимает своё подмножество, оно проверяется по каталогу модели».

**Ответ** — объект Media Status (`MediaStatusPresenter`):

```
id, object:"media.generation", status: pending|processing|completed|failed|cancelled,
created, model, completed_at?,
data?: { url?, b64_json?, thumbnail_b64?, revised_prompt?, duration? },
usage?: { input_units, output_units, duration_seconds, input_tokens, output_tokens,
          total_tokens, cost_rub, cost },
error?: { code, message, metadata },
content?: string, reasoning_summary?: string,
warnings?: string[]   // «неподдерживаемые параметры» — молчаливое игнорирование видно здесь
```

`warnings[]` — важная деталь для UI: провайдер не падает на неизвестном параметре, а сообщает о нём в этом массиве.

### Файлы на вход (`images`, `videos`, `reference_images`, `audios`)

Каждый элемент — `{ "type": "url" | "base64", "data": "<URL или base64>" }`. Base64 принимается **и с data-URI, и без**; если провайдер не умеет base64 — Polza сама заливает файл в хранилище и передаёт URL (доки, «Передача медиа на вход»). Это значит, что `MediaReference.base64DataUri` и `localPath` (после превращения в data-URI) уходят напрямую, без предварительного `POST /v1/storage/upload`.

Семантика массивов у видео per-model (из каталога): у `kling-4.0/video` `images` ≤2 — «начальный и конечный кадры» (`@first_frame`/`@last_frame` в промпте), `reference_images` ≤10 — «референсы» (`@image_1…`); у `google/gemini-omni-1.1-flash` есть ещё `first_frame_url`/`last_frame_url` (только парой, несовместимы с `images`), `videos` ≤1 (фрагмент до 10 с) и общая квота `images + videos×2 + character_ids ≤ 7`.

### Статус и результат

`GET /v1/media/{id}` — единственный поллинговый маршрут (живой 401 без ключа). Статусы: `pending` → `processing` → `completed` | `failed`; в `MediaStatusPresenter` есть ещё `cancelled`. Поллинг: 3–5 с для картинок, 5–10 с для видео. Результат — `data.url` на CDN (`s3.polza.ai/f/…`), файлы живут **7 дней**, дальше — `Storage API` с политикой `PERMANENT`.

### Операции над готовым медиа

`POST /v1/media/{id}/operations` — `{operation: "extend" | "upscale_1080p" | "upscale_4k", params?: {...}}`. `extend` (видео) — асинхронная: создаёт новую генерацию с новым id, параметры `prompt*`, `seeds`, `duration` (3–10 с). `upscale_*` — синхронные, результат сразу в `data.url`. `GET /v1/media/{id}/operations` отдаёт список доступных операций (живой 401 без ключа).

Плюс отдельный маршрут `POST /v1/upscale/generations` (живой 401) и модели `topaz/image-upscale`, `topaz/video-upscale` в каталоге.

### Видео: какой путь реально документирован

| Путь | Статус |
|---|---|
| `POST /api/v1/media` | основной, полный контракт `input` + `async` (доки «Генерация медиа», гайды Veo/Kling/Seedance) |
| `POST /api/v1/videos/generations` | маршрут **есть** (живой 401), в OpenAPI объявлен с ответом `201` и **пустыми схемами** тела/ответа; в FAQ Polza назван «генерацией видео». Контракт не документирован |
| `GET /api/v1/videos/{id}` | маршрут **есть** (живой 401), в OpenAPI/доках не описан |
| `GET /api/v1/videos/{id}/content` | **404** — нет |

**Вывод: видео писать только через `POST /v1/media` + `GET /v1/media/{id}`.** `/v1/videos/generations` не использовать — контракт неизвестен, а поведение (`201` = «задача создана») не проверено.

## Прайс

Отдельного эндпоинта котировки у Polza **нет** (в 59 путях OpenAPI нет ничего похожего на `/price`/`/quote`). Цена берётся из каталога:

* `top_provider.pricing.tiers[]` — `{conditions: ["image_resolution=2K"], cost_rub: "11.72016000"}`; пустые `conditions` — цена по умолчанию. Условия — строки `ключ=значение` по тем же ключам, что и `input` (`aspect_ratio`, `resolution`, `quality`, `duration`, `image_resolution`, `input_images_count`, `sound`, `has_video`, `upscale_factor`, `mode`, `engine`).
* `unitParam` — **множитель**: если задан (`duration`, `max_images`), то `cost_rub` в тирах — **ставка за единицу** («если задан, cost_rub в tiers — ставка за единицу этого параметра»). Если `unitParam` нет — `cost_rub` это цена **за задачу** (видно по тирам с `duration` в условиях: `wan/2.6` — `[resolution=720p,duration=5]=52.5`, `[…duration=10]=105.0`).
* `currency: "RUB"` везде. Токенные модели (`openai/gpt-image-2.6-*`, `google/gemini-3.1-flash-image`, `openai/gpt-5-image-mini`) вместо тиров отдают `prompt_per_million`, `image_input_per_million`, `image_output_per_million` — цену за картинку у них можно только оценить.
* Факт списания — `usage.cost_rub` в ответе генерации/статуса. `provider.max_price.*` — **потолок** роутинга, не котировка: `image` (₽ за картинку), `video_per_second` (₽ за секунду), `request` (₽ за запрос), `prompt`/`completion` (₽/1M), `audio`, `stt_per_minute`, `tts_per_million_characters` (OpenAPI `ProviderMaxPriceDto`).

Живой срез цен (₽, 2026-11-22):

| Модель | Единица | Цена | Примечание |
|---|---|---|---|
| `qwen/image` | за картинку | 2,25 (16:9/9:16) · 2,50 (3:4/4:3) · 3,00 (1:1) | самая дешёвая картинка; `images` ≤1 — умеет i2i |
| `yandex/yandex-art` | за картинку | 2,91 | только текст (`input_modalities: ["text"]`), промпт ≤500 символов |
| `openai/gpt-image-1.5` | за картинку | 3,00 (medium) / 16,50 (high) | `images` ≤16 |
| `black-forest-labs/flux-3-image-klein` | за картинку | 1,20 (1K) · 1,875 (2K) · 4,875 (4K) | `unitParam: max_images` — ставка за штуку |
| `grok-imagine-video-1-5` | **за секунду** | 1,62 (480p) · 3,0375 (720p) | `unitParam: duration`; 5 с 480p ≈ 8,10 ₽ — самое дешёвое видео |
| `kling-4.0/flash-video` | за секунду | 12,75 (720p) | `unitParam: duration` |
| `kling-4.0/video` | за секунду | 21,375 (720p) · 31,875 (1080p) | 3–30 с |
| `google/veo3` | за задачу | 187,50 (720p) · 197,50 (1080p) · 277,50 (4k) | без `unitParam` — цена за ролик |

## Референсы (image-to-image, first/last frame)

| Что нужно | Чем закрывается | Лимит |
|---|---|---|
| Картинка-основа / правка | `input.images[]` = `{type:"url"\|"base64", data}` | per-model `parameters.images.max` (0…16; у `qwen/image` 1, у `openai/gpt-5.4-image-2` 16, у `google/gemini-3.1-flash-image` 14) |
| Маска (инпейнт) | `input.mask_url` | только `qwen/image-2.1` и `openai/gpt-5-image` |
| Первый/последний кадр видео | `input.images[]` ≤2 (порядок = first/last, в промпте `@first_frame`/`@last_frame`) или `first_frame_url`+`last_frame_url` у gemini-omni | 2 |
| Визуальный референс видео | `input.reference_images[]` | до 10–30 у разных моделей |
| Видео на вход (v2v/продление) | `input.videos[]` (или `video_list[]` + `video_start`/`video_end`) | 1 (у seedance — до 10) |
| Аудио на вход | `input.audios[]`, `audio_ids[]` | ≤10 / ≤3 |

Отдельного `/images/edits` у Polza **нет** (живой 404 и на `/v1`, и на `/v2`) — вся правка идёт через `images[]` в `/v1/media`. Это отличие от PlusVibe, где sync-путь `/v1/images/edits` есть.

## Параметры модели для UI

Схема лежит **в каталоге** (`parameters` у каждой модели, дублируется в `top_provider.parameters` и `providers[].parameters`) — отдельный запрос не нужен. Формат: `{ "<key>": { type?, required?, values?, min?, max?, step?, default?, max_length?, description? } }`.

Частота ключей по живому каталогу (31 картинка / 23 видео):

| Ключ | Картинки | Видео |
|---|---|---|
| `prompt` | 30/31 | 22/23 |
| `aspect_ratio` | 30/31 | 19/23 |
| `images` | 30/31 | 23/23 |
| `image_resolution` | 14/31 | — |
| `output_format` | 12/31 | 1/23 |
| `quality` | 7/31 | — |
| `max_images` | 6/31 | — |
| `background` | 5/31 | — |
| `seed` | 4/31 (+`seeds` у Veo) | 7/23 (+`seeds` 2) |
| `mask_url` | 2/31 | — |
| `duration` | — | 19/23 |
| `resolution` | — | 17/23 |
| `videos` | — | 11/23 |
| `reference_images` | — | 5/23 |
| `generate_audio` | — | 6/23 |
| `negative_prompt` | **0/31** | **1/23** (`wan/2.5`, ≤500 символов) |

Дефолты и границы — прямо в схеме: `kling-4.0/video` → `duration {min:3, max:30, step:1, default:"5"}`, `resolution {default:"720p", values:["720p","1080p"]}`; `flux-3-image` → `image_resolution {values:["768","1K","1.5K","2K","4K"]}`, `aspect_ratio` 16 значений; `seedream-5-5` → `prompt {max_length:5000}`, `images {max:10, description:"каждый входной файл добавляет 1,08 ₽"}`.

## Роутинг провайдеров (`provider`)

`{require_parameters, allow_fallbacks, order[], only[], ignore[], sort: price|throughput|latency, data_residency: "ru", max_price{...}}`. `data_residency:"ru"` — жёсткий фильтр «только провайдеры на инфраструктуре РФ», иначе запрос отклоняется (не уходит за границу). Alias-синтаксис `model@provider=…` работает **только** для chat/responses/messages — для медиа не поддерживается.

## Лимиты, ошибки, идемпотентность

| Код | Значение | Повторять |
|---|---|---|
| 200 | успех (sync) | — |
| 201 | «Задача создана (для асинхронных операций)» | — |
| 400 | неверный запрос/параметр | нет |
| 401 | ключ | нет |
| 402 | нет средств **или** исчерпан лимит расходов, в т.ч. при параллельных запросах (резерв) | только при параллельной нагрузке |
| 403 | доступ/модерация | нет |
| 404 | нет ресурса | нет |
| 408 | таймаут у провайдера | да, с паузой |
| 413 | тело больше лимита провайдера | нет |
| 429 | лимит Polza (есть `Retry-After`) или лимит апстрима (нет `Retry-After`, `metadata.provider_name`) | да |
| 500/502/503 | сервер / плохой ответ апстрима / нет провайдера | 502 и 503 — да |

Тело ошибки: `{error:{code, message, trace_id, metadata?{provider_name, raw, reason}}, trace_id}`; у части 4xx — `details`.

**Идемпотентность: ключа нет.** Дословно из доки: «Запрос, который завершился ошибкой, деньги не списываются… Исключение — обрыв соединения с вашей стороны (например, по таймауту клиента): уже сгенерированная часть ответа оплачивается, а повтор будет новым запросом. **Ключа идемпотентности нет: каждый повтор — отдельный запрос.**» Лимиты: файл ≤50 МБ, изображение в хранилище ≤50 МБ, «лимит на запрос — 600 секунд»; лимиты запросов к модели — минутные и суточные на организацию (числа не публикуются).

## Таблица: поле нашей абстракции → чем закрывается → чего нет

| Метод `MediaProvider` | Чем закрывается у Polza | Чего нет / что писать самим |
|---|---|---|
| **`listModels(apiKey)`** | `GET /api/v1/models?type=image` и `?type=video` (публично, 200 без ключа). Один ответ уже содержит: `id`, `type`→`MediaKind`, `architecture.input_modalities`→`acceptsImageReference`/`acceptsVideoReference`, `parameters`→`MediaParamDef[]` (key/label/values/default/required), `pricing.tiers`→`MediaTierPrice`+`fromRub`, `pricing.unitParam`→`unit`, `short_description`/`name`. Фильтра «только модели с генерацией» не нужно — каталог и есть витрина | `priceStatus` (exact/estimate) — у Polza нет; выводить самим: `tiers` непустые → `exact`, только токенные цены → `estimate`. `priceUnavailable`/`generationAvailable` — нет, все модели в `type=image\|video` рабочие. `promptLimit` — брать из `parameters.prompt.max_length`. `imageInputModes`/`mode`-роуты — нет аналога (маршрут задаётся наличием `images` в `parameters`) |
| **`priceTable(apiKey, modelId)`** | Отдельного эндпоинта **нет**. Таблица строится локально из `pricing.tiers` той же карточки: `conditions: "resolution=720p"` → `opts {resolution: 720p}`, `cost_rub` → `price`. `chargeBasis`: `unit_rate` при `unitParam != null`, иначе `job_total`; `priceAffecting` — ключи, встречающиеся в `conditions` | Нет `GET /price-table`, нет `defaults`, нет `priceStatus`. Кэшировать каталог (один запрос на все модели) и строить `MediaPriceTable` из него — сетевого вызова на модель не будет вовсе |
| **`quotePrice(apiKey, request)`** | Эндпоинта **нет**. Считать локально: выбрать тир, чьи `conditions` совпали с выбранными `opts` (все условия сразу), взять `cost_rub`; при `unitParam` умножить на значение (`duration` из `opts`, `max_images` = `n`); при токенных ценах — оценка `fromRub` | Нет `quoteId`, нет `expiresAt`, нет серверной гарантии цены. `MediaPriceQuote.quoteId = null`. Потолок можно передать в `provider.max_price` (`image` — ₽/картинку, `video_per_second` — ₽/сек): тогда `MediaJobRequest.maxPriceRub` для видео надо **делить на длительность** |
| **`submitJob(apiKey, request)`** | `POST /api/v2/images/generations` (картинки, синхронно 200) либо `POST /api/v1/media` (`{model, input, async, provider}`) для картинок с референсами и для видео. `async:true` → сразу `{id, status:"pending"}` | **Нет `Idempotency-Key`** — повтор после таймаута = второй платный запрос. Нет «сразу не терминального состояния» как гарантии: sync-путь может вернуть готовый результат (`200` + `data[]`). При `>120 с` sync-путь отдаёт `pending` + id — то есть `submitJob` обязан уметь оба исхода. Отдельный `POST /v1/videos/generations` существует, но контракт не документирован — не использовать |
| **`pollJob(apiKey, jobId)`** | `GET /api/v1/media/{id}` — `status: pending\|processing\|completed\|failed\|cancelled`, `data.url` (картинка/видео/аудио — `oneOf`), `data.duration`, `usage.cost_rub`, `error{code,message}`, `warnings[]`. `progress` нет. Поллинг 3–5 с (картинки), 5–10 с (видео) | `/v2/media/{id}` не существует (404). `MediaJobStatus` без `Cancelled` — маппить в `Fail` с `errorCode="cancelled"`. Нет `advice`/`definitive` (как у PlusVibe) — заполнять самим: 402/403 → `definitive=true`, 429/5xx → `retry_later`. Нет `progress` |
| **`downloadResult(apiKey, url)`** | `data.url` → CDN `s3.polza.ai` / `storage.polza.ai`; по докам ссылка публичная (CDN «для доступа внутри России»), ключ не нужен. Альтернатива — `response_format:"b64_json"` (только `/v2/images/generations`) | Срок жизни **7 дней** — копировать байты сразу. Публичность CDN без ключа живой проверкой **не подтверждена** (нужна платная генерация) |

## Минимальный путь

**Картинки (самое дешёвое, что реально заработает):**
1. `GET /api/v1/models?type=image` без ключа → список моделей + цены + схемы параметров. Этого достаточно для витрины и выбора.
2. `POST /api/v2/images/generations` с `{model:"qwen/image", prompt, aspect_ratio:"16:9"}` → `200`, `data[0].url`, `usage.cost_rub` ≈ 2,25 ₽. Синхронно, без поллинга, без референсов.
3. Если нужен референс — тот же промпт через `POST /api/v1/media` с `input.images[{type:"base64", data:"data:image/png;base64,…"}]` и `async:true` → `{id}` → `GET /api/v1/media/{id}`.

**Видео:**
1. `POST /api/v1/media` с `{model:"grok-imagine-video-1-5", input:{prompt, resolution:"480p", duration:"5", aspect_ratio:"16:9"}, async:true, provider:{max_price:{video_per_second:3}}}` → `{id, status:"pending"}`.
2. `GET /api/v1/media/{id}` каждые 5–10 с до `completed` → `data.url` (MP4), `usage.cost_rub` ≈ 8,10 ₽ за 5 с 480p.
3. Опционально `provider.max_price.video_per_second` как страховка от дорогого апстрима.

## Отдельный вопрос: можно ли для Polza обойтись синхронным `submitJob` без поллинга?

**Для картинок — да, для видео — нет.**

* Картинки: `POST /api/v2/images/generations` синхронен (200, таймаут 120 с), отдаёт готовый `data[0].url` и `usage.cost_rub`. Это ровно тот же трюк, что уже есть у PlusVibe на `/v1/images/generations`. Оговорки: (а) при >120 с ответ приходит как `{id, status:"pending"}` — то есть **ветка поллинга обязана существовать как fallback**, иначе такие задачи теряются; (б) нет `Idempotency-Key`: если клиент оборвёт соединение раньше серверного таймаута, задача может быть уже создана и оплачена, а id у нас не будет — повтор спишет деньги второй раз; (в) 120 с блокирующего HTTP-запроса — плохой UX на мобильном и риск для батареи/сети, поэтому в приложении (как и с PlusVibe) async+поллинг остаётся основным путём, а sync — «быстрый» режим для дешёвых моделей.
* Видео: асинхронно по факту. `POST /v1/media` с `async:false` ждёт максимум 120 с, а рендер видео идёт минутами → вернётся `pending` и всё равно нужен `GET /v1/media/{id}`. Синхронного видеопути у Polza нет.

**Что это значит для `MediaProvider`:** интерфейс менять не нужно. `submitJob` уже возвращает `MediaJobState`, который допускает `Success` с `resultUrls` — sync-путь кладёт туда готовый URL и `priceRub`, async-путь — `Processing` + `jobId`. Требования к реализации: (1) `submitJob` должен разбирать **оба** ответа (готовый результат и `pending`-задачу) — иначе 120-секундный таймаут ломает поток; (2) `pollJob` всё равно обязателен (видео + fallback); (3) `idempotencyKey` из `MediaJobRequest` для Polza **игнорировать** и нигде не хранить как «повтор безопасен» — вместо этого в UI честно писать «повтор = новый платный запрос»; (4) таймаут HTTP-клиента на sync-путь ≥120 с (у `PlusVibeMediaProvider` сейчас 180 с — годится).

## Не проверено (нужен ключ / платная генерация)

* Живой `POST /v2/images/generations`, `/v1/images/generations`, `/v1/media` — не запускались (запрет владельца). Всё, что про тело/ответ, — из OpenAPI и доков.
* Публичность `data.url` на CDN без ключа (`downloadResult`) — не проверена.
* Точная форма `data` при `max_images > 1` (объект или массив) — в `MediaStatusPresenter` объявлен `oneOf` из объектов, а `/v2` отдаёт массив `data[]`; на живом ответе не сверялось.
* Единица `unitParam` проверена логикой цен (`grok-imagine-video-1-5` 1,62 ₽/с vs AITUNNEL `grok-imagine-video-1.5-lite` 3,40 ₽/с), но не живым списанием.
* Лимиты запросов к модели (RPM/RPD) — в доке не опубликованы.
* `POST /v1/videos/generations` и `GET /v1/videos/{id}` — маршруты есть (401), контракт неизвестен.

## Sources

* `GET https://polza.ai/api/openapi.json` (OpenAPI 3.0.0, живой, 412 347 байт) — тела, ответы, `ProviderDto`, `ProviderMaxPriceDto`, `MediaStatusPresenter`, `ModelPricingPresenter`
* `GET https://polza.ai/api/v1/models` (живой, без ключа, 394 модели)
* `GET https://polza.ai/api/v1/models/qwen/image`, `/api/v1/models/kling-4.0/video` (живые карточки)
* https://polza.ai/docs/llms-full.txt (дамп всей документации) — разделы: `POST Images Generations`, `Введение в API` (коды, повторы, лимиты), `POST Media`, `POST Media Operations`, `GET Media Status`, `GET Models`, `GET Models Catalog`, `Выбор провайдера`, `Передача медиа на вход`, `Veo 3.1`, `Nano Banana`
* https://polza.ai/docs/api-reference/images/generations
* https://polza.ai/docs/api-reference/introduction
* https://polza.ai/docs/api-reference/media/create
* https://polza.ai/docs/api-reference/media/status
* https://polza.ai/docs/api-reference/media/operations
* https://polza.ai/docs/gaidy/provider-selection
* https://polza.ai/docs/gaidy/media-input
