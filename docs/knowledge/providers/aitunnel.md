---
okf_version: "0.2"
providers: [aitunnel]
last_verified: 2026-11-22
docs: [https://aitunnel.ru/llms.txt]
base_urls: [https://api.aitunnel.ru/v1, https://ru-api.aitunnel.ru/v1]
---

# AITUNNEL — API-контракт для приложения

Документ переписан **по официальной документации** (`aitunnel.ru/docs`, `.md`-версии страниц + `llms.txt`). Главное, чего не было в прошлой версии: **баланс** — он документирован, приходит и отдельным эндпоинтом, и в каждом ответе модели.

У AITUNNEL **две поверхности**: `/v1/models` (её использует приложение) — минимальный OpenAI-список; всё богатство (цены, контекст, модальности, тарифы медиа) лежит в **публичном** `/public/aitunnel/models`, который приложение не запрашивает.

## 1. Что нужно приложению

| Что нужно приложению | Есть? | Где именно | Поле / единица |
|---|---|---|---|
| Список моделей | ✅ | `GET /v1/models` (ключ) — минимум; `GET /public/aitunnel/models` (**без ключа**) — полный каталог по группам | `/v1/models`: `{object:"list", data:[…]}`; публично — `{chat:{…}, images:{…}, videos:{…}, …}` |
| Цена входа | ✅ | публичный каталог, `chat.<id>` | `prompt_cost` — **number, ₽ за 1M токенов**, комиссия внутри |
| Цена выхода | ✅ | там же | `completion_cost` — number, ₽/1M |
| Цена кэша | ⚠️ | там же | только `cache_discount` (множитель: `0.9` = 90 % скидки на чтение), абсолютной цены кэша нет |
| Контекст | ✅ | там же | `context_size` — токены |
| Дата релиза | ⚠️ | `created` (и в `/v1/models`, и в каталоге) | unix «появления в каталоге» — по доке не дата релиза модели |
| Размер параметров | ❌ | — | поля нет ни в одной группе каталога |
| Рейтинг (arena) | ❌ | — | поля нет |
| tools | ⚠️ | в каталоге поля нет; tool-calling есть как возможность API (`/docs/tool-calling`) | отдельного флага «умеет tools» в каталоге нет |
| vision / модальности | ✅ | `modalities.input` / `.output` | `text`, `image`, `audio`, `video`, `file` (у групп медиа — `image`, `video`, `embedding`, `rerank`, `decision`) |
| **Баланс** | ✅ | `GET /v1/aitunnel/balance` (ключ) **и** `usage.balance` в каждом ответе модели | `balance` — number, ₽; `budget` — number, ₽ (остаток бюджета ключа, если задан) |
| Медиагенерация | ✅ | картинки — **sync** `POST /v1/images/generations`; видео — **async** `POST /v1/videos` → `GET /v1/videos/{id}` → `GET /v1/videos/{id}/content` | MP4 / base64-картинки в том же ответе |

## 2. Аутентификация

| Параметр | Значение | Источник |
|---|---|---|
| Схема | `Authorization: Bearer sk-aitunnel-…`; альтернатива `x-api-key` (клиенты Anthropic). При двух заголовках берётся `Authorization` | https://aitunnel.ru/docs/keys.md |
| Ключ управления | отдельный тип ключа с правами (`billing:read`, `usage:read`, …) — для баланса/расходов всего аккаунта | https://aitunnel.ru/docs/api/management-keys |
| `/v1/models` | ключ **нужен**; проверено без заголовков → **401** `{"error":{"code":401,"type":"authentication_failed","message":"Вы не указали токен…"}}` | проба 2026-11-22 |
| `/public/aitunnel/models` | ключ **не нужен** — дока: «Ключ и заголовок Authorization не нужны. Запрос можно слать из браузера»; проверено: 200, 617 011 Б | https://aitunnel.ru/docs/models.md |
| `/v1/aitunnel/balance` | API-ключ **или** ключ управления с правом `billing:read`; без ключа → 401 (проверено) | https://aitunnel.ru/docs/api/balance.md |

Зеркало: `https://ru-api.aitunnel.ru/v1` — «те же ключи, пути, модели, цены и лимиты», на случай блокировки основного хоста (симптом — таймаут / `Connection reset` / `Failed to fetch`). В коде приложения зеркала нет.

## 3. Эндпоинты, которые нас касаются

### 3.1 Каталог моделей

**`GET https://api.aitunnel.ru/v1/models`** (ключ) — формат OpenAI: только `id`, `created`, `object`, `owned_by`. Дока: «Цен и возможностей нет. В список попадают ваши пресеты с `owned_by: "preset"`».

**`GET https://api.aitunnel.ru/public/aitunnel/models`** (**без ключа**) — объект по группам: `chat`, `images`, `videos`, `embeddings`, `speech`, `transcriptions`, `rerank`, `decisions`, `moderations`. `GET …/public/aitunnel/models/chat` — сразу карта «имя модели → параметры».

Снимок 2026-11-22: 617 011 Б; **chat 202, images 50, videos 35**.

Общие поля записи (по доке): `provider`, `description` / `short_description`, `modalities.input` / `output`, `created`, `cache_discount`. Поля чата: `prompt_cost`, `completion_cost`, `context_size`, `max_output`, `price_tiers[]`, `batch{discount, window}`.

Живой пример (`claude-sonnet-4.6`, 2026-11-22):

```json
{"provider":"anthropic","short_description":"…","modalities":{"input":["text","image","file"],"output":["text"]},
 "created":1771342990,"prompt_cost":600,"completion_cost":3000,
 "context_size":1000000,"max_output":128000,"cache_discount":0.9,
 "batch":{"discount":0.5,"window":"24h"}}
```

**`GET /public/aitunnel/openrouter/models`** (тоже без ключа) — весь каталог OpenRouter по слагам `провайдер/модель`, с `prompt_cost`/`completion_cost` в ₽/1M и `router: true` у роутеров. Приложением не используется.

### 3.2 Прайс

Отдельного прайс-эндпоинта **нет** — цены в публичном каталоге. Единицы по группам (дока формулирует явно):

| Группа | Поля цены | Единица |
|---|---|---|
| `chat` | `prompt_cost`, `completion_cost` | **₽ за 1M токенов** |
| `images` | `min_price_per_image`, `max_price_per_image`, `text_tokens_input_price`, `image_tokens_input_price`, `image_tokens_output_price` | ₽ за картинку / ₽ за 1M токенов |
| `videos` | `min_price_per_second`, `max_price_per_second`, `size_prices{}` | ₽ за секунду |
| `speech` | цена за символы или минуту | ₽ / символы, ₽ / минута |

Плюс две вещи, которые легко упустить:
- **`price_tiers[]`** — лесенка цены по длине промпта: `{min_prompt_tokens, prompt_cost, completion_cost}`. Дока: «Ориентируйтесь на `price_tiers`, а не на `prompt_cost`, если шлёте длинный контекст» — на `qwen3.7-flash` верхняя ступень дороже базовой в 6,7 раза. Есть у части моделей.
- **`batch`** — скидка batch-режима (например `{discount: 0.5, window: "24h"}`).

Фактически списанную сумму всегда возвращает `usage.cost_rub` в ответе модели.

### 3.3 Баланс

**`GET https://api.aitunnel.ru/v1/aitunnel/balance`** — «текущий баланс аккаунта в рублях. Если у API-ключа задан бюджет, в ответе ещё остаток бюджета».

```json
{"balance": 4999.55, "budget": 850.0}
```

| Поле | Тип | Смысл по доке |
|---|---|---|
| `balance` | **number** | баланс аккаунта, ₽ |
| `budget` | **number**, опционально | остаток бюджета API-ключа, которым сделан запрос, ₽. **Поля нет**, если бюджет не задан и если запрос сделан ключом управления |

Поведение и нюансы (всё из доки):
- Цифры **актуальные**: учитывают все списания на момент вызова, включая запрос, который завершился только что. Задержки и кэша нет.
- Это **то же значение**, что `usage.balance` в ответе модели. То есть баланс приходит бесплатно с каждым ответом — отдельный запрос нужен только для «idle»-экрана.
- **При балансе 0 ₽ и ниже метод продолжает работать** — в отличие от запросов к моделям, которые получат `402`.
- Запрос, стоимость которого ещё уточняется у провайдера (`X-AITunnel-Cost-Pending: true`), попадёт в баланс после расчёта.
- Это не метод OpenAI: в SDK его нет, только HTTP.

Отказ при недостатке: `402` с типами `insufficient_balance` / `budget_exceeded` и `metadata.required_rub`. Если баланса хватает не на весь ответ — `X-AITunnel-Output-Capped`, `finish_reason: "length"`. Параллельные запросы делят баланс; баланс может ненадолго уйти в минус.

**`GET /v1/aitunnel/balance/history`** — всё, что меняло баланс, кроме расходов на запросы (ключ управления, `billing:read`): фильтры `type` (`topup|refund|referral|reward|promo|adjustment`), `status`, `method`, `from`/`to`, `timezone`, `payment_id`, `q`, `limit`, `cursor`.

**`GET /v1/aitunnel/key`** — бюджет целиком (лимит, сброс, «осталось сейчас»), статус ключа.

### 3.4 Медиагенерация

| Путь | Режим | Что делает |
|---|---|---|
| `POST /v1/images/generations` | **sync** | text-to-image; картинка в том же HTTP-ответе, без опроса. Таймаут провайдера — 9 минут, при `504` деньги **не списываются** |
| `POST /v1/images/edits` | **sync** | редактирование/композиция по референсам (до 25 МБ на файл) |
| `POST /v1/videos` | **async** | задача → `{id, polling_url, status:"pending"}` |
| `GET /v1/videos/{id}` | опрос | каждые 15–30 с до терминального статуса |
| `GET /v1/videos/{id}/content` | скачивание | MP4; доступно только в статусе `completed` |

Задачу провайдер доводит до конца сам: если клиент перестал опрашивать, резерв всё равно рассчитается, разница вернётся на баланс, готовое видео можно забрать позже по `id`.

### 3.5 Прочее, что даёт данные для карточки

- `usage` в каждом ответе: `cost`, `cost_rub` («одно число в рублях»), `balance`, а также объёмы (`video_seconds`, `speech_characters`, `transcription_seconds`, `embedding_inputs`, `rerank_documents`).
- `GET /v1/aitunnel/usage` — расход за период: `cost_rub` (из него `tool_cost_rub`, `log_cost_rub`), запросы, токены; `period` (`today|yesterday|7d|30d|90d|month|last_month`) или `from`/`to`, `group_by` (`day,hour,model,endpoint,user`), фильтры `model`, `endpoint`, `app`, `user`, `session_id`, `include=latency`.
- `GET /v1/aitunnel/requests` (+ `/filters`, `/export`, `/{id}`) — журнал запросов: `cost_rub`, `provider`, `duration_ms`, `ttft_ms`, `finish_reason`.
- `fallback` (`models[]` в запросе) — запасные модели; `provider` (order/only/ignore/sort/data_collection) — выбор провайдера; `presets` — пресеты вместо модели.
- Кэш промптов: `cache_control`, 5 минут или 1 час; скидка видна как `cache_discount`.

## 4. Чего в API нет

| Чего нет | Подтверждение |
|---|---|
| Цены и контекста в `/v1/models` | дока прямо: «Цен и возможностей нет» |
| Размера параметров | поля нет ни в `chat`, ни в `images`, ни в `videos` |
| Arena-рейтинга | поля нет |
| Флага «умеет tools» в каталоге | tool-calling описан отдельной страницей `/docs/tool-calling`, но поля в каталоге нет |
| Отдельного прайс-эндпоинта | прайс только в публичном каталоге |
| Абсолютной цены кэша | есть только множитель `cache_discount` |
| Баланса без ключа | `GET /v1/aitunnel/balance` без ключа → 401; проверить «401 vs 404» **нельзя**: AITUNNEL проверяет ключ раньше маршрута (401 и на выдуманном пути) |

## 5. Что не удалось проверить

- Ответ `/v1/models`, `/v1/aitunnel/balance`, `/v1/aitunnel/usage` с ключом — ключа AITUNNEL на comp нет; форма взята из доки (для баланса дока даёт полный JSON-пример).
- `usage.balance` в живом ответе модели — платные генерации запрещены, не запускались.

## 6. Первоисточники

- Индекс: https://aitunnel.ru/llms.txt; база и зеркало: https://aitunnel.ru/docs.md
- Список моделей (публичный каталог, поля, единицы, `price_tiers`, `/v1/models`): https://aitunnel.ru/docs/models.md
- **Баланс**: https://aitunnel.ru/docs/api/balance.md
- История баланса: https://aitunnel.ru/docs/api/balance-history.md
- Расход: https://aitunnel.ru/docs/api/usage.md; запросы: https://aitunnel.ru/docs/api/request.md
- Ключи и схемы авторизации: https://aitunnel.ru/docs/keys.md; ключ управления: https://aitunnel.ru/docs/api/management-keys
- Баланс и оплата (402, `X-AITunnel-Output-Capped`, `X-AITunnel-Cost-Pending`): https://aitunnel.ru/docs/payments.md
- Картинки (sync): https://aitunnel.ru/docs/images.md; видео (async): https://aitunnel.ru/docs/videos.md
- Живые пробы 2026-11-22: `/public/aitunnel/models` → 200, 617 011 Б, chat 202 / images 50 / videos 35; `/v1/models` → 401; `/v1/aitunnel/balance` → 401.
- Код: `data/Service.kt` (`AiTunnel`), `network/Requests.kt` (только `/v1/models`), `network/ProviderBalanceClient.kt` (AITUNNEL не поддержан).
