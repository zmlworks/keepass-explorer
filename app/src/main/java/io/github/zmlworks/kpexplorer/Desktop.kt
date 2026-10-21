package io.github.zmlworks.kpexplorer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.zmlworks.kpexplorer.ui.Px
import io.github.zmlworks.kpexplorer.ui.W

/** Teal desktop with a centered window (setup wizard / logon). */
object Desktop {
    fun frame(ctx: Context, window: View): View {
        val sv = ScrollView(ctx).apply { isFillViewport = true }
        val f = FrameLayout(ctx).apply { setPadding(W.dp(10), W.dp(16), W.dp(10), W.dp(16)) }
        f.addView(window, FrameLayout.LayoutParams(W.MATCH, W.WRAP, Gravity.CENTER).apply { })
        sv.addView(f)
        val outer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(sv, W.lp(W.MATCH, 0, 1f))
        outer.addView(taskbar(ctx), W.lp())
        return outer
    }

    /** A decorative Windows-98 taskbar with a "スタート" button and a clock. */
    fun taskbar(ctx: Context): View {
        val bar = W.hbox(ctx).apply {
            background = W.Bevel(W.Style.THIN_RAISED)
            setPadding(W.dp(3), W.dp(4), W.dp(3), W.dp(3))
        }
        val start = W.hbox(ctx).apply { background = W.Bevel(W.Style.BUTTON); setPadding(W.dp(6), W.dp(4), W.dp(10), W.dp(4)) }
        start.addView(W.icon(ctx, Px.key16, maxOf(1, W.iconScale() - 1)), W.lp(W.WRAP, W.WRAP, r = 4))
        start.addView(W.text(ctx, "スタート", 14f, true))
        bar.addView(start, W.lp(W.WRAP, W.dp(36)))
        bar.addView(View(ctx), W.lp(0, 1, 1f))
        val clock = W.text(ctx, java.time.LocalTime.now().let { "%d:%02d".format(it.hour, it.minute) }, 14f).apply {
            background = W.Bevel(W.Style.THIN_SUNKEN); setPadding(W.dp(12), W.dp(8), W.dp(12), W.dp(8))
        }
        bar.addView(clock, W.lp(W.WRAP, W.WRAP))
        return bar
    }

    /** Left-hand wizard picture: navy→teal gradient with large icons. */
    fun wizardArt(ctx: Context): View = object : View(ctx) {
        override fun onDraw(c: Canvas) {
            val p = Paint()
            p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), W.NAVY, W.DESKTOP, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            val s = W.iconScale()
            fun draw(b: Bitmap, x: Int, y: Int, k: Int) = c.drawBitmap(b, null, Rect(x, y, x + b.width * k, y + b.height * k), Paint())
            draw(Px.computer32, width / 2 - 16 * (s + 1), W.dp(30), s + 1)
            draw(Px.folder32, W.dp(10), W.dp(150), s)
            draw(Px.keyfile32, width - 32 * s - W.dp(8), W.dp(190), s)
            draw(Px.lock16, width / 2 - 8 * s, W.dp(260), s)
        }
    }.apply { minimumHeight = W.dp(320) }
}
