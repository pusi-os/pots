---
okf_version: "0.2"
providers: [polza]
last_verified: 2026-11-22
docs: [https://polza.ai/docs/llms.txt]
base_urls: [https://polza.ai/api/v1, https://polza.ai/api/v2]
---

# Polza.ai — API-контракт для приложения

Документ переписан **по официальной документации** (Mintlify-доки Polza: `llms.txt` + страницы API), а не по пробам. Всё, что помечено «проверено», подтверждено `curl` без ключа 2026-11-22; всё, что требует ключа, помечено как «по доке, ключа на comp нет».

В прошлой версии документа **не было баланса** — он есть и документирован двумя эндпоинтами (`/api/v2/balance` актуальный, `/api/v1/balance` устарел).

## 1. Что нужно приложению

| Что нужно приложению | Есть? | Где именно | Поле / единица |
|---|---|---|---|
| Список моделей | ✅ | `GET /api/v1/models` (ключ **не** нужен) | `{object:"list", data:[…]}`; проверено: 200, 394 модели |
| Цена входа | ✅ | `data[].top_provider.pricing` | `prompt_per_million` — **строка**, ₽/1M (`currency:"RUB"`) |
| Цена выхода | ✅ | там же | `completion_per_million` — строка, ₽/1M |
| Цена кэша | ✅ | там же | `input_cache_read_per_million`, `input_cache_write_per_million` — ₽/1M |
| Контекст | ✅ | `data[].context_length` | токены (дубль в `top_provider.context_length`) |
| Дата релиза | ⚠️ | `data[].created` | unix; по доке — «Timestamp создания», то есть дата появления в каталоге, **не** релиз модели |
| Размер параметров | ❌ | — | поля нет ни в `/models`, ни в `/models/catalog`, ни в `/key` |
| Рейтинг (arena) | ❌ | — | значения нет; в `/models/catalog` только ключ сортировки `sortBy=popular` |
| tools | ✅ | `data[].top_provider.supported_parameters[]` | `tools`, `tool_choice`, `structured_outputs`, `reasoning_effort`, … |
| vision / модальности | ✅ | `data[].architecture.input_modalities` / `output_modalities` | `text`, `image`, `audio`, `video`, `file` |
| **Баланс** | ✅ | `GET /api/v2/balance` (ключ обязателен) | `amount` / `available` / `reservedAmount` / `spentAmount` — **строки**, ₽ |
| Медиагенерация | ✅ | `POST /api/v1/media` (async) + `GET /api/v1/media/{id}`; синхронно — `POST /api/v1/images/generations` | `{id, status}` → `data.url`; результат на CDN 7 дней |

Бонусом, чего нет у большинства конкурентов: `data[].endpoints[]` (куда слать запрос), `data[].reasoning` (`mandatory`, `supported_efforts[]`), комплаенс-флаги `top_provider.is_fz152_compliant` / `stores_data_in_russia`, `parameters` (JSON-схема опций для медиа-моделей), `operations`.

## 2. Аутентификация

| Параметр | Значение | Источник |
|---|---|---|
| Схема | `Authorization: Bearer <ключ>` | OpenAPI `securitySchemes.bearer`, `bearerFormat: API Key` |
| `/api/v1/models` | **без ключа** — дока: эндпоинт публичный; проверено: 200 без заголовков | https://polza.ai/docs/api-reference/models/list.md |
| `/api/v2/balance`, `/api/v1/key` | ключ обязателен; проверено без ключа → **401**, на выдуманном `/api/v2/balance-xyz` → **404** | проба 2026-11-22 |

## 3. Эндпоинты, которые нас касаются

### 3.1 Каталог моделей

**`GET /api/v1/models`** — плоский список без пагинации. Параметры по доке: `type` (`chat|image|embedding|video|audio`), `include_providers=true`. Проверено: 200, 682 404 Б, **394 модели**; по `type`: chat 282, embedding 26, image 31, video 23, tts 12, stt 11, music 5, classification 4.

Поля записи (OpenAPI `PublicModelPresenter`; на живом снимке 394/394, если не указано иное):

| Поле | Тип | Что даёт |
|---|---|---|
| `id` | string | `vendor/model`, напр. `openai/gpt-6.2-sol` |
| `name` | string | человекочитаемое имя |
| `owned_by` | string | `polza-ai` |
| `type` | string | `chat`/`image`/`video`/`tts`/`stt`/`music`/`embedding`/`classification` |
| `short_description` | string | описание по-русски (именно `short_description`, не `description`) |
| `created` | number | unix, «Timestamp создания» |
| `architecture.input_modalities` / `output_modalities` / `modality` | string[] | `text`, `image`, `audio`, `video`, `file` |
| `context_length` | number | контекст, токены |
| `max_completion_tokens` | number | лимит ответа, токены |
| `top_provider.pricing.*` | string | цены в ₽/1M (см. 3.2) |
| `top_provider.supported_parameters` | string[] | `tools`, `tool_choice`, `structured_outputs`, … |
| `top_provider.is_moderated`, `is_unofficial`, `is_hosted_on_polza_infrastructure`, `stores_data_in_russia`, `is_fz152_compliant` | bool | маршрут и комплаенс |
| `reasoning` | object | `mandatory`, `default_enabled`, `supports_max_tokens`, `supports_summary`, `supported_efforts[]` |
| `parameters` | object | JSON-схема опций — только медиа-модели |
| `endpoints` | string[] | напр. `/v1/chat/completions` |

**`GET /api/v1/models/catalog`** — тот же каталог с пагинацией и фильтрами: `search`, `type`, `inputModalities`, `outputModalities`, `providers`, `contextLengthMin/Max`, `storesDataInRussia`, `sortBy` (`name|price|createdAt|popular`), `sort` (`price_asc|price_desc|name`), `sortOrder`, `page`, `limit`. Ответ — `{data:[…], meta:{page, limit, total, totalPages, availableProviders, availableTaskTags}}`. **Приложением не используется.**

### 3.2 Прайс

Отдельного прайс-эндпоинта **нет** — цены лежат в каталоге. Единица везде **₽ за 1M токенов**, валюта подписана полем `currency:"RUB"`.

| Поле `top_provider.pricing` | За что | Единица |
|---|---|---|
| `prompt_per_million` | вход | ₽/1M |
| `completion_per_million` | выход | ₽/1M |
| `input_cache_read_per_million` | чтение кэша | ₽/1M |
| `input_cache_write_per_million` | запись кэша | ₽/1M |
| `internal_reasoning_per_million` | reasoning-токены | ₽/1M |
| `image_input_per_million` / `image_output_per_million` | изображения (в токенах) | ₽/1M |
| `audio_per_million` / `input_audio_cache_per_million` | аудио | ₽/1M |
| `web_search_per_thousand` | веб-поиск | ₽ / 1000 запросов |
| `currency` | — | `"RUB"` |

В OpenAPI каталога есть ещё две вещи, которых нет в плоском `/models`:
- `PricingOverridePresenter` — **ступени и расписания цены**: `utc_days[]`, `utc_start`/`utc_end` (ЧЧММ UTC), `min_prompt_tokens` + те же `*_per_million`. Цена может зависеть от длины промпта и времени суток.
- `ClientTierPresenter` — `{conditions:[…], cost_rub}`: цена «за штуку» под условие (`image_resolution=4K` и т.п.), в рублях.

### 3.3 Баланс

**`GET https://polza.ai/api/v2/balance`** — актуальный (дока: «Используйте v2 в новом коде»).

```json
{"amount":"1234.56000000","available":"1200.06000000",
 "reservedAmount":"12.50000000","spentAmount":"845.30000000",
 "updatedAt":"2026-04-30T12:00:00.000Z"}
```

| Поле | Тип | Смысл |
|---|---|---|
| `amount` | **string**, 8 знаков после точки | баланс организации (кошелёк), ₽ |
| `available` | **string** | доступно к трате: остаток минус резерв под выполняющиеся запросы, не больше лимитов ключа и участника, ₽ |
| `reservedAmount` | string | зарезервировано под выполняющиеся запросы, ₽ |
| `spentAmount` | string | потрачено организацией за всё время, ₽ |
| `updatedAt` | string ISO 8601 | время последнего обновления баланса |

Что важно из доки:
- **Все суммы — строки**, не числа. Парсить с конвертацией.
- `reservedAmount` и `spentAmount` **опускаются**, если у участника, на которого выпущен ключ, задан личный лимит расходов.
- Ответ **кэшируется на 1 секунду** и денег не резервирует: между чтением `available` и запросом сумму могут занять.
- Для «хватит ли денег» ориентир — `available`, не `amount`.
- При нехватке — `402`, код `INSUFFICIENT_BALANCE`; в тексте ошибки резерв запроса, доступная сумма и уже зарезервированное.

**`GET /api/v1/balance`** — **устарел** (`deprecated: true` в OpenAPI). Отличие: в v1 `amount` для ключа участника с личным лимитом подменяется остатком лимита. Если приложение будет читать баланс Polza — читать v2.

**`GET /api/v1/key`** — лимит и расход **ключа** (не организации): `limit`, `limit_remaining`, `limit_reset` (`daily|weekly|monthly|never`), `usage`, `usage_daily`, `usage_weekly`, `usage_monthly` — числа в ₽. С токеном личного кабинета вернёт `400`. Имена полей совместимы с OpenRouter `GET /api/v1/key`, но без обёртки `data` и в рублях.

**Поведение при нуле:** запросы к моделям получают `402 INSUFFICIENT_BALANCE`. Про сам эндпоинт баланса дока такого поведения не описывает (в отличие от AITUNNEL, где прямо сказано «работает и при 0 ₽»).

### 3.4 Медиагенерация

| Путь | Режим | Что делает |
|---|---|---|
| `POST /api/v1/media` | **async** | универсальная генерация (изображения, видео, аудио, музыка, транскрипция). Возвращает `{id, object:"media.generation", status:"pending", created, model}`; опрос — `GET /api/v1/media/{id}` |
| `GET /api/v1/media/{id}` | опрос | `pending` → `processing` → `completed` / `failed` / `cancelled`; результат в `data.url`, стоимость в `usage.cost_rub` |
| `POST /api/v1/media/operations` | sync | операции над готовым медиа: `extend`, `upscale` |
| `POST /api/v1/images/generations` | **sync** | OpenAI-совместимая генерация картинок |

Дока рекомендует опрос каждые 3–5 с для изображений и 5–10 с для видео. Результаты на CDN **7 дней**, потом удаляются; для постоянного хранения — Storage API (`/api/v1/storage/*`, политика `PERMANENT`).

### 3.5 Прочее, что даёт данные для карточки

- `usage` в каждом ответе модели: `prompt_tokens`, `completion_tokens`, `total_tokens`, **`cost_rub`** (алиас `cost`), `prompt_tokens_details.cached_tokens`, `completion_tokens_details.reasoning_tokens`. Фактическую стоимость в рублях можно показывать сразу после ответа.
- `GET /api/v1/history/generations` — история генераций с фильтрацией и пагинацией.
- Кэширование промптов: https://polza.ai/docs/osobennosti/caching.md (~90 % дешевле на чтении).

## 4. Чего в API нет

| Чего нет | Подтверждение |
|---|---|
| Размер параметров модели | нет ни в `PublicModelPresenter` (`/v1/models/catalog`), ни в `/v1/models`, ни в `/v1/key` |
| Arena-рейтинг как значение | в `/models/catalog` есть только ключ сортировки `sortBy=popular`, самого числа в ответе нет |
| Дата релиза модели | есть только `created` — «Timestamp создания» записи каталога |
| Отдельный прайс-эндпоинт | в индексе доки среди эндпоинтов прайса нет; цены только в каталоге |
| Отдельный каталог медиа-моделей | медиа-модели лежат в общем `/models` с `type: image/video/tts/…`; отдельного пути дока не описывает |
| Публичный баланс без ключа | `GET /api/v2/balance` без ключа → 401 (проверено) |

## 5. Наблюдения по приложению (не правки)

1. `network/ProviderBalanceClient.kt` знает только PlusVibe / PlusVibe-Reserve / LLM Router — Polza там нет, хотя эндпоинт документирован. Если добавлять: путь `/api/v2/balance`, поле **`amount` (string)**, валюта RUB, показывать лучше `available`.
2. `GET /api/v1/models/catalog` не используется: он даёт пагинацию, фильтр по модальностям и сортировку по цене «из коробки».
3. DTO `Pricing` не читает `input_cache_read_per_million` / `input_cache_write_per_million`, хотя они приходят на 321 из 394 моделей.
4. `short_description` теряется: DTO ждёт `description`.

## 6. Первоисточники

- Индекс доки: https://polza.ai/docs/llms.txt — 2026-11-22.
- `GET Models`: https://polza.ai/docs/api-reference/models/list.md
- `GET Models Catalog` (поля, `PricingOverridePresenter`, `ClientTierPresenter`): https://polza.ai/docs/api-reference/models/catalog.md
- `GET Balance v2`: https://polza.ai/docs/api-reference/other/balance-v2.md
- `GET Balance` (устарел): https://polza.ai/docs/api-reference/other/balance.md
- `GET Key`: https://polza.ai/docs/api-reference/other/key.md
- `POST Media` / `GET Media Status`: https://polza.ai/docs/api-reference/media/create.md, `…/media/status.md`
- `Учет средств (Usage)`: https://polza.ai/docs/osobennosti/usage.md
- OpenAPI: https://polza.ai/api/openapi.json
- Живые пробы 2026-11-22: `/api/v1/models` → 200 (394 модели, распределение по `type`); `/api/v2/balance` → 401, `/api/v2/balance-xyz` → 404; `/api/v1/key` → 401.
- Код: `data/Service.kt` (`Polza`), `network/ProviderBalanceClient.kt`, `network/dtos/openaicompatible/OpenAICompatibleModelResponseDto.kt`.
