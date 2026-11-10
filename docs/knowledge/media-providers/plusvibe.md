---
okf_version: "0.2"
providers: [plusvibe]
last_verified: 2026-11-10
base_urls: [https://plusvibeapi.ru, https://gw.plusvibeapi.ru]
---

# PlusVibeAPI — медиа-контракт (snapshot)

Источник правды по ценам — `GET https://plusvibeapi.ru/api/media-catalog` (публичный, без ключа). Локальный runtime-парсер — `composeApp/src/commonMain/kotlin/com/pusios/pots/media/PlusVibeMediaProvider.kt`. Документация поставщика: https://plusvibeapi.ru/docs/.

## Base URLs

| Роль | Base |
|---|---|
| Основной | `https://plusvibeapi.ru` |
| Резерв (chat уже сконфигурирован так) | `https://gw.plusvibeapi.ru` |

Chat-сервис `plusvibe` / `plusvibe-reserve` уже существует (`Service.PlusVibe`, `Service.PlusVibeReserve`). Медиа использует **тот же API-ключ** (`sk-pv-…`, `Authorization: Bearer` или `x-api-key`). Отдельного медиа-ключа нет.

## Endpoints

### Catalog — список моделей и цен

`GET /api/media-catalog` — публичный, без авторизации. Использовать для витрины, расчётов и выбора модели до регистрации.

Ответ (`pricesAreEstimate`, `videoDisabled`, `models[]`):

| Поле `models[]` | Тип | Смысл |
|---|---|---|
| `model` | string | Публичный id для `model` в `POST /api/media/generate` |
| `kind` | string | `image` \| `video` \| `audio` \| `music` \| `3d` |
| `unit` | string | Единица тарификации («за изображение», «за секунду», «за ролик») |
| `note` | string | Краткое описание |
| `inputTypes` | string[] | Допустимые типы входа |
| `fromRub` (legacy `params`) | number? | Минимальная цена «от»; точна при `priceStatus=exact` |
| `priceStatus` | string | `exact` \| `estimate` \| `unavailable` |
| `priceUnavailable` | bool | true — маршрут нельзя вызвать (503) |
| `variants[]` | object[] | Маршруты `addressAs` (`gpt-image-2:network`, `flux-2:flow`) с `priceRub`, `priceStatus`, `priceUnavailable` |
| `params[]` / `axes[]` / `inputSchema` | object | Схема `opts` для валидации клиента |
| `generationAvailable` / `displayOnly` | bool | Служебные флаги; `displayOnly` — справочная модель без dispatch |

Таблица цен для реселлеров: `GET /api/media/price-table?model=<id>` (публично): `opts`, `price`, `unitPrice`, `priceScope` (`job_total` \| `unit_rate`), `jobTotalStatus`, `requiredInputs`. При `priceScope=unit_rate` поле `price=null`, тариф в `unitPrice`.

Точная котировка только через `POST /api/media/price` с реальным входом (особенно при `jobTotalStatus=metered` / `input_dependent`).

### Async generation — основной путь (изображения + видео)

`POST /api/media/generate` → `202 { ok:true, jobId, status:"processing" }`.
`GET /api/media/jobs/{id}` → опрос до `success` \| `fail` (`submitting` — нетерминальный, `media_submission_uncertain`, не создавать дубль).

Тело создания (strict-контракт, top-level только):

```
model (обяз, публичный id или base:alias)
prompt (обяз. если требует операция модели; для STT/аватара — пустая строка)
opts — внутри параметры модели (aspect_ratio, duration, resolution, image_url, …)
strict: bool — 422 strict_validation_failed до расчёта цены
quote_id — mq_… из /api/media/price (опционально; job запускается только по этой цене)
max_price_rub — потолок ₽; выше → 422 media_max_price_exceeded
webhook_url — публичный https для подписанного уведомления (не использовать из мобильного клиента)
```

Заголовки: `Authorization: Bearer sk-pv-…`, опционально `Idempotency-Key` (1–255 ASCII, 24 ч; повтор с тем же ключом+телом → тот же jobId; другое тело → 422; одновременная отправка → 409 + `Retry-After: 5`), `x-pv-cost: 1` — стоимость в final usage-событии.

Ответы создания: `202` принята; `400` невалидный JSON/тело; `401` ключ; `402` нет средств (admission); `422` неизвестная модель / невалидные opts / `ok:false` от facade / `media_max_price_exceeded`; `502` `media_submit_failed` (upstream недоступен, повтор с backoff); `503` `priceUnavailable` / routing guard / `VIDEO_DISABLED` (чениить модель или позже). Списание только при `success`, ровно по котировке если она была.

### Sync image shortcut (только изображения, без референсов)

`POST /v1/images/generations` — OpenAI Images API, синхронная до 300 с (клиентский таймаут ≥ 300 с). Тело: `model`, `prompt`, `n=1` только, `size` как пропорция (`1024x1024`→1:1, `1792x1024`→16:9, `1024x1792`→9:16, `1536x1024`→3:2, `1024x1536`→2:3; нет пропорции → 400 с перечнем), `quality` (только если есть в каталоге), `response_format: url|b64_json` (по умолчанию `url`, до 20 МБ). Неизвестные поля (`background`, `style`, `output_format`, …) → 400 по имени. Модели, которым нужно исходное изображение, здесь не работают — использовать async путь. Ответ `{ created, data:[{url}] }` + `x-pv-cost-rub`, `x-pv-media-job-id`. `504 cause=image_generation_timeout` — задача живёт, забирать по `GET /api/media/jobs/{id}`; история: `GET /v1/images/generations?limit=` (до 100). `410 media_result_expired` через 5 дней.

`POST /v1/images/edits` — multipart `image` / `image[]` или JSON `images:[{image_url}]`, тот же ответ/цена/ожидание. `mask` → 400 `unsupported_parameter`, модель без приёма исходника → `model_cannot_edit`. `POST /v1/images/edits` синхронно есть; видео синхронно нет (`/v1/video/generations`, `/v1/videos` → 410 `media_endpoint_retired` до авторизации).

### Price quote

`POST /api/media/price` — то же тело, что `generate`; ответ `{ ok:true, priceRub, quoteId:"mq_…", expiresAt }`. Передавать тот же `quote_id` + `max_price_rub ≥ quote` в `generate`. Невалидный ceiling → 400 `invalid_max_price_rub`; quote выше ceiling → 422; недостаток средств под ceiling → 402 `media_max_price_unfunded`. Цены на сайте — ориентировочные; точная сумма в `x-pv-cost-rub` каждого принятого ответа.

### Jobs poll

`GET /api/media/jobs/{id}`:

```
status: submitting | processing | success | fail
resultUrls: string[] — подписанные /api/media/file/{id}/{idx}?sig=… (без ключа) или https://plusvibeapi.ru/media/r/…
resultText: string — для STT (resultUrls пуст)
priceRub: number — итог списания (0 при fail)
resultsExpireAt: ISO — 5 дней после completion; потом 410 media_result_expired
errorCode / failMsg / failure{ code, advice, definitive, charged }
stage: generating | completed | failed | submission_unknown
```

Рекомендуемый polling: 3–5 с с экспоненциальной задержкой до 30 с. При `submission_outcome_unknown` (`stage: submission_unknown`, `errorCode: media_submission_uncertain`, `failure.advice: reconcile_first`) — опрашивать тот же jobId, не создавать дубль; за ~10–15 мин закрывается как `fail` (`media_submission_not_started`, без списания) — создавать новую задачу. `fail` с `definitive:true, advice:retry_later` — можно новую задачу после backoff; `fix_input` — чинить вход; `choose_other_route` — другую модель.

### Result file

`GET /api/media/file/{id}/{idx}` — подписанный прокси (HMAC `sig` в query). Открывается без ключа (`<img>`/`<video>`). `download=1` — как вложение (`.glb` для 3D). Поддерживает Range (перемотка). Провайдер скрыт, upstream-адрес клиент не видит. Результат живёт 5 дней; постоянный адрес `https://plusvibeapi.ru/media/r/…` после копирования в хранилище поставщика.

## opts — часто используемые параметры

| Параметр | Тип | Смысл |
|---|---|---|
| `aspect_ratio` | string | 1:1, 16:9, 9:16, 4:3, 3:4 |
| `duration` | string/number | Длительность видео, сек (5, 10, 15) |
| `resolution` | string | 480p, 720p, 1080p, 4K |
| `image_url` / `first_frame_url` / `last_frame_url` / `reference_image` | string URL | Публичные `https://` адреса, скачивает провайдер |
| `negative_prompt` | string | Что исключить (qwen-image, wan-2-7) |
| `guidance` / `seed` / `stability` | number | Guidance scale / seed / стабильность 0–1 |
| `camera` | string | Движение камеры (`pan left`) |

Неизвестный параметр игнорируется (в `strict` — 422). Машиночитаемый контракт: `GET /api/model-capabilities` / `GET /api/model-capabilities/{model}` и `GET /v1/model-capabilities/{model}` (`operations[].requestJsonSchema`).

## Свои файлы: base64 / Files API

Поля с URL принимают только публичные `https://`; `data:…;base64` в таком поле → 400. Свой файл — в парное `*_base64` поле (`image_base64`, `mask_base64`, `input_base64[]` до 16 файлов, `audio_base64`, `video_base64`); значение — полная строка `data:<mime>;base64,…`. Альтернатива — id своего файла из `POST /v1/files` (`image_file_id`, `audio_file_id`, `video_file_id`, `mask_file_id`, `first_frame_file_id`/`last_frame_file_id`, `input_file_ids[]`, `reference_*_file_ids[]`). Какие id-поля принимает модель — в её `requestJsonSchema`.

## STT / TTS / pipeline (вне скоупа v1, но тот же эндпоинт)

* STT: `model` = STT-модель (`whisper-large-v3-turbo` 0,02 ₽/мин, `nemotron-asr-multilingual`, `whisper-large-v3` 0,06 ₽/мин, `universal-2` 0,35 ₽/мин, `speech-to-text` 1 ₽/мин), `prompt:""`, `opts.audio_url` (или `audio_base64`), файл ≤ 25 МБ, `mp3/wav/m4a/flac/ogg/webm`. Готовый текст — `resultText`; итог по фактической длине.
* Pipeline фото → озвучка → говорящий аватар: `elevenlabs-v4-tts` (1–5000 символов; `voice` Rachel/Aria/Roger/Sarah/Laura/Charlie/George/Callum/River/Liam/Charlotte/Alice/Matilda/Will/Jessica/Eric/Chris/Brian/Daniel/Lily/Bill или `voice_id`; `language_code` auto/ru/en/…; `output_format` mp3/mp3_hq/opus) → `kling-ai-avatar` (`tier` std 720p/pro 1080p, `image_url`+`audio_url` обяз., до 5 мин) или `omnihuman-v2` (аудио 4 с–5 мин). Сначала `POST /api/media/price`, затем `generate` с тем же `quote_id`.

## Клонирование голоса (не v1)

`POST /api/media/voices` (`name`, `consent:true`, `engine`, `audio_url|audio_base64` или `samples[]`, только `elevenlabs`), `GET /api/media/voices`, `POST /api/media/voices/{id}/samples`, `DELETE /api/media/voices/{id}`. Одна запись ≤ 10 МБ (wav/mp3, до 60 с), ≤ 25 записей, сумма ≤ 300 с / ≤ 50 МБ, тело ≤ 70 953 644 B, ≤ 20 добавлений/сутки. Ошибки: 422 `voice_sample_too_large`/`invalid`/`samples_limit`/`samples_too_large`, 404 `voice_not_found`, 409 `voice_samples_unsupported`/`slots_full`, 502 `voice_samples_failed`, 429 `voice_samples_rate_limited`.

## Webhook (не использовать из приложения)

`PATCH /api/webhooks` — только сессия кабинета (cookie), принимает `mediaWebhookUrl`/`balanceWebhookUrl` (публичный HTTPS, SSRF-защита). Доставка `POST` на `mediaWebhookUrl` при терминальном статусе: `{ jobId, status, model, kind, resultUrls, failMsg, createdAt }`, подпись Standard Webhooks (`v1`, HMAC-SHA256 от `id.timestamp.тело`), до 5 попыток (1/2/4/8 мин), дедупликация обязательна по `jobId+status`. ПлюсVibe — держатель log: `GET /api/webhooks`, `POST /api/media/price`/`generate` с `webhook_url` подписываются секретом ключа (`secret` из `GET /api/media/webhook-secret`). Мобильный клиент не принимает вебхуки — только опрос.

## Families

Изображения (51), видео (82), аудио/TTS (41), музыка (11), 3D (7) — 192 модели на дату снапшота. Поиск по семействам: Seedance (`seedance-2.0`, `seedance-1`, `seedance-2-0-mini`), Wan (`wan-2-7`, `wan-2-6`, `wan-2-2`, `wan-animate`, `wan-speech-to-video`, `wan-2-7-image`), Hailuo (`hailuo-2-3`), Suno (`suno`), TTS (`tts`), 3D (`hunyuan3d`, `tripo`, `meshy`, `rodin`, `trellis`).

## Ограничения и policy для Pots

* Ссылки результата живут 5 дней → копировать байты в приложение/галерею сразу.
* `n=1` только на sync-пути изображений; несколько картинок — несколько задач.
* Таймаут sync-пути 300 с — async путь предпочтителен в приложении (polling + отмена).
* Оплата в рублях, данные в РФ (152-ФЗ).
* `nano-banana-pro` — актуальный id (не `gemini-3-pro-image`, он принимается только в `model` запроса async-пути как алиас).

## Sources

* https://plusvibeapi.ru/docs/api-reference
* https://plusvibeapi.ru/docs/images-generations
* https://plusvibeapi.ru/docs/video-generations
* https://plusvibeapi.ru/docs/media-generation
* https://plusvibeapi.ru/docs/media-pipeline
* https://plusvibeapi.ru/docs/media-models
* https://plusvibeapi.ru/docs/media-webhooks
* `GET https://plusvibeapi.ru/api/media-catalog` (live)
