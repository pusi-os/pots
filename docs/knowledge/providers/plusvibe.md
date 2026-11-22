---
okf_version: "0.2"
providers: [plusvibe]
last_verified: 2026-11-22
docs: [https://plusvibeapi.ru/docs/, https://plusvibeapi.ru/api/openapi]
base_urls: [https://plusvibeapi.ru/v1, https://gw.plusvibeapi.ru/v1]
---

# PlusVibeAPI — API-контракт для приложения (эталон)

Документ переписан **по официальной документации**: страницы доков + **OpenAPI-контракт** (`https://plusvibeapi.ru/api/openapi`), который для этого провайдера является самым точным первоисточником — в нём перечислены все пути и схемы.

Медиа-контракт PlusVibe под нашу абстракцию — отдельный документ: [`../media-providers/plusvibe.md`](../media-providers/plusvibe.md).

В прошлой версии не было баланса. Он есть и **уже используется приложением** — и, что важно, поле в коде совпадает с OpenAPI.

## 1. Что нужно приложению

| Что нужно приложению | Есть? | Где именно | Поле / единица |
|---|---|---|---|
| Список моделей | ✅ | `GET /v1/models` (ключ); публично — `GET /api/catalog` (**без ключа**) | `/v1/models`: `data[]` = только `id` + `object`; `/api/catalog`: 106 записей |
| Цена входа | ✅ | `GET /v1/pricing` (ключ) и `GET /api/catalog` (публично) | `variants[].inputRub` / `inputRub`, **₽/1M** (в прайсе валюта подписана: `currency: "RUB"`) |
| Цена выхода | ✅ | там же | `outputRub`, ₽/1M |
| Цена кэша | ✅ | там же | `cacheReadRub`, `cacheWriteRub`, `cacheable` (bool), ₽/1M |
| Контекст | ✅ | `/api/catalog` | `caps.contextWindow` / `variants[].maxContext`, токены |
| Дата релиза | ⚠️ | `/api/catalog` | `caps.releaseDate` — заполнено у 17 из 106 записей (снимок 2026-11-22) |
| Размер параметров | ❌ | — | поля нет ни в прайсе, ни в каталоге |
| Рейтинг (arena) | ❌ | — | поля нет; есть `popularity` (витринная метрика, не рейтинг) |
| tools | ✅ | `/api/catalog` | `caps.toolCalling` (bool) — 83 из 106 |
| vision / модальности | ✅ | `/api/catalog` | `caps.vision`, `caps.modalitiesIn/Out` — vision у 62 из 106 |
| **Баланс** | ✅ | `GET /v1/balance` (ключ) | `balance_rub` — **number**, ₽; `currency` — строка, `"RUB"` |
| Медиагенерация | ✅ | sync `POST /v1/images/generations`; async `POST /api/media/generate` → `GET /api/media/jobs/{id}`; цена — `POST /api/media/price` / `POST /api/media/public-price` | — |

## 2. Аутентификация

| Параметр | Значение | Источник |
|---|---|---|
| Схема | `Authorization: Bearer sk-pv-…`; для клиентов Anthropic — `x-api-key: sk-pv-…` | https://plusvibeapi.ru/docs/ |
| `/v1/models`, `/v1/pricing`, `/v1/balance` | ключ нужен; проверено: **401** `{"error":{"message":"Отсутствует или неверный API-ключ.","type":"authentication_error","code":401,"cause":"missing_key",…}}` | проба 2026-11-22 |
| `/api/catalog`, `/api/media-catalog` | **без ключа**; проверено: `/api/catalog` → 200, 526 742 Б, 106 записей | проба 2026-11-22 |
| Формат ключа | `sk-pv-…`, показывается один раз при создании | доки |
| Важно | PlusVibe проверяет ключ **раньше маршрута**: на выдуманном `/v1/balance-xyz` тоже 401. Метод «401 vs 404» здесь не работает | проба 2026-11-22 |

## 3. Эндпоинты, которые нас касаются

Полный список путей из OpenAPI (2026-11-22):

| Метод | Путь | Что делает |
|---|---|---|
| GET | `/v1/models` | список моделей |
| GET | `/v1/pricing` | прайс аккаунта (₽/1M) |
| GET | `/v1/balance` | остаток предоплаченного баланса |
| GET | `/v1/model-capabilities` | машиночитаемые возможности моделей |
| POST | `/v1/chat/completions` | чат (OpenAI-формат) |
| POST | `/v1/messages` | чат (Anthropic-формат) |
| POST | `/v1/messages/count_tokens` | подсчёт токенов (заглушка Anthropic) |
| POST | `/v1/responses` | Responses API |
| POST | `/v1/embeddings` | эмбеддинги |
| POST | `/v1/audio/speech` | синтез речи |
| POST | `/v1/audio/transcriptions` | распознавание речи |
| POST | `/v1/images/generations` | генерация картинок (**sync**) |
| GET | `/v1/images/generations` | история генераций картинок этого ключа |
| POST | `/api/media/generate` | задача медиагенерации (**async**) |
| GET | `/api/media/jobs/{id}` | статус задачи |
| POST | `/api/media/price`, `/api/media/public-price` | котировка цены медиа |
| GET | `/api/media/file/{id}/{idx}` | подписанная раздача файла |
| POST/GET/DELETE | `/api/media/voices…`, `/api/media/veo/extend…` | голоса, продление Veo |
| POST | `/v1/search`, `/v1/extract` | веб-поиск, извлечение страниц в markdown |
| POST | `/v1/decisions` | «решения» (да/нет, выбор, шкала) |
| GET | `/v1/generations`, `/v1/generations/{id}` | история расхода |
| GET | `/api/catalog` | **публичный** каталог текстовых моделей |
| GET | `/api/media-catalog` | **публичный** каталог медиа |
| POST/GET | `/v1/tickets…`, `/v1/technical-feedback…` | поддержка |

### 3.1 Каталог моделей

**`GET /v1/models`** (ключ) — по OpenAPI схема `ModelsResponse`: `{object:"list", data:[{id, object}]}`. **Больше ничего**: ни цены, ни контекста. Доки: по умолчанию один ID на модель, `?variants=1` — полный список вариантов-маршрутов (`model:alias`). Приложение `?variants=1` не запрашивает.

**`GET /api/catalog`** (**без ключа**) — самый полный публичный каталог из восьми сервисов. Снимок 2026-11-22: 200, 526 742 Б, **106 записей**. Наполненность: `caps.toolCalling == true` — 83/106, `caps.vision == true` — 62/106, `caps.releaseDate` — 17/106, `officialInputUsd` — 48/106.

Поля записи: `model` / `apiModel`, `variants[]` (`inputRub`, `outputRub`, `cachedInputRub`, `cachedWriteInputRub`, `maxContext`, `maxOutput`, `reasoning{supported,levels,billedAsOutput}`, `vision`, `modalitiesIn`, `uptimePct`, `ttftP50Ms`, `ttftP90Ms`, `tps`, `cacheHitPct`, `sourceLabel`, `pinAs`), `caps` (`contextWindow`, `maxOutput`, `modalitiesIn/Out`, `reasoning`, `toolCalling`, `jsonMode`, `vision`, `knowledgeCutoff`, `releaseDate`, `ruNote`), `displayLabel`, `blurb`, `maker`, `popularity`, `officialInputUsd` / `officialOutputUsd` / `officialCacheReadUsd`.

**`GET /v1/model-capabilities`** (ключ) — полный машиночитаемый контракт возможностей: операции, JSON-схемы медиа-запросов, `providerVariants`, ценовой статус.

### 3.2 Прайс

**`GET /v1/pricing`** (ключ) — схема `PricingResponse`:

```
{object:"list", currency:"RUB"|"USD",   // "Валюта всех цен в ответе: RUB на plusvibeapi.ru"
 data:[{model, variants:[{addressAs, inputRub, outputRub, cacheReadRub, cacheable,
                          cacheWriteRub, isDefault, providerAlias, pinAs, contextTiers[]}]}]}
```

Единица — **₽ за 1M токенов** (на `gw`-хосте и на основном; `USD` предусмотрен схемой, но на plusvibeapi.ru приходит RUB). По доке — «Цены аккаунта», то есть персональные, а не витринные.

Публичная альтернатива без ключа — `/api/catalog` (`variants[].inputRub` / `outputRub` / `cachedInputRub` / `cachedWriteInputRub`) плюс официальные цены вендора в долларах (`officialInputUsd`, `officialOutputUsd`, `officialCacheReadUsd`).

### 3.3 Баланс

**`GET https://plusvibeapi.ru/v1/balance`** (ключ). Описание в OpenAPI: «Возвращает остаток предоплаченного баланса в рублях». Схема `BalanceResponse`:

| Поле | Тип | Смысл |
|---|---|---|
| `balance_rub` | **number** | остаток предоплаченного баланса, ₽ |
| `currency` | string | `"RUB"` (пример в схеме) |

Оба поля — единственные в схеме; обязательность в OpenAPI не помечена (нет `required`), но других полей нет.

**Приложение уже это использует** и правильно: `network/ProviderBalanceClient.kt` → `BalanceSpec("https://plusvibeapi.ru/v1/balance", "balance_rub", "RUB")`, а если в ответе есть `currency` — берёт его, иначе `RUB`.

**Поведение при нуле:** OpenAPI объявляет `402 Payment Required` для платных методов (чат, изображения); отдельного текста «что происходит при нулевом балансе» дока не содержит. Фактический отказ приходит как `402`.

### 3.4 Медиагенерация

| Путь | Режим | Что делает |
|---|---|---|
| `POST /v1/images/generations` | **sync** | OpenAI-совместимая генерация картинки, результат в том же ответе |
| `GET /v1/images/generations` | sync | история генераций этого ключа |
| `POST /api/media/generate` | **async** | задача (картинки, видео, аудио) → id задачи |
| `GET /api/media/jobs/{id}` | опрос | статус задачи |
| `POST /api/media/price` / `public-price` | sync | котировка цены (с ключом / публично) |
| `GET /api/media/file/{id}/{idx}` | sync | подписанная раздача готового файла |
| `POST /api/media/veo/extend` (+ `/price`) | sync | продление готового Veo-видео |
| `/api/media/voices…` | sync | клонирование и список голосов |

### 3.5 Прочее, что даёт данные для карточки

- `GET /v1/generations` и `/v1/generations/{id}` — история расхода (OpenAPI: «List usage history», «Get a single usage record»). Приложением не используется.
- `POST /v1/search` / `/v1/extract` — веб-поиск и извлечение страниц; `POST /v1/decisions` — модель-решатель.
- `caps.reasoning{supported, levels, billedAsOutput}` в `/api/catalog` — уровни рассуждений и то, что они тарифицируются как выход.

## 4. Чего в API нет

| Чего нет | Подтверждение |
|---|---|
| Цен, контекста, модальностей в `/v1/models` | схема `ModelsResponse` содержит только `id` и `object` |
| Размера параметров | нет ни в `/v1/pricing`, ни в `/api/catalog` |
| Arena-рейтинга | нет; `popularity` — витринная метрика |
| Даты релиза у большинства моделей | `caps.releaseDate` заполнено у 17 из 106 |
| Публичного баланса без ключа | `/v1/balance` без ключа → 401 |
| Возможности отличить «путь не существует» от «нет ключа» | ключ проверяется раньше маршрута: 401 и на выдуманном пути |

## 5. Что стоит поправить в приложении (наблюдения, не правки)

1. **Цены PlusVibe не показываются вообще.** `/v1/models` их не содержит, `/v1/pricing` и `/api/catalog` не запрашиваются. «PlusVibe работает хорошо» держится на курируемом `ModelCatalog`, а не на ответе провайдера.
2. Кэш, tools, vision не показываются, хотя публичный `/api/catalog` отдаёт их **без ключа**.
3. Резервный хост заведён отдельным сервисом (`plusvibe-reserve`), а не как fallback внутри одного: при недоступности основного пользователь переключает сервис руками.

## 6. Что не удалось проверить

- Ответы `/v1/models`, `/v1/pricing`, `/v1/balance` с ключом — ключа PlusVibe на comp нет. Формы взяты из OpenAPI-контракта (это контракт, а не догадка), но живой ответ не снят.

## 7. Первоисточники

- Доки (base URL, резерв, авторизация, формат id, `?variants=1`): https://plusvibeapi.ru/docs/ — 2026-11-22.
- **OpenAPI** (`ModelsResponse`, `PricingResponse`, `BalanceResponse`, полный список путей): https://plusvibeapi.ru/api/openapi — 2026-11-22.
- llms.txt (91 текстовая модель, ₽/1M, публичные поверхности): https://plusvibeapi.ru/llms.txt
- Живые пробы 2026-11-22: `/api/catalog` → 200 (526 742 Б, 106 записей); `/v1/models`, `/v1/pricing`, `/v1/balance` → 401 `missing_key`.
- Код: `data/Service.kt` (`PlusVibe`), `network/ProviderBalanceClient.kt:45`, `data/ModelTransformations.kt`.
