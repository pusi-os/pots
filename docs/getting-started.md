# Getting Started

## Installation

### App Stores

- [**App Store**](https://apps.apple.com/us/app/pots-ai/id6758148023) (iOS)
- [**Google Play**](https://play.google.com/store/apps/details?id=com.pusios.pots) (Android)
- [**F-Droid**](https://f-droid.org/en/packages/com.pusios.pots/) (Android)

### Web

Try Pots directly in your browser at [pusi-os.github.io/pots/app](https://pusi-os.github.io/pots/app/).

### Homebrew (macOS)

```bash
brew install --cask pusi-os/tap/pots
```

### AUR (Arch Linux)

```bash
yay -S pots-bin
```

### Winget (Windows)

```bash
winget install pusi-os.Pots
```

### Direct Downloads

| Platform | Format | Download |
|----------|--------|----------|
| Android | APK | [GitHub Releases](https://github.com/pusi-os/pots/releases) |
| macOS | DMG | [GitHub Releases](https://github.com/pusi-os/pots/releases) |
| Windows | MSI | [GitHub Releases](https://github.com/pusi-os/pots/releases) |
| Linux | DEB | [GitHub Releases](https://github.com/pusi-os/pots/releases) |
| Linux | RPM | [GitHub Releases](https://github.com/pusi-os/pots/releases) |
| Linux | AppImage | [GitHub Releases](https://github.com/pusi-os/pots/releases) |
| Linux | Flatpak | [GitHub Releases](https://github.com/pusi-os/pots/releases) |
| Linux | Tarball | [GitHub Releases](https://github.com/pusi-os/pots/releases) |

## First Steps

1. Launch Pots — you'll see the chat screen with an animated welcome
2. Start chatting immediately using the **Free** tier (no API key needed)
3. For better models, open **Settings** and add a service (e.g. OpenAI, Gemini, DeepSeek)
4. Enter your API key — Pots validates the connection and loads available models automatically
5. Prefer to stay offline? On Android, iOS and desktop, add the **Local Model** service and download an on-device model — see [On-Device Inference](features/on-device-inference.md)

## Adding a Service

1. Open Settings
2. Tap **Add Service** and pick a provider
3. Paste your API key
4. Select a model from the dropdown
5. Drag services to reorder — the first one is your primary, the rest are fallbacks

See [Multi-Service](features/multi-service.md) for the full details on providers and fallback behavior.
