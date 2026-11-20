## v3.15.1 — 2026-11-20

### Улучшения
- **Пульс называет причину отказа.** Журнал больше не пишет безымянное `OpenAICompatibleConnectionException`: строка теперь читается как `Unreachable: connection refused (Polza.ai / gpt-4o)` — причина, собственные слова провайдера и модель, через которую шёл прогон. Семь причин различаются (`Unreachable`, `Timeout`, `Unauthorized`, `ModelNotFound`, `RateLimited`, `BadResponse`, `Unknown`), а не сливаются в одну.
- **Переключение на резервную модель видно.** Успешный прогон через резерв пишет `fell back to <модель> after <причина>` — и в журнале Пульса, и в логе запланированной задачи. Резерв, совпадающий с основной моделью, честно называется `reserve equals the primary instance`, а не «резерв не выбран».
- **Потсик как ассистент Android.** Приложение объявляет `VoiceInteractionService`, поэтому появляется в системном списке выбора ассистента. Жест ассистента открывает Потсика сразу в режиме прослушивания и приносит с собой текст экрана из `AssistStructure` — вопрос можно задать про то, что на экране, не уходя с этого экрана.
- **Облачко и реплики Потсика больше не обрезаются** границей окна поверх всех окон: окно считается под содержимое (руки в стороны и трёхстрочная реплика с учётом её «поп»-анимации), а капсула меню снова стоит в 8 dp от плеча персонажа.

### Исправления
- **Пульс не подменяет выбранную модель молча.** Если выбранный сервис исчез (провайдер удалён, инстанс пересоздан, настройки импортированы с другого устройства), прогон честно сообщает `configured instance not found: <id>` вместо отчёта по «первому попавшемуся» провайдеру. Пустой список сервисов — ошибка, а не успешный прогон, который ничего не сделал.
- **Повторы по классу ошибки, а не «всегда три раза».** Отклонённый ключ, отсутствующая модель и превышенная квота уходят на резерв сразу — повтор их не исправит. Недоступный эндпоинт, таймаут и лимит частоты повторяются в пределах 90 секунд на основной инстанс, так что зависший провайдер больше не съедает три полных таймаута.
- **Позиция Потсика сохраняется при обновлении.** Окно стало шире персонажа, и сохранённый левый край читается как край окна, а не персонажа — без этого питомец прыгнул бы вправо у всех, кто его двигал.
- **Жест ассистента не открывает вторую сессию распознавания.** Если диалог уже идёт, жест только прикладывает снимок экрана к следующему вопросу; если Потсик говорит, ответ обрывается и микрофон открывается заново — но синтез гасится до старта распознавателя, поэтому они по-прежнему никогда не работают одновременно.
- **Запланированные задачи используют ту модель, с которой созданы**, и уходят на резервную из настроек Пульса, когда та отказывает — раньше документация обещала «первый настроенный сервис».

## v3.15.0 — 2026-11-19

### Улучшения
- **Голосовой диалог из режима поверх окон**: микрофон в облачке над Потсиком теперь действительно включается. Раньше сервис объявлял тип `specialUse` без микрофона, и Android 14+ отказывал в доступе; теперь тип запрашивается с микрофоном, а при отказе системы (разрешение не выдано или сервис поднят из фона) персонаж не исчезает — окно остаётся, а голосовой флоу честно говорит «нет доступа к микрофону».
- **Повторный голосовой запрос работает**: распознаватель пересоздаётся на каждую сессию, а не переиспользуется. Раньше второй запрос подряд падал с `ERROR_RECOGNIZER_BUSY`, потому что `cancel()` асинхронный.
- **Текст можно ввести прямо в облачке** — поле ввода с кнопкой отправки, ответ приходит в ту же капсулу.
- **Лог установки агентов виден на экране**: установка OpenCode, Pi и Hermes стримит свой консольный вывод в панель (последние 64 КБ, по строкам, с вырезанными ANSI-последовательностями). Панель показывается и там, где установка реально падает — на экране проектов, а не только на экране настройки, который к тому моменту уже переключился. Раньше причина провала существовала только в logcat, где буфер на 256 КБ уже был затёрт системным шумом.
- **Пароль веб-панели копируется кнопкой и выделяется пальцем**, а OpenCode открывается уже авторизованным. Копия помечена как sensitive — система не показывает её в истории буфера и в превью вставки. Адрес в строке панели остался без учётных данных, чтобы пароль не попадал в скриншоты.
- **Плавность Потсика поверх окон**: окно держит 30 кадров в секунду вместо 4–8, дыхание больше не идёт ступеньками. Экономия осталась там, где она уместна, — на погашенном экране, где персонажа всё равно не видно. Перетаскивание больше не отстаёт от пальца: транзакции окна объединяются в одну на кадр.

### Исправления
- **Кнопка «Экран» снимает тот экран, где был пользователь**, а не приложение Pots. Экран согласия вынесен в собственную задачу Android, поэтому в кадр больше не попадает сам Pots.
- **Пустой чат после пропуска онбординга**: если сервис уже настроен, карточка «настройте модель» больше не показывается.
- **Набранный текст в облачке не стирается чужим ответом**: черновик очищается только ответом на тот вопрос, который был отправлен.
- **Поиск контактов не обходит приватность**: символы `%` и `_` в запросе экранируются, иначе запрос `%` выгружал первые контакты записной книжки. Признак «есть ещё» перестал быть всегда ложным.
- **Песочница: установка Hermes больше не падает молча** на `uv`-hardlink — полупостроенный runtime и uv-кэш удаляются перед повтором, а в лог перед всем остальным печатается проба `ln -f`/`os.link` с inode, чтобы следующий провал сразу говорил, работает ли `link()` в госте.
- **Типы foreground-сервиса вынесены в чистую функцию с тестами** — ошибка «откат повторно объявляет микрофон» стала структурно невозможной.

