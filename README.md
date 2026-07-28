# masumi

masumi is an Android manga translation app. Import a chapter and it automatically detects and recognizes text, translates it, removes the source text, typesets the translation, checks the result, and saves the finished pages to a persistent manga library.

## Features

- Imports JPEG, PNG, and WebP manga pages
- Runs text detection, OCR, cleanup, and typesetting locally
- Translates Japanese text through an OpenAI-compatible API
- Maintains a glossary for consistent names, honorifics, and punctuation
- Supports resumable background processing and multi-manga scheduling
- Caches local models for reuse across projects
- Saves completed chapters automatically and preserves reading progress

## Usage

1. Choose a persistent folder for the manga library on first launch.
2. Configure a translation provider in the app.
3. Import a folder containing manga pages.
4. Wait for the pipeline to finish; the completed chapter is saved automatically.

Image processing stays on the device. Recognized text that requires translation is sent to the configured translation provider. Provider credentials are stored in the app's private data.

## Build

Requires JDK 17, Android SDK 36, Android NDK 28.2, CMake 3.22, and an `arm64-v8a` Android device.

```bash
git submodule update --init --recursive
./gradlew :pipeline-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
tools/install-debug-preserving-data.sh
```

## Project structure

- `app`: Android UI, local inference, task scheduling, and the complete processing pipeline
- `pipeline-core`: project models, state management, processing rules, and artifact contracts

## License

[GPL-3.0](LICENSE)
