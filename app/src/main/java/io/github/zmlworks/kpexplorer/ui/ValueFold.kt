package io.github.zmlworks.kpexplorer.ui

import android.graphics.Rect
import android.text.method.TransformationMethod
import android.view.View
import android.widget.TextView

/**
 * 0.4.26: a one-line field value that is wider than its row. Shown with " …" at the end; one tap shows the
 * whole value (wrapped), the next tap one line again. A password ([secret]) cycles
 * ****** → one line (" …" when long) → whole → ******, or just ****** ↔ shown when it fits on one line.
 * While the field is edited the value is shown plainly on one line (horizontal scrolling, no " …").
 * The value itself ([TextView.getText]) never changes: copy / open-in-browser use it as before.
 */
class ValueFold(
    private val t: TextView,
    private val secret: Boolean,
    initial: State? = null,
    /** Shown instead of ****** while hidden (the preview's 「表示する」). */
    private val hiddenMark: CharSequence? = null,
    /** Called with each new state before it is drawn, so a font or colour it sets is used for the " …" cut. */
    private val onChange: (State) -> Unit = {}
) {
    enum class State { HIDDEN, LINE, FULL }

    var state: State = initial ?: if (secret) State.HIDDEN else State.LINE
        private set
    private var editing = false

    init {
        t.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) t.post { apply() } }
        apply()
    }

    private fun avail() = t.width - t.totalPaddingLeft - t.totalPaddingRight

    /** The whole value does not fit on one line (unknown before the first layout = false). */
    fun overflows(): Boolean {
        val w = avail()
        return w > 0 && t.paint.measureText(t.text, 0, t.text.length) > w
    }

    /** One tap on the field. */
    fun tap() {
        state = when (state) {
            State.HIDDEN -> State.LINE
            State.LINE -> if (overflows()) State.FULL else if (secret) State.HIDDEN else State.LINE
            State.FULL -> if (secret) State.HIDDEN else State.LINE
        }
        onChange(state); apply()
    }

    /** Password made visible by other means (long press = edit): at least one line. */
    fun reveal() { if (state == State.HIDDEN) { state = State.LINE; onChange(state); apply() } }

    fun setEditing(on: Boolean) { if (editing != on) { editing = on; apply() } }

    fun apply() {
        val full = state == State.FULL && !editing
        if (full) {
            t.setSingleLine(false); t.setHorizontallyScrolling(false); t.maxLines = Int.MAX_VALUE
        } else {
            t.setSingleLine(true); t.setHorizontallyScrolling(true)
        }
        // after setSingleLine (which installs its own transformation)
        t.transformationMethod = when {
            editing -> null
            state == State.HIDDEN -> hiddenMark?.let { Mark(it) } ?: Stars
            state == State.LINE -> Ellipsis
            else -> null
        }
        if (!full) t.scrollTo(0, 0)
    }

    private class Mark(val s: CharSequence) : TransformationMethod {
        override fun getTransformation(source: CharSequence, view: View): CharSequence = s
        override fun onFocusChanged(view: View, sourceText: CharSequence, focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {}
    }

    /** One line: the value cut to the row's width with " …" when it does not fit. */
    object Ellipsis : TransformationMethod {
        const val TAIL = " …"
        override fun getTransformation(source: CharSequence, view: View): CharSequence {
            val t = view as TextView
            val w = t.width - t.totalPaddingLeft - t.totalPaddingRight
            if (w <= 0 || t.paint.measureText(source, 0, source.length) <= w) return source
            var n = t.paint.breakText(source, 0, source.length, true, w - t.paint.measureText(TAIL), null).coerceAtLeast(0)
            // measured as one run (kerning, fallback fonts for characters the font lacks) it can still be wider
            while (n > 0 && t.paint.measureText(source.subSequence(0, n).toString() + TAIL) > w) n--
            return source.subSequence(0, n).toString() + TAIL
        }
        override fun onFocusChanged(view: View, sourceText: CharSequence, focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {}
    }
}