## v3.14.0 — 2026-11-19

### Улучшения
- **Потсик поверх окон стал рабочим**: туск «поверх окон» теперь применяется сразу при возврате из настроек, а не после перезапуска; персонажа можно тащить с поднятыми руками, размер меняется пинчем, окно остаётся на экране после поворота. На Android 12 и ниже режима нет — тумблер неактивен с честным текстом, без костылей.
- **Облачко на двойной тап по Потсику**: капсула с «Голос» и «Текст». Голосовой флоу слышит вас и показывает «Не расслышал, повторите» вместо тишины; текстовый открывает ввод. Если распознавание речи на устройстве недоступно, капсула честно говорит об этом, а не повторяет вопрос.
- **Кнопка «Экран» в облачке**: Потсик делает снимок экрана через MediaProjection и отвечает по нему. Согласие спрашивается один раз на режим, снимок живёт в памяти и никуда не пишется.
- **Потс умеет в контакты**: находит контакт по имени или номеру, создаёт новый, открывает набор с заполненным номером. Читает только результат запроса, а не всю записную книжку. Доступ выдаётся в Настройки → Контакты, без разрешения инструменты отвечают отказом с кнопкой в системные настройки.
- **Поручить задачу агенту песочницы прямо из чата**: новый инструмент `ask_sandbox_agent` отдаёт работу OpenCode, Pi или Hermes и возвращает ответ. Ключи в ответе вырезаются до обрезки, вывод и предупреждения не смешиваются.
- **OpenCode вернулся третьим агентом песочницы** — ставится с GitHub, получает провайдера, модель, ключ и эндпоинт из настроек Pots так же, как Pi и Hermes.
- **Веб-панели агентов в Pots Build**: «Запустить web UI» поднимает веб-интерфейс каждого агента (pi-web, Hermes webapp, `opencode serve`) и открывает его в браузере по умолчанию. Панель слушает только loopback телефона, пароль генерируется один раз и лежит в окружении, а не в командной строке. Занятый порт сдвигает панель на следующий свободный, и адрес показывает тот порт, который она реально получила. Тумблер «Поднимать при старте песочницы» делает то же самое на старте.
- **Песочница работает в фоне**: сессия не умирает вместе с экраном — foreground-сервис типа specialUse, исключение из батарейной оптимизации, а «Остановить» действительно останавливает.

### Исправления
- **`kill()` в песочнице больше не врёт**: раньше он гасил только `proot`, оставляя команду и занятый ею порт сиротами. Теперь фоновая сессия пишет свой pid в bind-смонтированный `/tmp`, остановка валит дерево процессов снизу вверх, и `success` возвращается только когда исчез каждый убитый pid, а не только `proot`. Повторный запуск больше не падает с «порт занят».
- **Проба порта в песочнице исполнялась под dash**, где `/dev/tcp` нет: она не различала «занято» и «свободно», из-за чего панель могла встать на занятый порт или объявить живую панель незапустившейся. Теперь проба идёт под `/bin/bash`, а исход, отличный от «соединение отвергнуто», считается «занято» — сломанная проба не выдаст чужой порт.
- **Поиск контактов больше не обходит приватность**: символы `%` и `_` в запросе экранируются, иначе запрос `%` выгружал первые контакты записной книжки.
- **`has_more` в поиске контактов стал живым**: Android-запрос берёт на строку больше, чем видит модель, и признак «есть ещё» перестал быть всегда ложным.
- **Агент считается установленным, только если бинарник отвечает на `--version`** — раньше перенесённый home без Node-рантайма объявлял Pi установленным и падал на запуске.

## v3.13.2 — 2026-11-18

### Улучшения
- Настройки → Сервисы: карточка «Приложение» с кнопками «Онбординг» (перезапускает мастер первого запуска) и «Обновить приложение» (проверяет релизы GitHub и ставит APK оттуда).
- Песочница Pots Build работает на Pi и Hermes вместо Claude Code и Grok: агенты получают провайдера, модель, ключ и эндпоинт прямо из настроек Pots — и при установке, и перед каждым запуском сессии. Ключ лежит только в окружении агента, файл `.env` Hermes доступен на чтение лишь владельцу.

### Исправления
- Генерация с референсом больше не падает: параметр `mode` берётся из значений своего семейства (image/video/3d), а не ставится константой `image-to-image`. Модели без нужного значения получают поведение по умолчанию провайдера, а в интерфейсе честно сказано, что модель не умеет референс. Возвращены nano-banana и nano-banana-pro через `mode=edit`.
- Провайдер без ключа (локальная Ollama) больше не обнуляет конфигурацию Pi: агент получает плейсхолдер вместо пустого ключа, иначе Pi отвергал весь `models.json` и терял провайдера, модель и эндпоинт.
- Спецсимволы в ключе не ломают проброс: каждое `$` экранируется (Pi интерполирует его в любом месте строки и иначе молча подменил бы ключ переменной песочницы), ведущий `!` не превращается в команду к выполнению.
- Агент считается установленным, только если бинарник отвечает на `--version`, а не просто лежит исполняемым файлом: раньше перенесённый home без Node-рантайма объявлял Pi установленным и падал на запуске.
- Миграция home не переносит npm-полезную нагрузку Pi без её Node-рантайма.

## v3.13.1 — 2026-11-16

