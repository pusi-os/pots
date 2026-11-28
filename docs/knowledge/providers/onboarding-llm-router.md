---
okf_version: "0.2"
providers: [llm-router]
kind: onboarding
last_verified: 2026-11-23
sources:
  - https://llm-router.org/ru            # лендинг: три шага, оплата в рублях и по СБП
  - https://llm-router.org/ru/pricing    # прайс: 17 моделей, $/1M, «ключ из личного кабинета»
  - https://llm-router.org/ru/docs       # документация: форматы API, ошибки 401/402
  - https://llm-router.org/login         # вход: Telegram или e-mail/пароль, «Sign up»
probes:
  - "GET https://llm-router.org/signup → 200"
  - "GET https://llm-router.org/login → 200 (вход по Telegram-QR или e-mail, ссылка Sign up)"
  - "GET https://llm-router.org/v1/models без ключа → 401 Invalid API key"
  - "GET https://llm-router.org/v1/pricing без ключа → 401; публичный HTML-прайс /ru/pricing → 200"
---

# LLM Router — как начать (онбординг)

Сервис в приложении: **LLM Router** (`llm-router`).

## 1. Регистрация

1. Откройте **https://llm-router.org/signup** (или https://llm-router.org/login → «Sign up»).
2. Два пути входа: **через Telegram** (открыть бота, нажать «Start» — вкладка обновится сама) или **e-mail + пароль**.
3. Отдельного подтверждения по почте в интерфейсе не требуется.

## 2. API-ключ

1. Ключ доступен **сразу в личном кабинете** после регистрации; формат — `llmr_…`.
2. Вставьте в Pots: Настройки → Сервисы → LLM Router → «API-ключ».

Проверка ключа: `GET https://llm-router.org/v1/models` с заголовком `Authorization: Bearer llmr_…` — вернёт список доступных id. Без ключа тот же путь отвечает `401 Invalid API key`.

## 3. Пополнение баланса

Модель — предоплаченный баланс, один на все модели; подписки и **минимального платежа нет**.

| Что | Детали |
|---|---|
| Валюта | рубли; баланс пополняется в кабинете |
| Способы | карта и **СБП** (заявлено на лендинге) |
| Списание | по фактически израсходованным токенам: вход, выход, а также reasoning- и кэш-токены, если модель их отдаёт |
| Когда деньги кончились | `402 Payment Required` — «Insufficient balance. Top up your account in the cabinet» |

Цены на модели — в долларах США за 1 млн токенов (публичный прайс: https://llm-router.org/ru/pricing, 17 моделей).

## 4. Что подставить в приложении

- Base URL: `https://llm-router.org/v1` (есть также Anthropic-формат `/v1/messages` и Gemini-формат).
- Заголовок: `Authorization: Bearer llmr_…`.

## Проверено / не проверено

Проверено 2026-11-23: `/signup` → 200, `/login` → 200 с двумя способами входа; `/v1/models` и `/v1/pricing` без ключа → 401; публичный прайс `/ru/pricing` → 200. Названия пунктов меню кабинета провайдер публично не описывает (кабинет за логином), поэтому путь до ключа сформулирован как «личный кабинет» без выдуманных подпунктов. Формат ответа `GET /v1/balance` и `GET /v1/pricing` не документирован — см. `llm-router.md`.
