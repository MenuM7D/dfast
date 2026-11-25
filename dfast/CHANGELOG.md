# DFast changelog

## 1.0.4

- The app opens again. `activity_main.xml` declared its navigation host as
  `androidx.fragment.app.NavHostFragment`, a class that does not exist, so
  `MainActivity` threw while inflating the layout and closed right after the
  splash screen. It now names `androidx.navigation.fragment.NavHostFragment`,
  which is what the rest of the app's layouts already used.
- The launcher icon is one coherent mark again. Night mode was resolving
  `icon_bg` to the inherited orange `#FF5A36`, the foreground vector's group
  matrix shifted the chevron up and to the left, and the pre-adaptive PNG
  mipmaps were still the old artwork. All three now render the indigo Flux mark,
  centred inside the adaptive safe zone.
- No feature changes.
- Release APKs are named `DFast-1.0.4-…`.

## 1.1.0 — Flux

- A complete new interface, not a restyle of the inherited one: a floating dock in
  place of the standard bar, a hero search field on Home, cinematic 16:9 media cards,
  a two-column tile hub, and a rebuilt welcome flow.
- New DFast mark, wordmark and adaptive launcher icon in indigo and aqua.
- Arabic text no longer clips. The type scale carried letter spacing, which breaks
  cursive joining and widens a run until a short label is ellipsized; tracking is now
  zero everywhere, and the dock renders only the active label so nothing is squeezed.
- Empty states and card chips are sized in dp and allowed to wrap.
- The full feature set is unchanged: search, downloads, queue, terminal, cookies,
  command templates, sources, logs and settings.
- Release APKs are named `DFast-1.1.0-…`.

## 1.0.2

- The Ember design system: warm paper surfaces, deep ink type and a single ember
  accent, with Inter bundled as the interface typeface and a new corner and type scale
  inherited by every screen.
- A floating bottom bar, a re-cut home search and media cards, a grouped More hub
  (Library / Application) and a redesigned welcome screen.
- One consistent screen transition, and thumbnails that match the new geometry.
- Discord assets, remote translator avatars and the two yt-dlp documentation links are
  removed; every outbound destination is now the project repository or the Telegram
  channel.
- Release APKs are named `DFast-1.0.2-…`.

## 1.0.1

- New DFast wordmark in the home toolbar and navigation drawer: a Signal Coral `D`
  followed by the Deep Ink `Fast` cut, with a light variant for dark themes.
- The last YTDLnis references inside the app are gone. Telegram now points to the
  project channel, the Discord entry is removed, and the translators dialog opens the
  project repository.
- New project README, privacy policy and changelog; inherited third-party badges,
  sponsor links and translated upstream READMEs are removed.
- Lyrics plugin user agent reports DFast.
- Release APKs are named `DFast-1.0.1-…` and published with `SHA256SUMS.txt`.

## 1.0.0

- First DFast release: full rebrand of the app as **DFast** with the `dfast.app`
  package, new Signal Coral / Deep Ink icon set, adaptive and themed icons, and signed
  APKs for `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` and `universal`.

---

DFast is a fork of YTDLnis by Denis Çerri. Earlier history is available in the upstream
project; this changelog starts with the DFast identity.
