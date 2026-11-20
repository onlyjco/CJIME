package com.kingzcheung.xime.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PackageButtonLayoutIsolationTest {

    @Test
    fun importedPackageButtonLayoutAppliesOnlyToPackageSchema(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val rime = SchemaManager.getRimeDir(app).apply { mkdirs() }
        File(rime, "xime.custom.yaml").delete()
        File(rime, SchemaManifestManager.PACKAGE_XIME_DIR).deleteRecursively()

        val staged = File(app.cacheDir, "pkg-button-layout").apply {
            deleteRecursively()
            mkdirs()
        }
        File(staged, "luan17.schema.yaml").writeText("schema:\n  schema_id: luan17\n  name: 乱17\n")
        File(staged, "xime.custom.yaml").writeText(
            """
            keyboard:
              qwerty:
                button_layout: compact
              luan17_layout:
                schemas: [luan17]
                layout:
                  rows:
                    - [q, [w, e], [r, t], [y, u], i, [o, p]]
                    - [a, [s, d], [f, g], h, [j, k], l]
                    - [z, [x, c], v, [b, n], m]
            """.trimIndent()
        )
        SchemaManifestManager.installStagedFiles(
            app, "pkg_luan17", "乱17", "1", true,
            staged, listOf("luan17.schema.yaml", "xime.custom.yaml")
        )

        KeysConfigHelper.loadConfig(app)

        assertEquals(ButtonLayout.STANDARD, KeysConfigHelper.getButtonLayout(false, "rime_ice"))
        assertEquals(ButtonLayout.STANDARD, KeysConfigHelper.getButtonLayout(false, "pinyin_17jian"))
        assertEquals(ButtonLayout.COMPACT, KeysConfigHelper.getButtonLayout(false, "luan17"))
        assertEquals(ButtonLayout.STANDARD, KeysConfigHelper.getButtonLayout(true, "luan17"))

        // 卸载后恢复干净
        SchemaManager.uninstallPackage(app, "pkg_luan17")
        KeysConfigHelper.loadConfig(app)
        assertEquals(ButtonLayout.STANDARD, KeysConfigHelper.getButtonLayout(false, "luan17"))
        assertEquals(ButtonLayout.STANDARD, KeysConfigHelper.getButtonLayout(false, "rime_ice"))
    }

    @Test
    fun importedPackageSectionButtonLayoutOverridesBuiltInFallbackOnlyForItsSchema(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val rime = SchemaManager.getRimeDir(app).apply { mkdirs() }
        File(rime, "xime.custom.yaml").delete()
        File(rime, SchemaManifestManager.PACKAGE_XIME_DIR).deleteRecursively()

        val staged = File(app.cacheDir, "pkg-section-layout").apply {
            deleteRecursively()
            mkdirs()
        }
        File(staged, "luan17_b.schema.yaml").writeText("schema:\n  schema_id: luan17_b\n  name: 乱17B\n")
        File(staged, "xime.custom.yaml").writeText(
            """
            keyboard:
              qwerty_17:
                schemas: [luan17_b]
                button_layout: compact
            """.trimIndent()
        )
        SchemaManifestManager.installStagedFiles(
            app, "pkg_luan17_b", "乱17B", "1", true,
            staged, listOf("luan17_b.schema.yaml", "xime.custom.yaml")
        )

        KeysConfigHelper.loadConfig(app)

        assertEquals(ButtonLayout.STANDARD, KeysConfigHelper.getButtonLayout(false, "pinyin_17jian"))
        assertEquals(ButtonLayout.COMPACT, KeysConfigHelper.getButtonLayout(false, "luan17_b"))
        SchemaManager.uninstallPackage(app, "pkg_luan17_b")
    }
}
