# STT/TTS-движки в Pots: сравнение, рекомендация и план для 3.21.5

**Статус:** исследование (материал для решения тимлида). Не реализация.
**Дата:** 2026-11-28. **Ветка-источник:** `origin/main` @ `74dbe2f` (релиз 3.21.4).
**Автор:** researcher (карта `t_f907fa49`).

Пометки: **ДОКАЗАНО** — проверено первоисточником или живым прогоном (указано каким);
**ГИПОТЕЗА** — следует из логики/доков, но не проверено на устройстве.

---

## 1. Короткий ответ

1. **faster-whisper в Android-приложение встроить нельзя.** Это Python-пакет поверх CTranslate2;
   Android-сборки CTranslate2 нет (открытый issue с 2024), путь — только Termux/proot или отдельный
   сервис. Как «встроенный движок наравне с Vosk» он не годится. **ДОКАЗАНО** (§3).
2. **Правильный ответ того же класса уже лежит в APK.** В приложении вендорен `sherpa-onnx 2.0.1`
   (`composeApp/libs/sherpa-onnx-2.0.1.aar`), и в нём **уже есть** `OfflineRecognizer`/`OnlineRecognizer`
   с NeMo-CTC, Whisper, Zipformer, Parakeet, Qwen3-ASR, Moonshine, Paraformer, VAD и TTS.
   Добавление STT-движка **не добавляет ни одного байта нативных библиотек в APK** — только Kotlin-обвязку
   и скачиваемую модель. **ДОКАЗАНО** (`javap` по `classes.jar` из AAR, §4).
3. **Лучшая русская модель для оффлайна — GigaAM v3 (MIT), а не Whisper.** На одном и том же ARM-железе
   и одной и той же русской фразе: GigaAM v3 CTC int8 (214 МиБ) — RTF **0.16–0.18**, 335 МБ RSS, текст
   **без единой ошибки**; Whisper small int8 (375 МБ) — RTF **4.31**, 906 МБ RSS, 4 ошибки;
   Whisper base int8 — RTF 1.20, 6 ошибок; Qwen3-ASR-0.6B int8 (954 МБ) — RTF **2.21**, **1.47 ГБ RSS**,
   3 ошибки на 10 слов. Плюс у GigaAM есть вариант **с пунктуацией и регистром** — та же 214-МБ модель.
   **ДОКАЗАНО** живым прогоном на Raspberry Pi 5 (§5.2).
4. **Локальные мультимодальные модели как STT/TTS — технически возможно, но не в 3.21.5.**
   Приложение уже на LiteRT-LM 0.18.1, а в API рантайма есть `Content.AudioBytes` — то есть аудио-вход
   поддержан на уровне SDK. Но: единственный litertlm-ASR с русским (Qwen3-ASR-0.6B, 960 МБ) требует
   другого C++-движка (`OmniEngine`), а не того Kotlin-API, которым пользуется Pots; LFM3-Audio-2B
   проверен только на английском; Gemma 4 E2B/12B аудио заявляют, но в приложении этот путь не подключён
   и есть открытый баг LiteRT-LM с SIGSEGV на аудио. **ДОКАЗАНО** (состав SDK, каталоги моделей),
   качество на русском — **ГИПОТЕЗА/не проверено**.
5. **TTS: не менять движки, добавить выбор русского голоса.** Рантайм sherpa-onnx уже содержит TTS;
   у Piper для русского есть 4 голоса по ~21 МБ (int8) — это чистое скачивание, без новых зависимостей.
   Локальный нейро-TTS через LiteRT (Audio8-TTS 0.6b, 23 языка, русский тестировался) на телефоне идёт
   с RTF ≈ 0.8 — медленнее реального времени; откладываем. **ДОКАЗАНО** (размеры релизов, замер вендора).

**Рекомендация в одну строку:** в 3.21.5 добавить третий STT-движок «Нейро (GigaAM v3)» на уже
встроенном sherpa-onnx (модель 214 МБ скачивается при первом выборе, SHA-256, не в APK), добавить
выбор русского голоса Piper, а локальные мультимодальные STT/TTS — не показывать, записав причину в долг.

---

## 2. Что уже есть в приложении (факты из кода, `74dbe2f`)

| Факт | Где | Пометка |
|---|---|---|
| STT-движков ровно два: `Vosk`, `System` | `data/AppSettings.kt:76` (`enum class SttEngine`) | ДОКАЗАНО |
| Фабрика движка: `createSpeechToText(engine, voskModelId)` | `handsfree/SpeechToText.kt:126`, `SpeechToText.android.kt:15` | ДОКАЗАНО |
| Контракт движка: `start/stop/cancel/release` + `onPartial/onResult/onError/onLevel/onSpeechStart/onFinalising` | `handsfree/SpeechToText.kt:16-77` | ДОКАЗАНО |
| Потребители: hands-free диалог (`ChatScreen.kt:274`) и оверлей (`PotsikCloudOverlay.kt:162`) — оба через одну фабрику | — | ДОКАЗАНО |
| Vosk-движок сам делает endpointing по уровню сигнала и байтам, склеивает сегменты в один транскрипт | `VoskSpeechToText.kt:30-140` | ДОКАЗАНО |
| Модели Vosk: реестр `ModelSpec` + установка из asset/скачивание в `filesDir`, прогресс, удаление | `speech/vosk/VoskModels.kt` | ДОКАЗАНО |
| TTS-движков три: `Native`, `Piper`, `Cloud` (заглушка) | `data/AppSettings.kt:63` | ДОКАЗАНО |
| Piper = sherpa-onnx TTS + `piper-phonemize` (GPLv3), голос `ru_RU-irina-medium` в ассетах | `speech/piper/*`, `docs/features/voice-engines.md` | ДОКАЗАНО |
| Настройки: карточки `TtsSection` и `SttSection` подряд в General | `ui/settings/GeneralSettings.kt:116,129` | ДОКАЗАНО |
| Размер debug APK ≈ 234 МБ, релиз 3.21.4 — 226 МБ | `docs/features/voice-engines.md`, handoff t_4dea350a | ДОКАЗАНО |
| LiteRT-LM 0.18.1 (Android/Desktop), каталог локальных моделей с pinned-ревизиями и SHA-256 | `docs/features/on-device-inference.md` | ДОКАЗАНО |

Вывод по архитектуре: **добавление третьего STT-движка — это 1 значение enum + 1 реализация `SpeechToText`
+ 1 ветка фабрики + карточка в настройках + реестр моделей.** Никаких переделок hands-free/оверлея.

---

## 3. faster-whisper: вердикт

**ДОКАЗАНО:**
- `faster-whisper` — Python-пакет, требование «Python >= 3.9», работает через `ctranslate2`
  (PyPI `faster-whisper`, раздел Requirements).
