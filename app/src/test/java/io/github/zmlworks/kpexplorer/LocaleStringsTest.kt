package io.github.zmlworks.kpexplorer

import android.content.res.Configuration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LocaleStringsTest {
    private fun ctx(tag: String) = RuntimeEnvironment.getApplication().createConfigurationContext(
        Configuration(RuntimeEnvironment.getApplication().resources.configuration).also {
            it.setLocale(Locale.forLanguageTag(tag))
        }
    )

    @Test fun englishDefaults() {
        val c = ctx("en")
        assertEquals("Recycle Bin", c.getString(R.string.recycle_bin))
        assertEquals("Import", c.getString(R.string.import_menu))
        assertEquals("Read-only", c.getString(R.string.read_only))
        assertEquals("Random string generator", c.getString(R.string.passgen_menu))
    }

    @Test fun japaneseOverrides() {
        val c = ctx("ja")
        assertEquals("ごみ箱", c.getString(R.string.recycle_bin))
        assertEquals("インポート", c.getString(R.string.import_menu))
        assertEquals("閲覧専用", c.getString(R.string.read_only))
        assertEquals("ランダム文字列生成", c.getString(R.string.passgen_menu))
    }
}
