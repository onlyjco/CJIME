# CJIME

An open-source Chinese & English input method for Android based on [CyIME](https://github.com/Cyletix/CyIME), maintained by [onlyjco](https://github.com/onlyjco). Powered by the Rime engine, CJIME integrates Pinyin, Shuangpin, Wubi, Stroke, Cangjie, Bopomofo, handwriting, speech recognition, live captions, and an enhanced clipboard with favorites.

[简体中文](README.md)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
![Platform](https://img.shields.io/badge/Platform-Android%209.0%2B-3DDC84)
![Tech](https://img.shields.io/badge/Tech-Kotlin%20%7C%20Compose%20%7C%20Rime-7F52FF)

---

## Download & Install

Download the unified lightweight release APK from [Releases](https://github.com/onlyjco/CJIME/releases):

- **`CJIME-<version>-arm64-v8a.apk`** (recommended for most devices; built with R8 code & resource shrinking for a small footprint—optional offline voice/handwriting models can be downloaded on demand inside the app)

> **Note**: CJIME uses its own package ID (`com.onlyjco.cjime`), so it installs alongside upstream CyIME or Xime without conflicts. Requires Android 9.0 (API 28) or higher.

---

## Key Features

### Input Schemes & Lexicon

- **Chinese Pinyin**: 26-key, 9-key, and 14-key layouts with built-in `rime-ice` lexicon and `rime-wanxiang` language model for typo tolerance, abbreviated Pinyin, fuzzy Pinyin, and mixed Chinese/English typing. External custom schemes are supported with configurable Pinyin or Shuangpin glide decoding.
- **Shuangpin (Double Pinyin)**: 6 built-in layouts (Xiaohe, Ziranma, Microsoft, Zhineng ABC, Ziguang, Sogou) plus custom Shuangpin mapping.
- **Shape-based & Traditional Schemes**: Wubi 86 / 98, Stroke, Cangjie 5, Quick (Sucheng), and Bopomofo, with independent portrait/landscape layout memory for Shuangpin, table-based schemes, and English keyboards.
- **Fine-grained Lexicon Filtering**: Independent switches for rare characters, Japanese Kanji, Korean Hanja, and Cantonese characters, with obscure classical poetry phrases stripped from the main lexicon to keep daily candidates clean.
- **English Input**: Smart completion, glide typing, adaptive frequency, and common phrase shortcuts.

### Candidate Interaction & Gestures

- **Partial Candidate Editing**: Drag to select characters or syllables in the primary candidate for inline Pinyin/code editing without clearing the whole sentence—supported across 26-key Pinyin/Shuangpin, built-in 14-key Pinyin/Shuangpin, and custom single-letter layouts without merged keys.
- **Stroke Filtering**: Trigger stroke filtering from a spacebar swipe when candidates are collapsed, or filter by strokes directly inside the expanded candidate panel.
- **Bottom Lift Area with System-Level Elevation & Clipboard/Favorites**: The fixed keyboard bottom lift bar supports system-level height elevation and displays quick actions for language switching and clipboard access (tap to open clipboard history; long-press to open favorites, falling back to clipboard if favorites are empty).
- **Gestures & Corner Key Popups**: 26-key glide typing coexists seamlessly with full-row fast up/down corner swipes; long-pressing letter keys opens a corner selection popup with the subscript symbol on the left and superscript symbol on the right. Includes 9-key glide selection memory, 14-key pause correction, and a screen-edge gesture exclusion toggle for the key area.

### Handwriting, Speech & Productivity

- **Speech Recognition**: Supports offline ASR models (SenseVoice, Paraformer, Zipformer, downloaded on demand) and custom online speech endpoints, automatically unbinding the isolated `:asr` process after idle to reclaim memory.
- **Handwriting Panel**: Chinese, English, and math formula recognition with local LaTeX rendering.
- **Live Captions**: Floating real-time speech transcription overlay for Chinese and English.
- **Clipboard & Favorites**: Searchable clipboard history with pinning, TXT/Markdown/JSON export, separate text & image favorites, and a toggle to show recently copied items directly on the candidate bar.
- **Hardware Keyboard Support**: Toolbar, productivity shortcuts, and game key-passthrough tuning for physical keyboards.
- **Visual Customization & Plugins**: Light/dark themes, frosted glass effects, customizable keyboard layouts, and plugin support.

---

## Building Locally

```bash
git clone --recursive https://github.com/onlyjco/CJIME.git
cd CJIME
./gradlew assembleRelease
```

Requires JDK 17 and Android SDK / NDK (`29.0.14206865`) with CMake to build native libraries (`librime`, `onnxruntime`, `sherpa-onnx`). Run `./gradlew assembleDebug` for a debug APK.

---

## GitHub Actions Workflows

- **Releases**: Pushing a `V*.*` tag (e.g., `git tag V1.0 && git push origin V1.0`) triggers [.github/workflows/release.yml](.github/workflows/release.yml) to build and publish the lightweight APK to GitHub Releases.
- **Nightly / Ad-hoc Builds**: Triggered manually on demand via `workflow_dispatch` in `CJIME Nightly Build` (scheduled daily runs are disabled).

---

## Credits & Acknowledgments

Special thanks to the original upstream author **Cyletix** and [CyIME](https://github.com/Cyletix/CyIME), as well as [Xime](https://github.com/ximeiorg/Xime)—CJIME's architecture, core input pipeline, and many UX foundations build directly upon their work.

We also gratefully acknowledge the open-source projects and data resources listed below (see [app/src/main/assets/NOTICE.txt](app/src/main/assets/NOTICE.txt) for full notices and license texts):

| Category | Project / Resource | License | Notes |
| :--- | :--- | :--- | :--- |
| **Upstream Projects** | [CyIME](https://github.com/Cyletix/CyIME) | GPL-3.0 | Upstream project by Cyletix |
| | [Xime](https://github.com/ximeiorg/Xime) | GPL-3.0 | Original open-source IME base |
| **Rime Engine & Deps** | [librime](https://github.com/rime/librime) | BSD-3-Clause | Core input method engine |
| | [Boost](https://www.boost.org/), [LevelDB](https://github.com/google/leveldb), [marisa-trie](https://github.com/s-yata/marisa-trie), [OpenCC](https://github.com/BYVoid/OpenCC), [yaml-cpp](https://github.com/jbeder/yaml-cpp), [glog](https://github.com/google/glog), [Snappy](https://github.com/google/snappy) | BSL-1.0 / BSD-3-Clause / BSD-2-Clause / Apache-2.0 / MIT | Runtime and build dependencies for `librime` |
| **Input Schemas & Lexicons** | [rime-ice](https://github.com/iDvel/rime-ice) | GPL-3.0 | Default Pinyin & Shuangpin schemas and lexicon |
| | [rime-wanxiang](https://github.com/amzxyz/rime_wanxiang) | GPL-3.0 | Language model for Chinese prediction and error correction |
| | [rime-wubi](https://github.com/rime/rime-wubi), [rime-wubi98](https://github.com/yanhuacuo/98wubi-tables) | LGPL-3.0 / MIT | Wubi 86 & 98 schemas |
| | [ChaiPUA](https://github.com/mirtlecn/ChaiPUA) | CC-BY-4.0 | Hanzi decomposition data for Wubi reverse lookup |
| | [rime-stroke](https://github.com/rime/rime-stroke) | LGPL-3.0 | Stroke input schema |
| | [Cangjie5](https://github.com/Cangjie5/Cangjie5), [rime-cangjie](https://github.com/rime/rime-cangjie), [rime-bopomofo](https://github.com/rime/rime-bopomofo) | GPL-3.0 / LGPL-3.0 | Cangjie 5 and Bopomofo schemas |
| | [rime-zhuyin](https://github.com/tnypxl/rime-zhuyin) | GPL-3.0 | Zhuyin lexicon & config reference |
| | [Sogou Cell Lexicons](https://pinyin.sogou.com/dict/) | — | Extended domain dictionaries |
| | [ECDICT](https://github.com/skywind3000/ECDICT), [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict), [opus-mt-zh-en](https://huggingface.co/Helsinki-NLP/opus-mt-zh-en) | MIT / CC-BY-SA-4.0 / CC-BY-4.0 | English-Chinese dictionary & translation reference |
| | LCCC, CrossWOZ, JDDC, ShareGPT, MathWriting, Corpus | MIT / Apache-2.0 / CC BY-NC-SA 4.0 | Corpora & frequency statistics (MathWriting is non-commercial) |
| **Speech Recognition** | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | On-device speech inference runtime |
| | [Silero VAD](https://github.com/snakers4/silero-vad) | MIT | Voice activity detection |
| | [SenseVoice](https://github.com/FunAudioLLM/SenseVoice), [Paraformer](https://github.com/modelscope/FunASR), [Zipformer](https://github.com/k2-fsa/icefall), X-ASR zh-en | Apache-2.0 / MIT / FunASR License | Multilingual and Chinese/English offline ASR models |
| | [3D-Speaker CAM++](https://github.com/modelscope/3D-Speaker) | Apache-2.0 | Speaker embedding support |
| **Handwriting & Math** | [ochwpro](https://github.com/tangruize/ochwpro) | GPL-3.0 | Handwriting recognition reference |
| | [Make Me a Hanzi](https://github.com/skishore/makemeahanzi), [AnimCJK](https://github.com/parsimonhi/animCJK), [cjkvi-ids](https://github.com/cjkvi/cjkvi-ids) | LGPL-3.0 / Arphic PL / CC-BY-SA-4.0 | Hanzi stroke and structural data |
| | [KaTeX](https://github.com/KaTeX/KaTeX) | MIT | Local math formula rendering |
| **Inference & Translation** | [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | Neural network inference runtime |
| | [slimt](https://github.com/browsermt/slimt), [Firefox Translations](https://github.com/mozilla/firefox-translations-models), [UER-py](https://github.com/dbiir/UER-py) | MPL-2.0 / Apache-2.0 | Offline translation & text processing support |
| **Android Stack** | [Jetpack Compose](https://developer.android.com/jetpack/compose), [Material 3](https://m3.material.io/), [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization), [Hilt](https://dagger.dev/hilt/), [Room](https://developer.android.com/training/data-storage/room), [DataStore](https://developer.android.com/topic/libraries/architecture/datastore), [Coil](https://github.com/coil-kt/coil), [OkHttp](https://square.github.io/okhttp/), [Retrofit](https://square.github.io/retrofit/), [ExoPlayer / Media3](https://developer.android.com/media/media3), [ZXing](https://github.com/zxing/zxing), [ReLinker](https://github.com/KeepSafe/ReLinker), [Accompanist](https://github.com/google/accompanist), [AndroidX](https://developer.android.com/jetpack/androidx) | Apache-2.0 | UI, networking, storage, and build tooling |
| **Design References** | [Trime](https://github.com/osfans/trime), [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) | GPL-3.0 / LGPL-2.1 | Input schema & Android keyboard UX references |

---

## License

This project is licensed under the **GNU General Public License v3.0 (GPLv3)**:

- **Permitted**: Use, modify, study, and distribute source or binary builds.
- **Required**: Any derivative or distributed version must also be licensed under **GPLv3** with full source availability and preserved copyright notices (Xime / CyIME and contributors).
- MathWriting data used for formula handwriting is subject to **CC BY-NC-SA 4.0** (non-commercial). See [LICENSE](LICENSE) and [app/src/main/assets/NOTICE.txt](app/src/main/assets/NOTICE.txt) for details.