### Улучшения
- Режим «поверх окон»: Потсик остаётся на рабочем столе, когда приложение свёрнуто, и возвращается в чат, когда его открывают (как PixelTag applyVisibility). Туск в настройках Потсика, по умолчанию выкл.

### Исправления
- На статичном экране часы кадров паркуются: персонаж дышит, а не крутит 60 fps вхолостую.

## v3.13.0 — 2026-11-15

### Улучшения
- Секция PotsGame вместо бесплатного уровня: настройка сервисов инференса без несуществующих лимитов.
- Онбординг-визард первого запуска в чате; после пропуска настройка доступна со «вторым шансом» — панелью провайдеров при первом неудачном запросе.
- Улучшение промпта в медиа-редакторе идёт под выбранный тип контента: изображение, видео, музыка и «Озвучить текст» под TTS.
- Канвас сохраняет пропорции источника без обрезки; кнопка «+» создаёт новый проект с явным сохранением текущего; карандаш рисует по стоп-кадру видео.
- Из верхнего тулбара медиа-редактора убрана первая ИИ-кнопка; рецентр кадра — двойной тап по пустому месту канваса.

### Исправления
- Режим HandsFree гарантированно снимается даже с мёртвой службой распознавания; зависшая сессия падает по таймауту с возможностью повтора.
- Панель провайдеров при отсутствии настроенных сервисов показывает одно честное состояние «инференс не настроен» вместо сообщения о лимите бесплатного уровня.
- Тип медиа-джобы живёт от отправки до резюма: музыка больше не сохраняется как `.png`.

## v3.12.0 — 2026-11-14

### Улучшения
- Медиа-студия переработана: компактные иконки сверху и снизу, канвас с точечной сеткой и скруглёнными элементами, скелетоны генерации и пустое состояние.
- В режиме медиа остаётся одно поле ввода: отдельное поле чата скрыто, пока студия открыта.
- Референсы для генерации изображений: файл из галереи, результат с канваса («Как референс») или фото из чата. Агент умеет улучшать присланное фото и помещать человека в другую сцену.
- Выбор медиа-модели показывает ценники, а выбранная строка читается в светлой и тёмной теме.
- Ошибки генерации показываются понятным текстом; при отсутствии ключа есть переход в настройки. Ход генерации пишется в журнал под тегом `PotsMedia`.

### Исправления
- Медиа берёт первый подключённый сервис, у которого есть ключ, а не просто первый в списке.
- Модель выбирается автоматически при первом открытии студии и запоминается.

## v3.12.1 — 2026-11-14

### Исправления
- Загрузка каталога медиа-моделей: полиморфные условия `tiers[].when` и не-примитивные значения в `inputTypes`, опциях параметров и таблице цен пропускаются вместо падения всего списка.

### Улучшения
- Потсик в медиа-студии: стоит слева от верхних тулбаров, кнопки 2×.

## v3.8.0 — 2026-10-29

### Исправления
- Клавиатура автоматически сворачивается после отправки запроса и при начале работы агента, в том числе в режиме hands‑free.
- На мобильных устройствах клавиатура не открывается автоматически, пока пользователь явно не выберет поле ввода.
- Пист/Потсик остаётся видимым в рабочей области, когда чат прокручивается.

## v3.7.9 — 2026-10-29

### Улучшения
- Скрытие клавиатуры после отправки запроса и корректный перенос фокуса, чтобы Потсик оставался в рабочей области и мог двигаться по полу формы ввода.
- Уточнено сохранение позиции Потсика при прокрутке: персонаж не уходит за верхний/левый край, а остаётся на видимой границе экрана.

## v3.7.8 — 2026-10-29

### Улучшения
- Уточнён учёт токенов в чате агентов: счётчики prompts теперь растут на разницу с предыдущим запросом, а не суммируют полный контекст каждого ответа API.
- Исправлен доступ локальной модели к встроенным и MCP‑инструментам: теперь учитываются глобальные переключатели, пользовательский выбор в «Локальная модель» и возможность отключить инструмент для экономии контекста.
- Встроенный навык `create-skill` доступен агенту независимо от профиля, а агентские навыки загружаются сразу после создания и в новых сессиях.
- Клавиатура скрывается после отправки сообщения, пока агент работает; Потсик сохраняет положение на экране и не уходит за края при прокрутке.

### Исправления
- Устранено дублирование подсчёта prompt‑токенов при запросах с вызовами инструментов.
- Исправлена видимость MCP‑инструментов для локальной модели при расхождении идентификаторов сервера.
- Стабилизировано открытие «О программе/релизных артефактов»: публикация Android APK и десктопных сборок выполняется через CI‑пайплайн по тегу.

## v3.6.4 — 2026-10-27

### Features
- Add named agent profiles with separate characters, tool choices, skills, MCP selection and Потсик appearance
- Add Russian character presets for psychological support, home therapy, coaching and Потсик
- Add private, on-device semantic search for Android photos and documents

### Improvements
- Make photo indexing incremental and orientation-aware; version the embedding index and report skipped items
- Index PDF pages and DOCX text, and add semantic photo labels
- Keep agent profile snapshots in app storage and make profile switching start a fresh chat

### Fixes
- Make shared Потсик appearance math compile on Web/Wasm
- Keep Android document and attachment pickers safe in Compose previews and screenshot tests

## v3.11.3 — 2026-11-10

