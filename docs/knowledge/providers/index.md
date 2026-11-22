---
okf_version: "0.2"
last_verified: 2026-11-22
providers: [polza, gptunnel, aitunnel, plusvibe, plusvibe-reserve, llm-router, openai-compatible, litert]
---

# Провайдеры инференса — API-контракты

OKF v0.2 bundle по **восьми сервисам** из `Service.all` (`data/Service.kt`). Документы **переписаны по официальной документации провайдеров** (`docs`, `llms.txt`, OpenAPI, `.md`-версии страниц) — с живыми пробами `curl` там, где ключ не нужен.

Предыдущая версия bundle (карта `t_175e3a12`) была собрана в основном пробами эндпоинтов и **не открывала доки** — из-за этого в ней не было эндпоинтов баланса, хотя они есть у всех четырёх платных сервисов. Это исправлено.

## Сводная таблица

| Сервис | id в коде | Список моделей | Цена (где / единица) | **Баланс (путь / поле)** | Медиагенерация | Документ |
|---|---|---|---|---|---|---|
| Polza.ai | `polza` | `GET /api/v1/models`, **без ключа**, 394 модели | в каталоге, `top_provider.pricing.*` — **₽/1M**, `currency:"RUB"` | ✅ `GET /api/v2/balance` → `amount` / `available` / `reservedAmount` / `spentAmount` — **строки**, ₽ | `POST /api/v1/media` (async) + `GET /api/v1/media/{id}` | [polza.md](polza.md) |
| GPTunnel | `gptunnel` | `GET /v1/models`, ключ, 401 без него | в каталоге, `cost_context` / `cost_completion` — **единица не документирована** | ✅ `GET /v1/balance` → `balance` (number, ₽, может быть < 0) + `creditLimit`; отдельно `POST /api/v1/balance/get` (партнёрский) | `POST /api/v2/media/tasks` (async) + `GET …/tasks/:id`; котировка `POST /api/v2/media/price` | [gptunnel.md](gptunnel.md) |
| AITUNNEL | `aitunnel` | `GET /v1/models` (ключ) — минимум; `GET /public/aitunnel/models` — **без ключа**, 202 чат-модели | публичный каталог, `prompt_cost` / `completion_cost` — **₽/1M** | ✅ `GET /v1/aitunnel/balance` → `balance` (number, ₽) + `budget` (опц., ₽); **плюс `usage.balance` в каждом ответе модели** | картинки — sync `POST /v1/images/generations`; видео — async `POST /v1/videos` | [aitunnel.md](aitunnel.md) |
| PlusVibeAPI (эталон) | `plusvibe` | `GET /v1/models` (ключ) — только `id`+`object`; публично `GET /api/catalog`, 106 записей | `GET /v1/pricing` / `/api/catalog` — **₽/1M**, `currency:"RUB"` | ✅ `GET /v1/balance` → `balance_rub` (number, ₽) + `currency:"RUB"` — **уже используется приложением** | sync `/v1/images/generations`; async `/api/media/generate` + `/api/media/jobs/{id}` | [plusvibe.md](plusvibe.md) |
| PlusVibeAPI (резерв) | `plusvibe-reserve` | как у основного (`gw.plusvibeapi.ru/v1`) | как у основного | ✅ `GET /v1/balance` на `gw` → `balance_rub` + `currency` (дока: «те же ключи, баланс и модели») | как у основного | [plusvibe-reserve.md](plusvibe-reserve.md) |
| LLM Router | `llm-router` | `GET /v1/models` (ключ), **поля не документированы** | публичный HTML-прайс `/pricing` — **$/1M**, 17 моделей; `GET /v1/pricing` (ключ) **не документирован** | ⚠️ `GET /v1/balance` — **путь существует** (401 без ключа, 404 на выдуманном), но **формат не документирован**; приложение читает `balance_usd` (догадка кода) | sync `POST /v1/images/generations`, `POST /v1/images/edits` | [llm-router.md](llm-router.md) |
| OpenAI-Compatible API | `openai-compatible` | `GET <base>/models` — `id`, `object`, `created`, `owned_by` | нет (локально 0 ₽ по смыслу) | **не применимо** — сервер пользовательский (Ollama/vLLM), аккаунта и предоплаты нет; универсального `/balance` в OpenAI-спецификации не существует | нет | [openai-compatible.md](openai-compatible.md) |
| LiteRT / локальная модель | `litert` | локальный каталог, 8 записей, сети нет | 0 ₽ по смыслу | **не применимо** — in-process инференс, нет сети, нет аккаунта; вместо баланса уместен бейдж «Локально · 0 ₽» | нет | [litert.md](litert.md) |

