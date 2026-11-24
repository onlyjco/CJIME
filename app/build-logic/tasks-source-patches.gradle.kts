// Keep local engine changes reproducible without publishing commits to upstream submodules.
val applyCyimeSourcePatches by tasks.registering {
    val patches = listOf(
        "src/main/jni/librime" to "cyime-librime.patch",
        "src/main/jni/librime-lua-deps" to "cyime-lua-android.patch",
        "src/main/assets/rime" to "cyime-rime-schema.patch",
    )
    doLast {
        for ((directory, name) in patches) {
            val source = file(directory)
            val patch = rootProject.file("patches/$name")
            fun git(vararg args: String): Int = providers.exec {
                workingDir(source)
                commandLine("git", *args)
                isIgnoreExitValue = true
            }.result.get().exitValue
            if (git("apply", "--reverse", "--check", patch.absolutePath) == 0) continue
            check(git("apply", "--check", patch.absolutePath) == 0) {
                "$name conflicts with $directory; preserve the local changes and resolve before building."
            }
            check(git("apply", patch.absolutePath) == 0) { "Failed to apply $name" }
        }
        // Keep librime submodule's plugin header in sync with main repo's librime-t9/src for reproducible CI builds.
        val canonicalSyllableHeader = file("src/main/jni/librime-t9/src/pinyin_syllable_selection.h")
        val pluginSyllableHeader = file("src/main/jni/librime/plugins/librime-t9/src/pinyin_syllable_selection.h")
        if (canonicalSyllableHeader.isFile && pluginSyllableHeader.parentFile.isDirectory) {
            val srcBytes = canonicalSyllableHeader.readBytes()
            if (!pluginSyllableHeader.isFile || !pluginSyllableHeader.readBytes().contentEquals(srcBytes)) {
                pluginSyllableHeader.writeBytes(srcBytes)
            }
        }
    }
}
tasks.configureEach {
    if (name == "preBuild" || name.startsWith("configureCMake") || name.startsWith("buildCMake")) {
        dependsOn(applyCyimeSourcePatches)
    }
}