### Features
- Media Studio: кнопка «Медиа» под HandsFree, канвас с drag/scale/rotate и всплывающим меню под точкой тапа, параметры из каталога, «✨ Улучшить промпт», импорт/экспорт в галерею, TTS «генерация готова»
- PlusVibeAPI как провайдер изображения и видео (`/api/media-catalog`, `/api/media/price`, `/api/media/generate`, опрос jobs, результат на 5 дней); интерфейс `MediaProvider` для следующих провайдеров
- Выбор image/video-моделей с ценами в Settings → Services → Media (тот же ключ, что для чата)
- Агент-инструменты `generate_image`, `generate_video`, `list_media_models`, `enhance_prompt`; встроенный медиа-рендер в чате с лайтбоксом, zoom, видеоплеером (seek/pause) и кнопкой «В канвас»
- Локальная LLM-вики провайдеров: `docs/knowledge/media-providers/`; спека `docs/features/media-studio.md`

### Fixes
- Android APK на GitHub теперь подписываются единым публичным FOSS-ключом (`androidApp/foss-release.keystore`, алиас `pots-foss`): все сборки из релизов имеют одну подпись, и обновления ставятся без переустановки. При переходе со сборки, подписанной другим ключом (например, из локального дашборда), нужно один раз удалить приложение
- Fix `String.format` в видеоплеере для iOS/wasm (замена на платформо-независимый формат времени)
- Make speech output and hands-free agree: starting hands-free turns speech on, the toggle works in both modes, and the loop can listen silently
- Stop a regular spoken reply before hands-free opens the microphone

## v3.3.0 — 2026-10-02

### Features
- Add Requesty and 1min.AI as providers

### Fixes
- Keep chat history intact when compaction fails or a tool loop trims context
- Back up an unreadable settings file instead of overwriting it, and make desktop settings writes atomic
- Fix Debian sandbox installs in Android work profiles and cloned apps
- Detect notification listener access on Android 8.0
- Stop or time out shell commands instead of leaving them running in the background
- Cancelling a request no longer shows up as an error
- Rename MCP tools whose names clash with another tool
- Accept OpenAI-compatible base URLs pasted with a trailing /chat/completions
- Show the provider's message for request-too-large errors on non-image requests
- Route GPT-5.6 and newer model families to the Responses API
- Send the OpenCode session header to every opencode.ai OpenAI-compatible instance
- Treat empty scheduled-task triggers and null tool arguments as absent
- Fire pots-ui countdown callbacks only from interactive messages
- Fix a stray %% in the Pots Build download label
- Verify email TLS hostnames and reject line breaks in IMAP/SMTP arguments
- Check every fetch_url redirect hop and cap the bytes it reads
- Limit open_url file:// links on Android to the sandbox's /root

### Improvements
- Don't retry provider errors that a retry can't fix
- Return tool errors as structured JSON
- Refresh model catalog Elo scores
- Upgrade dependencies

## v3.2.0 — 2026-09-07

### Features
- Support OpenAI's Responses API for GPT-5.6 models
- Add LFM2.5 1.2B Instruct to the on-device model catalog

### Fixes
- Cap stored message size so a large attachment can't brick the app
- Fix the Debian sandbox install failing on the base package step
- Send the OpenCode session header so OpenCode Zen no longer rejects requests

### Improvements
- Let on-device models declare their own tool support and sampling defaults, so models without a tool template no longer invent answers
- Refresh the on-device catalog: updated gemma-4-12b-it build with vision, audio, and multi-token prediction
- Refresh model catalog Elo scores
- Upgrade dependencies

## v3.1.0 — 2026-08-30

### Features
- Receive shared text from the Android share sheet
- Add Debian as a sandbox distribution, shared with Pots Build
- Add rename and delete for Pots Build projects
- Add custom model entry for OpenAI-compatible services
- Add an optional API key field when adding the Jina MCP server

### Fixes
- Fix model catalog context windows across major families

### Improvements
- Respect the system text-to-speech engine and voice
- Improve accessibility
- Improve PTY rendering
- Refresh model catalog Elo scores, free-tier models, and popular MCP servers
- Unify the platform, tool-layer, persistence, provider, and permission abstractions
- Unify the staggered settings layouts
- Upgrade iOS LiteRT-LM to 0.15.0
- Migrate deprecated Gradle DSL and clipboard APIs
- Add missing localizations
- Upgrade dependencies

## v3.0.0 — 2026-08-02

### Features
- Add Claude Code, Grok, and OpenCode support via the Debian Linux terminal
- Add checksum verification for local model downloads

### Improvements
- Improve Linux filesystem file handling
- Refresh open files when switching to a Linux system tab
- Upgrade dependencies

## v2.9.0 — 2026-07-25

### Features
- Add free-tier service suggestions when a rate limit is reached
- Add custom local model `.litert` import support

### Improvements
- Refresh popular MCP server list
- Update model catalog Elo scores
- Upgrade dependencies

## v2.8.3 — 2026-07-21

### Features
- Add Perplexity as a service
- Add AI Horde as a service

### Fixes
- Align socket timeout with chat request timeout

### Improvements
- Improve Alpine package search ranking
- Upgrade Alpine sandbox to 3.22
- Prefer requestScrollToItem for list scrolling
- Update model catalog Elo scores

## v2.8.2 — 2026-07-18

### Features
- Save copies of sent emails to the IMAP Sent folder
- Resolve email tool accounts by id, address, or single account

### Fixes
- Fix heartbeat CompletionHandlerException
- Add java.sql to desktop jlink runtime modules

### Improvements
- Increase chat request timeout to 180 seconds
- Auto-install bash package during Alpine Linux install
- Update model catalog (Kimi K3, Inkling, Elo scores)
- Upgrade dependencies

## v2.8.1 — 2026-07-11

