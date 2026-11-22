---
okf_version: "0.2"
providers: [gptunnel]
last_verified: 2026-11-22
docs: [https://docs.gptunnel.ru/]
base_urls: [https://gptunnel.ru/v1, https://gptunnel.ru/api/v2, https://gptunnel.ru/api/v1]
---

# GPTunnel (GPTunneL) — API-контракт для приложения

Документ переписан **по официальной документации** (`docs.gptunnel.ru`). Единственный из восьми сервисов с **не** Bearer-схемой авторизации.

В прошлой версии не было баланса. Он есть и документирован полностью — **двумя** эндпоинтами: `GET /v1/balance` (владелец ключа / организация) и `POST /api/v1/balance/get` (партнёр по клиентам).

## 1. Что нужно приложению

| Что нужно приложению | Есть? | Где именно | Поле / единица |
|---|---|---|---|
| Список моделей | ✅ | `GET /v1/models` (ключ обязателен) | `{object:"list", data:[…]}`; 401 без ключа (проверено) |
| Цена входа | ⚠️ | `data[].cost_context` | **единица не документирована** — ни ₽, ни $, ни «за 1M» в доке нет |
| Цена выхода | ⚠️ | `data[].cost_completion` | то же: единица не документирована |
| Цена кэша | ❌ | — | отдельного поля цены кэша нет; коэффициенты кэша описаны только прозой (см. 3.5) |
| Контекст | ❌ | — | поля нет; в примере ответа его тоже нет |
| Дата релиза | ⚠️ | `data[].created` | unix; семантика в доке не описана (таблицы полей нет) |
| Размер параметров | ❌ | — | поля нет |
| Рейтинг (arena) | ❌ | — | поля нет |
| tools | ⚠️ | в `/v1/models` — нет; в `POST /v1/chat/completions` — да | `tools`, `tool_choice`, `parallel_tool_calls`, `functions` (параметры запроса) |
| vision / модальности | ❌ | — | поля нет; «Vision» встречается только текстом в `title` («GPT 4o Vision 128K») |
| **Баланс** | ✅ | `GET /v1/balance` (ключ) | `balance` — **number**, ₽, может быть отрицательным; `creditLimit` — number, ₽ |
| Медиагенерация | ✅ | `POST /api/v2/media/tasks` (async) + `GET /api/v2/media/tasks/:id`; цена — `POST /api/v2/media/price` (котировка без списания) | `status: queued/running/done/failed`, `price` в ₽ |

## 2. Аутентификация

| Параметр | Значение | Источник |
|---|---|---|
| Схема | `Authorization: <ключ>` — **без** `Bearer` | доки: `Headers → Authorization* string — API ключ`, пример `-H 'Authorization: YOUR_API_KEY'`; в коде `ApiKeyAuthStyle.RAW` |
| `/v1/models`, `/v1/balance` | ключ обязателен; проверено: 401 `{"error":{"message":"Unauthorized","type":"unauthorized_request_error",…}}` | проба 2026-11-22 |
| Ограничение по моделям | ключ можно ограничить списком моделей: такой ключ видит в каталогах только разрешённые модели, остальные → `403 model_not_allowed` | https://docs.gptunnel.ru/ |
| Где берётся ключ | личный кабинет → вкладка «API» (и в бизнес-профиле) | там же |

## 3. Эндпоинты, которые нас касаются

### 3.1 Каталог моделей

`GET https://gptunnel.ru/v1/models` — `{object:"list", data:[…]}`. Дока: «Возвращает список всех доступных LLM моделей **с ценами**», и отдельно: «Цены на модели отдаются тем же эндпоинтом, что и список, — отдельного прайса запрашивать не нужно».

**Таблицы полей в доке нет** — только пример ответа. Поэтому семантика части полей не документирована:

```json
{"object":"list","data":[{"id":"gpt-4o","object":"model","created":1685584800,
 "title":"GPT 4o Vision 128K","max_capacity":4096,
 "cost_context":"1.3500","cost_completion":"2.7000",
 "deprecated":false,"deprecated_at":null,"deprecation_redirect_to":null}]}
```

| Поле | Документировано? | Что известно |
|---|---|---|
| `id` | пример | id для поля `model` |
| `object` | пример | `"model"` |
| `created` | пример | unix; что именно за дата — не сказано |
| `title` | пример | человекочитаемое имя |
| `max_capacity` | **нет** | в примере `4096`; ни описания, ни единицы в доке нет |
| `cost_context` | **нет** | в примере `"1.3500"`; **единица измерения не указана** |
| `cost_completion` | **нет** | в примере `"2.7000"`; единица не указана |
| `deprecated` | да | «дата снятия уже наступила» |
| `deprecated_at` | да | ISO 8601; дата в будущем = анонс |
| `deprecation_redirect_to` | да | id модели-аналога; `null` → после даты запросы вернут `410` |

Публичного каталога без ключа нет: `gptunnel.ru/api/models`, `/api/v1/models`, `/v1/models/prices` → **404** (проба 2026-11-22). Прайс на сайте (`https://www.gptunnel.ru/ru/prices`) — отдельная HTML-поверхность, парсить её приложение не должно.

### 3.2 Прайс

- Для LLM — **в каталоге** (`cost_context` / `cost_completion`), отдельного эндпоинта нет.
- Общее правило из доки: «Цены на запросы через API не отличаются от цен в интерфейсе, за исключением специальных тарифов» (спеццены организации — в «Бизнес → Цены», спеццены ключа — в допсоглашении и в интерфейсе не видны). https://docs.gptunnel.ru/getting-started/pricing/
- Для медиа — **`POST /api/v2/media/price`**: считает стоимость по телу `{model, prompt, params, inputs}` **без запуска задачи и без списания средств**. Ответ: `{code:0, model, price, currency:"RUB", params}`. Единственное место, где валюта цены подписана явно.
- Доплата, видимая в доке: `obfuscate` (скрытие PII) — 280 ₽ за 1M проверяемых токенов сверх цены модели.

### 3.3 Баланс

**`GET https://gptunnel.ru/v1/balance`** — «Возвращает баланс и кредитный лимит выбранного счёта **в рублях**».

| Параметр/поле | Тип | Описание по доке |
|---|---|---|
| `?useWalletBalance=true` | query bool | получить баланс личного счёта владельца ключа |
| `orgId` | string \| null | id организации, чей баланс вернули; `null` — личный счёт (в т. ч. при `useWalletBalance=true`) |
| `userId` | string | id владельца ключа (возвращается всегда) |
| `object` | string | всегда `"balance"` |
| `balance` | **number** | остаток **в рублях**, округлён до копеек, **может быть отрицательным**; бонусы и кредитный лимит в сумму не входят |
| `creditLimit` | **number** | кредитный лимит в рублях; для личного счёта всегда `0` |

Ответ 200 **всегда содержит все пять полей**. Неверный ключ → `401 Unauthorized`.

Как понять, к какому счёту привязан ключ: вызвать `GET /v1/balance` без параметров. `orgId != null` → ключ привязан к организации и вернулся её баланс; `orgId == null` → личный счёт (такой ключ всегда возвращает личный баланс).

Примеры из доки:

```json
{"orgId":"ddc0652cde46d307200011c4","userId":"0123456789abcdef01234567",
 "object":"balance","balance":-51.54,"creditLimit":15000}
```
```json
{"orgId":null,"userId":"0123456789abcdef01234567",
 "object":"balance","balance":120.5,"creditLimit":0}
```

**`POST https://gptunnel.ru/api/v1/balance/get`** — второй, «партнёрский»: остаток **клиента** (пользователя или организации), которым управляет ваш бизнес-ключ. Тело: ровно одно из `user_id` / `org_id` / `user_data.phone`. Ответ: `{code, type:"user"|"org", id, balance, bonus, credit_limit, inn, ogrn, currency:"RUB"}`. `bonus` — бонусы, **1 бонус = 0,5 ₽**. Ключ личного аккаунта → `403 code 13`. Приложению не нужно, но знать, что «баланс» у GPTunnel бывает двух смыслов, полезно.

**Поведение при нуле:** дока прямо допускает **отрицательный** `balance` и ненулевой `creditLimit` (кредитная линия), то есть нулевой баланс не обязательно означает отказ. Отказ приходит как `402 Insufficient Balance` в чате.

### 3.4 Медиагенерация

| Путь | Режим | Что делает |
|---|---|---|
| `GET /api/v2/media/models` | sync | модели, доступные ключу, **со схемой `params`, схемой `inputs` и ценой**; фильтр `?type=IMAGE|VIDEO|AUDIO` |
| `POST /api/v2/media/price` | sync | котировка цены без запуска и без списания |
| `POST /api/v2/media/tasks` | **async** | создание задачи: `model`, `prompt`, `params`, `inputs`, `webhook_url`, `idempotency_key`, `wait` (до ~30 с держит соединение) |
| `GET /api/v2/media/tasks/:id` | опрос | `queued`/`running`/`done`/`failed`; `result[].url`, `price`, `error.code`, `refunded` |
| `/v1/media/create`, `/v1/media/generate` | legacy | старые пути, дока перечисляет их в таблице «Устаревшие модели» |

Модель в медиа-каталоге отдаёт: `id`, `type`, `price`, `price_unit` (`per_generation|per_second|per_megapixel|per_1k_tokens`), `price_input`, `price_input_unit` (`per_character|per_1k_characters|per_token|per_1k_tokens|per_megapixel`), `discount_percent`, `pricing`, `prompt` (`required|optional|none`), `params[]`, `inputs[]`, `deprecated*`. То есть **тарифная модель медиа описана машиночитаемо**, включая единицу цены — в отличие от LLM-каталога.

### 3.5 Прочее, что даёт данные для карточки

- `usage` в ответе чата: `prompt_tokens`, `completion_tokens`, `total_tokens`, `prompt_cost`, `completion_cost`, `total_cost`, `prompt_tokens_details.cached_tokens` / `cache_write_tokens`. **Единица `prompt_cost`/`completion_cost` в доке тоже не названа** — по смыслу это стоимость запроса по вашему тарифу, но слово «рубли» рядом с этими полями не стоит (в отличие от эндпоинта баланса).
- Кэш: отдельного поля цены нет, есть **коэффициенты** прозой — для Claude чтение ×0.1, запись ×1.25 (5 мин) и ×2.0 (1 час) от цены input; для OpenAI-моделей чтение ×0.5. На личном счёте кэш на цену не влияет вообще (экономия остаётся у провайдера); на бизнес-аккаунте включается в кабинете.
- Депрекация: заголовки `Deprecation: true`, `Sunset`, `Warning: 299`, `x-model-requested`, `x-model-served`; в теле — `warnings[{code:"DEPRECATED_MODEL"}]`. Деньги списываются по **запрошенной** модели.
- `POST /v1/assistant/chat` — ассистент с базой знаний (RAG); `POST /v1/messages` — Anthropic-совместимый вход для Claude.

## 4. Чего в API нет

| Чего нет | Подтверждение |
|---|---|
| Контекст модели | в `/v1/models` поля нет ни в примере, ни в доке; окно контекста дока не описывает вообще |
| Модальности / vision как поле | поля нет; «Vision» — только текстом в `title` |
| Размер параметров, arena-рейтинг | полей нет |
| Единица `cost_context` / `cost_completion` | **не документирована**: дока не говорит ни валюту, ни базу (за 1 токен / за 1K / за 1M) |
| Смысл `max_capacity` | поля нет в описании, только в примере |
| Отдельный прайс-эндпоинт для LLM | дока прямо: «отдельного прайса запрашивать не нужно», цены в каталоге |
| Публичный каталог без ключа | `/api/models`, `/api/v1/models`, `/v1/models/prices` → 404 |
| Цена кэша отдельным полем | только коэффициенты в тексте |

## 5. Что не удалось проверить

- Живой ответ `/v1/models` и `/v1/balance` с ключом — ключа GPTunnel на comp нет. Опора на документированные примеры.
- Единицу `cost_context`: пробовал вывести из примера `usage` в доке — не сходится. В примере чата `prompt_tokens: 24`, `prompt_cost: 0.01608`, при `cost_context: "1.3500"` из примера каталога. Если бы `cost_context` был ₽/1M, 24 токена стоили бы 0,0000324 ₽; если ₽/1K — 0,0324 ₽. Ни то, ни другое не равно 0,01608, а отношение `prompt_cost/completion_cost` (2,23) не равно отношению `cost_context/cost_completion` (0,5). Значит **примеры в доке не связаны между собой, и единицу из них вывести нельзя** — только запросить у провайдера.

## 6. Первоисточники

- Индекс API-раздела: https://docs.gptunnel.ru/api/ — «GET /v1/models — список доступных LLM с ценами».
- `GET /v1/models` (пример, депрекация, 401): https://docs.gptunnel.ru/api/models/
- `GET /v1/balance` (поля, `useWalletBalance`, примеры): https://docs.gptunnel.ru/getting-started/balance/
- `POST /api/v1/balance/get` (партнёрский баланс, `currency: RUB`): https://docs.gptunnel.ru/business/balance-get/
- Стоимость запросов: https://docs.gptunnel.ru/getting-started/pricing/
- `POST /api/v2/media/price` (котировка, `currency:"RUB"`): https://docs.gptunnel.ru/creative-lab/price/
- `GET /api/v2/media/models` (тарифы и единицы медиа): https://docs.gptunnel.ru/creative-lab/models/
- `POST /api/v2/media/tasks` / `GET /api/v2/media/tasks/:id`: https://docs.gptunnel.ru/creative-lab/create-task/, `…/get-task/`
- `POST /v1/chat/completions` (tools, obfuscate, кэш, `usage`): https://docs.gptunnel.ru/api/chat-completions/
- Устаревшие модели: https://docs.gptunnel.ru/getting-started/deprecated-models/
- Живые пробы 2026-11-22: `/v1/models` → 401; `/v1/balance` → 401, `/v1/balance-xyz` → **404** (путь существует); `api.gptunnel.ru/v1/models` → 404; `www.gptunnel.ru/v1/models` → 301.
- Код: `data/Service.kt` (`Gptunnel`, `ApiKeyAuthStyle.RAW`), `network/ProviderBalanceClient.kt` (GPTunnel не поддержан).
