---
okf_version: "0.2"
providers: [llm-router]
last_verified: 2026-11-23
base_urls: [https://llm-router.org]
---

# LLM Router — медиа-контракт (snapshot 2026-11-23)

Источник правды: **официальная документация** `https://llm-router.org/docs` (одна страница: quick start, Anthropic/OpenAI-совместимые входы, `GET /v1/models`, коды ошибок, интеграции, раздел **Images** — «Image generation and editing»), русская версия `https://llm-router.org/ru/docs`, публичная страница тарифов `https://llm-router.org/pricing` и `https://llm-router.org/sitemap.xml` (полный перечень публичных страниц). Дополнительно — живая проверка маршрутов без ключа (см. таблицу ниже).

**Ничего платного не запускалось.** Все генерации требуют `Authorization: Bearer *** — ключа LLM Router на comp нет. Живыми `curl` проверено только существование маршрутов и требование авторизации. Контракт тела/ответа — из документации.

## Base URLs и авторизация

| Роль | Значение |
|---|---|
| Base | `https://llm-router.org`, API-пути — от `/v1` (`/v1/images/generations`, `/v1/images/edits`, `/v1/models`, `/v1/pricing`, `/v1/balance`) |
| Auth (OpenAI-совместимый вход) | `Authorization: Bearer <API_KEY>` — так во всех примерах раздела Images |
| Auth (Anthropic-вход) | `x-api-key: <API_KEY>` — документировано в настройке Claude Desktop («Gateway auth scheme → x-api-key») и в инструкции для Hermes (`api_mode: anthropic_messages` шлёт `POST /v1/messages` с `x-api-key`) |
| `ApiKeyAuthStyle` для `Service.LlmRouter` | `BEARER` (по умолчанию, уже так) — совпадает с примерами Images |
| Формат ключа | `llmr_…` (пример из `/pricing`: `llmr_YOUR_KEY`) |
| Ключ для каталога `/v1/models` | **нужен**: живой `GET` без ключа → `401 {"type":"error","error":{"type":"authentication_error","message":"Invalid API key."}}`. Публичного каталога картинок нет |
| Ключ для `/v1/pricing`, `/v1/balance` | нужен: оба живьём `401` (эндпоинты есть, но не документированы — см. ниже) |

Живые коды без ключа (2026-11-23, `curl`):

| Запрос | Ответ | Что доказывает |
|---|---|---|
| `GET /v1/images/generations` | `405` — «The GET method is not supported for route v1/images/generations. **Supported methods: POST**» | маршрут зарегистрирован, метод только `POST` |
| `GET /v1/images/edits` | `405` — то же для `v1/images/edits` | маршрут зарегистрирован, только `POST` |
| `POST /v1/images/generations` (пустое тело) | `401` `authentication_error` | авторизация проверяется **после** роутинга (в отличие от AITUNNEL) |
| `GET /v1/zzz-nonexistent` | `404` «The route v1/zzz-nonexistent could not be found» | контроль: `401` выше = маршрут реально есть |
| `POST /v1/zzz-nonexistent` | `404` (та же формулировка) | контроль для POST-путей |
| `GET /v1/models`, `GET /v1/pricing`, `GET /v1/balance` | `401` | маршруты есть, все три требуют ключа |

Важное следствие: **у LLM Router `401` на `POST` — доказательство существования маршрута** (роутинг раньше авторизации), поэтому проверка кодов ответа здесь информативнее, чем у AITUNNEL.

## Каталог моделей

`GET https://llm-router.org/v1/models` — «Returns the current list of available models. Use the identifiers from this response in the `model` parameter» (docs, раздел **Models**). Требует ключа.

**Чего в документации нет:** формы ответа (пример не приведён), признака модальности, цены, схемы параметров, разделения на image/video/text. Из доки известны только имена моделей, встречающиеся в примерах: `nano-banana` (генерация и композиция), `gpt-image-1.5` (multipart + маска), `gpt-image-2.5` (таблица разрешений), «Gemini models» (не отдают hosted url).

`GET https://llm-router.org/v1/pricing` — маршрут **существует** (живой `401`), но в публичной документации **не описан**. В Pots он уже используется: `Requests.getLlmRouterPricing()` парсит `{"data": [{"id", "modality", "prices": [{"op", "unit", "usd"}]}]}` (`network/dtos/llmrouter/LlmRouterPricingResponseDto.kt`). Это контракт **нашей стороны**, а не провайдера — до живой проверки с ключом считать неподтверждённым (см. «Не проверено»).

`GET https://llm-router.org/pricing` — публичная HTML-страница тарифов, но в ней **только текстовые модели** в USD за 1M токенов (GPT, Gemini, DeepSeek, GLM). **Цен на картинки публично не опубликовано** — они видны только в кабинете («Affects the rate — see pricing in the cabinet») и, возможно, в `/v1/pricing`.

## Изображения — синхронно, без задачи и поллинга

Раздел Images доки перечисляет ровно два эндпоинта, оба синхронные: «OpenAI-compatible format, separate from chat. Two endpoints: generation from scratch from text, and editing/composition of existing images».

| Эндпоинт | Назначение |
|---|---|
| `POST /v1/images/generations` | текст → картинка |
| `POST /v1/images/edits` | правка, маска, композиция из нескольких картинок |

**Асинхронного контура нет:** ни `tasks`, ни `status`, ни webhook в разделе Images не упоминаются; `GET` на оба пути → `405 Supported methods: POST` (живая проверка). Это принципиальное отличие от GPTunnel/Polza/AITUNNEL: `submitJob` здесь всегда терминальный, `pollJob` не вызывается никогда.

### `POST /v1/images/generations` (JSON)

| Поле | Тип | Обяз. | Смысл (по доке) |
|---|---|---|---|
| `model`* | string | да | id модели; список — `GET /v1/models` |
| `prompt`* | string | да | текстовое описание |
| `n` | integer | нет, `1` | число картинок в ответе. «Anything above the limit is **silently truncated**» — потолок задаёт модель, в доке он не назван |
| `size` | string | нет | форма кадра: `1024x1536` (портрет), `1536x1024` (пейзаж), `1024x1024` (квадрат). Для моделей с тирами разрешения можно передать литерал `"1K"` / `"2K"` / `"4K"` — «it affects the price and **does not set the frame shape**». Без `size` модель выбирает форму сама, в том числе из текста промпта; явный `size` побеждает промпт |
| `aspect_ratio` | string | нет | `1:1`, `2:3`, `3:2`, `3:4`, `4:3`, `4:5`, `5:4`, `9:16`, `16:9`, `21:9`, `auto`. «Takes priority over `size`. Works on `nano-banana` and `gpt-image-2.5`» — то есть **не на всех моделях** |
| `quality` | string | нет | `standard`/`hd` **или** `low`/`medium`/`high` — набор зависит от модели; влияет на ставку |
| `response_format` | string | нет | `url` \| `b64_json`. «Gemini models don't return a hosted `url` — use `b64_json`» |

Пример из доки: `{"model":"nano-banana","prompt":"…","n":1,"size":"1024x1536","response_format":"b64_json"}`.

### `POST /v1/images/edits`

Два режима, **разные content-type**:

| Режим | Тело | Что можно |
|---|---|---|
| multipart | `model`, `prompt`, `image` (файл), `mask` (файл) | «Only the OpenAI-compatible branch understands a mask (`gpt-image-*`). **White areas of the mask are what gets repainted**» |
| JSON | `model`, `prompt`, `images: [{"url": "…"}]` | композиция из нескольких картинок; в примере — `data:image/png;base64,...` в `url`. «A single image — the `image` field (multipart) or `images: [{url}]` (JSON, **up to several** for composition)» |

Маска — **только** у `gpt-image-*` и **только** в multipart. На любой другой модели/протоколе маска → `400` с текстом «Mask is not supported» (таблица ошибок доки).

**Ловушка для реализации:** в доке маска описана как «белые области перерисовываются». У OpenAI (и в привычном `image` + `mask`) правится прозрачная область маски. Прежде чем отправлять маску из нашего редактора, нужно либо инвертировать её, либо проверить поведение живым вызовом — иначе инпейнт сработает по дополнению.

### Форма и итоговые пиксели

Таблица доки (для `gpt-image-2.5`; «other models produce their own sizes»):

| `aspect_ratio` | `size` | пиксели |
|---|---|---|
| 1:1 | 1024x1024 | 1254×1254 |
| 2:3 | 1024x1536 | 1024×1536 |
| 3:4 | — | 1086×1448 |
| 4:5 | — | 1122×1402 |
| 9:16 | 1024x1792 | 941×1672 |
| 21:9 | — | 1915×821 |
| 3:2 | 1536x1024 | 1536×1024 |
| 16:9 | 1792x1024 | 1672×941 |

«If you need exactly the shape from the table, pass `aspect_ratio` or the matching `size`; without them the model picks the frame itself».

### Ответ

```json
{ "created": 1720000000,
  "data": [ { "b64_json": "iVBORw0KGgoAAAANSUhEUgAA..." } ] }
```

* В документированном примере — **только `b64_json`**, поля `url` в примере нет.
* `response_format: "url"` документирован как допустимое значение, но **где именно лежит URL (`data[].url`?) — в доке не показано**: считать «не подтверждено» до живой проверки.
* **Нет `usage`, нет `cost`, нет `model`, нет `revised_prompt`** — фактическую цену из ответа не узнать.
* Списание: «You're charged for every returned image at the rate of the chosen quality/size; **failed/empty attempts aren't charged**». То есть при `n: 4` и тихом усечении до 2 оплачиваются 2.

### Идемпотентность

В разделе Images: «For safe retries pass the `Idempotency-Key: <uuid>` header — a retry with the same key and the same API key **won't run generation or charge again**». Это единственный из четырёх медиа-провайдеров с **заголовком** `Idempotency-Key` (у GPTunnel — поле тела `idempotency_key`). TTL ключа в доке не назван.

### Ошибки (раздел Images)

| Код | Причина | Что делать |
|---|---|---|
| 400 | маска передана модели/протоколу, который её не поддерживает («Mask is not supported») | убрать маску или взять `gpt-image-*` через multipart |
| 401 | ключ/заголовок | исправить ключ |
| 402 | недостаточно средств | пополнить баланс в кабинете |
| 429 | лимит запросов | повтор с задержкой |
| 503 | «Pricing isn't configured for the model yet, or the upstream provider is temporarily unavailable» | разовый — повторить; постоянный — в поддержку |

Общий раздел ошибок доки добавляет `500` (server-side, повторить через несколько секунд) и `502`/`503` (upstream недоступен, повторить). Формат тела: `{"type":"error","error":{"type":"authentication_error","message":"Invalid API key."}}` для 401; для 405/404 — `{"error":{"message":"…","type":"request_error"}}` (живые ответы).

## Цены и списания

* Валюта сервиса — **USD**: страница `/pricing` — «Prices per 1M tokens in USD»; баланс в Pots читается как `balance_usd` (`ProviderBalanceClient`: `Service.LlmRouter -> BalanceSpec("https://llm-router.org/v1/balance", listOf("balance_usd"), "USD")`).
* Цены картинок публично не опубликованы; ставка зависит от модели и от `quality`/`size` («Affects the rate — see pricing in the cabinet»).
* Отдельного эндпоинта котировки в документации **нет** — только `/v1/pricing` (недокументированный маршрут) как источник ставок.
* Факт списания в ответе генерации **не приходит** (`usage`/`cost` отсутствуют) — сверять цену после вызова нечем.
* **Наша абстракция считает в рублях (`priceRub`), провайдер — в USD.** Нужен курс и явная пометка «оценка в ₽», иначе UI покажет рубли, которых никто не списывал.

## Таблица: метод нашей абстракции → чем закрывается → чего нет

| Метод `MediaProvider` | Чем закрывается у LLM Router | Чего нет / что писать самим |
|---|---|---|
| **`listModels(apiKey)`** | `GET /v1/models` с ключом — единственный каталог. Документировано только «список id для параметра `model`»; Pots уже разбирает его как OpenAI-совместимый `{data:[{id}]}` | **Нет модальности** — из ответа нельзя понять, что модель картинная; нужен либо allowlist id в коде, либо `modality` из `/v1/pricing` (недокументированный маршрут). **Нет цен** → `fromRub = null`, `priceStatus` неизвестен. **Нет схемы параметров** → `params` пустой, форма в UI строится из общих полей (`size`, `aspect_ratio`, `quality`, `n`, `response_format`) и не знает, что реально принимает конкретная модель. `promptLimit` не публикуется. `kind`/`unit`/`generationAvailable` — из локальной таблицы, не из каталога |
| **`priceTable(apiKey, modelId)`** | `GET /v1/pricing` — маршрут есть (живой `401`), **в документации не описан**. В Pots парсится как `{data:[{id, modality, prices:[{op, unit, usd}]}]}` — если форма подтвердится, этого хватит на `MediaPriceTable` (ставки USD, `op`/`unit`) | Всё держится на непроверенной форме. Публичная страница `/pricing` картинки не содержит вообще. Таблицы тиров «качество/размер → цена» нет — только ставки по `op`/`unit`, если они там есть. Парсить защитно (`ignoreUnknownKeys`, пустой список при дрейфе) |
| **`quotePrice(apiKey, request)`** | Эндпоинта котировки **нет**. Только локальная оценка: ставка из `/v1/pricing` (если подтвердится) × число картинок, с конвертацией USD→RUB | `quoteId = null`, `expiresAt = null`, серверной гарантии цены нет. Учесть: `n` может быть тихо усечён, и тогда спишется меньше — оценка «сверху» не гарантирует равенство. Учесть `size: "1K"/"2K"/"4K"` как ценовой тир |
| **`submitJob(apiKey, request)`** | `POST /v1/images/generations` (JSON: `model`, `prompt`, `n`, `size`, `aspect_ratio`, `quality`, `response_format`) либо `POST /v1/images/edits` (multipart с `image`/`mask` для `gpt-image-*`, или JSON с `images:[{url}]` для композиции). Всё синхронно: ответ `200` уже содержит результат → `MediaJobState(Success, resultUrls/байты, priceRub)`. `Idempotency-Key: <uuid>` из `MediaJobRequest.idempotencyKey` — провайдер его реально понимает | **Нет `jobId`** от провайдера → `jobId` придётся синтезировать локально (или `""`), `pollJob` для этого провайдера не вызывать. **Ответ по умолчанию `b64_json`, а не URL**: `MediaJobState.resultUrls` заполнить нечем — байты надо декодировать и сохранить в `localPath` внутри `submitJob`. **Нет потолка цены** (`maxPriceRub` игнорировать). Ошибки: `400` при маске не на той модели; `503` = «цена не настроена **или** upstream недоступен» — различить нельзя, слепо ретраить `503` рискованно. Таймаут HTTP-клиента — по факту синхронной генерации (дока лимит не называет) |
| **`pollJob(apiKey, jobId)`** | Ничем: у провайдера нет ни задачи, ни статуса, ни поллинга (в доке два эндпоинта, оба синхронные; `GET` на них → `405 Supported methods: POST`) | Метод должен быть **недостижим** (`error("LLM Router is synchronous")` или `MediaException.Unknown`). Если случайно вызвать — нечего опрашивать; в UI не показывать «в процессе», задача всегда терминальная в `submitJob` |
| **`downloadResult(apiKey, url)`** | При `response_format: "b64_json"` качать нечего — байты уже в ответе. При `response_format: "url"` поле с URL в доке **не показано** (только `data[].b64_json`), так что путь URL — «не подтверждено» | Реалистичный путь: декодировать `data[].b64_json` (или `data[].url`, если подтвердится) и писать в локальное хранилище прямо в `submitJob`; `downloadResult` — только для случая URL, и тогда нужен живой тест публичности ссылки |

## Минимальный путь

1. `GET /v1/models` с ключом → id моделей. Картинную модель взять из локального allowlist (например `nano-banana`, `gpt-image-1.5`, `gpt-image-2.5` — единственные, названные в доке) либо из `modality` в `/v1/pricing`, если подтвердится.
2. `POST /v1/images/generations` с `{model, prompt, size:"1024x1024", response_format:"b64_json"}` и заголовком `Idempotency-Key: <uuid из MediaJobRequest.idempotencyKey>` → `200`, `data[].b64_json`. Синхронно, без поллинга. Цену до вызова взять из `/v1/pricing` (если подтвердится) и показать как оценку в ₽.
3. Если нужен референс: `POST /v1/images/edits` JSON `{"model":"nano-banana","prompt":"…","images":[{"url":"data:image/png;base64,…"}]}`. Если нужна маска — только multipart с `model=gpt-image-*`, и сначала проверить семантику «белых областей».

## Не проверено (нужен ключ / платная генерация)

* Форма ответа `GET /v1/models` — в доке примера нет; Pots разбирает как OpenAI-совместимый `{data:[{id}]}`, но модальность/цены оттуда не берутся.
* `GET /v1/pricing` — маршрут есть (живой `401`), но **не документирован**; форма `{data:[{id,modality,prices:[{op,unit,usd}]}]}` известна только из нашего кода. Проверить с ключом.
* `GET /v1/balance` и поле `balance_usd` — маршрут есть (живой `401`), в документации отсутствует; поле взято из `ProviderBalanceClient`.
* `response_format: "url"` — где лежит URL в ответе, не показано; пример ответа только с `b64_json`.
* Семантика маски («белые области перерисовываются») против OpenAI-конвенции — требует живого теста с `gpt-image-*`.
* Потолок `n`, поддержка `aspect_ratio` по моделям, набор `quality` по моделям — не опубликованы.
* Цены картинок (USD за картинку, по `quality`/`size`) — публично не опубликованы, только в кабинете.
* TTL `Idempotency-Key`, лимиты запросов (RPM/RPD), таймаут синхронной генерации — не указаны.
* Живые генерация/правка не запускались (нет ключа; платные вызовы запрещены).

## Sources

* https://llm-router.org/docs — quick start, `Authorization: Bearer`, `GET /v1/models`, коды ошибок, Claude Desktop (`x-api-key`), Hermes (`api_mode: anthropic_messages`), раздел **Images**: два эндпоинта, параметры `model`/`prompt`/`image`/`images`/`mask`/`n`/`size`/`aspect_ratio`/`quality`/`response_format`, таблица форм и пикселей, структура ответа, `Idempotency-Key`, таблица ошибок Images
* https://llm-router.org/ru/docs — русская версия того же (сверка формулировок, в т.ч. про `Idempotency-Key`)
* https://llm-router.org/pricing — публичный прайс: только текстовые модели, USD за 1M токенов; картинок нет
* https://llm-router.org/sitemap.xml и `/robots.txt` — перечень публичных страниц (подтверждает, что отдельных страниц документации по картинкам нет)
* `composeApp/src/commonMain/kotlin/com/pusios/pots/network/Requests.kt` (`getLlmRouterPricing`, `https://llm-router.org/v1/pricing`) и `network/dtos/llmrouter/LlmRouterPricingResponseDto.kt` — форма прайса **нашей стороны** (не источник провайдера)
* `composeApp/src/commonMain/kotlin/com/pusios/pots/network/ProviderBalanceClient.kt` — `https://llm-router.org/v1/balance`, поле `balance_usd`, валюта `USD`
* Живые пробы без ключа (2026-11-23): `GET /v1/images/generations` и `/v1/images/edits` → `405` («Supported methods: POST»); `POST /v1/images/generations` → `401`; `GET /v1/models`, `/v1/pricing`, `/v1/balance` → `401`; `GET`/`POST /v1/zzz-nonexistent` → `404` «The route … could not be found»