### Features
- Migrate conversations to SQL storage
- Add Gemma 4 12B local model and missing catalog entries

### Fixes
- Fix Android 17 local network permission request

### Improvements
- Improve service and tool calling
- Add Atlas Cloud UTM tracking
- Update model catalog
- Upgrade dependencies

## v2.8.0 — 2026-07-01

### Features
- Add per-model image-capability gating so text-only models no longer offer image input
- Add Android assist-gesture launch support

### Fixes
- Reload conversations after importing settings
- Restore the Fetch URL tool in the Android tool list

### Improvements
- Group sponsored sections in the UI
- More prominent interactive loading animation
- Update model catalog (including DeepSeek V4 1M context window)
- Additional localizations
- Upgrade dependencies

## v2.7.1 — 2026-06-15

### Improvements
- Upgrade dependencies
- Update model catalog

## v2.7.0 — 2026-05-31

### Features
- Introduce Skills — packaged, reusable instructions the assistant can load on demand via the Linux sandbox, plus a built-in "create-skill" workflow to author your own
- Add Atlas Cloud as a featured provider

### Fixes
- Fix text selection in chat list
- Fix heartbeat active hours slider ranges
- Fix heartbeat Linux sandbox running in current conversation
- Fix terminal scroll out of bounds
- Fix OpenAI-compatible model loading when only the default URL is set

### Improvements
- Upgrade dependencies
- Update model catalog and scores
- Improve free endpoint DTO sanitization
- Extend API error mapping

## v2.6.3 — 2026-05-24

### Fixes
- Fixed quick service toggle not scaling properly with many services
- Fixed content serialization issue
- Fixed dynamic system prompt builder incorrectly handling deactivated tools and agent settings
- Strip orphaned tool calls left over from prior interruptions

### Improvements
- Updated model catalog

## v2.6.2 — 2026-05-21

### Features
- Parse inline tool calls

### Fixes
- Fix multiple service deletes in a row
- Fix DeepSeek thinking-mode tool calls returning reasoning_content error
- Improve fallback error handling

### Improvements
- Improve model sorting
- Update model catalog
- Upgrade SDKs and LiteRT
- Compose performance improvements
- Internal code cleanup

## v2.6.1 — 2026-05-17

### Features
- Collapsable reasoning content UI with cleaned-up handling across services

### Improvements
- Improve UI performance
- Add missing localizations
- Upgrade dependencies
- Internal code cleanup

## v2.6.0 — 2026-05-15

### Features
- Allow user-installed certificates
- Desktop AVX2+ CPU compatibility check

### Fixes
- Fix MCP tools serializing array and object arguments as strings
- Fix settings crash on Android 8.0
- Fix potential terminal sheet crashes
- Surface real errors from OpenAI-compatible chat

### Improvements
- Tolerate non-standard MCP tool schemas
- Improve SSH Alpine integration (requires re-installing Alpine)
- Add honesty, tool-use, and acting sections to chat system prompt
- Make chat input persistent between screen changes
- Upgrade Compose Multiplatform and SDKs
- Update model catalog
- Remove iOS x64 target and deprecated APIs

## v2.5.1 — 2026-05-13

### Fixes
- Fix terminal auto-scroll crash

### Improvements
- Update model catalog
- Add dynamic model endpoint for LongCat service
- Add Ollama Gemma4 to no-tool model list
- Support .gsc files and increase upload size limit to 200KB
- Show history delete undo inside modal
- Upgrade dependencies (coroutines, datetime, and others)

## v2.5.0 — 2026-05-06

### Features
- Theme switcher and native desktop UI scale settings
- Linux sandbox icon flash on shell execution

### Fixes
- Fix DeepSeek tool execution
- Fix sponsors display

### Improvements
- Improve Ollama error handling
- Compose performance improvements
- Add missing localizations
- Upgrade LiteRT
- Upgrade SDKs

## v2.4.1 — 2026-05-02

### Features
- Persistent terminal sessions in conversations
- Notification read integration

### Fixes
- Fix Linux sandbox initialization
- Fix Linux sandbox cd command

### Improvements
- Show detailed package upgrade info in Linux sandbox
- Show byte size for files smaller than 1 KB
- Material 3 search inputs
- Update model catalog and scores
- Upgrade SDKs

## v2.4.0 — 2026-04-28

### Features
- Linux sandbox file browser with file and folder actions
- Linux sandbox package manager
- Open files from the Linux sandbox
- Themed icon support
- Section selection dialog for exports

### Fixes
- Fix markdown regex parsing

### Improvements
- Persist Linux sandbox UI state during the session

## v2.3.4 — 2026-04-26

### Fixes
- Fix calendar event time zone issue
- Fix conversation restore ANR
- Fix iOS quick toggle hidden behind soft keyboard
- Fix escape character handling

### Improvements
- Add scheduled task details
- Simplify memory, schedule, and heartbeat tool toggles
- Improve email polling and simplify email/SMS tool UI
- Add missing model info
- Migrate deprecated APIs

## v2.3.3 — 2026-04-24

### Features
- Add SMS read and send support to FOSS build
- Add iOS and desktop notifications support
- Add Z.AI Coding Plan service

### Fixes
- Fix out-of-memory crash
- Fix crash when deleting models
- Fix crash when downloading models while offline

### Improvements
- Improve IMAP tool
- Use existing catalog for model context window info
- Upgrade Kotlin
- Add missing models

## v2.3.2 — 2026-04-23

### Fixes
- Fix pots-ui rendering and restore issues

### Improvements
- Make OLED dark mode opt-in
- Improve heartbeat, email polling, and task scheduling reliability
- Prevent incompatible models from being used in interactive chat and automations
- Align general grid UI
- Align localizations
- Upgrade dependencies

