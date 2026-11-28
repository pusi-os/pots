---
okf_version: "0.2"
providers: [gptunnel]
kind: onboarding
last_verified: 2026-11-23
sources:
  - https://docs.gptunnel.ru/                     # документация API
  - https://docs.gptunnel.ru/getting-started/pricing/
  - https://www.gptunnel.ru/ru/prices             # публичная страница цен и способов оплаты
  - https://gptunnel.ru/profile/api               # страница ключей в личном кабинете
probes:
  - "GET https://gptunnel.ru/register → 200; GET https://gptunnel.ru/login → 200"
  - "GET https://gptunnel.ru/profile/api → 200; /profile → 200"
  - "GET https://gptunnel.ru/v1/models без ключа → 401 Unauthorized"
---

# GPTunnel — как начать (онбординг)

Сервис в приложении: **GPTunnel** (`gptunnel`).

## 1. Регистрация

1. Откройте **https://gptunnel.ru/register** (или https://gptunnel.ru/login → «Регистрация»).
2. Подтвердите e-mail и войдите в личный кабинет.

## 2. API-ключ

1. Личный кабинет → раздел **API**: **https://gptunnel.ru/profile/api**.
2. Создайте ключ и скопируйте его целиком.
3. Вставьте в Pots: Настройки → Сервисы → GPTunnel → «API-ключ».

**Важно:** GPTunnel ждёт ключ в заголовке **без префикса `Bearer`** — `Authorization: <ключ>`.
В приложении это уже учтено (`ApiKeyAuthStyle.RAW`), но при ручной проверке через curl легко ошибиться.

Проверка ключа: `GET https://gptunnel.ru/v1/balance` → `{"orgId": null, "userId": "…", "object": "balance", "balance": <₽>, "creditLimit": 0}`.

## 3. Пополнение баланса

Пополнение — в личном кабинете; модель «платишь за использование», **минимальное пополнение 50 ₽**, подписок нет.

| Способ | Детали |
|---|---|
| СБП | без комиссии, зачисление сразу |
| Карты любых банков | МИР, Visa, Mastercard и другие |
| Счёт на оплату | для юрлиц: счёт и закрывающие документы |

Система лояльности: скидка 5 % — от 500 ₽ трат за 30 дней, 8 % — от 2 000 ₽, 12 % — от 6 000 ₽, 17 % — от 12 000 ₽, 22 % — от 30 000 ₽, 25 % — от 60 000 ₽. Цены на сайте могут отличаться от персональных — свой прайс виден в кабинете.

## 4. Что подставить в приложении

- Base URL: `https://gptunnel.ru/v1`.
- Каталог моделей: `GET https://gptunnel.ru/v1/models` (ключ обязателен; цены — в полях `cost_context` / `cost_completion` того же ответа).
- Баланс: `GET https://gptunnel.ru/v1/balance`; для баланса организации — `?useWalletBalance=true`.

## Проверено / не проверено

Проверено 2026-11-23: `/register`, `/login`, `/profile/api` отвечают 200; `/v1/models` без ключа → 401. Способы оплаты, минимум 50 ₽ и уровни лояльности — с публичной страницы https://www.gptunnel.ru/ru/prices. Схема ключа (`Authorization` без `Bearer`) — из доки GPTunnel (`api/models`, `api/chat-completions`) и из кода приложения. Единица `cost_context` / `cost_completion` в доке не указана (см. `gptunnel.md`, §3.2) — на витрине цену из этого поля показывать нельзя без проверки.
