<h1 align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="22%" alt="DFast icon" />
  <br />
  DFast
</h1>

<p align="center">
  <strong>A fast, private, open-source video and audio downloader for Android.</strong><br />
  Powered by yt-dlp · Material 3 · Android 7.0+
</p>

<p align="center">
  <a href="https://github.com/MenuM7D/dfast/releases/latest">
    <img src="https://img.shields.io/github/v/release/MenuM7D/dfast?style=for-the-badge&label=Download&color=FF5A36" alt="Latest release" />
  </a>
  <a href="https://github.com/MenuM7D/dfast/releases">
    <img src="https://img.shields.io/github/downloads/MenuM7D/dfast/total?style=for-the-badge&color=16121F" alt="Downloads" />
  </a>
  <a href="https://t.me/m7d_devvv">
    <img src="https://img.shields.io/badge/Telegram-m7d__devvv-FF5A36?style=for-the-badge&logo=telegram&logoColor=white" alt="Telegram" />
  </a>
</p>

<p align="center">
  <a href="https://github.com/MenuM7D/dfast">Repository</a>
  &nbsp;·&nbsp;
  <a href="https://github.com/MenuM7D/dfast/releases">Releases</a>
  &nbsp;·&nbsp;
  <a href="https://t.me/m7d_devvv">Telegram</a>
</p>

---

## Why DFast

DFast puts the full power of **yt-dlp** behind a clean Android interface. Paste a link or
search for one, pick the quality you want, and let the queue do the rest. Everything runs
on the device: no account, no tracking, no third-party servers, and no ads.

The app is built around the DFast identity — Signal Coral and Deep Ink, a geometric
wordmark and a download-arrow monogram — but the engineering underneath is the mature
yt-dlp toolchain, packaged so it just works on a phone.

## Highlights

- **1000+ sites** through yt-dlp, with the full format list exposed in the app.
- **Playlists** with per-item editing, a shared format, path or filename template.
- **Queue and schedule** downloads, run several at once, retry failed ones.
- **Audio or video**, with subtitle, chapter, thumbnail, metadata and SponsorBlock handling.
- **Cut and split** by timestamp or chapter before saving.
- **Cookies** for private or age-restricted content, plus incognito and quick-download modes.
- **Custom commands and templates**, editable and shareable, with a built-in terminal.
- **Share-sheet card** so a download can start without opening the app.
- **Material You** theming, five icon variants, light and dark.
- **Backup and restore** for settings, templates and history.

## Download and install

Every build is published on the [Releases page](https://github.com/MenuM7D/dfast/releases).

1. Download the APK for your device — `arm64-v8a` covers most phones, `universal` works anywhere.
2. Allow installation from your browser or file manager when Android asks.
3. Install and open DFast. The first launch walks you through storage permissions and the
   bundled runtimes.

Each release ships a `SHA256SUMS.txt`; check a download with:

```bash
sha256sum -c SHA256SUMS.txt
```

> **Coming from YTDLnis?** DFast uses the package name `dfast.app`, so it installs next to
> the old app instead of replacing it. Remove the old app manually once you have migrated
> your settings with a backup.

## Build from source

Requirements: JDK 17, Android SDK 36, and a device or emulator running Android 7.0+.

```bash
git clone https://github.com/MenuM7D/dfast.git
cd dfast/dfast
./gradlew assembleGithubDebug
```

The APK is written to `app/build/outputs/apk/github/debug/`. A signed release build uses
`keystore.properties` and `app/release-keystore.jks`, which are intentionally not committed;
GitHub Actions supplies them from repository secrets.

| Property | Value |
| --- | --- |
| Application ID | `dfast.app` |
| Minimum SDK | 24 (Android 7.0) |
| Target SDK | 36 |
| License | GNU GPL v3.0 |

## Integrate with other apps

DFast accepts `ACTION_SEND` intents, so automation tools can start a download without
opening the app.

| Extra | Values | Notes |
| --- | --- | --- |
| `android.intent.extra.TEXT` | URL, playlist or search query | Required |
| `TYPE` | `audio`, `video`, `command` | Optional |
| `BACKGROUND` | `true`, `false` | Skips the download card when `true` |

Example Tasker action: **Send Intent** → action `android.intent.action.SEND`, mime type
`text/*`, extra `android.intent.extra.TEXT` set to the URL.

## Project

- Source code, issues and releases: [github.com/MenuM7D/dfast](https://github.com/MenuM7D/dfast)
- Community and announcements: [t.me/m7d_devvv](https://t.me/m7d_devvv)
- Security and privacy: [SECURITY.md](SECURITY.md)
- Contributing: [CONTRIBUTING.MD](CONTRIBUTING.MD)
- Changelog: [CHANGELOG.md](CHANGELOG.md)

## Credits and license

DFast is a fork of **YTDLnis**, created by Denis Çerri, and keeps its GPLv3 license. It
stands on the work of the yt-dlp project and the youtubedl-android port; thank you to
everyone who maintains them.

DFast is free software: you can redistribute it and/or modify it under the terms of the
[GNU General Public License v3.0](LICENSE). The DFast name and artwork belong to this
project and may not be used to label modified builds.
