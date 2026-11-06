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
 * Entry icons: Google Material Symbols Outlined (Apache-2.0, navy).
 * Independent memo items (Order.Kind.MEMO) use [Ico.notepad32], not these.
 */
object MaterialIcons {
    private val cache = HashMap<String, Bitmap>()

    fun resId(id: String?): Int = when (id) {
        "mail" -> R.drawable.ms_mail
        "web" -> R.drawable.ms_web
        "wifi" -> R.drawable.ms_wifi
        "food" -> R.drawable.ms_food
        "user" -> R.drawable.ms_user
        "card" -> R.drawable.ms_card
        "bank" -> R.drawable.ms_bank
        "cart" -> R.drawable.ms_cart
        "game" -> R.drawable.ms_game
        "phone" -> R.drawable.ms_phone
        "lock" -> R.drawable.ms_lock
        "home" -> R.drawable.ms_home
        "star" -> R.drawable.ms_star
        "note" -> R.drawable.ms_note
        else -> R.drawable.ms_key
    }

    fun resId(def: EntryIcon.Def?): Int = resId(def?.id)

    fun drawable(ctx: Context, id: String?): Drawable =
        ctx.getDrawable(resId(id))!!.mutate()

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

    fun clearCache() { cache.clear() }

    /**
     * Icon chooser grid: large nearly-square tiles, glyphs only (no visible names).
     * [contentDescription] keeps the Japanese label for accessibility.
     */
    fun pickerGrid(ctx: Context, selectedId: String?, onPick: (String) -> Unit): FlowLayout {
        val grid = FlowLayout(ctx)
        // 0.3.11: larger glyphs; ~3 columns on phone widths
        val cell = W.dp(100)
        val glyph = W.dp(72)
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
