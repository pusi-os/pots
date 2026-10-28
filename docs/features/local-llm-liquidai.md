# LiquidAI LFM3 models — research and feasibility

Last verified: 2026-10-28

Status: research only. No new backend beyond LiteRT-LM is wired today.

## What we can run today

- `LFM2.5 1.2B Instruct` stays the only LiquidAI model in `MODEL_CATALOG` (`.litertlm`, LiteRT-LM).
- `LFM3-Omni-2B_int8` (`LFM3-Omni-2B_int8.litertlm`, 3.23 GB, commit `5afeef5fe4b49e13eaefd7b6957fe856d763db6d`, SHA-256 `959617973a7ddcb3d887fa0d9891d79556a266490d5c432b02a18ffcac3124ed`) is a community
  LiteRT conversion and is suitable for the existing LiteRT-LM chat path for text, 512x512 picture and 30 s audio input. It is added to the catalog as an optional/experimental model.
  Context is 4,096 tokens. GPU decode is about 27 tok/s on a Galaxy S26.
- Official LFM3 text/audio/omni repos publish GGUF builds (LiquidAI/*-GGUF: Q4_K_M around 1.1 GB, BF16 around 3.4 GB). These require a `llama.cpp` backend, which Pots does not ship yet.

## Voice-to-voice

- LFM3-Omni can answer in speech, but only through extra LiteRT graphs: depth decoder, frame embedding, codec decoder (BF16), plus the Python runner in the same repo. Pots today uses platform speech recognition as STT and the native/Piper/Pots TTS stack as TTS, so the simplest honest path is to keep STT/TTS platform-side and use the `.litertlm` file as the multimodal brain for text + image + audio input.
- The Omni file must be used with one Engine per conversation (running conv-state bug) and answers are greedy unless the app sets a sampler. Chat history should therefore create a new Engine per user turn or document this limitation.

## Why not LFM3-1.7B text yet

- There is no official LiteRT-LM bundle. The only public conversion is community-maintained (`cheesefelys/LFM3-1.7B-litertlm`, 889 MB, 23 downloads) and has not been validated against Pots' tool/voice requirements. Link only; do not ship as a download until verified.
- `LFM3-Audio-2B` is GGUF-only officially (`LiquidAI/LFM3-Audio-2B-GGUF`); its LiteRT counterpart is not published. Until a llama.cpp runtime is integrated, it cannot be used for on-device inference in Pots.

## Additional finding: LiquidAI FP8

- `LiquidAI/LFM3-1.7B-FP8` ships a 2.2 GB FP8 safetensors snapshot but no GGUF or LiteRT bundle. It is useful as a reference weight format, not as a directly runnable on-device option in Pots today.

## Summary of useful links

- LFM3-1.7B: https://huggingface.co/LiquidAI/LFM3-1.7B
- LFM3-1.7B GGUF: https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF
- LFM3-Audio-2B: https://huggingface.co/LiquidAI/LFM3-Audio-2B
- LFM3-Audio-2B GGUF: https://huggingface.co/LiquidAI/LFM3-Audio-2B-GGUF
- LFM3-Omni-2B: https://huggingface.co/LiquidAI/LFM3-Omni-2B
- LFM3-Omni-2B GGUF: https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF
- LFM3-Omni-2B LiteRT: https://huggingface.co/litert-community/LFM3-Omni-2B-LiteRT
- Community LFM3-1.7B LiteRT: https://huggingface.co/cheesefelys/LFM3-1.7B-litertlm

## Suggested next implementation steps

1. Keep liquid voices as optional catalog entries and mark them experimental.
2. Add a llama.cpp-backed local engine (GGUF) if true on-device audio/multimodal voice chat is needed; otherwise keep LiteRT-LM and map audio in/out through the platform STT/TTS in HandsFree.
3. If Omni is enabled, force one-conv-per-turn or an Engine reset before each request to avoid the conv-state carryover.
4. Add capability flags for `imageInput`, `audioInput`, `audioOutput` in `LocalModelCapabilities` before surfacing these models beyond text chat.
