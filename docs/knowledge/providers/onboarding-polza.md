---
okf_version: "0.2"
providers: [polza]
kind: onboarding
last_verified: 2026-11-23
sources:
  - https://polza.ai/docs/gaidy/getting-started.md   # регистрация, ключ, пополнение (официальный гайд)
  - https://polza.ai/dashboard/api-keys
  - https://polza.ai/dashboard/billing
probes:
  - "GET https://polza.ai/dashboard → 302 /login?redirect=/dashboard"
  - "GET https://polza.ai/dashboard/api-keys → 302 /login (путь существует)"
  - "GET https://polza.ai/api/v1/models без ключа → 200 (каталог публичный)"
---

# Polza.ai — как начать (онбординг)

Сервис в приложении: **Polza.ai** (`polza`).

## 1. Регистрация

1. Откройте **https://polza.ai/dashboard** и создайте аккаунт.
2. После регистрации создайте **организацию** (нужно указать название) — без неё кабинет работает не полностью.

## 2. API-ключ

1. Раздел: **https://polza.ai/dashboard/api-keys**.
2. «Создать ключ» → введите имя (например, «Pots»).
3. Скопируйте ключ сразу: он **отображается только один раз**.
4. Вставьте в Pots: Настройки → Сервисы → Polza.ai → «API-ключ».

Опционально: `GET https://polza.ai/api/v1/key` показывает лимит и расход именно этого ключа.

## 3. Пополнение баланса

Раздел: **https://polza.ai/dashboard/billing**. Модель pay-as-you-go.

| Способ | Минимум | Зачисление |
|---|---|---|
| Банковская карта (Visa, Mastercard, МИР) | **100 ₽** | мгновенно |
| СБП (QR-код в приложении банка) | — | мгновенно |
| Расчётный счёт организации (юрлица) | — | 1–2 рабочих дня после поступления |

Полезно: в «Настройках» включите уведомление о низком балансе и задайте порог (например, 500 ₽).

## 4. Что подставить в приложении

- Base URL: `https://polza.ai/api/v1` (чат: `POST https://polza.ai/api/v1/chat/completions`).
- Каталог моделей публичный: `GET https://polza.ai/api/v1/models` — ключ для него не нужен.

## Проверено / не проверено

Проверено 2026-11-23: `/dashboard`, `/dashboard/api-keys`, `/dashboard/billing` — редирект на `/login` (пути существуют); `/api/v1/models` без ключа → 200. Текст шагов, способы оплаты и минимумы — из официального гайда Polza «Начало работы».
