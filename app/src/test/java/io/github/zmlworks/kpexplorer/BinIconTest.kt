package io.github.zmlworks.kpexplorer

import android.graphics.Bitmap
import io.github.zmlworks.kpexplorer.ui.Px
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BinIconTest {
    private fun greens(b: Bitmap) = (0 until b.width).sumOf { x -> (0 until b.height).count { y -> val c = b.getPixel(x, y); (c ushr 24) != 0 && ((c shr 8) and 0xFF) > 0x70 && ((c shr 16) and 0xFF) < 0x40 } }

    @Test fun binIconsHaveRecycleArrowsAndDifferFromFolder() {
        assertEquals(16, Px.bin16.width); assertEquals(32, Px.bin32.width)
        assertTrue(greens(Px.bin16) >= 6); assertTrue(greens(Px.bin32) >= 20)
        assertTrue(!Px.bin16.sameAs(Px.folder16))
        val out = File(System.getenv("KPX_SCREENS_DIR") ?: "build/reports/screens").apply { mkdirs() }
        for ((n, b) in listOf("bin16" to Px.bin16, "bin32" to Px.bin32)) {
            val big = Bitmap.createScaledBitmap(b, 256, 256, false)
            FileOutputStream(File(out, "20-icon-$n.png")).use { big.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