## v2.3.1 — 2026-04-22

### Features
- Add LaTeX code block rendering

### Improvements
- Pure black backgrounds in dark mode for OLED power savings
- Improve fallback status UI
- Add Mistral 3.5 models to catalog
- Update Kimi model catalog (add K2.6, refresh Arena scores)
- Update free API endpoint
- Upgrade dependencies

## v2.3.0 — 2026-04-20

### Features
- Custom math formula rendering
- Expandable and editable memories
- Zoom and pan for attached images

### Fixes
- Hide thinking section on chat restart

### Improvements
- Add Alpine Linux package download mirrors
- Pulse animation on interactive chat stop button
- Add missing model info
- Performance improvements
- Replace ABI filter with runtime check
- Remove unnecessary Android version checks

## v2.2.1 — 2026-04-20

### Features
- Add local Qwen 3 0.6B model

### Improvements
- Upgrade LiteRT
- Defensive desktop ProGuard rules

## v2.2.0 — 2026-04-16

### Features
- Add custom markdown parser
- Preserve previous Pots UI output and allow editing

### Improvements
- Pulse animation on stop button
- Add missing localizations
- Add missing model info

## v2.1.5 — 2026-04-15

### Features
- Add copy-to-clipboard button for code blocks

### Fixes
- Filter out Gemini API responses marked as internal thoughts
- Add size safeguards for PDF and image compression failures

### Improvements
- Highlight currently selected model in model picker
- Improve Linux sandbox interactive interface
- Adjust Gemma 4 device performance expectations
- Upgrade BouncyCastle and remove version checker dependency
- UI polish and alignment

## v2.1.4 — 2026-04-12

### Features
- Add fast and expert mode toggles for free services
- Add Windows menu entry
- Enrich model catalog with arena scores, release dates, and context windows
- Support multi-file upload for attachments
- Enable simple tools for local Gemma 4
- Select TTS voice based on current locale

### Improvements
- Improve conversation restore on app restart and settings navigation
- Upgrade SDKs

## v2.1.2 — 2026-04-09

### Features
- Add local model context configuration

### Fixes
- Fix undo snackbars not executing after leaving screen

### Improvements
- Improve filetype checks for file attachments

## v2.1.1 — 2026-04-09

### Fixes
- Fix crash when opening Settings on devices without downloaded local models

## v2.1.0 — 2026-04-08

### Features
- Add support for local Gemma 4 via LiteRT
- Add PublicAI, Fireworks, DeepInfra, and OpenCode services

### Improvements
- Add missing localizations

## v2.0.3 — 2026-04-07

### Fixes
- Fix Bouncycastle and Coil proguard rules on desktop

### Improvements
- Simplify and improve chat input UI
- Extract reusable UI components
- Improve heartbeat error messages

## v2.0.2 — 2026-04-06

### Fixes
- Fix toolbar vertical padding
- Fix removeLast crash

### Improvements
- Add custom JSON parser for more flexible and resilient UI parsing
- Improve desktop release performance and reduce app size
- Simplify UI spacing by handling padding definitions locally
- General UI/UX improvements

## v2.0.1 — 2026-04-04

### Features
- Add possibility to define service for heartbeat
- Add additional MCP servers

### Improvements
- Improve pots-ui prompt and split-block parsing
- Replace Lottie animation with custom implementation
- Improve tool use UI
- Add missing localizations
- Clean up implementations

## v2.0.0 — 2026-04-03

### Features
- Add experimental interactive UI screen
- Add conversation import and export
- Add dedicated GitHub Pages screen
- Add custom MCP server headers

### Improvements
- Improve Pots UI parsing
- Use Gemini header auth instead of URL parameter

## v1.12.3 — 2026-04-02

### Features
- Add Dynamic UI — AI can create interactive buttons, forms, and cards inline in chat responses (opt-out in Settings)

### Fixes
- Fix hover pointer not showing over text inside buttons on desktop and web

## v1.12.2 — 2026-03-31

### Fixes
- Fix daemon start crash
- Fix heartbeat thinking in wrong conversation
- Fix leaked path in sandbox

### Improvements
- Improve shell integrations
- Add uninstall linux sandbox confirmation dialog
- Add NDK 29 requirement and reproducible build flags

## v1.12.1 — 2026-03-29

### Improvements
- Replace standard tabs with custom piles
- Build proot from source

## v1.12.0 — 2026-03-29

### Features
- Add Linux sandbox for Android shell commands with ANSI terminal output
- Add AiHubMix service
- Add desktop scrollbars

### Fixes
- Fix macOS window application appearance

### Improvements
- Improve settings UI
- Migrate extended icons to custom icons
- Upgrade Ktor and add slf4j-nop

## v1.11.2 — 2026-03-25

### Improvements
- Improve heartbeat reliability with previous response context and failure logging
- Add proper context compaction for conversations
- Upgrade SDKs

## v1.11.1 — 2026-03-24

### Fixes
- Fix crash when restoring encrypted backups

## v1.11.0 — 2026-03-24

### Features
- Add skills feature
- Support text and PDF file attachments
- Add minimax and zai services
- Show LLM model performance ranking for Splinterlands auto battle
- Add undo snackbars to revert deletions
- Replace re-order arrows with drag and drop

### Improvements
- Move conversations to secure platform-specific encrypted storage
- Replace location-from-IP tool with HTTPS-secured alternative
- Improve UI performance
- Add missing localizations and hover pointers
- Upgrade Compose Multiplatform, Lifecycle, and Spotless SDKs

