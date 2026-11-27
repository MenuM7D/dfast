# New source: guided steps (option J) plan

Goal: replace the bottom sheet for creating/editing a watch-list source with a full-screen guided flow in DFast's own style. No saved field, scheduling function or download option is lost. Nothing starts until the user gives his go.

## Principles
- Additive and isolated. The old ObserveSourcesBottomSheetDialog stays in the code as the fallback until the new flow is device-confirmed. A hidden switch in Developer mode ("Classic source sheet", default off) opens it.
- One data path. The new screens read and write the same ObserveSourcesItem and its DownloadItem template, saved by the same repository and view model calls as the sheet. No new database fields, no migration.
- Shared download screens (DownloadVideo/Audio/Command fragments, populateFormatCard, FormatSelectionBottomSheetDialog) are not edited. The new flow only hosts or calls them.
- Tabler icons only, no emoji, IBM Plex Sans Arabic, theme colors (colorPrimary, orbitGlass). No chips row, no tab bar, no filled text fields.
- No clipped text: every label and summary wraps (no maxLines=1 without a full-text fallback), rows grow with content, buttons are full width and wrap.
- RTL: start/end attributes only, no left/right; chevrons mirror (autoMirrored vectors); Back/Next swap sides automatically.

## Flow (3 steps, step track on top, Back/Next at the bottom)
Step 1 "Source": link (paste button, validates), name (optional, defaults to the channel/playlist name), Next disabled until the link is valid. Edit mode opens with values filled.
Step 2 "What to download": big radio cards Video / Audio only / Custom command (maps to DownloadType video/audio/command). Under the card:
- Video: quality strip (Best, then the real heights offered by the app's format list: 2160p, 1440p, 1080p, 720p, 480p, 360p...), container row (default, mp4, webm, mkv, mov, avi, flv, gif from video_containers).
- Audio: container row (default, mp3, m4a, aac, alac, flac, opus, wav, vorbis from audio_containers), bitrate/quality as in the audio format list.
- Command: template picker (existing command templates) plus the command text field.
- Honest caption under quality: "Best available is picked for each video" only when Best is selected.
- Save folder: friendly card (folder name + "Change", full path wrapped under it; uses the same folder picker intent and FileUtil.formatPath).
- "Extras" row opens a full page listing the real toggles the sheet has today: subtitles, chapters, thumbnail embed, SponsorBlock, ad/sponsor skipping, title template, extra commands, audio adjust, recode video, cut/crop where they apply. Each toggle writes the same DownloadItem field.
- "Use for all new sources" switch (same default-saving the sheet does today, only if it exists in the sheet code; verified in task 1).
Step 3 "When": every N + unit (minute, hour, day, week, month from ObserveSourcesRepository.EveryCategory), weekday picker when week, day-of-month when month, start date/time, end (never / on date / after N runs), then three switches: only new uploads, retry missing downloads, sync with source. Summary sentence at the top ("Every day, starting today, never ends"). Save button here.

## Tasks
1. Audit (read only): list every control in observe_sources_bottom_sheet.xml and the three download fragments, with the field each writes. Output a table in this file. Gate: every control has a home in steps 1 to 3 or Extras.
2. Extract a small pure Kotlin helper (ObserveSourceDraft) that holds the form state and converts to/from ObserveSourcesItem. Unit-test the conversion against sample items (round trip equals).
3. Build ObserveSourceWizardFragment + three step layouts + step track + strings (new strings in all 28 locales; reuse existing strings first). Navigation entry replaces the sheet destination in the observe list fragment and the details sheet's Edit.
4. Wire step 2 to the existing format and folder pickers and the Extras page; wire step 3 to the same scheduling code (move shared parsing into the helper, keep the sheet using its own copy untouched).
5. Structural tests: all ids present, no maxLines=1 or singleLine, start/end only, Tabler marker on new drawables, every Extras toggle writes a field, 28-locale check for new strings.
6. Build on CI, then a rendered check: render the XML step layouts with Arabic and English text at large font scale in the workspace (layout dump + screenshot of an emulator-free renderer if available; otherwise the user's device is the only real proof and is labeled so).
7. Beta.9 pre-release (arm64 only, SHA256SUMS, same cert), classic fallback switch kept. Official release only on his explicit go.

## Risks and what stays unverified
- Real quality list depends on the source (formats come from yt-dlp per video). For watch-list sources use the generic height ladder and always keep Best; this is a behavior to confirm against the sheet code in task 1.
- Hosting existing fragments vs reimplementing pickers: prefer calling the existing pickers.
- Not device-tested until the user installs it; rendering of steps is reasoned from XML plus the CI structural tests.
