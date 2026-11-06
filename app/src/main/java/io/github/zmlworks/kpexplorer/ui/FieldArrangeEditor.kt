package io.github.zmlworks.kpexplorer.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.zmlworks.kpexplorer.KeyFields
import io.github.zmlworks.kpexplorer.db.Order

/**
 * Drag-to-reorder field arrangement (鍵「欄の編集」).
 *
 * Handle is RIGHTMOST; custom delete × sits immediately LEFT of the handle.
 * Body (label) scrolls; only the handle starts a drag. Title omitted; always
 * prepended visible in [snapshot]. Missing standard fields appear under the
 * boundary so they can be restored by dragging up (replaces old menu 「欄を戻す」).
 */
class FieldArrangeEditor(
    private val ctx: Context,
    initial: List<Pair<String, Boolean>>,
    private val onChanged: () -> Unit = {},
) : LinearLayout(ctx) {
    val shown: MutableList<String> = ArrayList()
    val hidden: MutableList<String> = ArrayList()
    val removedCustoms: MutableSet<String> = linkedSetOf()

    private val list = LinearLayout(ctx).apply { orientation = VERTICAL }
    val scroll: ScrollView = ScrollView(ctx).apply {
        // 0.3.15: no visible scrollbar (user: スクロールは削除). Overflow still scrolls
        // silently + edge autoscroll while dragging so many custom fields stay reachable.
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        isScrollbarFadingEnabled = true
        isFillViewport = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
    }
    private val stage = FrameLayout(ctx)
    private val ghost = LinearLayout(ctx).apply {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        background = W.Bevel(W.Style.THIN_RAISED, W.LIGHT)
        setPadding(W.dp(4), W.dp(4), W.dp(4), W.dp(4))
        visibility = GONE
        elevation = W.dp(6).toFloat()
        alpha = 0.95f
    }
    private val ghostLabel = W.text(ctx, "", 15f, true)
    private val handler = Handler(Looper.getMainLooper())

    var dragKey: String? = null
        private set
    private var dragPointerId = -1
    private var lastRawY = 0f
    private var ghostH = W.dp(44)
    private var autoScrollDir = 0
    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (dragKey == null || autoScrollDir == 0) return
            scroll.scrollBy(0, autoScrollDir * W.dp(12))
            relocateByFinger(lastRawY)
            positionGhost(lastRawY)
            handler.postDelayed(this, 16L)
        }
    }

    init {
        orientation = VERTICAL
        load(initial)
        // Ghost: label … ×(spacer) handle on the right (mirrors rows)
        ghost.addView(ghostLabel, W.lp(0, W.WRAP, 1f))
        ghost.addView(ImageView(ctx).apply {
            setImageDrawable(Glyph(Glyph.Kind.GRIP, W.SHADOW))
            scaleType = ImageView.ScaleType.CENTER
            contentDescription = "並べ替え"
        }, W.lp(W.dp(48), W.dp(48), l = 4))
        scroll.addView(list)
        stage.addView(scroll, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        stage.addView(ghost, FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(stage, W.lp(W.MATCH, 0, 1f))
        render()
    }

    private fun load(src: List<Pair<String, Boolean>>) {
        shown.clear(); hidden.clear()
        val seen = HashSet<String>()
        for ((k, v) in src) {
            if (k == Order.F_TITLE || k in seen) continue
            seen.add(k)
            if (v) shown.add(k) else hidden.add(k)
        }
        // Missing standard fields → hidden so the user can drag them back up
        for (k in Order.DEFAULT_LAYOUT) {
            if (k == Order.F_TITLE || k in seen) continue
            seen.add(k)
            hidden.add(k)
        }
    }

    /** Title always first+visible, then editor rows. Hides keep values via "-" in KPExplorer.Fields. */
    fun snapshot(): List<Pair<String, Boolean>> =
        listOf(Order.F_TITLE to true) + shown.map { it to true } + hidden.map { it to false }

    fun isDragging(): Boolean = dragKey != null

    fun render() {
        list.removeAllViews()
        shown.forEach { k ->
            list.addView(fieldRow(k, visible = true, dimmed = dragKey == k), W.lp(W.MATCH, W.WRAP, b = 2))
        }
        list.addView(dividerRow(), W.lp(W.MATCH, W.WRAP, t = 6, b = 6))
        hidden.forEach { k ->
            list.addView(fieldRow(k, visible = false, dimmed = dragKey == k), W.lp(W.MATCH, W.WRAP, b = 2))
        }
        onChanged()
    }

    private fun dividerRow(): View {
        val row = W.hbox(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(W.dp(4), W.dp(8), W.dp(4), W.dp(8))
            setBackgroundColor(0xFFE8E4DC.toInt())
            tag = TAG_DIVIDER
            minimumHeight = W.dp(36)
        }
        row.addView(View(ctx).apply { setBackgroundColor(W.NAVY) }, W.lp(0, W.dp(2), 1f))
        row.addView(W.text(ctx, " この下は非表示 ", 13f, true, W.NAVY), W.lp(W.WRAP, W.WRAP))
        row.addView(View(ctx).apply { setBackgroundColor(W.NAVY) }, W.lp(0, W.dp(2), 1f))
        return row
    }

    private fun fieldRow(k: String, visible: Boolean, dimmed: Boolean): LinearLayout {
        val row = W.hbox(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = W.dp(44)
            background = W.Bevel(W.Style.THIN_RAISED, if (visible) W.FACE else 0xFFD0D0D0.toInt())
            setPadding(W.dp(4), W.dp(4), W.dp(4), W.dp(4))
            tag = k
            alpha = if (dimmed) 0.25f else 1f
        }
        val label = KeyFields.label(k).removeSuffix(":")
        row.addView(W.text(ctx, if (visible) label else "($label)", 15f).apply {
            isSingleLine = true; ellipsize = android.text.TextUtils.TruncateAt.END
        }, W.lp(0, W.WRAP, 1f))
        // Custom delete × immediately LEFT of the rightmost handle (larger ~48dp hit)
        if (k !in Order.DEFAULT_LAYOUT) {
            row.addView(W.text(ctx, "×", 22f, true).apply {
                gravity = Gravity.CENTER; contentDescription = "欄を削除"
                minimumWidth = W.dp(48); minimumHeight = W.dp(48)
                isClickable = true
                tag = "delete:$k"
                setOnClickListener {
                    if (dragKey != null) return@setOnClickListener
                    shown.remove(k); hidden.remove(k); removedCustoms.add(k); render()
                }
            }, W.lp(W.dp(48), W.dp(48), r = 2))
        }
        val handle = ImageView(ctx).apply {
            setImageDrawable(Glyph(Glyph.Kind.GRIP, W.TEXT))
            contentDescription = "並べ替え"
            minimumWidth = W.dp(56); minimumHeight = W.dp(48)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(W.dp(8), W.dp(8), W.dp(8), W.dp(8))
            isClickable = true
            tag = "handle:$k"
        }
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (dragKey == null) beginDrag(k, e)
                    true
                }
                else -> dragKey == k
            }
        }
        row.addView(handle, W.lp(W.dp(56), W.dp(48)))
        return row
    }

    private fun beginDrag(key: String, e: MotionEvent) {
        dragKey = key
        dragPointerId = e.getPointerId(0)
        lastRawY = e.rawY
        ghostLabel.text = KeyFields.label(key).removeSuffix(":")
        ghost.visibility = VISIBLE
        val w = if (stage.width > 0) stage.width else W.dp(320)
        ghost.measure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        ghostH = ghost.measuredHeight.coerceAtLeast(W.dp(48))
        ghost.layoutParams = FrameLayout.LayoutParams(w, ghostH)
        positionGhost(e.rawY)
        var p: View? = this
        while (p != null) {
            p.parent?.requestDisallowInterceptTouchEvent(true)
            p = p.parent as? View
        }
        post {
            if (dragKey == key) render()
        }
    }

    private fun endDrag() {
        handler.removeCallbacks(autoScrollRunnable)
        autoScrollDir = 0
        dragKey = null
        dragPointerId = -1
        ghost.visibility = GONE
        var p: View? = this
        while (p != null) {
            p.parent?.requestDisallowInterceptTouchEvent(false)
            p = p.parent as? View
        }
        render()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (dragKey != null) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val idx = (0 until ev.pointerCount).firstOrNull { ev.getPointerId(it) == dragPointerId } ?: 0
                    lastRawY = ev.getRawY(idx)
                    updateAutoScroll(lastRawY)
                    relocateByFinger(lastRawY)
                    positionGhost(lastRawY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    endDrag(); return true
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.getPointerId(ev.actionIndex) == dragPointerId) endDrag()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun positionGhost(rawY: Float) {
        val loc = IntArray(2)
        stage.getLocationOnScreen(loc)
        val localY = rawY - loc[1] - ghostH / 2f
        val max = (stage.height - ghostH).toFloat().coerceAtLeast(0f)
        ghost.translationY = localY.coerceIn(0f, max)
        ghost.translationX = 0f
    }

    private fun updateAutoScroll(rawY: Float) {
        val loc = IntArray(2)
        scroll.getLocationOnScreen(loc)
        val top = loc[1]
        val bottom = top + scroll.height
        val edge = W.dp(48)
        val dir = when {
            rawY < top + edge -> -1
            rawY > bottom - edge -> 1
            else -> 0
        }
        if (dir != autoScrollDir) {
            autoScrollDir = dir
            handler.removeCallbacks(autoScrollRunnable)
            if (dir != 0) handler.post(autoScrollRunnable)
        }
    }

    private fun relocateByFinger(rawY: Float) {
        val key = dragKey ?: return
        val divider = list.findViewWithTag<View>(TAG_DIVIDER) ?: return
        val dLoc = IntArray(2); divider.getLocationOnScreen(dLoc)
        val wantHidden = rawY >= dLoc[1] + divider.height / 2f

        data class Slot(val key: String, val centerY: Float)
        val slots = ArrayList<Slot>()
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            val tag = child.tag as? String ?: continue
            if (tag == TAG_DIVIDER || tag == key) continue
            val loc = IntArray(2); child.getLocationOnScreen(loc)
            slots.add(Slot(tag, loc[1] + child.height / 2f))
        }

        val others = (shown + hidden).filter { it != key }
        fun cy(k: String): Float =
            slots.find { it.key == k }?.centerY
                ?: (if (k in shown) shown.indexOf(k) * 1000f else 50_000f + hidden.indexOf(k) * 1000f)
        val ordered = others.sortedBy { cy(it) }

        val newShown = ArrayList<String>()
        val newHidden = ArrayList<String>()
        var placed = false
        fun placeDragged() {
            if (placed) return
            if (wantHidden) newHidden.add(key) else newShown.add(key)
            placed = true
        }
        for (ok in ordered) {
            if (!placed && rawY <= cy(ok)) placeDragged()
            if (hidden.contains(ok)) newHidden.add(ok) else newShown.add(ok)
        }
        if (!placed) placeDragged()

        for (ok in others) {
            if (ok !in newShown && ok !in newHidden) {
                if (ok in hidden) newHidden.add(ok) else newShown.add(ok)
            }
        }

        if (newShown == shown && newHidden == hidden) return
        shown.clear(); shown.addAll(newShown)
        hidden.clear(); hidden.addAll(newHidden)
        render()
    }

    /** Discrete step helper (tests / accessibility). */
    fun step(key: String, currentlyHidden: Boolean, dir: Int): Boolean {
        if (key == Order.F_TITLE) return false
        val inHidden = key in hidden
        if (!inHidden) {
            val i = shown.indexOf(key); if (i < 0) return false
            val j = i + dir
            if (dir < 0) {
                if (j < 0) return false
                shown.add(j, shown.removeAt(i)); render(); return true
            }
            if (j < shown.size) {
                shown.add(j, shown.removeAt(i)); render(); return true
            }
            shown.removeAt(i); hidden.add(0, key); render(); return true
        } else {
            val i = hidden.indexOf(key); if (i < 0) return false
            val j = i + dir
            if (dir > 0) {
                if (j >= hidden.size) return false
                hidden.add(j, hidden.removeAt(i)); render(); return true
            }
            if (j >= 0) {
                hidden.add(j, hidden.removeAt(i)); render(); return true
            }
            hidden.removeAt(i); shown.add(key); render(); return true
        }
    }

    fun place(key: String, visible: Boolean, index: Int) {
        if (key == Order.F_TITLE) return
        shown.remove(key); hidden.remove(key)
        if (visible) shown.add(index.coerceIn(0, shown.size), key)
        else hidden.add(index.coerceIn(0, hidden.size), key)
        render()
    }

    fun handleFor(key: String): View? = list.findViewWithTag("handle:$key")
    fun deleteFor(key: String): View? = list.findViewWithTag("delete:$key")
    fun rowFor(key: String): View? = list.findViewWithTag(key)
    fun dividerView(): View? = list.findViewWithTag(TAG_DIVIDER)

    companion object {
        const val TAG_DIVIDER = "divider"
    }
}