### Баланс: итог по восьми

| Сервис | Баланс | Путь | Поле | Валюта | Документирован? |
|---|---|---|---|---|---|
| Polza | ✅ есть | `GET /api/v2/balance` | `amount` (**string**), лучше `available` | RUB | да (v2; `/v1/balance` помечен deprecated) |
| GPTunnel | ✅ есть | `GET /v1/balance` | `balance` (number) | RUB | да |
| AITUNNEL | ✅ есть | `GET /v1/aitunnel/balance` | `balance` (number), `budget` (опц.) | RUB | да (+ `usage.balance` в каждом ответе) |
| PlusVibe | ✅ есть | `GET /v1/balance` | `balance_rub` (number), `currency` | RUB | да (OpenAPI `BalanceResponse`) |
| PlusVibe (резерв) | ✅ есть | `GET /v1/balance` на `gw` | `balance_rub` (number), `currency` | RUB | да (эквивалентность объявлена докой) |
| LLM Router | ⚠️ путь есть, формат нет | `GET /v1/balance` | приложение ждёт `balance_usd` | USD (предп.) | **нет** |
| OpenAI-Compatible | — не применимо | — | — | — | — |
| LiteRT | — не применимо | — | — | — | — |

**Важно для UI:** у трёх сервисов сумма приходит **строкой** (Polza: `amount`, `available`, …), у трёх — **числом** (GPTunnel `balance`, AITUNNEL `balance`, PlusVibe `balance_rub`). Валюту нельзя считать одинаковой: Polza/GPTunnel/AITUNNEL/PlusVibe — рубли, LLM Router — доллары.

## Что читает приложение сейчас

| Что | Где в коде | Состояние |
|---|---|---|
| `/models` каждого сервиса | `network/Requests.kt:getOpenAICompatibleModels` | работает для всех восьми |
| Баланс PlusVibe, PlusVibe-Reserve, LLM Router | `network/ProviderBalanceClient.kt` | работает (LLM Router — с неподтверждённым полем) |
| Баланс Polza, AITUNNEL, GPTunnel | — | **не читается**, хотя эндпоинты документированы |
| Прайс LLM Router | `network/Requests.kt:getLlmRouterPricing` | **сломан**: запрос без ключа → всегда 401 |

## Какие ещё эндпоинты мы не используем (кроме баланса)

