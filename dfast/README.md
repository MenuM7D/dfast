# DFast — Android module

This directory contains the Android application. For the project overview, features,
downloads and privacy policy, read the [project README](../README.md).

## Build

```bash
./gradlew assembleGithubDebug     # debug APK
./gradlew assembleGithubRelease   # signed release APK
```

Outputs land in `app/build/outputs/apk/github/<variant>/`.

- JDK 17, Android SDK 36, Android 7.0+ (minSdk 24).
- Application ID: `dfast.app`.
- Release signing reads `keystore.properties` and `app/release-keystore.jks`; both are
  local-only files supplied by the CI secrets.
- The Room schema lives in `app/schemas/dfast.app.database.DBManager` and
  `app/schemas/dfast.app.data.DBManager`.

## Links

- Repository: [github.com/MenuM7D/dfast](https://github.com/MenuM7D/dfast)
- Telegram: [t.me/m7d_devvv](https://t.me/m7d_devvv)

Licensed under the GNU GPL v3.0 — see [LICENSE](LICENSE).
