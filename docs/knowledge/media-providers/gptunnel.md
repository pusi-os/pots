---
okf_version: "0.2"
providers: [gptunnel]
last_verified: 2026-11-23
base_urls: [https://gptunnel.ru/api/v2/media, https://gptunnel.ru/v1]
---

# GPTunnel — медиа-контракт (snapshot 2026-11-23)

Источник правды: **официальная документация CreativeLab API** — `https://docs.gptunnel.ru/creative-lab/` (обзор, базовый URL, статусы), `/creative-lab/models/` (каталог + схема `params`/`inputs`), `/creative-lab/price/` (котировка), `/creative-lab/create-task/` (создание задачи), `/creative-lab/get-task/` (получение задачи), `/creative-lab/webhooks/` (подпись и ретраи), `/creative-lab/errors/` (коды), плюс `/getting-started/balance/`, `/getting-started/deprecated-models/` и разбор v2 в блоге `https://www.gptunnel.ru/ru/blog/creative-lab-api-v2`.

**Ничего платного не запускалось.** Генерация и котировка требуют заголовка `Authorization` — ключа GPTunnel на comp нет. Живыми `curl` проверено только то, что отдаётся без ключа: коды ответа (существование маршрута, требование авторизации) и `GET https://gptunnel.ru/llms.txt`. Тела запросов/ответов ниже — из документации, ссылки на конкретные разделы даны по ходу текста.

## Base URLs и авторизация

| Роль | Значение |
|---|---|
| Base (медиа) | `https://gptunnel.ru/api/v2/media` — все пути ниже от него: `/models`, `/price`, `/tasks`, `/tasks/:id` |
| Base (текст, не медиа) | `https://gptunnel.ru/v1` — `/v1/models`, `/v1/chat/completions`, `/v1/balance` |
| Auth | `Authorization: <API_KEY>`, **с префиксом `Bearer` или без него** — «Авторизация — заголовком Authorization (с префиксом Bearer или без него)» (docs.gptunnel.ru/creative-lab/) |
| `ApiKeyAuthStyle` для `Service.Gptunnel` | `RAW` (уже так в `data/Service.kt`) — совпадает с документированной схемой; `BEARER` тоже принимается |
| Ключ для каталога `/api/v2/media/models` | **нужен**: живой `GET` без ключа → `401 {"error":{"message":"Unauthorized","type":"unauthorized_request_error",...}}`. Каталог отдаёт «модели, доступные вашему API-ключу», то есть зависит от ключа, а не публичный |
| Ключ для `/price`, `/tasks`, `/tasks/:id` | нужен: все три без ключа → `401` |
| Ключ для `/v1/models` (LLM) | нужен: живой `401` |

Живые коды без ключа (2026-11-23, `curl`, без ключа — списаний быть не может):

| Запрос | Ответ | Что доказывает |
|---|---|---|
| `GET /api/v2/media/models` | `401` | маршрут есть, каталог не публичный |
| `GET /api/v2/media/models?type=IMAGE` | `401` | фильтр не делает каталог публичным |
| `POST /api/v2/media/price` (пустое тело) | `401` | маршрут котировки есть |
| `POST /api/v2/media/tasks` (пустое тело) | `401` | маршрут создания задачи есть |
| `GET /api/v2/media/tasks/6a61e021a6e77553a989c41d` | `401` | маршрут получения задачи есть |
| `GET /api/v2/media/price` | `404 {"code":99,"message":"API method not found, check the URL."}` | метод сверяется до авторизации; `99` = «метод не найден» |
| `POST /api/v2/zzz-nonexistent` | `404` (HTML `Cannot POST /api/v2/zzz-nonexistent`) | контроль: на несуществующем пути `401` не приходит, значит `401` выше = маршрут реально зарегистрирован |

## Каталог медиа-моделей (ключ обязателен)

`GET https://gptunnel.ru/api/v2/media/models?type=IMAGE|VIDEO|AUDIO`

«Возвращает модели, доступные вашему API-ключу (с учётом списка разрешённых моделей, если он задан для ключа), вместе со схемой параметров (`params`), схемой входных файлов (`inputs`) и ценой. **Эта схема — источник истины**: используйте её, чтобы понять, какие `params` и `inputs` принимает конкретная модель» (docs.gptunnel.ru/creative-lab/models/).

| Query | Тип | Смысл |
|---|---|---|
| `type` | string | фильтр по типу модели — «например `IMAGE`, `VIDEO`, `AUDIO`». Перечень в доке не закрыт («например»), поэтому считать его неполным |

Ответ: `{"code": 0, "models": [ … ]}`. Поля модели:

| Поле | Тип | Смысл (дословно по доке) |
|---|---|---|
| `id`* | string | идентификатор модели — передаётся в поле `model` при создании задачи |
| `type` | string | тип модели (`IMAGE`, `VIDEO`, `AUDIO`) |
| `price`* | number | **цена в рублях с учётом действующих скидок** — за то, что названо в `price_unit`. `per_generation` — цена одной генерации; `per_second`/`per_megapixel`/`per_1k_tokens` — **ставка**, которую умножат на длительность/площадь/токены. «В любом случае это не итог: точную сумму за конкретную комбинацию параметров считает `/media/price`» |
| `price_unit` | string | `per_generation` \| `per_second` \| `per_megapixel` \| `per_1k_tokens`. Поля нет — цены за выход у модели нет, счёт идёт по входу (`price_input`) |
| `price_input` | number | входная ставка в рублях с учётом скидок — за символы/токены промпта, мегапиксели приложенных изображений |
| `price_input_unit` | string | `per_character` \| `per_1k_characters` \| `per_token` \| `per_1k_tokens` \| `per_megapixel` |
| `discount_percent` | number | процент скидки, уже учтённый в `price` и `price_input` для той же комбинации параметров |
| `pricing` | object | «тарифное правило модели: как цена собирается из параметров. Всегда база ДО скидок. Есть не у всех моделей» — **структура объекта в доке не описана** |
| `prompt`* | string | требование к промпту: `required` \| `optional` \| `none` |
| `prompt_optional_when` | object | условие, при котором промпт можно опустить (у Suno — включённые одновременно `is_custom` и `instrumental`) |
| `params`* | array | схема настраиваемых параметров генерации (см. ниже) |
| `inputs` | array | схема входных файлов: роль, форматы, число файлов, ограничения |
| `deprecated` | boolean | дата снятия уже наступила — запросы выполняются на аналоге либо возвращают `410` |
| `deprecated_at` | string | дата снятия провайдером, ISO 8601; дата в будущем — анонс |
| `deprecation_redirect_to` | string \| null | id модели-аналога; `null` — аналога нет, после даты создание вернёт `410` |
| `moderation` | string | встречается в примере ответа (`"auto"`), **в таблице полей не описано — не подтверждено** |
| `code` | string | встречается в примере ответа (дубль `id`), **в таблице полей не описано — не подтверждено** |

Схема `params[]` (из примера и таблицы полей): `key`, `type` (`enum` \| `number` \| …), `label`, `default`, `options[{value,label}]` (для `enum`), `min`, `max`, `step`, `integer`, `unit`. Важная оговорка доки: «в `params` публикуется только то, что клиент вправе задать сам. У модели могут быть параметры с фиксированным значением — их видно в ответе задачи и расчёта цены, но изменить нельзя».

Схема `inputs[]`: `key`, `kind` (`image` \| `video` \| `audio`), `role` (например `first_frame`), `required`, `count{min,max}`, `formats[]` (например `["jpg","png","webp"]`).

## Расчёт цены без списания

`POST https://gptunnel.ru/api/v2/media/price`

«Возвращает стоимость генерации по телу `{ model, prompt, params, inputs }` **без запуска задачи и без списания средств**. Считается тем же способом, что и фактическое списание при создании задачи» (docs.gptunnel.ru/creative-lab/price/).

| Поле | Тип | Обяз. | Смысл |
|---|---|---|---|
| `model`* | string | да | id модели |
| `prompt` | string | нет | текст будущей генерации. У озвучки **обязателен** (цена зависит от длины), иначе `400`. У моделей со счётом по токенам промпта (`gpt-image`) не обязателен, но входит в цену: без него сумма выйдет ниже фактического списания |
| `params` | object | нет | параметры генерации, влияющие на цену (длительность, разрешение…) |
| `inputs` | object | нет | входные файлы по ролям — «файлы отсюда не скачиваются и на доступность не проверяются», нужны только для надбавки за референсы |

Ответ:

```json
{ "code": 0, "model": "kling-v3", "price": 210, "currency": "RUB",
  "params": { "resolution": "1080p", "duration": 5, "aspect_ratio": "16:9" } }
```

`params` в ответе — **применённые** значения (присланное + дефолты + фиксированные параметры модели); «если присланного параметра в ответе нет, он не настраивается». `currency` — `"RUB"`. Оговорка доки: модели, которые тарифицируются ещё и по промпту/входным файлам (токены, мегапиксели), возвращают цену **без этой части**.

**Это единственный серверный источник точной цены у GPTunnel** — и он же лучшая валидация тела перед боевым вызовом: «валидация та же, но без денег».

## Создание задачи

`POST https://gptunnel.ru/api/v2/media/tasks` — «единая точка создания задачи для всех медиа-моделей» (docs.gptunnel.ru/creative-lab/create-task/).

| Поле | Тип | Обяз. | Смысл |
|---|---|---|---|
| `model`* | string | да | id модели из `GET /api/v2/media/models` |
| `prompt` | string | по модели | требование задаёт поле `prompt` схемы модели: `required` \| `optional` \| `none` |
| `params` | object | нет | параметры по схеме `params` выбранной модели |
| `inputs` | object | нет | входные файлы **по ролям**: `{ role: [url, ...] }` |
| `webhook_url` | string | нет | публичный HTTPS-адрес; после `done`/`failed` туда уйдёт объект задачи |
| `idempotency_key` | string | нет | повтор с тем же ключом вернёт уже созданную задачу, а не создаст новую |
| `wait` | boolean | нет, `false` | `true` — держать соединение до готовности и вернуть финальный объект |

### `idempotency_key`

* Повтор с тем же ключом → **та же задача**, без повторного списания.
* Тот же ключ с **другим телом** → `409` с `code: 23` («сервер сравнивает хеш запроса, а не только ключ»).
* Ключ должен быть привязан к бизнес-операции (id заказа/сообщения/строки очереди), а не генерироваться заново на каждую попытку.
* **TTL ключа в доке не указан** — наша абстракция обещает 24 ч, у GPTunnel это не подтверждено.

### `wait: true`

«Запрос будет держать соединение и вернёт задачу, как только она завершится (`done` или `failed`). Ожидание ограничено **примерно 30 секундами**: если за это время задача не завершилась, вернётся объект в текущем статусе (`queued` / `running`), и результат нужно получить обычным способом — опросом `GET /media/tasks/:id` или через webhook». Для видео на `wait` полагаться бессмысленно.

**Важно:** «задача живёт ~30 с» из спецификации — это про таймаут `wait`, а не про срок жизни задачи. Сама задача живёт до `done`/`failed`; ограничение 30 с — только на удержание HTTP-соединения.

### Строгость валидации

* Верхний уровень тела валидируется **строго**: неизвестное поле → `400` с `code: 3`, имя поля в `details`. «Это ровно тот случай, когда `idempotencyKey` вместо `idempotency_key` молча снимал защиту от двойного списания» (blog, creative-lab-api-v2).
* Внутри `params` правило мягче: неизвестный модели ключ не роняет запрос, а возвращается в `ignored_params` с причиной. Недопустимое значение принимаемого параметра (`duration: 999` при пределе 10) → `400`, а не тихая подстановка.

### Входные файлы

`inputs` — объект вида `{ "<role>": ["<url>", ...] }`, роли берутся из `inputs[].role` схемы модели (например `first_frame`). Ссылки должны быть публично доступны серверу GPTunnel; поддерживается инлайн — `data:`-URL **до 20 МБ на файл** и **50 МБ на всё тело**. «Битый или нечитаемый файл отклоняется сразу, с `code: 25`, до создания задачи и списания».

### Подмена снятой модели

Если у запрошенной модели заполнен `deprecation_redirect_to`, после `deprecated_at` задача создаётся на аналоге; **деньги списываются по запрошенной модели**. Признаки подмены: заголовки `Deprecation: true`, `Sunset`, `x-model-requested`, `x-model-served` и массив `warnings` с элементом `code: "DEPRECATED_MODEL"`. В объекте задачи `model` остаётся запрошенной моделью. Аналога нет → создание вернёт `410` с `code: 21`.

### Пример ответа

```json
{ "code": 0, "id": "6a61e021a6e77553a989c41d", "model": "kling-v3",
  "status": "running", "prompt": "a red fox running through snow, cinematic",
  "params": { "resolution": "1080p", "duration": 5, "aspect_ratio": "16:9" },
  "price": 210, "result": [],
  "webhook": { "url": "https://your-service.example/webhooks/gptunnel", "status": "pending" },
  "created_at": 1784799265 }
```

`id` — 24-значный идентификатор задачи.

## Получение задачи (poll)

`GET https://gptunnel.ru/api/v2/media/tasks/:id` — «опрашивайте этот метод, пока статус не станет `done` или `failed`» (docs.gptunnel.ru/creative-lab/get-task/).

| Поле | Тип | Смысл |
|---|---|---|
| `id`* | string | идентификатор задачи |
| `model` | string | **запрошенная** модель (при подмене аналогом здесь остаётся то, что прислали; кто выполнил — заголовок `x-model-served`) |
| `status`* | string | `queued` \| `running` \| `done` \| `failed` |
| `params` | object | применённые параметры генерации |
| `price` | number | **списанная стоимость в рублях** |
| `result`* | array | массив результатов при `done`, иначе пустой; у каждого элемента есть `url`. Модели с несколькими выходами возвращают несколько элементов «в порядке готовности»; музыкальные дополняют элемент `id`, `cover_url`, `duration`, `title`, `lyric`, `tags` |
| `error` | object | при `failed`: `code` — стабильный код для ветвления, `title` — точный машиночитаемый код причины (может быть детальнее `code`), `message` — человекочитаемое описание |
| `refunded` | boolean | был ли возврат средств за неуспешную задачу |
| `created_at` | number | время создания (Unix, секунды) |

Ответ при `failed` (документированный пример):

```json
{ "code": 0, "id": "6a61e021a6e77553a989c41d", "model": "kling-v3", "status": "failed",
  "error": { "code": 1001, "title": "SENSITIVE_CONTENT", "message": "Sensitive content has been detected…" },
  "refunded": true, "created_at": 1784799265 }
```

Обратите внимание: `code` в корне равен `0` даже при `status: "failed"` — успешность HTTP-запроса и успешность генерации это разные вещи, ветвиться надо по `status` и `error.code`.

**Поля `currency` в объекте задачи нет.** `currency: "RUB"` документирован только в ответе `POST /media/price`; в задаче `price` описан словами «списанная стоимость в рублях». Это расхождение со спецификацией карты — здесь написан факт из доки.

**Рекомендованного интервала опроса дока не даёт** — брать общий совет абстракции (3–5 с с backoff до 30 с). `progress`, `cancel`, `advice`, `definitive` в контракте отсутствуют.

## Webhooks

`webhook_url` при создании → после `done`/`failed` на адрес уходит `POST` с **тем же объектом задачи**, что отдаёт `GET /media/tasks/:id` (docs.gptunnel.ru/creative-lab/webhooks/).

| Заголовок | Значение |
|---|---|
| `X-Gptunnel-Event` | `task.done` или `task.failed` |
| `X-Gptunnel-Delivery` | идентификатор задачи |
| `X-Gptunnel-Signature` | `t=<unix>,v1=<hex>` — HMAC-SHA256 от строки `"<t>.<сырое тело>"` с **API-ключом в роли секрета** |
| `User-Agent` | `gptunnel-webhook/2` |

Доставка «хотя бы один раз»: при неудаче повтор примерно через **5, 20, 60 и 180 с**, обработчик должен быть идемпотентен по id задачи. Эндпоинт обязан отвечать `2xx`. Адрес — публичный HTTPS.

## Статусы, коды ошибок, лимиты

При успехе HTTP `200` и `code: 0`; при ошибке — осмысленный HTTP-статус и машиночитаемый `code` в теле (docs.gptunnel.ru/creative-lab/errors/):

```json
{ "code": 5, "message": "Insufficient balance, check account balance.", "trace_id": "662f1a9c4b1e2d0012a3b4c5" }
```

| Code | HTTP | Значение | Повторять |
|---|---|---|---|
| 0 | 200 | успех | — |
| 1 | 500 | internal server error (`trace_id`) | да |
| 3 | 400 | invalid input, check schema issues (`details`) | нет |
| 5 | 402 | insufficient balance | нет |
| 6 | 429 | API key limit exceeded (дневной/месячный лимит ключа) | нет |
| 7 | 403 | модель недоступна аккаунту (banned/restricted) | нет |
| 20 | 404 | model not found / недоступна через LAB API | нет |
| 21 | 410 | модель снята, аналога нет | нет |
| 22 | 404 | task not found (только задачи, созданные через LAB API v2) | нет |
| 23 | 409 | задача с этим `idempotency_key` уже есть с другим телом | нет |
| 24 | 404 | модель недоступна **этому API-ключу** | нет |
| 25 | 400 | входной файл отклонён моделью (размер, формат, разрешение, соотношение) | нет |
| 26 | 400 | промпт отклонён моделью (длина, содержание) | нет |
| 27 | 400 | комбинация параметров не поддерживается моделью | нет |
| 28 | 503 | «generation failed for a reason we could not attribute» — повторить тот же запрос позже | **да, единственный код, который стоит повторять автоматически** |
| 29 | 403 | модель не разрешена этому ключу | нет |
| 30 | 429 | too many generation requests | да, после окна |
| 31 | 429 | слишком много одновременно работающих задач | да, после завершения текущих |
| 99 | 404 | API method not found, check the URL | нет |
| 1001 | 400 | sensitive content detected | нет |
| 1002 | 400 | request blocked by moderation | нет |

Неверный/отсутствующий ключ → `401` с **другим** форматом тела: `{"error": {"message": "Unauthorized"}}` (не `code`/`message`).

## Цены: единицы и как считать

* Каталог: `price` + `price_unit` — **ставка**, а не итог. `per_generation` — цена одной генерации; `per_second` — ₽/с результата; `per_megapixel` — ₽/Мп; `per_1k_tokens` — ₽/1000 токенов ответа.
* `price_input` + `price_input_unit` — отдельная строка счёта за присланное (символы/токены промпта, мегапиксели вложений). У моделей со счётом по входу `price` приходит `0` и `price_unit` отсутствует.
* Итог за конкретную комбинацию — только `POST /media/price` (или `price` в готовой задаче). «Цена считается той же функцией, что и фактическое списание, — „посчитали одно, списали другое“ тут невозможно».
* Валюта — **RUB** («Оплата в рублях, без VPN и подписок»). `currency: "RUB"` приходит в ответе `/price`; в объекте задачи валюта не полем, а словами.
* `discount_percent` — уже учтённая скидка; отдельная лояльность (Plus…Diamond 5–25 %) описана в `llms.txt` как зависящая от трат за 30 дней.

## Баланс

`GET https://gptunnel.ru/v1/balance` (docs.gptunnel.ru/getting-started/balance/) — читает баланс счёта, к которому привязан ключ.

| Поле | Смысл |
|---|---|
| `orgId` | `null` для личного счёта; иначе id организации |
| `userId` | id владельца ключа |
| `object` | всегда `"balance"` |
| `balance` | остаток в рублях, округлён до копеек, **может быть отрицательным** |
| `creditLimit` | кредитный лимит в рублях; для личного счёта всегда `0` |

`?useWalletBalance=true` — баланс личного счёта владельца ключа. Бонусы и кредитный лимит в `balance` не входят. Живой вызов без ключа → `401` (совпадает с `ProviderBalanceClient`, который уже читает `balance` как число в `RUB`).

## Таблица: метод нашей абстракции → чем закрывается → чего нет

| Метод `MediaProvider` | Чем закрывается у GPTunnel | Чего нет / что писать самим |
|---|---|---|
| **`listModels(apiKey)`** | `GET /api/v2/media/models` (ключ обязателен; `?type=IMAGE` / `VIDEO` / `AUDIO`). Один ответ уже содержит почти всё для витрины: `id`→`MediaModel.id`, `type`→`MediaKind`, `params[]`→`MediaParamDef` (key/label/type/values/default/min/max/step/unit), `inputs[]`→`acceptsImageReference`/`acceptsVideoReference`/`acceptsAudioReference` + роли и лимиты, `price`+`price_unit`→`fromRub`+`unit`, `price_input`+`price_input_unit`, `discount_percent`, `prompt`→обязательность, `deprecated`/`deprecation_redirect_to`→`generationAvailable` | **`promptLimit` нет** — дока публикует только `required`/`optional`/`none`, длину промпта не даёт (узнаётся только отказом `code: 26`). **Названия/описания модели нет** — поля `title`/`description` в схеме не документированы (label есть только у параметров). `priceStatus`: каталог отдаёт ставку, а не итог → `estimate`, `exact` только после `/price`. `fromRub` для `per_second`/`per_megapixel` — **ставка, не цена задачи**; для карточки «от N ₽» надо умножать на дефолтную длительность/разрешение. Тип `AUDIO` покрывает и музыку, и озвучку — разделять по `params`/`inputs`, отдельного признака нет |
| **`priceTable(apiKey, modelId)`** | Отдельного эндпоинта таблицы цен **нет**. Всё, что есть, лежит в каталоге: `price`/`price_unit`, `price_input`/`price_input_unit`, `discount_percent`, `pricing` (правило сборки цены) и `params[]` (оси, по которым цена меняется). Таблицу строить локально из карточки модели | **Структура `pricing` в доке не описана** — матрицу «комбинация → цена» из неё не собрать гарантированно. Полного перечня тиров, как `pricing.tiers` у Polza, нет. Точная цена конкретной комбинации — только `POST /price` (это `quotePrice`, а не таблица). Кэшировать каталог: один запрос на все модели |
| **`quotePrice(apiKey, request)`** | `POST /api/v2/media/price` `{model, prompt?, params?, inputs?}` → `{code, model, price, currency:"RUB", params}`. **Ничего не запускает и не списывает**, считается той же функцией, что и списание. Возвращает применённые параметры — бесплатная валидация тела | **Нет `quoteId` и `expiresAt`** → `MediaPriceQuote.quoteId = null`, `expiresAt = null`. Цена не «заморожена»: между котировкой и созданием задачи она может измениться (скидки/тариф), повторной сверки нет. У моделей с оплатой по промпту/входам цена **без этой части**, если не передать `prompt`/`inputs` — передавать те же значения, что уйдут в задачу |
| **`submitJob(apiKey, request)`** | `POST /api/v2/media/tasks` `{model, prompt?, params?, inputs?, webhook_url?, idempotency_key?, wait?}`. Ответ — **один** объект задачи (`id`, `status`, `price`, `result`, …) для всех моделей. `idempotency_key` поддерживается нативно: повтор с тем же ключом возвращает ту же задачу без второго списания, тот же ключ с другим телом → `409`/`code: 23`. `wait: true` — короткое ожидание до ~30 с и финальный объект в ответе | **TTL `idempotency_key` не указан** (абстракция обещает 24 ч — не подтверждено). **Нет `maxPriceRub`/потолка цены** в теле: ограничить стоимость запросом нельзя, только сверить `price` до вызова и не отправлять. Верхний уровень тела строгий: лишнее поле → `400 code: 3` (опечатка в `idempotency_key` = отказ, а не тихая потеря защиты — но и не «молча ок»). `inputs` — только `{role: [url|data-URL]}`, роли берутся из `inputs[]` схемы; поведение при неизвестной роли в доке не описано — рассчитывать на отказ (`code: 3`/`25`), а не на молчаливый игнор |
| **`pollJob(apiKey, jobId)`** | `GET /api/v2/media/tasks/:id` → `status: queued\|running\|done\|failed`, `result[].url` (+ `title`/`duration`/`cover_url` у музыки), `price` (списанная сумма в ₽), `error{code,title,message}`, `refunded`, `params`, `created_at` | **Нет `progress`** → `MediaJobState.progress = null`. **Нет `cancel`**. Нет `advice`/`definitive` — маппить самим по `error.code`: `28`→`retry_later`, `25/26/27/1001/1002`→`fix_input`, `5`→пополнить баланс, `23`→`fix_input` (другое тело под тем же ключом), `30/31`→`retry_later`, `21/24/29`→`definitive`. `status: "failed"` приходит с `code: 0` в корне — ветвиться по `status`/`error.code`, не по корневому `code`. Задачи из v1 этим методом не видны (`code: 22`) |
| **`downloadResult(apiKey, url)`** | `result[].url` на `cdn.gptunnel.com`. **Ссылки живут 48 часов** — «если результат нужен позже, скачайте файл на свой ресурс» (aside в `/creative-lab/` и `/creative-lab/get-task/`) | Публичность CDN-ссылки **без ключа не документирована и не проверена** (нужна платная генерация). 48 ч — жёсткий срок: качать сразу после `done`, иначе результат потерян безвозвратно. Инлайн-выхода (`b64_json`) в контракте нет — только URL |

## Минимальный путь

**Картинка (sync через `wait`, без поллинга):**
1. `GET /api/v2/media/models?type=IMAGE` с ключом → `id` + `params` + `inputs` + `price`/`price_unit`. Витрина и форма строятся из этого ответа.
2. `POST /api/v2/media/price` с тем же `{model, prompt, params}` → точная цена в ₽ **до** генерации, без списания. Здесь же бесплатно проверяется корректность тела.
3. `POST /api/v2/media/tasks` с `{model, prompt, params, idempotency_key}` и `wait: true` → либо готовый объект (`status: "done"`, `result[0].url`, `price`), либо `queued`/`running` через ~30 с — тогда `GET /api/v2/media/tasks/:id` до `done`.
4. Скачать `result[0].url` в течение 48 часов.

**Видео:** тот же шаг 3 без `wait` (для видео он бесполезен) → `GET /api/v2/media/tasks/:id` с паузой 3–5 с (backoff до 30 с) до `done`/`failed`. При `failed` смотреть `error.code`; `refunded: true` — деньги вернулись, повтор осмыслен только для `28`/`30`/`31`.

## Что в спецификации карты сверилось, а что нет

| Утверждение из карты | Статус по докам |
|---|---|
| Каталог `GET /api/v2/media/models?type=IMAGE\|VIDEO\|AUDIO` | ✅ подтверждено (`/creative-lab/models/`), но **требует ключа** (живой `401`), публичным не является |
| Котировка без списания `POST /api/v2/media/price` | ✅ подтверждено (`/creative-lab/price/`), живой `401` без ключа подтверждает маршрут |
| Async `POST /api/v2/media/tasks`, поля `webhook_url`, `idempotency_key` | ✅ подтверждено (`/creative-lab/create-task/`) |
| «Задача живёт ~30 с» | ⚠️ **неточно**: ~30 с — это предел `wait: true`, а не срок жизни задачи |
| Статусы `queued/running/done/failed` | ✅ подтверждено (`/creative-lab/`) |
| `done` → `result[].url`, `price` | ✅ подтверждено (`/creative-lab/get-task/`) |
| `failed` → `error.code` | ✅ подтверждено; плюс `error.title` (точнее кода) и `error.message` |
| Поле `refunded` | ✅ подтверждено (`/creative-lab/get-task/`) |
| `currency: "RUB"` | ⚠️ **только в ответе `/price`**. В объекте задачи поля `currency` нет — там `price` словами «списанная стоимость в рублях» |

## Не проверено (нужен ключ / платная генерация)

* Живой каталог `/api/v2/media/models` — не запрашивался (без ключа `401`). Число моделей, реальные `type`, состав `params`/`inputs`, наличие `pricing` — из доки, не из ответа.
* Живой `POST /api/v2/media/price` — не запускался (нужен ключ). Формат ответа — из примера доки.
* Живая генерация и опрос — не запускались (запрет владельца на платные вызовы).
* Публичность `result[].url` на `cdn.gptunnel.com` без ключа — не проверена.
* TTL `idempotency_key` — не указан.
* Структура объекта `pricing` в каталоге — не описана.
* Поля `moderation` и `code` у модели — есть в примере ответа, но не в таблице полей.
* Полный список значений `type` — дока даёт «например IMAGE, VIDEO, AUDIO»; где живут музыка/озвучка (Suno, Mureka, tts2) — по типам не разложено (у озвучки отдельный каталог голосов `/creative-lab/voices/`).
* Интервал опроса и лимиты RPM/RPD — не опубликованы (только коды `6`, `30`, `31`).

## Sources

* https://docs.gptunnel.ru/creative-lab/ — обзор, базовый URL `https://gptunnel.ru/api/v2/media`, схема `Authorization` с `Bearer`/без, статусы задачи, срок жизни ссылок 48 ч
* https://docs.gptunnel.ru/creative-lab/models/ — `GET /api/v2/media/models`, `?type`, поля модели, схемы `params`/`inputs`
* https://docs.gptunnel.ru/creative-lab/price/ — `POST /api/v2/media/price`, тело, ответ с `currency: "RUB"`, оговорка про токенные модели
* https://docs.gptunnel.ru/creative-lab/create-task/ — `POST /api/v2/media/tasks`, `wait`, `idempotency_key`, `inputs`, `data:`-URL 20/50 МБ, подмена снятой модели
* https://docs.gptunnel.ru/creative-lab/get-task/ — `GET /api/v2/media/tasks/:id`, поля объекта задачи, `refunded`, `error.title`
* https://docs.gptunnel.ru/creative-lab/webhooks/ — заголовки, HMAC-SHA256, ретраи 5/20/60/180 с
* https://docs.gptunnel.ru/creative-lab/errors/ — таблица кодов, формат `{code, message, trace_id}`, `401` в формате `{error:{message}}`
* https://docs.gptunnel.ru/getting-started/balance/ — `GET /v1/balance`, поля и знак баланса
* https://docs.gptunnel.ru/getting-started/deprecated-models/ — `deprecated_at`, `deprecation_redirect_to`, `410`/`code: 21`, заголовки подмены
* https://docs.gptunnel.ru/getting-started/pricing/ — «цены через API не отличаются от цен в интерфейсе», спецтарифы
* https://www.gptunnel.ru/ru/blog/creative-lab-api-v2 — разбор v2: строгая валидация верхнего уровня, `ignored_params`, `409`/`code: 23`, 48 ч на ссылки
* https://gptunnel.ru/llms.txt — общий обзор платформы, валюта и модель оплаты (RUB, pay-as-you-go, лояльность)
* Живые пробы без ключа (2026-11-23): `GET /api/v2/media/models`, `?type=IMAGE`, `GET /v1/models`, `GET /v1/balance`, `POST /api/v2/media/price`, `POST /api/v2/media/tasks`, `GET /api/v2/media/tasks/:id` → `401`; `GET /api/v2/media/price` → `404 code 99`; `POST /api/v2/zzz-nonexistent` → HTML `404`