| Сервис | Эндпоинт | Что даёт | Почему стоит знать |
|---|---|---|---|
| **LLM Router** | `GET /v1/pricing` | прайс аккаунта | зовётся **без ключа** → всегда 401 → цены не появляются никогда; лечится одной строкой `applyApiKey` |
| **LLM Router** | `GET /v1/balance` | баланс | читается, но поле `balance_usd` нигде не документировано — риск тихого `null` |
| **Polza** | `GET /api/v1/models/catalog` | каталог с пагинацией, фильтрами по модальностям/контексту, сортировкой по цене | заменяет ручную фильтрацию в коде |
| **Polza** | `GET /api/v1/key` | лимит и расход API-ключа (`limit`, `usage_monthly`, …) | пер-ключевая аналитика |
| **Polza** | `GET /api/v1/history/generations` | история генераций | — |
| **AITUNNEL** | `GET /public/aitunnel/models` | цены, контекст, модальности, `price_tiers` — **без ключа** | самый дешёвый фикс «нет цены» из всех восьми |
| **AITUNNEL** | `GET /v1/aitunnel/usage`, `/requests`, `/balance/history`, `/aitunnel/key` | расход, журнал запросов, история баланса, бюджет ключа | — |
| **GPTunnel** | `GET /api/v2/media/models`, `POST /api/v2/media/price` | медиа-каталог со схемами параметров и **котировка цены без списания** | позволяет показать цену до генерации |
| **GPTunnel** | `POST /api/v1/balance/get` | баланс клиента (партнёрский) | другой смысл «баланса», не путать с `/v1/balance` |
| **PlusVibe** | `GET /v1/pricing` | цены вариантов маршрутов (₽/1M) | `/models` цен не содержит |
| **PlusVibe** | `GET /api/catalog` | 106 моделей: цены, `caps.toolCalling`, `caps.vision`, `releaseDate`, TTFT/TPS — **без ключа** | самый полный публичный каталог |
| **PlusVibe** | `GET /v1/model-capabilities` | машиночитаемые возможности и JSON-схемы медиа | — |
| **PlusVibe** | `GET /v1/generations` | история расхода | — |
| **OpenAI-Compatible** | `GET /api/tags`, `POST /api/show` (Ollama) | `context_length`, `parameter_size`, `quantization_level`, `capabilities[]` | единственный способ показать контекст локальных моделей |
| — (вне восьми) | `GET https://openrouter.ai/api/v1/auth/key`, `GET https://router.requesty.ai/v1/models` | только валидация ключа | функции в `Requests.kt` есть, но сервисов OpenRouter/Requesty в `Service.all` нет — мёртвый код |

## Поле есть в API, но единица не документирована

Список для UI: **не подписывать валюту там, где её никто не подтвердил.**

| Сервис | Поле | Что не документировано | Что делать |
|---|---|---|---|
| **GPTunnel** | `cost_context`, `cost_completion` (`/v1/models`) | **ни валюта, ни база** (за 1 токен / за 1K / за 1M). В доке поля есть только в примере ответа, таблицы полей нет. Вывести из примера `usage` в доке не получается — примеры между собой не согласованы (разбор в `gptunnel.md`, §5) | либо запросить у провайдера, либо не показывать цену GPTunnel вовсе |
| **GPTunnel** | `usage.prompt_cost` / `completion_cost` / `total_cost` | валюта не названа (по смыслу — рубли по тарифу, но слово «рубли» рядом не стоит; в отличие от `/v1/balance`, где прямо сказано «в рублях») | показывать как «стоимость», без символа |
| **GPTunnel** | `max_capacity` | смысл и единица: ни описания, ни «токены» в доке нет | не использовать как «лимит выхода» без подтверждения |
| **LLM Router** | `balance_usd` (`/v1/balance`) | **всё**: путь не документирован, имя поля взято из кода приложения | считать фичу экспериментальной; при `null` не показывать «0 $» |
| **LLM Router** | `prices[].usd` / `unit` (`/v1/pricing`) | формат ответа не документирован; единица `1m_tokens` — из DTO приложения | то же |
| **AITUNNEL** | `cache_discount` | это **множитель**, а не цена: абсолютной цены чтения кэша в каталоге нет | не подставлять в колонку «цена кэша» как число |
| **Polza** | `created` | это дата появления в каталоге, а не релиз модели | подпись «в каталоге с», не «релиз» |
| **Все** | `created` в `/models` | семантика у всех разная (каталог / сервер / загрузка) | не выдавать за дату релиза модели |

## Ссылки на официальную документацию

| Сервис | Доки |
|---|---|
| Polza | https://polza.ai/docs/llms.txt · OpenAPI https://polza.ai/api/openapi.json |
| GPTunnel | https://docs.gptunnel.ru/ |
| AITUNNEL | https://aitunnel.ru/llms.txt · https://aitunnel.ru/docs.md |
| PlusVibe | https://plusvibeapi.ru/docs/ · https://plusvibeapi.ru/api/openapi · https://plusvibeapi.ru/llms.txt |
| LLM Router | https://llm-router.org/docs/ · https://llm-router.org/pricing |
| OpenAI-Compatible | https://platform.openai.com/docs/api-reference/models/list · https://docs.ollama.com/api |
| LiteRT | https://github.com/google-ai-edge/LiteRT-LM · `docs/knowledge/litert/` |

## История

* [Update log](log.md)
