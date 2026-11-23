# CJIME

Android 开源中英文输入法，基于 [CyIME](https://github.com/Cyletix/CyIME) 制作，由 [onlyjco](https://github.com/onlyjco) 维护。底层集成 Rime 引擎，集全拼、双拼、五笔、笔画、仓颉、注音、手写、语音识别、实时字幕、剪贴板与收藏夹等功能于一体。

[English](README.en.md)

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](https://www.gnu.org/licenses/gpl-3.0)
![Platform](https://img.shields.io/badge/Platform-Android%209.0%2B-3DDC84)
![Tech](https://img.shields.io/badge/Tech-Kotlin%20%7C%20Compose%20%7C%20Rime-7F52FF)

---

## 下载与安装

在 [Releases](https://github.com/onlyjco/CJIME/releases) 下载对应版本的轻量优化安装包：

- **`CJIME-<版本>-arm64-v8a.apk`**（推荐绝大多数手机安装；已开启 R8 代码与资源精简，体积小巧，需要使用的语音/手写等离线模型可在应用内按需下载）

> **说明**：CJIME 使用独立包名 `com.onlyjco.cjime`，可与原版 CyIME 或 Xime 并存安装；系统要求 Android 9.0（API 28）及以上。

---

## 主要特性

### 输入方案与词库

- **汉语拼音**：支持 26 键、9 键、14 键（双拼/全拼），内置雾凇拼音词库与万象语法模型，纠错、简拼、模糊音、中英混输开箱即用；支持外部自定义方案并可按全拼 / 双拼指定滑行解码方式
- **双拼**：内置 6 种常见双拼方案（小鹤、自然码、微软、智能 ABC、紫光、搜狗）及自定义双拼方案
- **形码方案**：五笔 86 / 98、笔画输入、仓颉五代、速成、注音；横竖屏可分别记忆双拼、码表与英文键盘排列
- **词库精细控制**：提供生僻字、日文汉字、韩文汉字、粤语字独立过滤开关，并移除了主词库中的生僻古诗词句，减少日常候选干扰
- **英文输入**：支持英文联想补全、滑行输入、词频自适应与常见词组快捷上屏

### 候选操作与特色交互

- **候选词局部修改**：在首选候选中支持拖选局部字词进行行内修改拼音或编码，无需删光重写；兼容 26 键全拼/双拼、内置 14 键全拼/双拼及无双字母合并键的自定义单字母布局
- **笔画筛选**：候选栏未展开时可从空格键滑动起笔触发笔画筛选，展开候选页后也支持按笔画继续筛选重码候选
- **留白按键系统级抬高与剪贴板/收藏夹**：固定键盘底部留白支持系统级抬高适配与高度调节，并可在留白区快捷切换语言、打开剪贴板（短按）与收藏夹（长按进入收藏夹，收藏夹为空时自动回退至剪贴板）
- **手势与长按角标**：26 键全拼/双拼支持滑行输入与全排快速上下滑角标输入共存；长按字母键弹出选择框，左为下角标、右为上角标；9 键滑行支持选字记忆，14 键支持停顿修正；提供按键区防系统边缘手势误触开关

### 手写、语音与效率工具

- **语音输入**：支持本地离线语音识别（SenseVoice、Paraformer、Zipformer 等模型可在设置中按需下载）与在线语音识别接口配置，语音识别空闲后自动解绑 `:asr` 独立进程回收内存
- **手写识别**：内置中文、英文与数学公式（LaTeX 渲染）手写输入面板
- **实时字幕**：支持中英文语音实时转写浮窗显示
- **剪贴板与收藏夹**：支持剪贴板历史记录、置顶、搜索、导出为 TXT / Markdown / JSON，独立管理文本与图片收藏夹，以及在候选栏显示刚复制内容开关
- **物理键盘支持**：外接物理键盘时支持候选栏工具条、常用快捷键以及游戏按键直通等适配
- **界面与扩展**：支持深浅色主题、毛玻璃与多种视觉样式调节、自定义键盘布局以及扩展插件机制

---

## 本地构建

```bash
git clone --recursive https://github.com/onlyjco/CJIME.git
cd CJIME
./gradlew assembleRelease
```

构建前需配置 JDK 17 与 Android SDK / NDK（项目使用 NDK `29.0.14206865` 与 CMake 编译 `librime`、`onnxruntime`、`sherpa-onnx` 原生库）。如需构建调试包可执行 `./gradlew assembleDebug`。

---

## 仓库自动化发布

- **正式发布（Release）**：推送以 `V` 开头的标签（如 `git tag V1.0 && git push origin V1.0`）时触发 [.github/workflows/release.yml](.github/workflows/release.yml) 构建并发布轻量版 APK 到 GitHub Releases
- **测试构建（Nightly）**：仅在你主动需要时于 GitHub Actions 手动触发 `CJIME Nightly Build`（已关闭每日定时自动构建）

---

## 鸣谢

首先特别感谢原作者 **Cyletix** 及其开源项目 [CyIME](https://github.com/Cyletix/CyIME)，以及最初的开源输入法项目 [Xime](https://github.com/ximeiorg/Xime)。CJIME 的核心架构、大量交互设计与工程实现均源自上述项目。

同时感谢以下所有开源项目与数据资源的贡献（完整声明与许可证详情见 [app/src/main/assets/NOTICE.txt](app/src/main/assets/NOTICE.txt)）：

| 类别 | 项目 / 资源 | 开源协议 | 说明 |
| :--- | :--- | :--- | :--- |
| **输入法框架** | [CyIME](https://github.com/Cyletix/CyIME) | GPL-3.0 | 上游基础项目，作者 Cyletix |
| | [Xime](https://github.com/ximeiorg/Xime) | GPL-3.0 | 初始开源输入法底座 |
| **Rime 引擎及依赖** | [librime](https://github.com/rime/librime) | BSD-3-Clause | 核心输入法引擎 |
| | [Boost](https://www.boost.org/), [LevelDB](https://github.com/google/leveldb), [marisa-trie](https://github.com/s-yata/marisa-trie), [OpenCC](https://github.com/BYVoid/OpenCC), [yaml-cpp](https://github.com/jbeder/yaml-cpp), [glog](https://github.com/google/glog), [Snappy](https://github.com/google/snappy) | BSL-1.0 / BSD-3-Clause / BSD-2-Clause / Apache-2.0 / MIT | `librime` 运行与构建依赖 |
| **输入方案与词库** | [雾凇拼音 (rime-ice)](https://github.com/iDvel/rime-ice) | GPL-3.0 | 默认全拼 / 双拼方案与核心词库 |
| | [万象语言模型 (rime-wanxiang)](https://github.com/amzxyz/rime_wanxiang) | GPL-3.0 | 中文语音纠错与预测语言模型 |
| | [rime-wubi](https://github.com/rime/rime-wubi), [rime-wubi98](https://github.com/yanhuacuo/98wubi-tables) | LGPL-3.0 / MIT | 五笔 86 与 98 版输入方案 |
| | [ChaiPUA / 汉字拆分](https://github.com/mirtlecn/ChaiPUA) | CC-BY-4.0 | 五笔汉字拆分反查字根数据 |
| | [rime-stroke](https://github.com/rime/rime-stroke) | LGPL-3.0 | 笔画输入方案 |
| | [Cangjie5](https://github.com/Cangjie5/Cangjie5), [rime-cangjie](https://github.com/rime/rime-cangjie), [rime-bopomofo](https://github.com/rime/rime-bopomofo) | GPL-3.0 / LGPL-3.0 | 仓颉五代与注音输入方案 |
| | [小麦注音 (rime-zhuyin)](https://github.com/tnypxl/rime-zhuyin) | GPL-3.0 | 注音词库与配置参考 |
| | [搜狗细胞词库](https://pinyin.sogou.com/dict/) | — | 扩展专业词库 |
| | [ECDICT](https://github.com/skywind3000/ECDICT), [CC-CEDICT](https://www.mdbg.net/chinese/dictionary?page=cc-cedict), [opus-mt-zh-en](https://huggingface.co/Helsinki-NLP/opus-mt-zh-en) | MIT / CC-BY-SA-4.0 / CC-BY-4.0 | 英汉词典与翻译参考 |
| | LCCC, CrossWOZ, JDDC, ShareGPT, MathWriting, Corpus | MIT / Apache-2.0 / CC BY-NC-SA 4.0 | 语料与词频统计辅助（MathWriting 仅用于非商业研究） |
| **语音识别** | [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | Apache-2.0 | 本地端侧语音推理框架 |
| | [Silero VAD](https://github.com/snakers4/silero-vad) | MIT | 语音活动端点检测 |
| | [SenseVoice](https://github.com/FunAudioLLM/SenseVoice), [Paraformer](https://github.com/modelscope/FunASR), [Zipformer](https://github.com/k2-fsa/icefall), X-ASR zh-en | Apache-2.0 / MIT / FunASR License | 多语言及中英文离线语音识别模型 |
| | [3D-Speaker CAM++](https://github.com/modelscope/3D-Speaker) | Apache-2.0 | 声纹特征提取支持 |
| **手写与公式渲染** | [ochwpro](https://github.com/tangruize/ochwpro) | GPL-3.0 | 在线 / 离线手写识别辅助 |
| | [Make Me a Hanzi](https://github.com/skishore/makemeahanzi), [AnimCJK](https://github.com/parsimonhi/animCJK), [cjkvi-ids](https://github.com/cjkvi/cjkvi-ids) | LGPL-3.0 / Arphic PL / CC-BY-SA-4.0 | 汉字笔画结构与字形数据 |
| | [KaTeX](https://github.com/KaTeX/KaTeX) | MIT | 数学公式手写结果的本地排版渲染 |
| **推理与翻译底座** | [ONNX Runtime](https://github.com/microsoft/onnxruntime) | MIT | 端侧神经网络模型推理引擎 |
| | [slimt](https://github.com/browsermt/slimt), [Firefox Translations](https://github.com/mozilla/firefox-translations-models), [UER-py](https://github.com/dbiir/UER-py) | MPL-2.0 / Apache-2.0 | 离线翻译与文本处理模型支持 |
| **Android 组件** | [Jetpack Compose](https://developer.android.com/jetpack/compose), [Material 3](https://m3.material.io/), [Kotlin Coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Kotlinx Serialization](https://github.com/Kotlin/kotlinx.serialization), [Hilt](https://dagger.dev/hilt/), [Room](https://developer.android.com/training/data-storage/room), [DataStore](https://developer.android.com/topic/libraries/architecture/datastore), [Coil](https://github.com/coil-kt/coil), [OkHttp](https://square.github.io/okhttp/), [Retrofit](https://square.github.io/retrofit/), [ExoPlayer / Media3](https://developer.android.com/media/media3), [ZXing](https://github.com/zxing/zxing), [ReLinker](https://github.com/KeepSafe/ReLinker), [Accompanist](https://github.com/google/accompanist), [AndroidX](https://developer.android.com/jetpack/androidx) | Apache-2.0 | 核心框架、网络、存储、UI 与构建工具 |
| **设计参考** | [同文输入法 (Trime)](https://github.com/osfans/trime), [小企鹅输入法 (Fcitx5 for Android)](https://github.com/fcitx5-android/fcitx5-android), [同文/Gboard/搜狗等交互参考](https://github.com/onlyjco/CJIME) | GPL-3.0 / LGPL-2.1 | 方案配置与移动端键盘交互参考 |

---

## 开源协议与许可

本项目以 **GNU General Public License v3.0 (GPLv3)** 协议开源：

- **允许**：自由使用、修改源代码、研究机制及分发修改版本
- **要求**：任何基于本项目的修改、衍生及分发版本必须同样以 **GPLv3** 协议开放全部源代码并保留原作者（Xime / CyIME）及后续贡献者版权声明
- 数学公式手写涉及的 MathWriting 数据资源遵循 **CC BY-NC-SA 4.0**（非商业用途限制）；详细第三方许可说明请参见 [LICENSE](LICENSE) 与 [app/src/main/assets/NOTICE.txt](app/src/main/assets/NOTICE.txt)
