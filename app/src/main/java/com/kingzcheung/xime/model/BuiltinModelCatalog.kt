package com.kingzcheung.xime.model

/** Versioned local catalog: downloads go directly to model publishers, without a market request. */
object BuiltinModelCatalog {
    /** CyIME 自己 Release 里的原样转存（许可见该 Release 的 NOTICE-models.txt），海外线路优先。 */
    private const val RELEASE = "https://github.com/Cyletix/CyIME/releases/download/models/"
    const val SPEAKER_MODEL = "speaker-campplus-zh"
    const val SPEAKER_MODEL_FILE = "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
    /** 语音引擎（sherpa-onnx 原生库）。轻量版不带，和语音模型一起下载（speech/SherpaLibrary.kt、SpeechEnginePack.kt）。 */
    const val SPEECH_ENGINE = "speech-engine"
    /** 手写生僻字模板（assets/handwriting 里的文件）。轻量版不带，和手写模型一起下载。 */
    const val HANDWRITING_RARE = "handwriting-rare"

    val models = com.kingzcheung.xime.speech.SpeechModelCatalog.models + CandidateModelCatalog.models + listOf(
        ModelInfo("ochwpro", "手写模型", "本地手写识别模型。", ModelCategory.HANDWRITING,
            // v1.1、v1.2：在 v1.0 上用用户贡献的手写样本微调（只改贡献题里常用字的输出层：v1.1 3 人 100 字，v1.2 45 人 180 字），
            // 字表不变。最新的放第一个。
            versions = listOf(ModelVersion(version = "v1.2", size = "6.7 MB",
                files = listOf(
                    ModelFile("ochwpro.onnx", "https://www.modelscope.cn/models/Cyletix/cyime-model/resolve/master/ochwpro-v1.2-ochwpro.onnx", "8a6095f9ccd222fa391d4e98e2795f478d3d88daaa62d4844677fac18c7acb02", 7001270L, mirrors = listOf(RELEASE + "ochwpro-v1.2-ochwpro.onnx")),
                    ModelFile("char_index.json", "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/char_index.json", "171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176", 51317L, mirrors = listOf(RELEASE + "ochwpro-v1.0-char_index.json")),
                )),
                ModelVersion(version = "v1.1", size = "6.7 MB",
                files = listOf(
                    ModelFile("ochwpro.onnx", "https://www.modelscope.cn/models/Cyletix/cyime-model/resolve/master/ochwpro-v1.1-ochwpro.onnx", "d625e6b6fa0dc39499bc15a8ac960249fcea9e71e34334522d32524557d1136d", 7001270L, mirrors = listOf(RELEASE + "ochwpro-v1.1-ochwpro.onnx")),
                    ModelFile("char_index.json", "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/char_index.json", "171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176", 51317L, mirrors = listOf(RELEASE + "ochwpro-v1.0-char_index.json")),
                )),
                ModelVersion(version = "v1.0", size = "6.7 MB",
                files = listOf(
                    ModelFile("ochwpro.onnx", "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/ochwpro.onnx", "eb04d62a314c7d7bac4e34d6ce0c24137474d30ff94b929115a621385420ce13", 7001270L, mirrors = listOf(RELEASE + "ochwpro-v1.0-ochwpro.onnx")),
                    ModelFile("char_index.json", "https://www.modelscope.cn/models/bikeand/ochwpro/resolve/master/char_index.json", "171cf2ac23731428ac9dd28f64be82dece5f66a0ca3d674ffb3b567b51532176", 51317L, mirrors = listOf(RELEASE + "ochwpro-v1.0-char_index.json")),
                )))),
        // 语音引擎：sherpa-onnx 1.13.8（静态链接 onnxruntime 的 AAR，tasks-speech.gradle.kts 固定哈希）里的 libsherpa-onnx-jni.so，
        // 和以前打进安装包的逐字节相同，每个 ABI 打成一个 tar.bz2 传到 Release `models` 和魔搭。轻量版和语音模型一起下载；
        // 完整版、离线包仍在安装包里。32 位 x86 那份要安装包里的 libonnxruntime.so（和以前一样）。
        ModelInfo(SPEECH_ENGINE, "语音引擎", "语音输入和字幕识别用的程序库（sherpa-onnx）。轻量版下载语音模型时一起下载，完整版已内置。", ModelCategory.OTHER,
            versions = listOf(when (NativeAbi.current) {
                "armeabi-v7a" -> ModelVersion(version = "1.13.8", size = "7.3 MB",
                    archiveUrl = RELEASE + "speech-engine-sherpa-onnx-1.13.8-armeabi-v7a.tar.bz2", sha256 = "7894451dbe5b40aa4d9aeba0037a7863a4ea32aa9c4c332c71ff770405348882",
                    archiveMirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "speech-engine-sherpa-onnx-1.13.8-armeabi-v7a.tar.bz2"),
                    files = listOf(ModelFile("libsherpa-onnx-jni.so", "", "2c0350f9c23dda60318c0d195fcc02d505f05cdbfba89492a4468d2c4276be28", 16437284L)))
                "x86_64" -> ModelVersion(version = "1.13.8", size = "8.6 MB",
                    archiveUrl = RELEASE + "speech-engine-sherpa-onnx-1.13.8-x86_64.tar.bz2", sha256 = "13559cfcf1ec2935a72eb0541fff99531a6fd60e696c51df36c34b108c66d217",
                    archiveMirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "speech-engine-sherpa-onnx-1.13.8-x86_64.tar.bz2"),
                    files = listOf(ModelFile("libsherpa-onnx-jni.so", "", "968e24f61d1e4dae0d5e7860aded42fee45b0f804e1c5a42dfa49186864cefc4", 27359872L)))
                "x86" -> ModelVersion(version = "1.13.8", size = "1.9 MB",
                    archiveUrl = RELEASE + "speech-engine-sherpa-onnx-1.13.8-x86.tar.bz2", sha256 = "abeaf9f3acb91535b1a72fa180fa0a2f183ef4f5602671ae0cfb623ad1ba49ce",
                    archiveMirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "speech-engine-sherpa-onnx-1.13.8-x86.tar.bz2"),
                    files = listOf(ModelFile("libsherpa-onnx-jni.so", "", "914ce3d8e77c4ec13b178a8657b2bb9d6187ea6deddbdd519be0373c71eb681d", 5540732L)))
                else -> ModelVersion(version = "1.13.8", size = "7.8 MB",
                    archiveUrl = RELEASE + "speech-engine-sherpa-onnx-1.13.8-arm64-v8a.tar.bz2", sha256 = "038427c68909fae26c5c2a4ceb080b13e75a6d3c0f688700915530c019761f74",
                    archiveMirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "speech-engine-sherpa-onnx-1.13.8-arm64-v8a.tar.bz2"),
                    files = listOf(ModelFile("libsherpa-onnx-jni.so", "", "b4a53d915c19cb6d09b1cdc9a8084be54419b647aab9097ffdfb46f30c3c4dd8", 24169352L)))
            })),
        // 手写生僻字模板、日语手写模板：app/src/main/assets/handwriting 里的原文件（scripts/packs/make_packs.py 打包，
        // 换了文件重跑它并改这里，HandwritingPackCatalogTest 对得上才过）。轻量版不带，完整版、离线包仍在安装包里。
        ModelInfo(HANDWRITING_RARE, "手写生僻字", "手写时认字表外的生僻字用的笔顺模板。轻量版下载手写模型时一起下载，完整版已内置。", ModelCategory.OTHER,
            versions = listOf(ModelVersion(version = "c2f28f4e", size = "3.8 MB",
                archiveUrl = RELEASE + "handwriting-rare-c2f28f4e.tar.bz2", sha256 = "c2f28f4eefede9b70f844d2db84083b26ddbd76e9ec3f85525d559e98826438d",
                archiveMirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "handwriting-rare-c2f28f4e.tar.bz2"),
                files = listOf(
                    ModelFile("hanzi_rare_och.bin", "", "b901f5d89eedcd6614adb606a08926af2c035281e384964376e79595ac6dc26c", 4797590L),
                    ModelFile("hanzi_rare_och.json", "", "f31e0a2396092aea18d0440f5911d8cce2e64cdbbb13f44c94dd74e221f2356b", 60370L),
                )))),
        // 公式手写（LaTeX）：tools/handwriting_training 训练，只用公开的 MathWriting 2024，不含用户样本；
        // 日语、英文手写的“边写边学”也用它算特征。
        ModelInfo("handwriting-formula", "公式手写模型", "手写数学公式，识别成 LaTeX；日语、英文手写的边写边学也需要它。", ModelCategory.HANDWRITING,
            versions = listOf(ModelVersion(version = "v1", size = "32.4 MB",
                files = listOf(
                    ModelFile("formula_encoder.onnx", RELEASE + "formula-handwriting-v1-encoder.onnx", "c2c55f14e8d3bc892c7f86ec9b237e54f46997300550a5c76501c54b7344b6dd", 21137800L, mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "formula-handwriting-v1-encoder.onnx")),
                    ModelFile("formula_decoder.onnx", RELEASE + "formula-handwriting-v1-decoder.onnx", "ab11c1ceb4cb58b044be89b77c71eb32fcbc53ff1ddfc136c51444b002bdb2a9", 13346580L, mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "formula-handwriting-v1-decoder.onnx")),
                    ModelFile("formula_vocab.json", RELEASE + "formula-handwriting-v1-vocab.json", "73653abb6993d5f2451501c804fb66671c05f86b4165bbe10cb3ef6ef96ec92c", 2003L, mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "formula-handwriting-v1-vocab.json")),
                    ModelFile("formula_meta.json", RELEASE + "formula-handwriting-v1-meta.json", "2ad149afaa9f3872f872e1d4bf9560dfb67be21d08d69ec1a0fa05441cff7541", 205L, mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "formula-handwriting-v1-meta.json")),
                )))),
        // 候选注释“释义”用的词典（tools/dictionaries/glossary/build_glossary.py 生成）；id 与 CandidateGlossary.MODEL_ID 一致。
        ModelInfo("candidate-gloss", "候选中英释义词典", "设置里“候选注释内容”选释义后，中文候选旁显示英文，中文键盘里的英文候选旁显示中文。英→中取自 ECDICT，中→英取自 CC-CEDICT（MDBG）；常见的日常短语也显示英文。", ModelCategory.OTHER,
            // v8：去掉俚语／粗口义项，多音字按 Rime 常用读音、义项按英文反查来选（v1 有 几号 → heroin）；补齐常用单字和词组；
            // 常见的说话用短语用电脑上离线翻译翻好、且译回中文一致的才收，不收带地名人名的（带 “+”，只显示）。
            // v10：单字只给常用且意思明确的（公开语料最常用约 870 字人工看过），生僻字不显示；公开聊天语料最常用的词组人工补齐、改正错义项；v11 常用实词不再被过严的筛子挡掉，补“X的”形容词；v12 不用截断的长释义，常用动词加“了”给过去式。
            versions = listOf(ModelVersion(version = "v12", size = "2.7 MB",
                files = listOf(
                    ModelFile("cyime_gloss-v12.tsv", "https://github.com/Cyletix/CyIME/releases/download/models/cyime_gloss-v12.tsv", "46ad175c5ea63cf1d9de5999aa615dbaf09a5689e71dc7d20c17e177fd22e761", 2747059L,
                        mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "cyime_gloss-v12.tsv")),
                )))),
        // 字幕模式分说话人用的声纹模型（3D-Speaker CAM++ 中文，sherpa-onnx 导出，Apache-2.0）；不是语音识别模型。
        ModelInfo(SPEAKER_MODEL, "声纹模型 · 中文", "字幕模式用来区分不同说话人。", ModelCategory.SPEAKER,
            versions = listOf(ModelVersion(version = "v1.0", size = "27.0 MB",
                files = listOf(
                    // 上游 Release 标签就拼作 recongition；字面量写全，打包脚本按文本解析目录。
                    ModelFile("3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx", "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx", "f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11", 28281138L,
                        mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx")),
                )))),
        // 2026-11-03 起不再提供下载：中文边说边出字用 Paraformer，定稿用 SenseVoice / X-ASR（效果更好）。
        // 已经下载、正在用的照旧能用；条目留着，模型管理里可以删。
        ModelInfo("zipformer-zh-int8", "Zipformer · 中文", "中文流式语音识别，可使用 SenseVoice 二次校正。", ModelCategory.ASR,
            versions = listOf(ModelVersion(version = "v1.0", size = "132.63MB", retired = true,
                archiveUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2", sha256 = "5a2832047ea1f97dd0dc595b816c230c4bafad65cfc0341fa57517cadc50afd0",
                // 魔搭上的同一归档（2026-10-12 核对哈希一致），国内线路优先用它。
                archiveMirrors = listOf("https://www.modelscope.cn/models/bikeand/asr/resolve/master/sherpa-onnx-streaming-zipformer-zh-int8-2025-06-30.tar.bz2"),
                files = listOf(
                    ModelFile("encoder.int8.onnx", "", "", 0L),
                    ModelFile("decoder.onnx", "", "", 0L),
                    ModelFile("joiner.int8.onnx", "", "", 0L),
                    ModelFile("tokens.txt", "", "", 0L),
                )))),
        // 离线翻译（翻译插件没填 API 时用，translate/OfflineTranslation.kt；引擎 libcytranslate.so 在安装包里）。
        // Mozilla Firefox 翻译模型，取自 storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data
        // 的 db/models.json（2026-11-08），解压后原样转存到 Release `models` 和魔搭，文件名前加 translate-<方向>-v1-。
        // 中译英：Firefox「Release Android」版 models/zh-en/cjk_retrain_base-memory_cNY_yaJCStGwnTeXgW8A5w，两边共用一个词表。
        ModelInfo("translate-zh-en", "离线翻译 · 中译英", "翻译插件没填 API 时，在手机上把中文翻成英文，不联网。Mozilla Firefox 的翻译模型。", ModelCategory.OTHER,
            versions = listOf(ModelVersion(version = "v1", size = "52.0 MB",
                files = listOf(
                    ModelFile("model.zhen.intgemm.alphas.bin", RELEASE + "translate-zh-en-v1-model.zhen.intgemm.alphas.bin", "5cd149601802fc8a18124a1c1306144dbbedc058630c4ddb2d53aa76fa9c7c06", 43977787L,
                        mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "translate-zh-en-v1-model.zhen.intgemm.alphas.bin")),
                    ModelFile("vocab.zhen.spm", RELEASE + "translate-zh-en-v1-vocab.zhen.spm", "dff594318ab7d8b7b60b844ab98ebe6b932ae8045fab15235404c787715965b3", 1359697L,
                        mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "translate-zh-en-v1-vocab.zhen.spm")),
                    ModelFile("lex.50.50.zhen.s2t.bin", RELEASE + "translate-zh-en-v1-lex.50.50.zhen.s2t.bin", "8524dd4c93ffd7f0ae7be32f77a7c14fea7cdbbebe34ea6ad0ec368d5ddd8b13", 9219192L,
                        mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "translate-zh-en-v1-lex.50.50.zhen.s2t.bin")),
                )))),
        // 英译中：Firefox「Release」版 models/en-zh/llmaat_finetune10M_qe8_f2_ByQcSxGXQRqGi-UTxYE43g，源、目标各一个词表。
        ModelInfo("translate-en-zh", "离线翻译 · 英译中", "翻译插件没填 API 时，在手机上把英文翻成中文，不联网。Mozilla Firefox 的翻译模型。", ModelCategory.OTHER,
            versions = listOf(ModelVersion(version = "v1", size = "48.7 MB",
                files = listOf(
                    ModelFile("model.enzh.intgemm.alphas.bin", RELEASE + "translate-en-zh-v1-model.enzh.intgemm.alphas.bin", "ce4486f728641a36269a245248dcb53405e76d96d9eba68dcb4172f29521e092", 42992955L,
                        mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "translate-en-zh-v1-model.enzh.intgemm.alphas.bin")),
                    ModelFile("srcvocab.enzh.spm", RELEASE + "translate-en-zh-v1-srcvocab.enzh.spm", "bd9b65504acc6d9726dd281f7defc2adb7c2c22d0688fe2f84697de25197c8c5", 806952L,
                        mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "translate-en-zh-v1-srcvocab.enzh.spm")),
                    ModelFile("trgvocab.enzh.spm", RELEASE + "translate-en-zh-v1-trgvocab.enzh.spm", "aded6993c36e440284d11cec3f6b8aef9c0e43188a772d80be342a713adf223d", 772004L,
                        mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "translate-en-zh-v1-trgvocab.enzh.spm")),
                    ModelFile("lex.50.50.enzh.s2t.bin", RELEASE + "translate-en-zh-v1-lex.50.50.enzh.s2t.bin", "4a5e5827788060f1d718a8132b69440929387514a045796e9b77f935db68c055", 6506248L,
                        mirrors = listOf(DownloadMirrors.CYIME_MODELSCOPE + "translate-en-zh-v1-lex.50.50.enzh.s2t.bin")),
                )))),
    )
}
