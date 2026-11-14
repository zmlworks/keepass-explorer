package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.graphics.Typeface
import io.github.zmlworks.kpexplorer.R

/**
 * 0.4.25: the monospace font of field values — JetBrains Mono (bundled, Latin subset, no ligatures; see
 * THIRD_PARTY_NOTICES). 0.4.24 used the system "monospace" family, which did not look monospace on the user's
 * device (some phones map it to their own proportional font). Characters it lacks (Japanese …) fall back to the
 * system fonts. If the resource cannot be loaded, the system monospace is used.
 */
object Mono {
    private var cached: Typeface? = null
    fun typeface(ctx: Context): Typeface = cached
        ?: (runCatching { ctx.applicationContext.resources.getFont(R.font.kpx_mono) }.getOrNull() ?: Typeface.MONOSPACE).also { cached = it }
}
