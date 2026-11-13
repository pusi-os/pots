# Медиа-студия — референсы расширения (аудио, рисование, монтаж)

Материалы подготовки третьего режима медиа-студии (генерация аудио),
скопированы из `~/wiki/design/` 2026-11-13 (карточка `t_f1891f4c`, доска `pots`).

| Файл | Что это |
|---|---|
| `pots-media-ux-spec.md` | UX/UI-спек расширения (@joni, карточка `t_5b9da93f`): режимы, чипы, плеер, редактор, ошибки |
| `pots-audio-gen-research.md` | Справка по аудио-моделям PlusVibe (@pifiya, карточка `t_647099fc`): состав шита, цены, единицы, оси |
| `pots-audio-catalog-dump.json` | Дамп `GET https://plusvibeapi.ru/api/media-catalog` от 2026-11-13: 57 моделей `kind=audio\|music`, поле `class` добавлено исследованием (в живом API его нет; нативное поле — `task`) |
| `icons/` | 27 векторных иконок 24×24 (`fill="currentColor"`, Material Filled) + README с таблицей назначений |

Контракт провайдера: `docs/knowledge/media-providers/plusvibe.md`.
