<div align="center">

<img src="docs/brand/qingyu-icon-master.png" width="96" alt="Qingyu IME icon" />

# Qingyu IME

**A local-first Chinese input method for Android · powered by Rime**

T9 · Full Pinyin · Shuangpin · L17 · Five-stroke · Handwriting · Offline voice

[简体中文](README.md) · [English](README.en.md)

[![Build](https://github.com/xiaoyuan0459/qingyu-ime/actions/workflows/build.yml/badge.svg)](https://github.com/xiaoyuan0459/qingyu-ime/actions/workflows/build.yml)
[![License: GPL-3.0-only](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](LICENSE)

</div>

> **Status: Pre-Alpha (`0.1.1-dev`).** The input pipeline runs end-to-end on emulators. arm64 device and OEM ROM coverage is still being filled in. There is no release recommended for daily use yet; to try it now, grab the [v0.1.1-dev preview build](https://github.com/xiaoyuan0459/qingyu-ime/releases) (debug-signed). See the [roadmap](ROADMAP.md) and [technical debt list](docs/TECHNICAL_DEBT.md).

---

## Why Qingyu

- **Input never leaves the device.** No cloud IME, account, sync, ads, or analytics SDK. Candidates, dictionaries and handwriting inference all run on-device; the core input path needs no network.
- **Handwriting that covers rare characters.** A self-trained closed-set classifier with **27,664 character classes**, 8.4 MB int8 weights, running on ncnn. On an unseen-writing-style test set: top-1 88.8%, top-5 97.1%.
- **Swappable dictionaries with rollback.** Ships a pinned rime-ice build. Rime dictionary packs can be imported with manifest, path, size and SHA-256 validation, atomic activation, and fallback to the previous version on failure.
- **Full scheme coverage.** T9, full pinyin, Flypy / Natural / ABC / MS / Sogou / Ziguang shuangpin, L17, five-stroke, English, and mixed Chinese-English input.
- **Five permissions.** The core path uses only `VIBRATE`, `RECORD_AUDIO` and `POST_NOTIFICATIONS`; `INTERNET` / `ACCESS_NETWORK_STATE` exist solely for the optional offline-voice download and handwriting sample upload that the user turns on.
- **Auditable source.** GPL-3.0-only, with the native engine built from source. Every dependency is pinned by commit and hash; no prebuilt binaries are trusted.
- **Self-checking input path.** The debug build ships 15 engine self-checks (typing, commit, paging, simplified/traditional, five-stroke, L17, mixed Chinese-English, association, pack switch and rollback), so "are the candidates right" is a repeatable assertion instead of eyeballing.

## Features

| Area | What is implemented |
| --- | --- |
| Chinese input | T9, full pinyin, six shuangpin layouts, L17, five-stroke, simplified/traditional toggle |
| Dictionaries | Pinned rime-ice default; local Rime pack import with validation, atomic install and rollback; optional rime-frost pack distributed separately |
| English & symbols | Full English keyboard, number row, symbols, Emoji, kaomoji, stylized text, paired symbols |
| Handwriting | Self-trained single-character model, 27,664 classes, int8 8.4 MB, fully on-device; training data is synthetic from OFL fonts, no CASIA-derived data |
| Voice | Long-press space; local Vosk small model first, falls back to Android 12+ on-device recognition |
| Keyboard | Dark theme, custom themes, size adjustment, one-handed, floating, customizable toolbar with reordering |
| Input aids | Clipboard history, phrases, Chinese association, text editing, side symbols, user data backup and restore |
| Data & privacy | No cloud input; separate switches for clipboard history and handwriting samples — see the [privacy and data inventory](imesdk/docs/隐私与数据清单.md) (Chinese) |

## Tech stack

| Layer | Choice | Notes |
| --- | --- | --- |
| Language | Kotlin 2.2.20 | 307 files, ~38,400 lines; View system with XML / Splitties DSL, no Compose |
| Input engine | librime 1.11.2 (built from source) | Pinned commit and recursive submodules, with local patches; exposed over JNI as `RimeSession` / `RimeEngine` |
| Handwriting | ncnn 20260526 + self-trained model | 64×64 single-channel input, 27,664-class output, int8 quantized; ONNX → ncnn via `pnnx` |
| Speech | Vosk Android 0.3.47 | Chinese / English small models downloaded on first opt-in; recognition stays on-device |
| Dictionaries | rime-ice, rime-stroke, pinyin-data, OpenCC | Packs compiled by `tools/build-rime-pack.py` from pinned upstream revisions |
| App framework | AndroidX, Material 1.12, Room 2.6.1, Coroutines 1.9, kotlinx.serialization | Persistence, async work and settings |
| Build | AGP 9.4.0 / Gradle 9.6.0 / JDK 21 / NDK 30 / CMake 3.22.1 | Native deps prepared by `tools/bootstrap-native-deps.py` from a lock file |
| Platform | minSdk 23, compile / target SDK 37 | Debug: arm64-v8a + x86_64. Release: arm64-v8a only |
| CI | GitHub Actions | Builds both ABIs, runs lint, validates dependencies and licenses, exports dependency SHA-256 manifests |

The native side is only ~1,440 lines of C/C++ (`imesdk/src/main/cpp`): JNI bridge, stroke preprocessing, and librime sessions. No upstream fork is maintained; the few behaviour changes (such as correction segmentation weight) are replayed as build-time patch scripts. Layering, input data flow and threading are described in [wiki: Architecture](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Architecture) and [wiki: Input-Pipeline](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Input-Pipeline).

## Performance and memory

Numbers below come from an **Android 15 (API 35) x86_64 emulator, debug build, software rendering**, collected with `tools/measure-ime-memory.sh`, `dumpsys gfxinfo` and `dumpsys meminfo`. Methodology, change list and open items: [wiki: Performance](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Performance). They show direction and catch regressions; **they are not a device promise** — the arm64 device baseline is still tracked as [DEBT-DEVICE-01](docs/TECHNICAL_DEBT.md), and the release build has not been measured separately.

| Metric | After | Before | Scope |
| --- | --- | --- | --- |
| Per-key handling (mean) | **10.2 ms** | 12.7 ms | Key event in → candidates / bubble / candidate bar refreshed |
| Frame time P50 | **20 ms** | 31 ms | `dumpsys gfxinfo`, continuous typing |
| Frame time P90 | **48 ms** | 53 ms | Same |
| Steady-state PSS | **≈100 MB** | 104.6 MB | `dumpsys meminfo`, settings screen open |
| Native Heap PSS | **21.9 MB** | 25.0 MB | librime + ncnn allocations |
| First-screen PSS | ≈78 MB | — | Before the dictionary is mapped |

Steady-state breakdown (debug): Native Heap ~22 MB, dictionary mmap ~16.6 MB, anonymous librime pages ~17 MB, Dalvik + ART ~20 MB. The footprint is dominated by **dictionary table mmap** and **librime's internal structures**, not the UI. The handwriting model is lazily loaded and released in `onTrimMemory`, so it is no longer resident. That figure is high for an IME; the next steps — mapping only the active schemas and reducing librime allocation churn — are not done yet.

Package and assets:

| Item | Size | Notes |
| --- | --- | --- |
| Debug APK | 74 MB | arm64-v8a + x86_64, x86_64 unstripped |
| Pre-release `v0.1.1-dev` | 74 MB | The same debug build, attached to the GitHub Release with `SHA256SUMS.txt` |
| Release APK | Not published | arm64-v8a only, substantially smaller |
| Bundled dictionary `rime-base.zip` | 36 MB | ~83 MB unpacked, one rollback version retained |
| Handwriting model (int8) | 8.4 MB | 27,664 classes, bundled |
| Offline speech model | ~40–42 MB | Not bundled; downloaded on first opt-in |

Hot-path work targets operations that run on every keypress and produce no new result: candidate-bar shape caching, diff-based candidate refresh, keycap background reuse with dirty-region redraw, T9 side-column adapter reuse, composing-bubble width deduplication, and a single engine context read per key. None of it changes candidate order or input semantics.

## Install

To skip building, download `app-offline-debug.apk` from [Releases](https://github.com/xiaoyuan0459/qingyu-ime/releases) — a pre-release, debug-signed build with `SHA256SUMS.txt` on the same page.

Or install your own build:

```sh
adb install -r app/build/outputs/apk/offline/debug/app-offline-debug.apk
```

Then enable it:

1. Settings → System → Languages & input → On-screen keyboard → Manage on-screen keyboards, enable "Qingyu IME".
2. Select "Qingyu IME" as the current keyboard.
3. On an emulator with a physical keyboard attached, enable "Show soft keyboard with hardware keyboard" in Qingyu settings.

Debug package name: `com.qingyu.inputmethod.offline.debug`.

## Build from source

Requires JDK 21, Python 3.11+, Git, Android SDK, NDK 30.0.14904198 and CMake 3.22.1+. The exact matrix is in the [development guide](docs/DEVELOPMENT.md).

Prepare the pinned native dependencies first (network required):

```sh
python tools/bootstrap-native-deps.py
python tools/bootstrap-native-deps.py --check
```

Windows:

```powershell
.\gradlew.bat :app:assembleOfflineDebug
```

Linux / macOS:

```sh
./gradlew :app:assembleOfflineDebug
```

Output: `app/build/outputs/apk/offline/debug/app-offline-debug.apk`.

Checks:

```powershell
.\gradlew.bat :imesdk:testOfflineDebugUnitTest :app:lintOfflineDebug
```

Toolchain details, native build pitfalls and signing: [wiki: Build](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Build). Device scenarios and evidence format: [wiki: Testing](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Testing).

## Permissions and data

The core input path has no network access and no cloud input, accounts, sync, ads or telemetry. Network permissions exist only for optional features the user turns on.

| Permission | Purpose |
| --- | --- |
| `VIBRATE` | Key haptics |
| `RECORD_AUDIO` | Voice input; requested on first long-press |
| `POST_NOTIFICATIONS` | Reminder when first-run setup is incomplete; skipped if denied |
| `INTERNET` / `ACCESS_NETWORK_STATE` | Downloading the Vosk offline model; uploading handwriting samples only when a builder configures an endpoint and the user explicitly consents |

- **Voice**: enabling "Offline voice" downloads a ~40–42 MB Vosk model from alphacephei.com. Recognition is local; audio and transcripts are not persisted. Without it, Android 12+ on-device recognition is used. If enabled but the model is missing, the app prompts for a download and does not record.
- **Handwriting**: the default model runs locally. Sample saving is off by default and only writes on explicit character selection. Auto-upload is off by default, requires separate consent, defaults to Wi-Fi only, and sends strokes, the selected character and the date — no account, device identifier, other input or screenshots.
- **Clipboard**: history is on by default, keeps the latest 50 text entries, and can be disabled. It is the single largest piece of content the app stores, so it is called out separately in the privacy policy and can be cleared in one tap.

Full fields, storage locations and retention: [privacy and data inventory](imesdk/docs/隐私与数据清单.md) (Chinese), mirrored in [wiki: Data-and-Privacy](https://github.com/xiaoyuan0459/qingyu-ime/wiki/Data-and-Privacy).

## Known limitations

Open source is not the same as finished. These are the explicitly unfinished parts; acceptance criteria live in the [technical debt list](docs/TECHNICAL_DEBT.md):

- **Device coverage**: almost all conclusions come from an x86_64 emulator; the arm64 and OEM ROM matrix is still a release blocker.
- **No release build**: only debug APKs are produced; signing, SBOM and the final license archive are not closed out.
- **Association latency**: the first lookup for a new final character takes ~250–400 ms (26 prefix probes) and is instant once cached; polyphonic characters are looked up by their first reading.
- **The 6th five-stroke key (wildcard) does nothing**: librime has no wildcard support, so its purpose is undecided.
- **Single-stroke confusion**: the model ranks a plain horizontal stroke as `…` first and `一` second — a training-distribution issue.

## Project layout

| Path | Contents |
| --- | --- |
| `app/` | Application shell, manifest, packaging and signing |
| `imesdk/` | IME service, keyboard UI, input pipeline, data and native engine |
| `imesdk/src/main/cpp/` | JNI bridge, stroke preprocessing, librime session |
| `imesdk/src/main/assets/` | Dictionary packs, handwriting model, OpenCC data and license material |
| `tools/` | Native dependency setup, dictionary packaging, model conversion and checks |
| `server/handwriting-upload/` | Optional relay for handwriting samples; not part of the APK build |
| `docs/` | Development guide, ADRs, technical debt, brand assets |

Dependencies point one way: `app` → `imesdk`. UI, input orchestration, data and engine live in `imesdk`; the app shell only handles packaging and entry points.

## How this was built

Implementation in this project is **primarily written with AI coding assistants**: OpenAI Codex, Claude models, Qoder and WorkBuddy have contributed code, refactors, test coverage and documentation. The maintainer owns product definition, architecture decisions, interaction design, code review and device acceptance. Every commit is reviewed by a human before it reaches `master`.

AI assistance changes no licensing terms: the project remains GPL-3.0-only, and attribution for third-party code, dictionaries, models and images still applies. We state this because readers deserve to know how the code was produced — and because it is genuinely where much of the velocity comes from.

Engineering constraints, module boundaries and verification expectations are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Sources and licensing

Licensed under [GPL-3.0-only](LICENSE). Third-party code, dictionaries, models and images keep their own licenses; the root license does not replace attribution.

**Actually used**:

| Project | Role | License |
| --- | --- | --- |
| [YuyanIme](https://github.com/gurecn/YuyanIme) | Starting point for the Android IME framework, keyboard views and settings structure; heavily reworked here | BSD-3-Clause |
| [librime](https://github.com/rime/librime) 1.11.2 | Rime input engine, built from source | BSD-3-Clause |
| [OpenCC](https://github.com/BYVoid/OpenCC) | Simplified/traditional and Emoji conversion data | Apache-2.0 |
| [rime-ice](https://github.com/iDvel/rime-ice) | Default bundled dictionary | GPL-3.0-only |
| [rime-stroke](https://github.com/rime/rime-stroke) | Five-stroke dictionary | LGPL-3.0-or-later |
| [pinyin-data](https://github.com/mozillazg/pinyin-data) | Hanzi pinyin table | MIT |
| [ncnn](https://github.com/Tencent/ncnn) | Handwriting inference runtime | BSD-3-Clause |
| [Vosk](https://github.com/alphacep/vosk-api) | Local offline speech recognition | Apache-2.0 |
| [rime-frost](https://github.com/gaboolic/rime-frost) | Optional dictionary pack, distributed separately | GPL-3.0-or-later |

**Input methods consulted** (behaviour and geometry only; no code, images, icons, skins, dictionaries, models or copy were reproduced):

| Reference | What was studied | Boundary |
| --- | --- | --- |
| Meizu Sogou IME 10.32.39.202412161509 | Candidate bar shape, toolbox and clipboard layout, symbol / emoji panels, settings hierarchy, text editing, T9 word segmentation and the handwriting side column | Each item reimplemented from scratch; the reference APK and screenshots stay local and are never distributed with the source or APK |
| [YuyanIme](https://github.com/gurecn/YuyanIme) (the upstream this project started from) | Overall skeleton of the Android IME framework, keyboard views and settings | Attribution kept under BSD-3-Clause; native layer, input pipeline and handwriting were rewritten |
| Trime (同文) | How the early JNI binding was split into methods | The binding was rewritten semantically as `RimeSession`; no old binding or naming is retained |

**Explicitly not consulted**: no cloud input, suggestion cloud, account system or ad styling; no input path that only works fully online.

The full used / referenced / removed / unverified inventory is in [CREDITS.md](CREDITS.md); license status and open verification items are in [LICENSING.md](LICENSING.md).

## Contributing

Read [CONTRIBUTING.md](CONTRIBUTING.md) and use [Issues](https://github.com/xiaoyuan0459/qingyu-ime/issues) for bugs and design discussions. Report vulnerabilities privately per [SECURITY.md](SECURITY.md); all discussion follows the [code of conduct](CODE_OF_CONDUCT.md).

- [Technical wiki](https://github.com/xiaoyuan0459/qingyu-ime/wiki) — architecture, input pipeline, build, performance and memory, data and privacy, handwriting model, UI semantics, feature coverage, testing, release
- [Development guide](docs/DEVELOPMENT.md) — toolchain, native dependencies, build and signing
- [ADRs](docs/adr/) — background and consequences of key decisions
- [Technical debt](docs/TECHNICAL_DEBT.md) — known gaps and release blockers
