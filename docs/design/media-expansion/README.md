# Медиа-студия — референсы расширения (аудио, рисование, монтаж)

Материалы подготовки третьего режима медиа-студии (генерация аудио),
скопированы из `~/wiki/design/` 2026-11-13 (карточка `t_f1891f4c`, доска `pots`).
Блок качества изображений и параметров видео добавлен 2026-11-13
(карточки `t_52b5bf84`, `t_4741bec6`, `t_da67ac0d`).

| Файл | Что это |
|---|---|
| `pots-media-ux-spec.md` | UX/UI-спек расширения (@joni): режимы, чипы, плеер, редактор, ошибки; §13–§17 аудио-флоу; §19 селектор качества изображений и параметры видео (v4) |
| `pots-audio-gen-research.md` | Справка по аудио-моделям PlusVibe (@pifiya, карточка `t_647099fc`): состав шита, цены, единицы, оси |
| `pots-audio-catalog-dump.json` | Дамп `GET https://plusvibeapi.ru/api/media-catalog` от 2026-11-13: 57 моделей `kind=audio\|music`, поле `class` добавлено исследованием (в живом API его нет; нативное поле — `task`) |
| `pots-quality-params-research.md` | Справка по качеству изображений и параметрам видео (@pifiya, карточка `t_4741bec6`): ось `quality`, price-table, тумблеры звука, разрешение в оси `quality` |
| `pots-image-quality-price-tables.json` | Живые `GET /api/media/price-table` 8 моделей изображений от 2026-11-13 (строки `opts`/`price`/`priceStatus`/`chargeBasis`) — фикстура тестов цен |
| `pots-image-video-catalog-dump.json` | Срез того же дампа каталога: 20 моделей `kind=image\|video` с осями качества/звука/разрешения (+ негативные кейсы) — фикстура тестов тулбара |
| `icons/` | 27 векторных иконок 24×24 (`fill="currentColor"`, Material Filled) + README с таблицей назначений |

Контракт провайдера: `docs/knowledge/media-providers/plusvibe.md`.
