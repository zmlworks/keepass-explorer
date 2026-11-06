package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import io.github.zmlworks.kpexplorer.R
import io.github.zmlworks.kpexplorer.db.EntryIcon

/**
 * Google Material Symbols (Outlined) used for the 15 selectable entry icons.
 * Vector drawables live in res/drawable/ms_*.xml (Apache-2.0); tinted navy to match the UI.
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

    /** Rasterize for explorer list / grids that still use [Bitmap]. */
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
}
