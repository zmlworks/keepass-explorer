package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import io.github.zmlworks.kpexplorer.R
import io.github.zmlworks.kpexplorer.db.EntryIcon

/**
 * 0.4.9 entry icons: Fugue Icons 3.5.6 (16px) by Yusuke Kamiyamane, CC BY 3.0
 * (https://p.yusukekamiyamane.com/). Original colours; enlarged only by integer nearest-neighbour
 * scaling at draw time (no pixels edited). Independent memos use [Ico.notepad32] (own art), folders Px.folder.
 */
object FugueIcons {
    private val cache = HashMap<String, Bitmap>()
    private val src = HashMap<String, Bitmap>()

    /** Picture of each [EntryIcon] id: `fugue_<id>.png`, an unchanged 16 px Fugue icon (file names: THIRD_PARTY_NOTICES). */
    fun resId(id: String?): Int = when (id) {
        "mail" -> R.drawable.fugue_mail
        "web" -> R.drawable.fugue_web
        "wifi" -> R.drawable.fugue_wifi
        "food" -> R.drawable.fugue_food
        "user" -> R.drawable.fugue_user
        "card" -> R.drawable.fugue_card
        "bank" -> R.drawable.fugue_bank
        "cart" -> R.drawable.fugue_cart
        "game" -> R.drawable.fugue_game
        "phone" -> R.drawable.fugue_phone
        "lock" -> R.drawable.fugue_lock
        "home" -> R.drawable.fugue_home
        "star" -> R.drawable.fugue_star
        "note" -> R.drawable.fugue_note
        "info" -> R.drawable.fugue_info
        "telephone" -> R.drawable.fugue_telephone
        "work" -> R.drawable.fugue_work
        "school" -> R.drawable.fugue_school
        "tag" -> R.drawable.fugue_tag
        "puzzle" -> R.drawable.fugue_puzzle
        "video" -> R.drawable.fugue_video
        "music" -> R.drawable.fugue_music
        "ticket" -> R.drawable.fugue_ticket
        "car" -> R.drawable.fugue_car
        "train" -> R.drawable.fugue_train
        else -> R.drawable.fugue_key
    }

    fun resId(def: EntryIcon.Def?): Int = resId(def?.id)

    /** The untouched 16×16 Fugue bitmap. */
    fun source(ctx: Context, id: String?): Bitmap = src.getOrPut(id ?: "key") {
        val o = android.graphics.BitmapFactory.Options().apply { inScaled = false }
        android.graphics.BitmapFactory.decodeResource(ctx.resources, resId(id), o)
    }

    /** Drawable that paints the 16px art at the largest integer scale fitting its bounds (crisp pixels). */
    private class Crisp(val b: Bitmap) : Drawable() {
        private val paint = android.graphics.Paint().apply { isFilterBitmap = false; isAntiAlias = false }
        override fun draw(c: Canvas) {
            val r = bounds; val k = maxOf(1, minOf(r.width() / b.width, r.height() / b.height))
            val w = b.width * k; val h = b.height * k
            val l = r.left + (r.width() - w) / 2; val t = r.top + (r.height() - h) / 2
            c.drawBitmap(b, null, android.graphics.Rect(l, t, l + w, t + h), paint)
        }
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth() = b.width * 4
        override fun getIntrinsicHeight() = b.height * 4
    }

    fun drawable(ctx: Context, id: String?): Drawable = Crisp(source(ctx, id))

    fun drawable(ctx: Context, def: EntryIcon.Def?): Drawable = drawable(ctx, def?.id)

    fun bitmap(ctx: Context, id: String?, size: Int): Bitmap {
        val key = "${id ?: "key"}/$size"
        cache[key]?.let { return it }
        val d = drawable(ctx, id)
        d.setBounds(0, 0, size, size)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.draw(Canvas(b))
        cache[key] = b
        return b
    }

    fun bitmap(ctx: Context, def: EntryIcon.Def?, size: Int): Bitmap = bitmap(ctx, def?.id, size)

    fun clearCache() { cache.clear(); src.clear() }

    /**
     * Icon chooser grid: large nearly-square tiles, glyphs only (no visible names).
     * [contentDescription] keeps the Japanese label for accessibility.
     */
    fun pickerGrid(ctx: Context, selectedId: String?, onPick: (String) -> Unit): FlowLayout {
        val grid = FlowLayout(ctx)
        // 0.4.34: 26 icons — 4 columns on phone widths (0.3.11 – 0.4.33: 100 dp tiles, 3 columns, 15 icons)
        val cell = W.dp(80)
        val glyph = W.dp(64)
        for (d in EntryIcon.ALL) {
            val tile = android.widget.FrameLayout(ctx).apply {
                background = W.Bevel(if ((selectedId ?: "key") == d.id) W.Style.SUNKEN else W.Style.BUTTON)
                isClickable = true
                contentDescription = d.label
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnClickListener { onPick(d.id) }
            }
            tile.addView(ImageView(ctx).apply {
                setImageDrawable(drawable(ctx, d.id)); scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, android.widget.FrameLayout.LayoutParams(glyph, glyph, Gravity.CENTER))
            grid.addView(tile, ViewGroup.MarginLayoutParams(cell, cell).apply {
                setMargins(W.dp(4), W.dp(4), W.dp(4), W.dp(4))
            })
        }
        return grid
    }
}