- CTranslate2 — C++-библиотека без официальной Android-сборки; запрос сообщества «Build Ctranslate2 for
  android» (OpenNMT/CTranslate2#1683) висит без решения. Готовых Android-биндингов нет.
- В приложении нет Python-рантайма; единственный путь — Termux/proot-песочница (в Pots есть `sandbox`,
  но это пользовательский сценарий, а не встроенный движок: модель на 1.5 ГБ + Python-стек внутри proot,
  задержка старта, батарея, отсутствие доступа к `AudioRecord` без костылей).

**Что это значит:** faster-whisper надо честно вычеркнуть из списка «встроить наравне с Vosk» и заменить
эквивалентом того же класса. Эквивалент — sherpa-onnx: те же веса Whisper (и не только), но C++/ONNX,
JNI, Android AAR, уже в проекте.

---

## 4. Ключевая находка: sherpa-onnx уже в APK и уже умеет всё, что нужно

**ДОКАЗАНО** (`unzip` + `javap` по `composeApp/libs/sherpa-onnx-2.0.1.aar`):

- AAR 49 441 341 Б, внутри `jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libsherpa-onnx-jni.so` и
  `libonnxruntime.so` — нативные библиотеки **уже в APK**, их вес в APK не изменится.
- В `classes.jar` присутствуют: `OfflineRecognizer`, `OnlineRecognizer`, `VoiceActivityDetector`,
  `KeywordSpotter`, `OfflineTts`, `OfflineSpeakerDiarization`.
- `OfflineModelConfig` (тот самый, что нужен для нового движка) содержит поля:
  `nemo` (NeMo CTC — это GigaAM), `whisper`, `transducer`, `paraformer`, `zipformerCtc`, `senseVoice`,
  `moonshine`, `dolphin`, `wenetCtc`, `omnilingual`, `qwen3Asr`, `canary`, `cohereTranscribe`,
  `funasrNano`, `fireRedAsr*`, `medasr`, `teleSpeech`.
- В `libsherpa-onnx-jni.so` есть строки `offline-whisper-model.cc`, `offline-nemo-enc-dec-ctc-model.cc`,
  `offline-paraformer-model.cc`, `offline-moonshine-*.cc`, `online-t-one-ctc-model.cc` — код всех этих
  семейств **скомпилирован внутрь**, это не «в теории поддерживается».

Следствия:
1. Новый движок = чистый Kotlin поверх существующего JNI. **Ноль** новых нативных зависимостей,
   **ноль** роста APK, никаких изменений в CI-сборке.
2. Риск «сборка сломается из-за бинарников» отсутствует — бинарники уже собираются и уже проходят CI.
3. Актуальная версия апстрима — **2.0.3** (22.11.2026, PyPI/Releases); у нас 2.0.1. Для GigaAM/Whisper
   обновление **не требуется** (2.0.1 их уже поддерживает). Апгрейд — отдельным решением, не в 3.21.5.

---

## 5. Кандидаты: сравнение

### 5.1 Таблица (русский — главный критерий)

| Движок / модель | Как встраивается | Размер модели | Русский: качество | Скорость на ARM | Streaming | Лицензия | Итог |
|---|---|---|---|---|---|---|---|
| **Vosk small-ru-0.22** (уже есть, дефолт) | Java/JNI, `vosk-android` | 45 МБ (в APK) | WER 22.71 (openstt audiobooks), 31.97 (openstt youtube), 29.89 (sova devices), 11.79 (golos crowd) | реальное время (Kaldi, лёгкая) | да (partials) | Apache 2.0 | оставить как лёгкий/стриминговый дефолт |
| **Vosk ru-0.42** (уже есть, скачивается) | то же | 1.8 ГБ | 4.5 / 11.1 / 19.5 / 36.0 (calls) / 4.4 (golos) / 17.9 (sova) | ~реальное время, ~2 ГБ RAM | да | Apache 2.0 | оставить для «максимум точности» |
| **GigaAM v3 CTC int8** (рекомендуемый) | sherpa-onnx `nemo` (уже в AAR) | **214 МиБ** int8 | WER **3.0** (open datasets), 4.5 (Golos farfield), 7.8 (natural), 10.3 (callcenter); среднее **9.2** | **RTF 0.16–0.18** (замер), 335 МБ RSS | нет (offline, после фразы) | **MIT** | **встроить в 3.21.5** |
| **GigaAM v3 CTC punct int8** | то же | 214 МиБ int8 | то же + пунктуация/регистр (замер: слова точные, 2 ошибки в границах предложений) | RTF **0.163** (замер) | нет | MIT | **встроить как вариант по умолчанию** |
| GigaAM v3 RNNT / e2e | sherpa-onnx `nemo` | 167 МБ bz2 (≈220 МиБ) | среднее **8.4** (RNNT) — чуть лучше CTC | ГИПОТЕЗА: медленнее CTC (авторегрессия) | нет | MIT | кандидат на потом |
| **Whisper small int8** | sherpa-onnx `whisper` | 375 МБ (112+262) | среднее **25.1** (по таблице GigaAM), замер: 4 ошибки в 21 слове | **RTF 4.31**, **906 МБ RSS** (замер) | нет | MIT | не для hands-free; только «точная расшифровка файла», позже |
| Whisper base int8 | то же | 160 МБ | хуже (замер: 6 ошибок) | RTF 1.20 (замер) | нет | MIT | нет |
| Whisper large-v3 | то же | 1.06 ГБ bz2 | 25.1 среднее | на телефоне неподъёмно | нет | MIT | нет |
| **Parakeet TDT 0.6B v3** | sherpa-onnx `transducer` | 487 МБ int8 bz2 | русский **заявлен** (25 европейских языков), WER по ru не публикован | ГИПОТЕЗА: RTF ≈1–2 (0.6B TDT) | нет | CC-BY-4.0 | кандидат для мультиязычности, позже |
| **T-one (T-Bank)** | sherpa-onnx `online` (код в AAR есть) | 128 МБ bz2 | WER 5.7 (с LM) — телефония; среднее по доменам 19.4 | ГИПОТЕЗА: стриминг, лёгкая (71.7M параметров) | **да, настоящий стриминг** | Apache 2.0 | лучший кандидат на «partials в hands-free», позже |
| Moonshine tiny/base | sherpa-onnx `moonshine` | 30–111 МБ | **русского нет** (en/ja/ko/zh/es/vi/uk) | быстрая | нет | MIT | нет |
| Distil-Whisper | — | — | только английский | — | нет | MIT | нет |
| Qwen3-ASR-0.6B (sherpa-onnx) | sherpa-onnx `qwen3Asr` (поле уже есть в нашем AAR) | 954 МБ на диске | ru: CV **14.07**, Fleurs **9.91** (0.6B); замер: 3 ошибки в 10 словах, пунктуация верная | **RTF 2.21**, **1.47 ГБ RSS** (замер) | нет | Apache 2.0 | не для диалога: 1 ГБ модели и 1.5 ГБ памяти |
| Qwen3-ASR-0.6B (`.litertlm`) | LiteRT-LM **OmniEngine** (C++) | 960 МБ | ru в списке 30 языков + пунктуация | ГИПОТЕЗА: медленно | 5-сек окна | Apache 2.0 | не в 3.21.5 (другой движок) |
| LFM3-Audio-2B (`.litertlm`) | LiteRT-LM 0.18.1 (как у нас) | 1.53 ГБ | 18 языков транскрипции, **проверен только английский** | Pixel 8a: 12.95 tok/s, аудио-энкодер 360 мс/30 с | 30-сек окна | LFM Open License v1.0 | не в 3.21.5 |
| LFM3-Omni-2B (`.litertlm`) | LiteRT-LM | 3.2 ГБ | текст-языки включают ru, **речевые — нет** | GPU 4.1 ГБ памяти | 30-сек окна | LFM Open License | нет |
| Gemma 4 E2B / 12B | LiteRT-LM | 2.59 / 6.88 ГБ | аудио-вход заявлен; качество ASR по ru не измерено | 12B на телефоне нереален | — | Gemma Terms | нет |
| VibeVoice-ASR-BitNet (`.litertlm`) | LiteRT-LM | 1.98 ГБ | **русского нет** (en/zh/fr/it/ko/pt/vi) | Galaxy S26: 51 tok/s GPU | 30-сек окна | MIT | нет |

Источники — в §12.

### 5.2 Живые замеры (первоисточник: мой прогон)

Железо: **Raspberry Pi 5, ARM Cortex-A76, 4 ядра**, sherpa-onnx **2.0.3** (linux-aarch64 CLI).
Аудио: `test_wavs/example.wav` из релиза модели — 11.29 с русской речи (Пушкин, «У лукоморья…»),
эталон: «Ничьих не требуя похвал, счастлив уж я надеждой сладкой, что дева с трепетом любви посмотрит,
может быть, украдкой на песни грешные мои. У лукоморья дуб зелёный».

| Модель | Диск | Потоков | RTF | Время на 11.29 с | Пик RSS | Распознано |
|---|---|---|---|---|---|---|
| GigaAM v3 CTC int8 | 214 МиБ | 2 | **0.163–0.180** | 1.84–2.03 с | **335 МБ** | слова точные, без пунктуации/регистра |
| GigaAM v3 CTC int8 | 214 МиБ | 4 | 0.253–0.294 | 2.0–3.3 с | — | то же (4 потока на Pi **медленнее** 2 — конкуренция за кэш/частоты) |
| GigaAM v3 CTC **punct** int8 | 214 МиБ | 2 | **0.163** | 1.84 с | — | «Ничьих, не требуя похвал, счастлив уж я надеждой сладкой, Что дева с трепетом любви посмотрит, может быть украдкой На песни грешные мои. У лукоморья дуб зелёный,» — **все слова верны**, 2 ошибки в границах/регистре |
| Whisper base int8 | 160 МБ | 2 | 1.196 | 13.5 с | — | «Ничь их не требуя похвал, счастлив уж я надежды сладкой, что девострепетам любви посмотрит может быть украдкой на песне грешные мои. О лукоморь это убзеленый.» — **6 ошибок** |
| Whisper small int8 | 375 МБ | 2 | **4.314** | 48.7 с | **906 МБ** | «…у краткой На песни…», «У Лукаморьи это убзеленый» — **3–4 ошибки**, верная пунктуация |

Отдельный прогон (другое аудио — `ru1.wav` из комплекта Qwen3-ASR, 4.76 с, эталон «Барсук, живущий в
киевском зоопарке, совершил побег из своего вольера»):

| Модель | Диск | Потоков | RTF | Время на 4.76 с | Пик RSS | Распознано |
|---|---|---|---|---|---|---|
| Qwen3-ASR-0.6B int8 (sherpa-onnx) | 954 МБ | 2 | **2.208** | 10.5 с | **1.47 ГБ** | «Барсук, живущий в Киевском Запарке, совершил побег от своего волиера.» — пунктуация/регистр верные, **3 ошибки** («Запарке», «от», «волиера») |

Как читать: **GigaAM v3 в 7–13 раз быстрее Whisper и в 12 раз быстрее Qwen3-ASR на том же железе,
при меньшем RSS (335 МБ против 906 МБ и 1.47 ГБ) и лучшем тексте.**
Телефон (big-ядра A7xx/A8xx) обычно быстрее Pi 5 на ядро, но троттлит; ориентир для телефона —
**RTF 0.1–0.4 для GigaAM** и **RTF 2–6 для Whisper small** (**ГИПОТЕЗА**, на телефоне не мерил).

### 5.3 Где Vosk small проседает (ответ на вопрос владельца)

**ДОКАЗАНО** (таблица моделей alphacephei.com):
- Домены: 22.7 % (аудиокниги openstt), **32.0 % (YouTube openstt)**, **29.9 % (sova devices)**, 11.8 % (golos crowd).
  То есть на «живой» речи и на устройствах — треть слов мимо; на чистом чтении — пятая часть.
- Пунктуации и регистра у small-модели **нет вообще** — Vosk предлагает отдельную модель
  `vosk-recasepunc-ru-0.22` весом **1.6 ГБ** (alphacephei, раздел Punctuation models). Для телефона это
  несоразмерно; поэтому в приложении текст приходит «простынёй» без точек и заглавных.
- Имена/числа: small-модель — компактная Kaldi-модель с ограниченным словарём и слабым LM; OOV-имена
  и числа пишутся как услышано (это свойство класса, а не баг настройки). **ГИПОТЕЗА** в части
  «как именно ломается» — прямых публичных замеров «имена/числа» для small-ru нет; косвенно видно по
  разрыву 11.8 % (golos, чистые фразы) против 32 % (youtube).
- У GigaAM v3 пунктуация и регистр есть **в той же 214-МБ модели** (вариант `punct`), а e2e-варианты
  GigaAM дополнительно нормализуют текст (заявление карточки модели).

---

## 6. Локальные мультимодальные модели как бэкенд STT/TTS

### 6.1 Что реально в приложении сейчас

- **LiteRT-LM 0.18.1** (Android/Desktop) — `docs/features/on-device-inference.md`.
- В SDK есть аудио-контент: **ДОКАЗАНО** `javap` по `litertlm-android-0.18.1.aar`:
  `Content$AudioBytes(byte[])`, `Content$AudioFile(String)`, `InputData$Audio(byte[])`, а официальная
  документация LiteRT-LM показывает `EngineConfig(audioBackend = Backend.CPU())` и
  `Contents.of(Content.AudioBytes(...), Content.Text("..."))`. То есть **аудио-вход в рантайме есть**.
- Но в Pots аудио в модель **не передаётся**: `LiteRTInferenceEngine.kt` собирает только
  `Content.Text` и `Content.ImageBytes`; `LocalModelCapabilities.supportsAudio` читается из метаданных
  бандла, но **никто его не потребляет** (`docs/features/on-device-inference.md`, «No image input» —
  «declare vision and audio modalities … but nothing consumes it yet»).
- В каталоге приложения уже есть `lfm3-omni-2b-int8` (3.2 ГБ, GPU 4.1 ГБ) с комментарием в коде:
  «reads text, 512x512 images and audio (30 s windows) and replies in text … Speech output needs the
  extra TFLite graphs … and is NOT covered by Pots' LiteRT-LM chat today — text/picture/audio input only».

### 6.2 Кандидаты и честная оценка

| Модель | Аудио-вход | Русский | Выход речи | Реальность для 3.21.5 |
|---|---|---|---|---|
| **LFM3-Audio-2B** (1.53 ГБ, litertlm) | да, через LiteRT-LM 0.18.1 | 18 языков транскрипции по карточке источника; **вендор проверил только английский** (WER 5.56 % FLEURS en) | да, но нужно **4 доп. TFLite-графа** и свой цикл в приложении; Pixel 8a: 0.81 с синтеза на 1 с речи (RTF 0.88) | Не в 3.21.5. Русский не подтверждён, +1.5 ГБ, TTS-цикл писать с нуля |
| **LFM3-Omni-2B** (3.2 ГБ, уже в каталоге) | да | текстовые языки включают ru; **речевые (синтез) — нет** | нет (в приложении только текст) | Только как «чат, который слышит», не как движок распознавания |
| **Gemma 4 E2B / E4B / 12B** | аудио-энкодер заявлен (E2B: ~300M параметров аудио-энкодера по карточке Google) | качество ASR по русскому **не опубликовано** | нет (текст) | Не в 3.21.5: в приложении аудио-путь не подключён, есть открытый баг LiteRT-LM (SIGSEGV на аудио+видео на CPU, issue #1848) |
| **Qwen3-ASR-0.6B** (`.litertlm`, 960 МБ) | да | **ru в списке 30 языков**, пунктуация и регистр, авто-определение языка | нет | Не в 3.21.5: требует `OmniEngine`/`omni_runner` (C++), а не Kotlin-API приложения; 960 МБ |
| **VibeVoice-ASR-BitNet** (1.98 ГБ) | да | **русского нет** | нет | Нет |
| **Audio8-TTS-Realistic-0.6b** | — | 23 языка, русский в тестовом наборе | да, zero-shot клон голоса | Не в 3.21.5: 5 графов + host-loop, RTF 0.80–0.85 на телефонах (медленнее реального времени), ~2 ГБ RSS |

**Вывод по мультимодальности:** направление живое и через год-два станет основным, но сегодня
ни одна локальная мультимодальная модель не даёт **одновременно** русского, приемлемой скорости и
пути интеграции без нового движка. Правильно — **не показывать опцию** и записать причину в долг.

---

## 7. TTS: что делать в 3.21.5

**ДОКАЗАНО** (релиз `tts-models` sherpa-onnx, размеры ассетов):

| Голос | Размер int8 | fp16 | fp32 | Лицензия |
|---|---|---|---|---|
| `vits-piper-ru_RU-irina-medium` (уже в APK) | 21.1 МБ | 35.9 МБ | 67.2 МБ | MIT (голос), GPLv3 (`piper-phonemize`) |
| `vits-piper-ru_RU-denis-medium` | 21.1 МБ | 35.9 МБ | 67.2 МБ | то же |
| `vits-piper-ru_RU-dmitri-medium` | 21.1 МБ | 35.9 МБ | 67.2 МБ | то же |
| `vits-piper-ru_RU-ruslan-medium` | 21.1 МБ | 35.9 МБ | 67.2 МБ | то же |
| `vits-mms-rus` (Meta MMS) | — | — | 107.8 МБ | CC-BY-NC 4.0 (**не для коммерции**) |

Рекомендация: **движки не менять** (`Native` / `Piper` / `Cloud`), внутри Piper добавить выбор голоса —
3 дополнительных русских голоса по ~21 МБ, скачивание по требованию, тот же `sherpa-onnx OfflineTts`
(новых зависимостей ноль). `vits-mms-rus` не брать из-за NC-лицензии.

Локальный нейро-TTS на LiteRT (Audio8) — в долг: RTF 0.80–0.85 на Pixel 11 Pro / Galaxy S26 значит
озвучка «пленкой» отстаёт от реального времени, плюс 5 отдельных графов и ~2 ГБ RSS.

---

## 8. Роутинг: как выбирать движок

### 8.1 STT

| Сценарий | Движок по умолчанию | Почему | Фолбэк |
|---|---|---|---|
| Hands-free диалог (короткая фраза, ждём ответ) | **Нейро (GigaAM v3 punct)** | RTF 0.16 → 3-секундная фраза распознаётся ~0.5 с, есть пунктуация | модель не скачана → Vosk (встроенная) |
| Оверлей / куб-диалог | тот же выбор, что в настройках | один движок на все сценарии (требование карты) | Vosk |
| Долгая непрерывная запись, встречи | **Vosk** (стриминг, partials, 45 МБ) | partials и низкая память; GigaAM — только после фразы | — |
| Диктовка / «точная расшифровка» | Нейро (GigaAM punct) | лучший русский текст | Vosk ru-0.42 |
| Нет русского в фразе (пользователь говорит по-английски) | **Системное** или Vosk small-en | GigaAM — только русский | System |
| Мало памяти (< 700 МБ свободно) | Vosk small | 335 МБ RSS у GigaAM — не влезет рядом с моделью LLM | — |
| Модель не скачана / скачивается | Vosk small (сразу, без ожидания) | не блокируем пользователя загрузкой 214 МБ | — |

Правила:
- **Один выбор на всё приложение** (как сейчас): `SttEngine` в настройках, читают hands-free и оверлей.
- **Никаких «тихих» подмен без сообщения**: если выбран нейро-движок, а модель отсутствует — карточка
  настроек показывает «Не скачана · 214 МБ · Скачать», а при попытке записи — понятная ошибка
  «Модель не скачана» + кнопка в настройки. Молчаливый откат на Vosk допустим только как явно
  подписанный фолбэк («распознано Vosk — нейро-модель не готова»).
- **Память**: перед загрузкой GigaAM проверять `ActivityManager.MemoryInfo.availMem ≥ 700 МБ`
  (335 МБ замеренный RSS + арена ORT + запас); иначе — отказ с текстом, а не OOM-kill.
- **Потоки**: 2 (на Pi 4 потока оказались медленнее 2). Не давать слайдер, пока нет данных с телефонов.

### 8.2 TTS

| Сценарий | Движок | Правило |
|---|---|---|
| Обычный чат | выбор пользователя (`Native`/`Piper`) | как сейчас |
| Piper выбран, но голос не установлен | Native | подписанный фолбэк (уже реализовано) |
| Куб-диалог (`audio_down`) | Piper, фолбэк Native | как сейчас |
| `Cloud` | не реализован | оставить заглушкой, честный текст «пока недоступно» |

### 8.3 Где хранятся модели и как скачиваются

- Каталог: `context.filesDir/stt/<model-id>/` (рядом с существующим `filesDir/vosk-*`, но отдельно —
  чтобы удаление Vosk-моделей не задевало нейро).
- Скачивание: **не в APK**. `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/<asset>`
  (GitHub Releases отдают `Range` → докачка возможна), pinned-URL + **SHA-256** в коде (по образцу
  `LocalModelCatalog` для LiteRT: ревизия/дайджест/размер, маркер-файл после проверки).
- Распаковка `tar.bz2` потоком в каталог модели, проверка контрольных файлов (`model.int8.onnx`,
  `tokens.txt`), затем маркер `.sha256`. Неполный архив → удалить и сказать «скачайте ещё раз».
- Прогресс — в карточке настроек (0–100 %), как сейчас у Vosk; отмена — удаление частичного файла.
- **Предупреждение о трафике**: 214 МБ. Показывать размер **до** скачивания и предлагать «скачивать
  только по Wi-Fi» (флаг в настройках, по умолчанию включён) — иначе первый выбор движка съест трафик.

---

## 9. UX секции «Озвучивание и Распознавание речи»

Принципы из `ui-ux-pro-max`: векторные иконки (не эмодзи), тач-таргеты ≥ 48 dp, состояния загрузки,
прогрессивное раскрытие, честные ограничения, один primary CTA на карточку, контраст ≥ 4.5:1.

### 9.1 Структура (сверху вниз)

```
Настройки → Общие
┌─ Озвучивание ────────────────────────────────────────────────┐
│ [icon: speaker]  Движок речи                                 │
│  ( ) Нативный (системный)      Системный голос платформы     │
│  (•) Piper (офлайн)            Работает без интернета        │
│      └ Голос:  [Ирина ▾]       Встроена · 21 МБ              │
│         [icon: play] Прослушать голос                        │
│  ( ) Облачный (скоро)          Пока недоступно               │
│  Скорость речи  ──────●──────                                │
└──────────────────────────────────────────────────────────────┘
┌─ Распознавание речи ─────────────────────────────────────────┐
│ [icon: mic]  Движок распознавания                            │
│  ( ) Системное                 Сервис устройства, может      │
│                                требовать интернет            │
│  ( ) Vosk (офлайн)             Лёгкий, работает везде        │
│  (•) Нейро (GigaAM v3)         Лучшее качество на русском    │
│      └ Модель:                                               │
│         (•) GigaAM v3 + пунктуация · 214 МБ   [Скачать]      │
│         ( ) GigaAM v3 (без пунктуации) · 214 МБ [Скачать]    │
│         ( ) Whisper small (мультиязычная) · 375 МБ           │
│             ⚠ Медленно на телефоне, много памяти   [Скачать] │
│      [icon: mic] Проверить на микрофоне                      │
│      «Распознано: …»  (результат 3-секундной записи)         │
│  ( ) Локальная модель (LiteRT)  — скрыто в 3.21.5, см. §6    │
└──────────────────────────────────────────────────────────────┘
```

Правила:
- **Прогрессивное раскрытие**: список моделей показывается только для выбранного движка (как сейчас
  для Vosk). Внутри модели — одна строка статуса: `Не скачана · 214 МБ` / `Скачивается… 42 %` /
  `Установлена · 214 МБ` / `Встроена в приложение`.
- **Один primary CTA**: у выбранной, но не скачанной модели — `Скачать`; у скачанной — `Проверить на
  микрофоне`; `Удалить` — вторичная текстовая кнопка, только у невыбранных/скачанных.
- **Состояния загрузки**: `LinearProgressIndicator` + проценты + «Отменить». Во время скачивания
  карточка не даёт переключиться на эту модель (радио disabled, 0.38 alpha, `enabled=false`).
- **Честные ограничения** в тексте карточки: «Нейро-модель: 214 МБ, только русский, распознаёт после
  фразы (не по словам)». Для Whisper: «375 МБ, на телефоне медленно и требует ~900 МБ памяти».
- **Проверка на микрофоне** (обязательный элемент карты): кнопка → 3 секунды записи с индикатором
  уровня → «Распознано: <текст>»; если пусто — «Ничего не распознано, попробуйте ближе к микрофону».
  Тот же движок, что в диалоге (как `PiperVoiceTest` для TTS).
- **Иконки**: `mic`, `speaker`, `download`, `trash`, `play`, `check`, `alert` — inline SVG/`Icons.Outlined`
  с `currentColor`, без эмодзи.
- **Тач-таргеты**: вся строка модели — кликабельная карточка (≥ 56 dp по высоте), кнопки — ≥ 48 dp.

### 9.2 Точные тексты (ru / en) — новые ключи

| Ключ | ru | en |
|---|---|---|
| `settings_stt_engine_neural` | Нейро (GigaAM v3) | Neural (GigaAM v3) |
| `settings_stt_engine_neural_description` | Лучшее качество на русском. Модель скачивается один раз, работает без интернета. | Best Russian accuracy. The model downloads once and then works offline. |
| `settings_stt_model_punct` | GigaAM v3 + пунктуация | GigaAM v3 with punctuation |
| `settings_stt_model_plain` | GigaAM v3 (без пунктуации) | GigaAM v3 (no punctuation) |
| `settings_stt_model_whisper_small` | Whisper small (мультиязычная) | Whisper small (multilingual) |
| `settings_stt_model_whisper_small_warning` | Медленно на телефоне, нужно около 900 МБ памяти | Slow on phones, needs about 900 MB of memory |
| `settings_stt_model_download` | Скачать | Download |
| `settings_stt_model_cancel` | Отменить | Cancel |
| `settings_stt_model_not_installed` | Не скачана | Not downloaded |
| `settings_stt_model_downloading` | Скачивается… | Downloading… |
| `settings_stt_model_size_hint` | Модель скачивается при первом использовании и не входит в APK | The model is downloaded on first use and is not part of the APK |
| `settings_stt_wifi_only` | Скачивать модели только по Wi-Fi | Download models over Wi-Fi only |
| `settings_stt_test_mic` | Проверить на микрофоне | Test with the microphone |
| `settings_stt_test_recording` | Говорите… | Speak now… |
| `settings_stt_test_result` | Распознано: %1$s | Recognized: %1$s |
| `settings_stt_test_empty` | Ничего не распознано — попробуйте ближе к микрофону | Nothing recognized — try closer to the microphone |
| `settings_stt_test_failed` | Не удалось записать — проверьте разрешение на микрофон | Could not record — check the microphone permission |
| `settings_stt_neural_only_russian` | Понимает только русскую речь | Understands Russian only |
| `settings_stt_neural_after_phrase` | Распознаёт после фразы, а не по словам | Recognizes whole phrases, not word by word |
| `settings_stt_error_model_missing` | Модель не скачана. Откройте Настройки → Общие → Распознавание речи | The model is not downloaded. Open Settings → General → Speech recognition |
| `settings_stt_error_low_memory` | Недостаточно памяти для нейро-модели — выбрана Vosk | Not enough memory for the neural model — using Vosk |
| `settings_tts_piper_voice_denis` | Денис (русский) | Denis (Russian) |
| `settings_tts_piper_voice_dmitri` | Дмитрий (русский) | Dmitri (Russian) |
| `settings_tts_piper_voice_ruslan` | Руслан (русский) | Ruslan (Russian) |
| `settings_tts_piper_voice_download` | Скачать голос · 21 МБ | Download voice · 21 MB |
| `settings_voice_section_title` | Голос | Voice |

Существующие ключи (`settings_stt*`, `settings_tts*`) не переименовывать — только добавлять.

---

## 10. План интеграции в 3.21.5 (по шагам, с рисками)

| # | Шаг | Объём | Риск | Как проверить |
|---|---|---|---|---|
| 1 | `SttEngine.Neural` в enum + миграция парсинга (неизвестное значение → Vosk, уже так) | 5 строк | низкий | unit-тест парсинга настроек (обратная совместимость) |
| 2 | Реестр нейро-моделей: id, label, asset-name, url, sha256, sizeBytes, installedSize, флаг «punct» | новый файл `speech/sherpa/SherpaSttModels.kt` | средний: нужен pinned SHA-256 (считать с релиза и зафиксировать) | тест: неполный файл → отказ и удаление |
| 3 | Загрузчик: HTTP c `Range`, прогресс, отмена, распаковка `tar.bz2` потоком, маркер `.sha256` | ~250 строк | средний: распаковка bz2 на Android (commons-compress) — **новая зависимость**; альтернатива — перепаковать модели в `.tar.gz`/zip самим | тест на 3G/обрыв, повторная докачка |
| 4 | `SherpaSpeechToText : SpeechToText` — `AudioRecord` + endpointing (переиспользовать логику Vosk-движка), на endpoint — `OfflineRecognizer.decode(segment)` в фоновом потоке, `onResult` | ~250 строк | средний: задержка на фразе (не partials) — UX должен это подписать; конкуренция за CPU с Piper | живой прогон на телефоне: 3-сек фраза ≤ 1 с |
| 5 | Ветка в `createSpeechToText`: `SttEngine.Neural -> SherpaSpeechToText(context, modelId)`; проверка «модель есть» → иначе `SpeechToTextError.Unavailable` | 5 строк | низкий | unit-тест фабрики |
| 6 | Карточка настроек: радио-движки + список моделей + статусы + кнопки + «Проверить на микрофоне» | ~200 строк Compose | средний: не раздувать `SttSection.kt` (он общий) — вынести `NeuralModelPicker` | скриншот-тест 375 dp и landscape |
| 7 | Память: перед загрузкой `availMem ≥ 700 МБ`; при нехватке — понятная ошибка, не OOM | ~30 строк | низкий | эмулятор с `-memory 512` |
| 8 | TTS: выбор голоса Piper (3 новых) + скачивание 21 МБ | ~120 строк | низкий (тот же `OfflineTts`) | тест «Прослушать голос» для каждого |
| 9 | Строки ru/en, KDoc по-английски, `docs/features/stt-tts-engines.md` фактами реализации | — | низкий | `values-ru` паритет ключей |
| 10 | CI: unit-тесты роутинга и парсинга; сборка APK — **без изменений** (нативных библиотек не добавляем) | — | низкий | оба джоба `success` |

**Оценка:** 3.21.5 реалистично = шаги 1–7, 9, 10 (новый STT-движок + настройки). Шаг 8 (голоса Piper)
можно в тот же релиз, если останется время; иначе — 3.21.6.

**Отдельно: не делать в 3.21.5**
- Обновление AAR sherpa-onnx 2.0.1 → 2.0.3 (не требуется для GigaAM/Whisper; отдельный риск бинарной замены).
- Whisper small как вариант в hands-free (906 МБ RSS, RTF 4.3 на Pi).
- Qwen3-ASR 0.6B (1 ГБ модели, 1.47 ГБ RSS, RTF 2.2 на Pi — не для диалога; как «точная расшифровка файла» — кандидат в 3.21.6+).
- T-one (стриминг) — следующий шаг, лучший кандидат на partials в hands-free.
- Локальные мультимодальные STT/TTS (LiteRT) — долг с причиной (§6).
- Пунктуация для Vosk через `vosk-recasepunc-ru-0.22` (1.6 ГБ) — несоразмерно.

---

## 11. Файлы, которые придётся менять

| Файл | Что |
|---|---|
| `composeApp/src/commonMain/.../data/AppSettings.kt` | `SttEngine.Neural`, ключ настройки модели, флаг «только Wi-Fi» |
| `composeApp/src/commonMain/.../handsfree/SpeechToText.kt` | KDoc контракта (offline-движок не даёт partials) — без смены сигнатур |
| `composeApp/src/androidMain/.../handsfree/SpeechToText.android.kt` | ветка фабрики для `Neural` |
| **новый** `composeApp/src/androidMain/.../speech/sherpa/SherpaSttModels.kt` | реестр моделей, pinned URL/SHA-256/размер, установка, прогресс, удаление |
| **новый** `composeApp/src/androidMain/.../speech/sherpa/SherpaSpeechToText.kt` | `SpeechToText` на `OfflineRecognizer` + `AudioRecord` + endpointing |
| **новый** `composeApp/src/androidMain/.../speech/sherpa/SherpaSttModelDownloader.kt` | HTTP Range, прогресс, отмена, распаковка, проверка дайджеста |
| `composeApp/src/commonMain/.../ui/settings/SttSection.kt` | карточки движков, список моделей с состояниями, кнопка проверки |
| `composeApp/src/androidMain/.../ui/settings/SttSection.android.kt` | `actual`-функции для новых моделей/статусов/удаления |
| `composeApp/src/commonMain/.../ui/settings/GeneralSettings.kt` | порядок карточек, прокидывание новых колбэков |
| `composeApp/src/commonMain/.../ui/settings/SettingsUiState.kt`, `SettingsViewModel.kt`, `SettingsActions.kt` | состояние выбранной модели/скачивания, действия |
| `composeApp/src/commonMain/.../ui/settings/PiperVoiceTest.kt` + `speech/piper/PiperVoices.kt` | выбор и скачивание голоса (шаг 8) |
| `composeApp/src/commonMain/composeResources/values/strings.xml`, `values-ru/strings.xml` | новые ключи (§9.2) |
| `composeApp/build.gradle.kts` | только если берём `org.apache.commons:commons-compress` для `tar.bz2` (иначе — свой минимальный распаковщик или перепаковка моделей в zip) |
| `composeApp/proguard-rules.pro` | keep-правила для новых классов sherpa-onnx, если R8 начнёт резать JNI-колбэки (как было с `StreamingCallback`) |
| `docs/features/stt-tts-engines.md` | этот документ → факты реализации |
| `docs/features/voice-engines.md` | ссылка на новый движок (сейчас описывает только Vosk/System) |
| `docs/user-guide/*` (раздел настроек) | как выбрать движок, скачать модель, проверить микрофон |
| `THIRD_PARTY_LICENSES.md` | GigaAM v3 (MIT, GigaChat Team), Whisper (MIT), Parakeet (CC-BY-4.0) — если попадут в поставку |

---

## 12. Источники

**Первоисточники / живой прогон (ДОКАЗАНО)**
1. `composeApp/libs/sherpa-onnx-2.0.1.aar` — `unzip` + `javap`: наличие `OfflineRecognizer`,
   `OfflineModelConfig{nemo, whisper, qwen3Asr, canary, …}`, `OnlineRecognizer`, `OfflineTts`; строки
   `offline-whisper-model.cc`, `offline-nemo-enc-dec-ctc-model.cc`, `online-t-one-ctc-model.cc` в
   `libsherpa-onnx-jni.so`.
2. `litertlm-android-0.18.1.aar` из Gradle-кэша — `javap`: `Content$AudioBytes`, `Content$AudioFile`,
   `InputData$Audio`.
3. Живой прогон на Raspberry Pi 5 (ARM Cortex-A76), sherpa-onnx 2.0.3 CLI:
   `sherpa-onnx-nemo-ctc-giga-am-v3-russian-2025-12-16` (model.int8.onnx = 224 721 476 Б, LICENSE = MIT),
   `…-punct-…` (224 893 661 Б), `sherpa-onnx-whisper-base` (enc int8 29 120 534 Б + dec int8 130 672 026 Б),
   `sherpa-onnx-whisper-small` (112 442 483 + 262 226 114 Б),
   `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` (conv_frontend 44 148 281 Б + encoder.int8 182 491 662 Б +
   decoder.int8 755 914 231 Б + tokenizer 4 452 933 Б; ассет релиза 878 702 423 Б). RTF/RSS/текст — таблица §5.2.
4. GitHub API `k2-fsa/sherpa-onnx` releases: `asr-models` (точные размеры ассетов GigaAM v3 CTC/punct/
   transducer, T-one streaming 128 468 156 Б, whisper tiny/base/small/medium/large-v3, parakeet v3
   487 211 443 Б, moonshine, qwen3-asr 0.6B 878 702 423 Б), `tts-models` (4 русских голоса Piper:
   int8 21 058 905 / 21 127 907 / 21 129 441 / 21 149 417 Б, fp16 ~35.9 МБ, fp32 ~67 МБ, `vits-mms-rus`
   107 761 221 Б), `v2.0.3` (релиз 22.11.2026).

**Документация и карточки моделей**
5. Vosk models, alphacephei.com — WER по доменам для `vosk-model-small-ru-0.22` и `vosk-model-ru-0.42`,
   `vosk-recasepunc-ru-0.22` (1.6 ГБ).
6. GigaAM (salute-developers/GigaAM, README) — MIT с 2024/12, v3 (2025/11), **v4 (2026/11)**:
   +35 % к v3-E2E на русском, стриминг 160/320/640 мс, Zapformer; ONNX-экспорт.
7. HF `ai-sage/GigaAM-v3` — таблица WER: open datasets 3.0 (v3_ctc) / 2.6 (v3_rnnt) / 5.7 (T-one+LM) /
   12.0 (Whisper); Golos farfield 4.5/3.9/12.2/16.7; natural 7.8/6.9/14.5/13.6; callcenter 10.3/9.5/13.5/23.9;
   среднее **9.2 / 8.4 / 19.4 / 25.1**; e2e-варианты дают пунктуацию и нормализацию.
8. k2-fsa docs: `offline-ctc/nemo/russian.html` (GigaAM v2: 226 МБ int8, RTF 0.329/0.165 на x86),
   `sherpa-onnx-releases` `asr-models` (наличие GigaAM v3 CTC/RNNT/punct, T-one).
9. HF `litert-community/LFM3-Audio-2B-LiteRT` — 1.53 ГБ, LiteRT-LM 0.18.1, 18 языков транскрипции,
   проверен только английский (WER 5.56 % FLEURS en), Pixel 8a: 12.95 tok/s, аудио-энкодер 360 мс/30 с,
   TTS требует 4 доп. графа + свой цикл (0.81 с на 1 с речи), лицензия LFM Open License v1.0.
10. Liquid AI docs `LFM3-Omni-2B` — текстовые языки включают Russian; **речевые (синтез) — без русского**.
11. HF `litert-community/Qwen3-ASR-0.6B` — 960 МБ int8 litertlm, 30 языков (ru в списке) + 22 диалекта,
    пунктуация/регистр, авто-LID, требует `OmniEngine`/`omni_runner`.
12. arXiv 2601.21337 (Qwen3-ASR Technical Report) — WER ru: CommonVoice 14.07/8.28/5.73,
    MLC-SLM 19.24/15.17/13.16, Fleurs 9.91/5.99/4.81 (0.6B/1.7B/Flash).
13. HF `litert-community/VibeVoice-ASR-BitNet` — 1.98 ГБ, языки en/zh/fr/it/ko/pt/vi (русского нет),
    Galaxy S26 51.2 tok/s GPU, MIT.
14. HF `litert-community/Audio8-TTS-Realistic-0.6b` — 23 языка (русский в тестовом наборе), RTF 0.853
    (Pixel 11 Pro) / 0.797 (Galaxy S26), Apache-2.0, 5 графов + host-loop.
15. LiteRT-LM docs (Kotlin API, `Content.AudioBytes`, `audioBackend`) + issue #1848 (Gemma 4 E2B:
    audio+vision SIGSEGV на CPU/Linux).
16. HF `nvidia/parakeet-tdt-0.6b-v3` — 25 европейских языков, включая **ru** и uk (CC-BY-4.0).
17. `voicekit-team/T-one` (GitHub) / HF `t-tech/T-one` — Apache 2.0, 71.7M параметров, стриминговый
    Conformer для русской телефонии.
18. PyPI `faster-whisper` (Python ≥ 3.9, CTranslate2); OpenNMT/CTranslate2 issue #1683 (Android-сборки нет).
19. `ggml-org/whisper.cpp` `examples/whisper.android` (JNI-пример) — существует, но дублирует уже
    вендоренный рантайм и даёт то же качество Whisper.

---

## 13. Что не удалось проверить

1. **RTF на реальном телефоне.** Замеры — Raspberry Pi 5 (Cortex-A76). Телефонные big-ядра быстрее,
   но троттлят; цифры «RTF 0.1–0.4 для GigaAM» — экстраполяция.
2. **Качество GigaAM v3 на именах, числах и адресах.** Публичных WER по этим категориям нет; на тестовой
   фразе числа/имена отсутствуют. Нужен прогон на 20–30 реальных фразах владельца (можно собрать
   в «Проверить на микрофоне»).
3. **Числа/даты у punct-варианта.** Проверено, что пунктуация и регистр появляются; нормализует ли эта
   модель «двадцать шестое ноября» → «26.11» — не проверял (e2e-варианты GigaAM это заявляют, punct-CTC — нет).
4. **Qwen3-ASR 0.6B — на телефоне.** На Pi 5 он измерен (RTF 2.21, RSS 1.47 ГБ), на телефоне — нет.
   Не проверено также, загрузится ли `.litertlm`-вариант Qwen3-ASR в обычный Kotlin-API Pots (по
   документации ему нужен `OmniEngine`) — это **ГИПОТЕЗА**, что не загрузится.
5. **LFM3-Audio-2B / Gemma 4 E2B на русском.** Русский в списках языков есть (LFM3-Audio — 18 языков
   транскрипции, Gemma — аудио-модальность), но ни одного опубликованного замера WER по русскому нет;
   вендор LFM3-Audio проверял только английский.
6. **Parakeet v3 на русском.** Язык заявлен, WER по ru не опубликован; не запускал.
7. **T-one как стриминговый движок в приложении.** Код в AAR есть, модель 128 МБ есть; интеграцию
   и задержку partials не проверял.
8. **Вес APK после добавления.** Нативных библиотек не добавляется, но точный прирост (+ Kotlin-код,
   + commons-compress, если берём) не измерен — локальные Gradle-сборки запрещены, гейт только CI.
9. **Лицензионная чистота Parakeet (CC-BY-4.0) и LFM Open License** в контексте распространения APK —
   требует отдельного решения, если эти модели попадут в поставку.

## Implementation status (3.21.5)

Shipped in this release:

- `SttEngine.Neural` — a third recognition engine on the vendored sherpa-onnx
  runtime (NeMo CTC), model = GigaAM v3 CTC int8, downloaded on first use from
  the pinned Hugging Face mirror (two files, size + SHA-256 pinned in
  `SherpaSttModels.kt`), resumable via `Range`, verified before the model is
  marked installed. Nothing is added to the APK: the same AAR already serves
  Piper.
- One routing decision (`routeSttEngine`) used by the hands-free chat, the
  overlay pill and the settings microphone test. A missing model or less than
  700 MB of free RAM falls back to Vosk **and the card says so**; the platform
  recognizer is never a silent substitute because it may need the network.
- The recognition section of the settings was rebuilt around that: engine
  cards, per-engine model list (progressive disclosure), size shown before the
  download, progress with `Отменить`, `Удалить` as a secondary action, and
  `Проверить на микрофоне` running the routed engine on three seconds of audio.

Deliberately not done, with the reason:

- **More Piper voices.** The report recommends three Russian voices; this
  release ships only the bundled `ru_RU-irina-medium`. The TTS *engine* is
  already the local neural one (sherpa-onnx VITS through Piper), so a second
  engine would be the same runtime with another voice — the honest unit of work
  is "downloadable voices", which is a separate card. Native/Piper/Cloud stay
  as they are.
- **A local multimodal model as the speech backend.** The LiteRT candidates the
  report examined (OmniEngine, LFM3-Audio) are English-first with no Russian
  ASR quality data, and the Android runtime has no proven Russian audio path.
  The option is not shown in the UI rather than shown and broken.
- **Streaming partials for the neural engine.** NeMo CTC in sherpa-onnx is an
  offline decoder: it transcribes a finished phrase. The session keeps Vosk's
  end-of-speech rules and decodes at the pause, so the wave and the
  «Говорите…» affordance still move in real time, but there is no word-by-word
  preview.

Verification performed for this implementation:

- Both pinned models downloaded and hashed locally: `model.int8.onnx`
  `d5fea8df…3b35` (224 893 661 B) and `tokens.txt` `142de757…39cd` (2 007 B)
  for the punctuated export; the plain export's `tokens.txt` hashes to
  `17cc5144…92f2` (196 B). All three pinned URLs answer HTTP 206 to a `Range`
  probe with the pinned `x-linked-size`.
- The punctuated model decoded the upstream `example.wav` through the
  sherpa-onnx 2.0.3 CLI with `--num-threads=2`: RTF 0.143 (1.609 s for
  11.290 s of audio) and correct Russian text with punctuation and
  capitalisation. That confirms the files, the NeMo CTC config and the thread
  count; it does not exercise the Android JNI wrapper, which CI's build does.
- The routing table and the settings round-trip are covered by unit tests
  (`SttRoutingTest`, `SttSettingsTest`): requested engine honoured, fallback on
  a missing model, fallback on a memory shortfall, unknown memory not treated
  as a shortfall, an unknown stored engine name reading back as Vosk, and the
  new keys staying out of the settings export.

Still unverified, and why: RTF and memory on a real phone (the measurement is a
Raspberry Pi 5 proxy), GigaAM v3 accuracy on names, numbers and addresses (no
public Russian WER for the CTC export), and the exact APK delta (local Gradle
builds are forbidden in this repo; CI is the gate).
