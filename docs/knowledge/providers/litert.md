---
okf_version: "0.2"
providers: [litert]
last_verified: 2026-11-22
docs: [https://github.com/google-ai-edge/LiteRT-LM]
base_urls: []
---

# LiteRT / локальная модель (`litert`) — API-контракт для приложения

**Сетевого API у этого сервиса нет вообще.** Нет `chatUrl`, нет `modelsUrl`, нет ключа (`isOnDevice = true`). Инференс идёт в процессе приложения, список моделей — локальный каталог `inference/LocalModelCatalog.kt`, файлы скачиваются с HuggingFace (`litert-community/*`) на пин-коммит.

Поэтому «документация провайдера» здесь — это LiteRT-LM (формат `.litertlm`, требования к рантайму) и карточки моделей на HuggingFace; а источник данных для карточки модели — сам каталог приложения.

## 1. Что нужно приложению

| Что нужно приложению | Есть? | Где именно | Поле / единица |
|---|---|---|---|
| Список моделей | ✅ | локальный каталог `MODEL_CATALOG` (8 записей) + `getDownloadedModels()` | `id`, `displayName`, `sizeBytes` |
| Цена входа | ✅ по смыслу | локальный инференс | **0 ₽** (платить некому); в API/каталоге поля нет |
| Цена выхода | ✅ по смыслу | — | 0 ₽ |
| Цена кэша | **не применимо** | — | кэша промптов нет |
| Контекст | ✅ | `defaultContextTokens` / `maxContextTokens` | токены (4 096 / до 65 536) |
| Дата релиза | ❌ | — | поля нет |
| Размер параметров | ⚠️ | только из `displayName` («Gemma 4 E2B») | полем не хранится; `sizeBytes` — вес файла, не параметры |
| Рейтинг (arena) | ❌ | — | поля нет |
| tools | ❌ | — | локальные модели tool-calling не заявляют |
| vision / модальности | ✅ | `supportsImages` | bool; выставлен у **4 из 8** (gemma-4-e2b/e4b/12b, lfm3-omni-2b-int8) |
| **Баланс** | **не применимо** | — | нет аккаунта, нет предоплаты, нет сети. Показывать вместо баланса — «Локально · 0 ₽» (прецедент: `LOCAL_TTS_BADGE` в `LocalTts.kt`) |
| Медиагенерация | ❌ | — | LiteRT-LM в приложении используется только для текста/картинки на вход; генерации медиа нет |

## 2. Аутентификация

| Параметр | Значение |
|---|---|
| `ApiKeyAuthStyle` | не применяется (запросов нет) |
| `requiresApiKey` | `false` |
| Ключ для списка моделей | не нужен: список = `localInferenceEngine.getDownloadedModels()` |

## 3. Каталог моделей

Список строится из локального каталога и того, что реально скачано (`data/RemoteDataRepository.kt`): `downloaded.map { SettingsModel(id = it.id, subtitle = "${it.displayName} (${formatFileSize(it.sizeBytes)})", …) }`.

Каталог на 2026-11-22 (`MODEL_CATALOG`, 8 записей):

| id | Размер | Контекст (default / max) | `supportsImages` | Примечание |
|---|---|---|---|---|
| `gemma-4-e2b-it` | 2,59 ГБ | 4 096 / 32 768 | ✅ | `isRecommended = true` |
| `gemma-4-e4b-it` | 3,66 ГБ | 4 096 / 32 768 | ✅ | |
| `gemma-4-12b-it` | 6,88 ГБ | 4 096 / 32 768 | ✅ | |
| `lfm2.5-1.2b-instruct` | 736 МБ | 4 096 / 4 096 | ❌ | |
| `qwen3-0.6b` | 614 МБ | 4 096 / 32 768 | ❌ | |
| `embeddinggemma-2-text-vision-440m` | 388 МБ | 0 / 0 | ❌ | `isEmbedding = true`; в имени «text+vision», но флаг `supportsImages` **не выставлен** |
| `lfm3-omni-2b-int8` | 3,23 ГБ | 4 096 / 32 768 | ✅ | экспериментальная, читает текст/картинку/аудио |
| `lfm3-1.7b-int4` | 889 МБ | 4 096 / 65 536 | ❌ | экспериментальная |

Поля каталога, которые **не** попадают в `SettingsModel`: `defaultContextTokens`, `maxContextTokens`, `gpuMemoryMb`, `kvPerTokenBytes`, `supportsImages`, `isRecommended`, `isEmbedding`, `sha256`, `downloadUrl`.

## 4. Чего в API нет

| Чего нет | Подтверждение |
|---|---|
| Любого сетевого эндпоинта | у сервиса не заданы `chatUrl`/`modelsUrl`; инференс in-process |
| Баланса, цены, кэша | нет аккаунта и нет сети |
| Даты релиза, размера параметров, arena-рейтинга | полей в каталоге нет |
| Медиагенерации | LiteRT-LM в приложении не используется для генерации |

## 5. Что стоит поправить в приложении (наблюдения, не правки)

1. **Контекст известен, но не показан**: `maxContextTokens` лежит в каталоге, а `SettingsModel.contextWindow` заполняется только из ответа API или `ModelCatalog` — для локальных моделей API нет.
2. **`supportsImages` у сервиса = `true` жёстко**, хотя у 4 из 8 моделей vision нет — UI не предупредит, что картинку такая модель не примет.
3. Размер модели виден только строкой `subtitle` («Gemma 4 E2B IT (2.4 GB)»); в сортировку и фильтры он не попадает.
4. Нет бейджа «локально · 0 ₽» в пикере моделей, хотя в медиа-разделе прецедент есть.
5. Эмбеддер (`embeddinggemma-…`) в списке выглядит как обычная чат-модель: фильтра «это эмбеддер» нет, хотя `isEmbedding = true` в каталоге есть.

## 6. Первоисточники

- Каталог, пины, дайджесты: `composeApp/src/commonMain/kotlin/com/pusios/pots/inference/LocalModelCatalog.kt`; политика пинов и сверка дайджестов — `docs/knowledge/litert/` (`index.md`, `models.md`, `pin-policy.md`), проверено 2026-11-22.
- LiteRT-LM (формат `.litertlm`, требования к рантайму): https://github.com/google-ai-edge/LiteRT-LM
- Модели: `https://huggingface.co/litert-community/*` (пин на неизменяемый коммит в `downloadUrl` + `sha256` файла).
- Код: `data/Service.kt` (`LiteRT`), `data/RemoteDataRepository.kt` (источник списка в UI).
