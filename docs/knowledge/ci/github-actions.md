# GitHub Actions build research (Pots, no iOS)

Status: research only.
Last verified: 2026-10-28

## Goal

Use GitHub-hosted runners plus an optional desktop self-hosted runner to build Linux, Windows and Android artifacts in parallel. iOS remains excluded for now.

## Runners

- Windows: `windows-latest`
- Linux: `ubuntu-latest`
- Android: `ubuntu-latest` is fine, but JDK/Android SDK images are ~6 GB
- For signed release, keep fastlane-keystore secrets

## Required setup on all runners

```yaml
- uses: actions/setup-java@v4
  with:
    java-version: '21'
    distribution: temurin
- uses: gradle/actions/setup-gradle@v5
```

- Cache Gradle user home and Kotlin incrementals.

## Secret keys mapping

```yaml
- name: expose free-tier key
  run: |
    echo "pots.free.apiKey=${{ secrets.POTS_FREE_TIER_API_KEY }}" >> local.properties
- name: configure signing
  run: |
    echo "KEYSTORE_FILE=${{ runner.temp }}/release.jks" >> $GITHUB_ENV
    ...
```

## Example Android job

```yaml
android:
  runs-on: ubuntu-latest
  steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-java@v4
  - uses: gradle/actions/setup-gradle@v5
  - run: ./gradlew :androidApp:assembleFossRelease
  - uses: actions/upload-artifact@v4
    with:
      name: android-apk
      path: androidApp/build/outputs/apk/foss/release/*.apk
```

## Example Linux desktop job

```yaml
linux:
  runs-on: ubuntu-latest
  steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-java@v4
  - uses: gradle/actions/setup-gradle@v5
  - run: ./gradlew :composeApp:createDistributable
  - uses: actions/upload-artifact@v4
    with:
      name: desktop-linux
      path: composeApp/build/compose/binaries/main/app/Pots
```

## Example Windows desktop job

```yaml
windows:
  runs-on: windows-latest
  steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-java@v4
  - uses: gradle/actions/setup-gradle@v5
  - run: ./gradlew :composeApp:createDistributable
  - uses: actions/upload-artifact@v4
    with:
      name: desktop-windows
      path: composeApp/build/compose/binaries/main/app/Pots
```

## Release process

1. On `push: tags: v*` run all build jobs plus Paparazzi goldens:
   - `./gradlew :screenshotTests:updateScreenshots` must be committed before PR
   - `./gradlew :screenshotTests:compileDebugUnitTestSources` check
2. Use `actions/upload-artifact` for each platform and `gh release create --attach`.
3. Publish Android APK to the dashboard with `scripts/publish-apk.sh` from a self-hosted runner (or via SSH step).
4. IBM ProGuard/R8 desktop rules and BouncyCastle restoration steps must be validated, so always run a release smoke check before a tag.

## Known pitfalls

- Android host test and instrumented tests require either simulator build or instrumentation runner.
- Desktop screenshot goldens depend on JDK 21.
- `composeApp:desktopTest` includes Paparazzi checks and may fail on JDK 17.
- The `pots.free.apiKey` is git-ignored (`local.properties`), so CI must materialize it from secrets.
- GitHub-hosted runners are ephemeral; signing keys cannot live on disk without secret handling.
- Large native loads may exceed default 6-hour/per-job limits on heavy runs.

## Suggested next steps

- Add `.github/workflows/ci.yml` with matrix: `[ubuntu-latest, windows-latest]`, Android separate
- Add a manual `workflow_dispatch` for dash publishing
- Add artifact retention (`7 days`) and size budget checks
