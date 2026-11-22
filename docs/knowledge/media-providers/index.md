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
* [Refresh playbook](refresh-playbook.md) — end-to-end procedure for adding a new provider or refreshing an existing one.

## History

* [Update log](log.md)
