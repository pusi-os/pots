# Third-Party Licenses

This project includes the following third-party binaries in `androidApp/src/main/jniLibs/`,
plus the voice-engine libraries and models listed at the end.
The app shows these notices, the full license texts and a written offer for the source under
Settings › Licenses (`composeApp/src/commonMain/composeResources/files/licenses/THIRD_PARTY_NOTICES.txt`).
Keep the pinned versions there and in `build-proot.sh` in sync.

## PRoot (Termux fork)

- **Files:** `libproot.so`, `libproot-loader.so`, `libproot-loader32.so`
- **Source:** https://github.com/termux/proot/tree/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f (built by `build-proot.sh`)
- **License:** GPL-2.0
- **Copyright:** Copyright (C) PRoot developers

PRoot is a user-space implementation of chroot, mount --bind, and binfmt_misc. It is used to run an Alpine Linux environment inside the Android app without requiring root access. PRoot is executed as a separate process and is not linked into the application code.

The full GPL-2.0 license text is available at: https://www.gnu.org/licenses/old-licenses/gpl-2.0.html

## talloc

- **Files:** `libtalloc.so`
- **Source:** https://www.samba.org/ftp/talloc/talloc-2.4.3.tar.gz (version 2.4.3, built by `build-proot.sh`)
- **License:** LGPL-3.0
- **Copyright:** Copyright (C) Andrew Tridgell, Stefan Metzmacher, and contributors

talloc is a hierarchical memory allocator used as a dependency of PRoot. It is dynamically linked.

The full LGPL-3.0 license text is available at: https://www.gnu.org/licenses/lgpl-3.0.html

## Voice engines (Android)

Offline speech synthesis and recognition, linked on Android with models shipped in the assets:

- **piper-phonemize v1.5.9** — GPL-3.0, https://github.com/csukuangfj/piper-phonemize (Piper text frontend)
- **sherpa-onnx 2.0.1** — Apache-2.0, https://github.com/k2-fsa/sherpa-onnx (vendored AAR in `composeApp/libs/`)
- **Piper voice ru_RU-irina-medium** — MIT, https://huggingface.co/rhasspy/piper-voices (bundled in `androidApp/src/main/assets/piper/`)
- **vosk-android 0.3.47** — Apache-2.0, https://github.com/alphacephei/vosk-api
- **Vosk model vosk-model-small-ru-0.22** — Apache-2.0, https://alphacephei.com/vosk/models (bundled in `androidApp/src/main/assets/vosk/`)

Note: piper-phonemize is GPLv3 and linked into the Android build. See `docs/features/voice-engines.md`.

## ADB client (Android)

The ADB agent (Settings › ADB devices, and the `adb_*` agent tools) links these Java/Kotlin
libraries into the Android build. They are not binaries in `jniLibs`, so no source offer is needed
for them — but their notices ship with the app.

- **libadb-android 3.1.1** — Apache-2.0, https://github.com/MuntashirAkon/libadb-android (ADB client with the SPAKE2 wireless-debugging pairing handshake)
- **conscrypt-android 2.5.3** — Apache-2.0, https://github.com/google/conscrypt (TLS provider that offers `exportKeyingMaterial`, which the pairing handshake needs)
- **bcprov-jdk18on 1.86** — Bouncy Castle License (MIT-style), https://www.bouncycastle.org/licence.html (SPAKE2 and Ed25519 primitives)
- **spake2-android** — LGPL-3.0, https://github.com/MuntashirAkon/spake2-android (transitive dependency of libadb-android)

spake2-android is LGPL-3.0 and is linked unmodified; its source is available at the URL above.

## Bundled fonts

- **DejaVu Serif** — Bitstream Vera Fonts Copyright (permissive, requires the notice), https://dejavu-fonts.github.io/
- **File:** `composeApp/src/commonMain/composeResources/font/pots_text_serif.ttf` (the media studio's serif text layer; the sans option is the platform default family and ships no file)
- **Copyright:** Copyright (c) 2003 by Bitstream, Inc. All Rights Reserved; DejaVu changes are in public domain.
- **Notice:** the full Bitstream Vera notice is shown under Settings › Licenses (`composeApp/src/commonMain/composeResources/files/licenses/THIRD_PARTY_NOTICES.txt`, section «Bundled fonts»).