## v1.10.0 — 2026-03-18

### Features
- Add Splinterlands LLM auto-battle integration
- Add web loading spinner
- List sponsors on free tier UI
- Add bottom scroll pointer icon and rounded corners stop icon

### Fixes
- Fix heartbeat and tasks handling
- Fix icon button sizes

### Improvements
- Upgrade Kotlin to 2.3.20
- Upgrade Koin

## v1.9.8 — 2026-03-15

### Features
- Add scroll to bottom button
- Show selected model on service rows in settings

### Fixes
- Fix model filter

### Improvements
- Improve startup time and migrate deprecated APIs
- Improve performance

## v1.9.7 — 2026-03-14

### Features
- Add HuggingFace, LongCat, Moonshot, Together and Venice service support

### Improvements
- Sort services in modal alphabetically
- Clean up service quick select and history modal UI

## v1.9.6 — 2026-03-14

### Features
- Add chat histories and separate heartbeat chat

### Improvements
- Remove hard-coded /v1 path in OpenAI-compatible service setup

## v1.9.5 — 2026-03-12

### Features
- Add quick service toggle
- Add delete buttons to completed tasks

### Fixes
- Fix reoccurring tasks on fail
- Strip markers in the content field
- Rename daemon notification title

### Improvements
- Improve input layout arrangements
- Migrate project structure for AGP 9 requirements
- Upgrade SDKs

## v1.9.4 — 2026-03-11

### Features
- Add option to reset modified heartbeat
- Add button to cancel ongoing requests
- Add search input to model modal

### Fixes
- Fix settings import parsing
- Fix MCP server tool definitions
- Add missing UI pointer

## v1.9.3 — 2026-03-11

### Features
- Add confirmation dialog for settings import

### Fixes
- Fix silent import error

### Improvements
- Replace tasks and email icons with trash can icon

## v1.9.2 — 2026-03-10

### Features
- Add Anthropic service support

### Fixes
- Fix duplicate model key crash in models modal

## v1.9.1 — 2026-03-10

### Features
- Add settings import and export

### Fixes
- Fix deprecated APIs and unused code
- Gracefully catch daemon service exceptions

## v1.9.0 — 2026-03-09

### Features
- Add MCP server support

### Fixes
- Fix dark mode text colors
- Fix broken translations

### Improvements
- Cleaner services box UI
- Show tool usage in single row with minimum display duration
- Remove unused strings

## v1.8.2 — 2026-03-08

### Features
- Add file:// URL opening support
- Add URL-opening tool
- Add attached image previews in chat UI
- Add configurable active hours for heartbeat
- Add localizations

### Improvements
- Smoother animations and loading indicators
- Remove unused code and simplify internals
- Add docs link to app settings

## v1.8.1 — 2026-03-07

### Features
- Add Ollama Cloud and Cerebras as AI services
- Add UI scale option for desktop
- Preselect Kimi K2.5 model if available
- Add localizations

### Fixes
- Fix service modal scroll behavior
- Fix old service migration deletion
- Fix iOS image scaling

### Improvements
- Separate screen navigation for web/desktop and mobile
- Set Linux default font size to 110%
- Reorder tools list
- Upgrade SDKs

## v1.8.0 — 2026-03-05

### Features
- Add multi-service support with fallback logic
- Add image upload support for all services
- Add email plugin

### Improvements
- Improve agentic retry and timeout logic
- Normalize content for text-to-speech
- Upgrade Ktor

## v1.7.11 — 2026-03-04

### Features
- Add heartbeat monitoring
- Show OpenAI-compatible URL endpoint in settings

### Fixes
- Fix clear icon on desktop and web

### Improvements
- Improve daemon reliability
- Make full toggle UI clickable
- Improve settings UI on large screens
- Add OS to user agent string
- Clean up tool logic
- Migrate deprecated datetime APIs
- Remove unused code and improve performance

## v1.7.10 — 2026-03-03

### Features
- Add AUR and Snap packaging
- Add Arch Linux install instructions

### Improvements
- Update SHA for Flathub

## v1.7.9 — 2026-03-03

### Features
- Add search tool with support for more providers
- Add task scheduling and background daemon

### Improvements
- Remove cancelled tasks from task list
- Fix string formatting

## v1.7.8 — 2026-03-02

### Features
- Add memory system for persistent context
- Add memory toggle in settings
- Add shell command execution for desktop and Android
- Add re-generate button on last bot response

### Fixes
- Fix scroll width issue
- Fix tests

### Improvements
- Remove topics feature
- Update localizations
- Upgrade SDKs

## v1.7.7 — 2026-02-26

### Fixes
- Fix RTL UI layout issues

### Improvements
- Add locale configurations for iOS and Android
- Add new store localizations
- Improve performance

## v1.7.6 — 2026-02-25

### Features
- Add identity.md settings for customizable AI persona
- Add tablet screenshots

### Fixes
- Fix unit test
- Fix minimum tablet screenshot size

## v1.7.5 — 2026-02-24

### Features
- Add OpenClaw service support
- Add missing localizations

### Fixes
- Fix unit tests

## v1.7.4 — 2026-02-24

### Features
- Add Android alarm tool

### Improvements
- Disable topics by default
- Disable tools for certain models
- Permit cleartext traffic for local network access

## v1.7.3 — 2026-02-21

### Features
- Make topics optional

### Fixes
- Fix wrong version code
- Fix topic UI issues
- Fix web dark mode background

### Improvements
- Upgrade Compottie animation library
- Unify and simplify service logic

## v1.7.2 — 2026-02-19

