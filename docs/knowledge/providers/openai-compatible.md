---
okf_version: "0.2"
providers: [openai-compatible]
last_verified: 2026-11-22
docs: ["https://platform.openai.com/docs/api-reference/models/list", "https://docs.ollama.com/api"]
base_urls: ["<задаётся пользователем>", "http://localhost:11434/v1"]
---

# OpenAI-Compatible API — API-контракт для приложения

Единственный сервис, где **base URL вводит пользователь**, а ключ необязателен. «Провайдер» здесь — не компания, а любой OpenAI-совместимый сервер: Ollama, vLLM, LiteLLM, LM Studio.

Поэтому у этого документа два первоисточника: спецификация OpenAI (`GET /v1/models`) и API сервера, на который смотрит пользователь (эталон — Ollama).

## 1. Что нужно приложению

| Что нужно приложению | Есть? | Где именно | Поле / единица |
|---|---|---|---|
| Список моделей | ✅ | `GET <base>/models` | `{object:"list", data:[{id, object, created, owned_by}]}`; проверено на Ollama 0.31.2 |
| Цена входа | ❌ | — | в OpenAI-формате поля нет; у локального сервера цена по смыслу 0 ₽, но это не выводится из API |
| Цена выхода | ❌ | — | то же |
| Цена кэша | ❌ | — | поля нет |
| Контекст | ❌ | в `/models` — нет; **есть в нативном API Ollama** | `GET /api/tags` → `details.context_length` (у `lfm2.5-32k` = 128000); `POST /api/show` → `<family>.context_length` |
| Дата релиза | ⚠️ | `data[].created` | unix; у локального сервера это дата появления модели в сервере (`ollama pull`), **не** релиз модели |
| Размер параметров | ❌ | в `/models` — нет; **есть в нативном API Ollama** | `/api/tags` → `details.parameter_size` («2.7B»), `details.quantization_level` («Q4_K_M») |
| Рейтинг (arena) | ❌ | — | поля нет |
| tools | ❌ | — | в `/models` флага нет; поддержка зависит от модели и задаётся в запросе |
| vision / модальности | ❌ | — | в `/models` поля нет; у Ollama частично видно через `capabilities[]` в `/api/tags` |
| **Баланс** | **не применимо** | — | у пользовательского/локального сервера нет аккаунта и предоплаченного баланса. Единственный источник «денег» здесь — сам пользователь; в API такого понятия нет |
| Медиагенерация | ❌ | — | в OpenAI-формате `/models` медиа-моделей нет; `/v1/images/generations` приложение не зовёт |

## 2. Аутентификация

| Параметр | Значение |
|---|---|
| Схема | `Authorization: Bearer <ключ>` |
| `requiresApiKey` | `false`, `supportsOptionalApiKey = true` |
| Пустой ключ | заголовок `Authorization` **не отправляется вовсе** (комментарий в коде: пустой заголовок читается частью шлюзов как битые креды → 401) |
| Где берётся ключ | у пользователя или локального сервера; поле в UI необязательное |

Нормализация base URL (`Service.normalizeOpenAICompatibleBaseUrl`): trim, срезать хвостовой `/`, срезать хвост **только** `/chat/completions`. Поэтому вставленный полный `…/v1/models` **не** срезается → получится `…/v1/models/models` (404). Стоит срезать и `/models`, и `/responses` — они объявлены у сервиса.

## 3. Эндпоинты, которые нас касаются

### 3.1 Список моделей

`GET <base>/models` → стандартная обёртка. Проверено живьём на локальном **Ollama 0.31.2** (2026-11-22):

```json
{"object":"list","data":[{"id":"gemma3:1b","object":"model","created":1783970196,"owned_by":"library"}, …]}
```

Из OpenAI-совместимого `/models` приходят **только** `id`, `object`, `created`, `owned_by` — это и есть спецификация OpenAI (`GET /v1/models`).

### 3.2 Что есть сверх OpenAI-формата (нативно у Ollama, без ключа)

`GET /api/tags` — на модель: `name`, `model`, `modified_at`, `size` (байты), `digest`, `details.parent_model`, `details.format`, `details.family`, `details.families`, `details.parameter_size`, `details.quantization_level`, `details.context_length`, `details.embedding_length`, `capabilities[]`. Проверено на том же инстансе.

`POST /api/show` — `general.parameter_count`, `<family>.context_length`.

**Приложение эти два эндпоинта не использует** — а это единственный способ показать контекст и размер параметров для локальных моделей.

## 4. Баланс: почему «не применимо»

| Вопрос | Ответ |
|---|---|
| Есть ли эндпоинт баланса? | Нет и не может быть: сервер — пользовательский (Ollama на `localhost`), аккаунта и предоплаты нет |
| Что показывать вместо баланса | «Локально · 0 ₽» — прецедент в приложении уже есть (`LocalTts.kt`: `LOCAL_TTS_BADGE`) |
| Может ли быть баланс у платного OpenAI-совместимого шлюза? | Да, но тогда это отдельный провайдер со своим эндпоинтом; универсального пути `GET /balance` в OpenAI-спецификации нет |

## 5. Чего в API нет

| Чего нет | Подтверждение |
|---|---|
| Цены, кэша, контекста, размера параметров, модальностей в `/models` | спецификация OpenAI: только `id`, `object`, `created`, `owned_by`; подтверждено живым ответом Ollama |
| Баланса | понятия нет в OpenAI-спецификации |
| Голого массива `[…]` в ответе | приложение такой ответ не разбирает (`modelsResponseIsArray = false` у всех восьми сервисов) — сервер, отдающий массив без обёртки, для этого сервиса нерабочий |
| Медиа-эндпоинтов в списке моделей | `/models` не различает модальности |

## 6. Что не удалось проверить

- Поведение с серверами, отдающими `pricing`/`context_length` в OpenRouter-стиле (vLLM с кастомными плагинами, LiteLLM) — на comp такого сервера нет. Код такие поля читает (`pricePerMillion()`, `context_length`), но живой ответ не снят.

## 7. Первоисточники

- OpenAI, `GET /v1/models` (набор полей): https://platform.openai.com/docs/api-reference/models/list
- Ollama API (`/v1/models`, `/api/tags`, `/api/show`): https://docs.ollama.com/api — сверено живым прогоном на **Ollama 0.31.2**, 2026-11-22 (`/api/version` → `{"version":"0.31.2"}`).
- Живые пробы 2026-11-22: `GET http://localhost:11434/v1/models` → 200, поля `id`/`object`/`created`/`owned_by`; `GET /api/tags` → `details.parameter_size`, `details.context_length`, `capabilities[]`.
- Код: `data/Service.kt` (`OpenAICompatible`, `normalizeOpenAICompatibleBaseUrl`), `network/Requests.kt` (`getOpenAICompatibleModels`).
