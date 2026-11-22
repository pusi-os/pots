---
okf_version: "0.2"
---

# Media provider knowledge

OKF v0.2 bundle for Pots’s **cloud media generation providers** (images, video, audio, music, 3D). Agents use this folder for provider contracts, pricing semantics, and freshness when adding or refreshing a provider adapter.

The app **never** reads this directory at runtime. All live contracts live in `composeApp/src/commonMain/kotlin/com/pusios/pots/media/`.

## Catalogs

* [PlusVibeAPI media](plusvibe.md) — async `/api/media/generate` HTTP contract, catalog, price quote, polling lifecycle, PlusVibe `x-pv-cost-rub` billing.
* [Polza.ai media](polza.md) — OpenAI-совместимые картинки `/v2/images/generations` (sync, 120 с), универсальный `/v1/media` (картинки с референсами + видео, `async`), каталог с per-model схемами и `pricing.tiers`, роутинг `provider.max_price`, нет ключа идемпотентности.
* [AITUNNEL media](aitunnel.md) — публичные каталоги `/public/aitunnel/models/{images,videos}`, синхронные картинки `/v1/images/generations` (до 9 мин, `b64_json`), асинхронное видео `/v1/videos` → `/v1/videos/{id}` → `/content`, резерв worst-case вместо котировки.
* [GPTunnel media](gptunnel.md) — CreativeLab API v2 (`/api/v2/media`, ключ обязателен): каталог `GET /models` со схемой `params`/`inputs`, точная котировка без списания `POST /price` (`currency: "RUB"`), единый `POST /tasks` для фото/видео/аудио с `idempotency_key`, `webhook_url` и `wait` ~30 с, опрос `GET /tasks/:id` (`queued/running/done/failed`, `refunded`), ссылки на результат живут 48 ч.
* [LLM Router media](llm-router.md) — только картинки и только синхронно: `POST /v1/images/generations` и `POST /v1/images/edits` (multipart с маской у `gpt-image-*`, JSON-композиция через `images:[{url}]`), ответ `b64_json`, заголовок `Idempotency-Key`; ни задач, ни поллинга, каталог и цены только по ключу (USD).
* [Refresh playbook](refresh-playbook.md) — end-to-end procedure for adding a new provider or refreshing an existing one.

## History

* [Update log](log.md)
