---
okf_version: "0.2"
providers: [plusvibe-reserve]
last_verified: 2026-11-22
docs: [https://plusvibeapi.ru/docs/, https://plusvibeapi.ru/api/openapi]
base_urls: [https://gw.plusvibeapi.ru/v1]
---

# PlusVibeAPI (резерв, `gw.plusvibeapi.ru`) — API-контракт для приложения

Это **тот же API**, что [`plusvibe.md`](plusvibe.md), на другом хосте. Отдельный сервис в приложении, а не fallback. Полный разбор эндпоинтов и полей — в `plusvibe.md`; здесь только то, что отличается.

Дока: «Если plusvibeapi.ru работает медленно или недоступен, используйте `https://gw.plusvibeapi.ru/v1` (где адрес указывается без `/v1` — `https://gw.plusvibeapi.ru`): **те же ключи, баланс и модели**».

## 1. Что нужно приложению

| Что нужно приложению | Есть? | Где именно | Поле / единица |
|---|---|---|---|
| Список моделей | ✅ | `GET /v1/models` (ключ) | как у основного: `data[]` = `id` + `object` |
| Цена входа | ✅ | `GET /v1/pricing` (ключ) / `GET /api/catalog` (без ключа) | `inputRub`, ₽/1M |
| Цена выхода | ✅ | там же | `outputRub`, ₽/1M |
| Цена кэша | ✅ | там же | `cacheReadRub` / `cacheWriteRub`, ₽/1M |
| Контекст | ✅ | `/api/catalog` | `caps.contextWindow`, токены |
| Дата релиза | ⚠️ | `/api/catalog` | `caps.releaseDate`, заполнено не у всех |
| Размер параметров | ❌ | — | поля нет |
| Рейтинг (arena) | ❌ | — | поля нет |
| tools | ✅ | `/api/catalog` | `caps.toolCalling` |
| vision / модальности | ✅ | `/api/catalog` | `caps.vision`, `modalitiesIn/Out` |
| **Баланс** | ✅ | `GET /v1/balance` (ключ) | `balance_rub` — number, ₽; `currency` — `"RUB"` |
| Медиагенерация | ✅ | sync `/v1/images/generations`; async `/api/media/generate` + `/api/media/jobs/{id}` | как у основного |

## 2. Аутентификация

| Параметр | Значение |
|---|---|
| Схема | `Authorization: Bearer sk-pv-…` (тот же ключ, что у основного хоста; отдельного ключа для `gw` нет) |
| `/v1/models`, `/v1/pricing`, `/v1/balance` | ключ нужен; проверено: **401** `{"error":{"message":"Отсутствует или неверный API-ключ.","type":"authentication_error","code":401,"cause":"missing_key",…}}` — тело идентично основному хосту |
| `/api/catalog` | без ключа: **200**, но медленно (см. §3) |

## 3. Отличия от основного хоста

| Что | Основной `plusvibeapi.ru` | Резерв `gw.plusvibeapi.ru` |
|---|---|---|
| Роль | основной base | резервный base, «те же ключи, баланс и модели» |
| `/api/catalog` | 200, ~518–527 КБ за один быстрый запрос | 200, но **медленно**: за 25 с пришло ~23 КБ из ~518 КБ (запрос оборван таймаутом). Для прогрева списка моделей не годится |
| Отдельная схема в OpenAPI | да (единственный опубликованный контракт) | **нет** — провайдер отдельной схемы для `gw` не публикует |
| В приложении | `Service.PlusVibe` | `Service.PlusVibeReserve` — отдельный сервис, не fallback |

## 4. Баланс

`GET https://gw.plusvibeapi.ru/v1/balance` → та же схема `BalanceResponse`, что у основного: `{balance_rub: number, currency: "RUB"}`. Дока прямо говорит, что баланс на `gw` тот же.

Приложение это уже использует: `network/ProviderBalanceClient.kt` → `BalanceSpec("https://gw.plusvibeapi.ru/v1/balance", "balance_rub", "RUB")`.

**Поведение при нуле:** как у основного — `402 Payment Required` от платных методов; отдельного описания «при нулевом балансе» в доке нет.

## 5. Чего в API нет

Всё то же, что у основного (`/v1/models` без цен и контекста, нет размера параметров и arena-рейтинга). Плюс специфичное:

| Чего нет | Подтверждение |
|---|---|
| Отдельного контракта/схемы для `gw` | дока объявляет эквивалентность текстом, отдельной OpenAPI-схемы нет |
| Быстрого публичного каталога на `gw` | `/api/catalog` тянется ~23 КБ за 25 с |
| Автоматического переключения хоста в приложении | в `Requests.kt` fallback-логики нет; список моделей и выбранная модель хранятся per-instance |

## 6. Что не удалось проверить

- Ответы `/v1/models`, `/v1/pricing`, `/v1/balance` на `gw` с ключом — ключа нет.
- Полную загрузку `/api/catalog` с `gw`: за 25 с пришло 23 КБ из ~518 КБ, запрос оборван таймаутом (статус 200).

## 7. Первоисточники

- Резервный base и его эквивалентность («те же ключи, баланс и модели»): https://plusvibeapi.ru/docs/
- Контракт `/v1/models`, `/v1/pricing`, `/v1/balance` (`BalanceResponse`): https://plusvibeapi.ru/api/openapi
- Живые пробы 2026-11-22: `gw…/v1/models` → 401 `missing_key`; `gw…/v1/balance` → 401; `gw…/api/catalog` → 200, медленно.
- Код: `data/Service.kt` (`PlusVibeReserve`), `network/ProviderBalanceClient.kt:46`.
