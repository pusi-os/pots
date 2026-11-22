---
okf_version: "0.2"
providers: [llm-router]
last_verified: 2026-11-22
docs: [https://llm-router.org/docs/]
base_urls: [https://llm-router.org/v1, https://llm-router.org]
---

# LLM Router — API-контракт для приложения

Документ переписан **по официальной документации** (`llm-router.org/docs`) и публичному прайсу (`llm-router.org/pricing`).

Главный вывод: **баланс у LLM Router есть как эндпоинт, но не документирован нигде** — ни на странице доков, ни в `sitemap.xml`, ни на лендинге. Дока упоминает баланс только как `402 Payment Required` и советует смотреть его «в кабинете». Это единственный из четырёх платных сервисов, где формат баланса приходится угадывать.

## 1. Что нужно приложению

| Что нужно приложению | Есть? | Где именно | Поле / единица |
|---|---|---|---|
| Список моделей | ✅ | `GET /v1/models` (ключ) | обёртка OpenAI-стиля; **набор полей не документирован** (дока: одна строка «Returns the current list of available models») |
| Цена входа | ⚠️ | публичный прайс `https://llm-router.org/pricing` (HTML, 17 моделей) и `GET /v1/pricing` (ключ, **не документирован**) | **$/1M токенов**; у `/v1/pricing` поля не документированы |
| Цена выхода | ⚠️ | там же | $/1M |
| Цена кэша | ⚠️ | только `GET /v1/pricing` (не документирован); на лендинге отдельной колонки нет | по DTO приложения — `op=cache_read`; дока молчит |
| Контекст | ❌ | — | в доке нет ни для `/v1/models`, ни для прайса |
| Дата релиза | ❌ | — | дока поля не описывает |
| Размер параметров | ❌ | — | поля нет |
| Рейтинг (arena) | ❌ | — | поля нет |
| tools | ⚠️ | в `/v1/chat/completions` (OpenAI-совместимо) | параметры запроса; флага в каталоге нет |
| vision / модальности | ⚠️ | отдельные медиа-эндпоинты (`/v1/images/*`); в `/v1/models` поля не документированы | — |
| **Баланс** | ⚠️ | `GET /v1/balance` — **путь существует** (401 без ключа, 404 на выдуманном), но **формат не документирован** | приложение читает `balance_usd` (USD) — это догадка кода, не дока |
| Медиагенерация | ✅ | `POST /v1/images/generations` (text→image), `POST /v1/images/edits` (правка/маска/композиция) — **sync** | картинка в том же ответе (`url` или `b64_json`); тариф — за каждое возвращённое изображение |

## 2. Аутентификация

| Параметр | Значение | Источник |
|---|---|---|
| Схема (OpenAI-формат) | `Authorization: Bearer llmr_…` | https://llm-router.org/docs/ |
| Схема (Anthropic-формат) | тот же ключ в `x-api-key`; `ANTHROPIC_BASE_URL=https://llm-router.org` (без `/v1`) | там же |
| `/v1/models` | ключ **нужен**: без него **401** `{"type":"error","error":{"type":"authentication_error","message":"Invalid API key."}}` | проба 2026-11-22 |
| `/v1/pricing` | ключ **нужен**: без него **401** (и **404** на `/v1/pricing-xyz` — путь существует) | проба 2026-11-22 |
| `/v1/balance` | ключ **нужен**: без него **401** (и **404** на `/v1/balance-xyz` — путь существует) | проба 2026-11-22 |
| Формат ключа | `llmr_…` (виден в кабинете) | лендинг `/pricing` |

## 3. Эндпоинты, которые нас касаются

### 3.1 Каталог моделей

`GET https://llm-router.org/v1/models` — дока описывает его одной строкой: «Returns the current list of available models. Use the identifiers from this response in the `model` parameter». **Набора полей не публикует.**

Публичного каталога нет: `/api/models`, `/api/public/models`, `/v1/models?public=1` → 404/401. Полный список моделей провайдер показывает в панели `/panel/models` (анонимно → 404) и в примерах конфигов на странице доков (там перечислены, в частности, `gpt-5.6-luna/terra/sol`, `gpt-6-*`, `gemini-3.5-flash`, `deepseek-v4-*`, `glm-5.3*`, `claude-sonnet-4.6`, `claude-opus-4.8`, `claude-haiku-4-5`).

### 3.2 Прайс

**Публичный (без ключа, HTML):** `https://llm-router.org/pricing` — «Prices per 1M tokens in USD, current as of ноябрь 2026». Ровно **17 моделей**, вход и выход, $/1M:

| Модель | Вход $/1M | Выход $/1M |
|---|---|---|
| `gpt-6-astra` | 5.00 | 25.00 |
| `gpt-6.2-astra` | 4.00 | 20.00 |
| `gpt-5.6-sol` | 2.00 | 10.00 |
| `gpt-5.6-terra` | 1.00 | 6.00 |
| `gpt-6-sol` | 1.00 | 5.00 |
| `gpt-6.1-sol` | 1.00 | 5.00 |
| `gpt-6.2-sol` | 1.00 | 5.00 |
| `gpt-6.1-terra` | 0.50 | 2.50 |
| `gpt-5.6-luna` | 0.10 | 0.60 |
| `gpt-6-luna` | 0.05 | 0.25 |
| `gemini-3.5-flash` | 0.60 | 3.60 |
| `gemini-3.8-flash` | 0.375 | 1.875 |
| `gemini-3-flash` | 0.25 | 1.50 |
| `deepseek-v4-pro` | 0.446 | 0.892 |
| `glm-5.3` | 0.035 | 0.70 |
| `deepseek-v4-flash` | 0.045 | 0.09 |
| `glm-5.3-flash` | 0.02 | 0.039 |

Лендинг добавляет: «Each request is charged by the tokens it actually used: input and output tokens, plus reasoning and cache tokens where the model reports them» — то есть reasoning и кэш **тарифицируются**, но отдельных ставок на странице нет.

**`GET /v1/pricing` (ключ) — существует, но не документирован.** Дока о нём не упоминает вообще. Проверено: 401 без ключа, 404 на выдуманном пути. Приложение зовёт его **без ключа** — см. §5.

### 3.3 Баланс

**`GET https://llm-router.org/v1/balance`** — путь существует (401 без ключа, 404 на `/v1/balance-xyz`), но **описания нет ни в доке, ни где-либо ещё**: `sitemap.xml` содержит только `/`, `/ru`, `/pricing`, `/ru/pricing`, `/claude-code`, `/codex`, `/docs`, `/ru/docs`.

Что дока говорит о балансе косвенно:
- В таблице ошибок: `402 Payment Required — Insufficient balance. Top up your account in the cabinet.` То есть баланс — предоплатный, пополняется в кабинете, проверяется по `402`.
- На лендинге: «You top up one balance and use any model from the list: there is no subscription and no per-model commitment».
- В разделе картинок тот же `402`.

Приложение (`network/ProviderBalanceClient.kt`) читает из ответа поле **`balance_usd`** и валюту `USD`. **Это не подтверждено докой** — если провайдер называет поле иначе, баланс молча будет `null` (клиент возвращает `null` при любой неудаче). Проверить без ключа нельзя.

### 3.4 Медиагенерация

| Путь | Режим | Что делает |
|---|---|---|
| `POST /v1/images/generations` | **sync** | text→image; параметры `model`, `prompt`, `n`, `size`, `aspect_ratio`, `quality`, `response_format` (`url`\|`b64_json`) |
| `POST /v1/images/edits` | **sync** | правка/маска (multipart, только `gpt-image-*` понимает маску) и композиция из нескольких картинок (JSON `images:[{url}]`) |

Нюансы из доки: `size` — «форма кадра» (`1024x1536`, `1536x1024`, `1024x1024`), роутер подбирает ближайшее поддерживаемое соотношение; для моделей с тирами разрешения можно передать `"1K"`/`"2K"`/`"4K"` (влияет на цену, не на форму); неудачные/пустые попытки **не тарифицируются**; для безопасных ретраев — заголовок `Idempotency-Key: <uuid>`; `503` = «Pricing isn't configured for the model yet» либо временная недоступность апстрима.

### 3.5 Прочее

- Форматы на одном хосте: OpenAI (`POST /v1/chat/completions`), Anthropic (`POST /v1/messages`), Responses (`POST /v1/responses`), Gemini-совместимые запросы — по лендингу.
- `usage` в ответе: `prompt_tokens` / `completion_tokens` / `total_tokens` (OpenAI-формат) либо `input_tokens` / `output_tokens` (Anthropic-формат). **Денег в `usage` нет** — стоимость в ответе не приходит.
- Приватность (лендинг): «We log metadata only: model, tokens and latency. Prompt content is never stored and never trained on».

## 4. Чего в API нет

| Чего нет | Подтверждение |
|---|---|
| Документированного формата `/v1/models` | дока даёт одну строку без полей |
| Документированного прайс-эндпоинта | `/v1/pricing` в доке не упомянут; прайс публикуется только HTML-страницей `/pricing` |
| **Документированного баланс-эндпоинта** | `/v1/balance` в доке не упомянут; баланс упоминается только как `402` и «в кабинете» |
| Публичного каталога моделей | `/api/models`, `/api/public/models`, `/panel/models` → 404 |
| Контекста, размера параметров, arena-рейтинга | полей нет ни в доке, ни в прайсе |
| Стоимости запроса в `usage` | в ответах только токены |
| Цены кэша/рассуждений отдельными ставками | лендинг говорит «plus reasoning and cache tokens», ставок не публикует |

## 5. Что стоит поправить в приложении (наблюдения, не правки)

1. `Requests.getLlmRouterPricing()` зовёт `GET https://llm-router.org/v1/pricing` **без авторизации** → всегда `401` → цены LLM Router в UI не появляются никогда. Механизм (DTO + маппинг `pricesById`) уже написан; не хватает `applyApiKey(service, apiKey)`.
2. `ProviderBalanceClient` читает `balance_usd` для LLM Router — **поле не документировано**. Пока формат не подтверждён ключом, это риск тихого `null`; безопаснее считать фичу «экспериментальной».
3. Формат `/v1/models` не документирован: если провайдер отдаст голый массив, сервис упадёт на парсинге (`modelsResponseIsArray` у `LlmRouter` не выставлен).

## 6. Что не удалось проверить

- Форму `/v1/models`, `/v1/pricing`, `/v1/balance` с ключом — ключа LLM Router на comp нет. Всё, что известно о `/v1/pricing` и `/v1/balance`, — это факт существования пути (401 vs 404), не форма ответа.
- Название поля баланса (`balance_usd`) — взято из кода приложения, не из доки.

## 7. Первоисточники

- Доки (base URL, `/v1/models`, `/v1/messages`, `x-api-key`, коды ошибок, картинки): https://llm-router.org/docs/ — 2026-11-22.
- Публичный прайс (17 моделей, USD/1M): https://llm-router.org/pricing — «current as of ноябрь 2026».
- `sitemap.xml` (полный список страниц — баланса среди них нет): https://llm-router.org/sitemap.xml
- Живые пробы 2026-11-22: `/v1/models` → 401, `/v1/models-xyz` → 404; `/v1/pricing` → 401, `/v1/pricing-xyz` → 404; `/v1/balance` → 401, `/v1/balance-xyz` → 404; `/api/models`, `/api/public/models`, `/panel/models` → 404.
- Код: `data/Service.kt` (`LlmRouter`), `network/Requests.kt:277-284`, `network/ProviderBalanceClient.kt:47`, `network/dtos/llmrouter/LlmRouterPricingResponseDto.kt`.