### Features
- Add Flathub packaging and Linux tarball release asset

### Fixes
- Fix race condition in service selection

## v1.7.1 — 2026-02-19

### Features
- Add topic headers
- Add content localizations
- Add automated Play Store screenshot localization

### Improvements
- Remove SMS permissions to pass Play Store review

## v1.7.0 — 2026-02-16

### Features
- Add explore screen with curated topics
- Add space topic
- Add input clear icons

### Fixes
- Fix color scheme background
- Fix outdated models endpoint
- Fix topics padding

### Improvements
- Simplify JSON parsing
- Adjust markdown header font sizes
- Upgrade SDKs

## v1.6.2 — 2026-02-11

### Improvements
- Add missing ProGuard rules
- Separate FOSS and Play Store build flavors

## v1.6.1 — 2026-02-11

### Features
- Add Nvidia as an AI service

### Improvements
- Always show privacy info
- Upgrade SDKs

## v1.6.0 — 2026-02-03

### Features
- Add tools/skills support for AI-driven actions
- Add SMS tool
- Add local conversation history storage
- Add markdown image loading support

### Fixes
- Fix generic TTS exception handling
- Fix encryption warning
- Fix deprecated edge-to-edge behavior

### Improvements
- Use multiplatform time for conversation history
- Upgrade date dependency

## v1.5.2 — 2026-01-31

### Features
- Add OpenRouter service support
- Add xAI service support

### Fixes
- Fix race condition
- Fix test

### Improvements
- Simplify Groq authentication
- Update Android JVM target to 21
- Upgrade SDKs

## v1.5.1 — 2026-01-28

### Improvements
- Improve Enter+Shift key handling for all platforms
- Improve dropdown click handling
- Upgrade SDKs

## v1.5.0 — 2026-01-27

### Features
- Add Ollama / OpenAI-compatible service support with authentication

### Improvements
- Keep focus on input after sending chat
- Add chat and settings ViewModel tests
- Clean up service separation
- Upgrade SDKs

## v1.4.1 — 2026-01-24

### Features
- Add iOS text-to-speech support
- Add iOS launch screen

### Fixes
- Fix cursor behavior
- Fix proper edge-to-edge display on iOS

## v1.4.0 — 2026-01-23

### Features
- Add free tier and iOS release

## v1.3.5 — 2026-01-18

### Features
- Add in-app review SDK
- Add localizations

## v1.3.4 — 2026-01-18

### Features
- Add AppImage and RPM build targets

## v1.3.3 — 2026-01-17

### Features
- Add Windows and Linux desktop builds

## v1.3.2 — 2026-01-17

### Improvements
- Harden code
- Add version codes to release files
- Upgrade SDKs

## v1.3.1 — 2025-12-29

### Features
- Add macOS DMG build
- Add code syntax highlighting

### Improvements
- Improve ChatScreen performance by stabilizing lambdas
- Separate state and actions for better Compose performance
- Hoist LazyListState for improved scroll performance

## v1.3.0 — 2025-12-21

### Features
- Add dynamic color scheme support
- Add localizations
- Move all user-facing strings to strings.xml

## v1.2.14 — 2025-12-20

### Improvements
- Upgrade SDKs

## v1.2.13 — 2025-12-13

### Improvements
- Add and sort Groq models by date
- Upgrade SDKs

## v1.2.12 — 2025-11-30

### Fixes
- Fix error mapping and UI styling

### Improvements
- Improve empty API key fallback behavior

## v1.2.11 — 2025-11-29

### Improvements
- Update SDKs and models

## v1.2.10 — 2025-11-15

### Improvements
- Remove markdown formatting
- Upgrade SDKs

## v1.2.9 — 2025-10-26

### Improvements
- Upgrade SDKs

## v1.2.8 — 2025-10-05

### Improvements
- Upgrade SDKs

## v1.2.7 — 2025-09-27

### Improvements
- Upgrade SDKs

## v1.2.5 — 2025-09-13

### Improvements
- Downgrade AGP for F-Droid compatibility

## v1.2.4 — 2025-09-06

### Improvements
- Upgrade SDKs

## v1.2.2 — 2025-08-03

### Improvements
- Migrate to new FileKit library
- Upgrade SDKs

## v1.2.1 — 2025-07-05

### Improvements
- Add new Gemini models
- Refactor ChatScreen and apply Compose best practices
- Upgrade SDKs

## v1.1.4 — 2025-05-29

### Features
- Add Gemini 2.5 Flash and Pro preview models
- Implement specific error handling for Gemini API

### Fixes
- Fix error handling catch and UI

### Improvements
- Improve code quality and apply best practices
- Upgrade SDKs

## v1.1.3 — 2025-05-11

### Improvements
- Add web navigation binding
- Upgrade SDKs

## v1.1.2 — 2025-05-04

### Improvements
- Upgrade SDKs

## v1.1.1 — 2025-04-18

### Features
- Add drag-and-drop support for attachments
- Add Gemini file upload support

### Fixes
- Fix TextToSpeechSynthesisInterruptedError crash on Android
- Fix dark/light mode status bar colors

### Improvements
- Add default Groq model
- Move appVersion to version catalog
- Upgrade SDKs

## v1.0.2 — 2025-01-03

### Features
- Add copy-to-clipboard, speak, and flag actions

### Improvements
- Remove hard-coded API key and add proxy for Groq endpoint

## v1.0.1 — 2024-12-30

### Features
- Add dark mode support

## v1.0.0 — 2024-12-30

### Features
- Initial release
- Chat interface with AI services
- Text-to-speech support
- F-Droid packaging
