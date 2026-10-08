# Plain Voice Reader for Android

A deliberately simple Android text-to-speech reader. It treats documents as paragraphs rather than trying to reproduce their visual page layout.

## Version 0.1 features

- EPUB 2/3 text extraction
- PDF text extraction for PDFs that already contain selectable text
- MOBI/PRC text extraction for unencrypted MOBI using no compression or PalmDOC compression
- TXT/Markdown text files
- Android system Text-to-Speech, no API key required
- 0.5x to 2.5x speech rate
- Play/pause, previous paragraph, next paragraph
- Tap any paragraph to move the reading position
- Current paragraph highlighting and automatic scrolling
- Remembers the last paragraph for each file
- Uses Android's Storage Access Framework, so no broad storage permission is requested

## Known first-version limits

- Scanned/image-only PDFs need OCR and are not readable yet.
- DRM-protected books are not supported and the app does not attempt to bypass DRM.
- MOBI HUFF/CDIC compression is not supported in v0.1. PalmDOC MOBI files are supported.
- Pause stops the Android TTS utterance. Pressing Play again restarts the current paragraph rather than resuming mid-sentence.
- Background/lock-screen media controls are not yet implemented.

## Build an APK

Open this folder in Android Studio. Recommended environment:

- JDK 17 or newer
- Android Studio with Android SDK 35 installed

Let Gradle sync and download dependencies, then select:

`Build > Build App Bundles or APKs > Build APKs`

The debug APK will normally be created under:

`app/build/outputs/apk/debug/app-debug.apk`

Copy the APK to your Android phone, open it, and allow installation from that source if Android asks.

## Why PDFBox is included

Android does not provide a built-in API for extracting selectable text from arbitrary PDFs. This project uses PdfBox-Android for that job.

## MOBI note

The app contains a small text-only PalmDOC/MOBI parser. It is intentionally limited to the common unencrypted PalmDOC variants. It does not decrypt Kindle books.

## Automatic APK build with GitHub

A GitHub Actions workflow is included at `.github/workflows/build-apk.yml`. If the project is placed in a GitHub repository, every push to `main` can build a debug APK automatically. The APK is then available as a workflow artifact named `PlainVoiceReader-debug-apk`.

## Library and voices

Add Book copies each supported document into the app's private storage, so moving or deleting the original does not break the library. The home screen shows the most recently read book, progress, last-read date, and EPUB cover/title/author when present. The reader supports section jumps, bookmarks, and speed steps of 0.05× from 1× to 3×. EPUB headings and repeated PDF margin headers or page numbers are skipped during speech. PDF section detection is heuristic and depends on the source text.

The Voice screen lists offline voices exposed by the installed Android text-to-speech engine. Available voices and their quality vary by device. For free offline neural speech on Android 11+, [VoxSherpa TTS](https://github.com/CodeBySonu95/VoxSherpa-TTS) is a GPLv3 Android speech engine that offers Kokoro and Piper models. Install it, download a Kokoro model there, select VoxSherpa as the default Android speech engine, and reopen this app to choose one of its voices. Kokoro model weights are Apache-licensed. This app does not bundle a model, and voice quality and speed depend on the phone. Background media controls are not yet provided.
